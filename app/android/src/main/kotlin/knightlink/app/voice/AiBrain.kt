package knightlink.app.voice

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import knightlink.app.ble.StringStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How KnightLink answers free-form driver questions. Deterministic safety alerts never depend on AI. */
enum class AiMode(val label: String, val summary: String) {
    NO_AI("No AI (Deterministic)", "Offline templates & fixed intent matrix (<150 ms)"),
    LOCAL_AI("Local AI (On-Device)", "On-device LiteRT-LM model downloaded over Wi-Fi"),
    CLOUD_AI("Cloud AI (BYO Key)", "Your own OpenAI / Gemini / Anthropic / Ollama key"),
    HYBRID_AI("Hybrid AI", "On-device first, Cloud AI fallback when online"),
}

enum class CloudProvider(val label: String) {
    GEMINI("Google Gemini"),
    OPENAI("OpenAI / Compatible"),
    ANTHROPIC("Anthropic Claude"),
    OLLAMA("LAN Ollama"),
}

data class AiSettings(
    val mode: AiMode = AiMode.NO_AI,
    val provider: CloudProvider = CloudProvider.GEMINI,
    val hasCloudKey: Boolean = false,
)

/**
 * Persists [AiMode], [CloudProvider], and an Android Keystore AES-GCM encrypted BYO API key.
 * Plaintext API keys are never written to SharedPreferences or logs.
 */
class AiSettingsStore(private val store: StringStore) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private fun load(): AiSettings {
        val mode = store.get(KEY_MODE)?.let { raw -> AiMode.entries.firstOrNull { it.name == raw } } ?: AiMode.NO_AI
        val provider = store.get(KEY_PROVIDER)?.let { raw -> CloudProvider.entries.firstOrNull { it.name == raw } } ?: CloudProvider.GEMINI
        val hasKey = !store.get(KEY_API_KEY_ENC).isNullOrBlank()
        return AiSettings(mode, provider, hasKey)
    }

    fun setMode(mode: AiMode) {
        store.put(KEY_MODE, mode.name)
        _settings.value = _settings.value.copy(mode = mode)
    }

    fun setProvider(provider: CloudProvider) {
        store.put(KEY_PROVIDER, provider.name)
        _settings.value = _settings.value.copy(provider = provider)
    }

    fun saveApiKey(rawKey: String): Boolean {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            store.put(KEY_API_KEY_ENC, "")
            _settings.value = _settings.value.copy(hasCloudKey = false)
            return true
        }
        val encrypted = encrypt(trimmed) ?: return false
        store.put(KEY_API_KEY_ENC, encrypted)
        _settings.value = _settings.value.copy(hasCloudKey = true)
        return true
    }

    fun readApiKey(): String? {
        val encoded = store.get(KEY_API_KEY_ENC)?.takeIf { it.isNotBlank() } ?: return null
        return decrypt(encoded)
    }

    private fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, destinationOffset = 1)
        ciphertext.copyInto(combined, destinationOffset = 1 + iv.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_MODE = "ai_mode"
        const val KEY_PROVIDER = "ai_cloud_provider"
        const val KEY_API_KEY_ENC = "ai_cloud_key_enc"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "knightlink_byo_ai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Deterministic guardrail (ADR 0004): checks numbers in any LLM-generated answer against verified telemetry values.
 * If an LLM invents a number not present in [verifiedValues] (within [tolerance]), the caller must fall back to [deterministicFallback].
 */
object TelemetryFactGuard {
    private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?""")

    fun verifyOrFallback(
        candidateText: String?,
        verifiedValues: Collection<Double>,
        deterministicFallback: String,
        tolerance: Double = 1.0,
    ): String {
        val text = candidateText?.trim().takeUnless { it.isNullOrEmpty() } ?: return deterministicFallback
        val mentioned = NUMBER_REGEX.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (mentioned.isEmpty()) return text
        val allVerified = mentioned.all { num ->
            verifiedValues.any { kotlin.math.abs(it - num) <= tolerance }
        }
        return if (allVerified) text else deterministicFallback
    }
}
