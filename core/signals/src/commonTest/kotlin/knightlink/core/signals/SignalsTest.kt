package knightlink.core.signals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionTest {
    private fun eval(formula: String, vararg bytes: Int) =
        Expression.compile(formula).evaluate(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(799.0, eval("(256*A+B)/4", 0x0C, 0x7C))
        assertEquals(67.0, eval("A-40", 0x6B))
        assertEquals(14.0, eval("2+3*4"))
        assertEquals(20.0, eval("(2+3)*4"))
        assertEquals(-3.0, eval("-A+2", 5))
        assertEquals(2.5, eval("A/2", 5))
        assertEquals(1.0, eval("8/4/2"))
        assertEquals(255.0, eval("0xFF"))
    }

    @Test
    fun bytesAreUnsigned() {
        assertEquals(255.0, eval("A", 0xFF))
        assertEquals(95.686, eval("A/2.55", 0xF4), 0.001)
    }

    @Test
    fun bitOperators() {
        assertEquals(3.0, eval("(A>>4)&3", 0x3A))
        assertEquals(33.0, eval("((A<<8)|B)/32", 0x04, 0x20))
        assertEquals(1.0, eval("A&1", 0x0F))
        assertEquals(7.0, eval("1|2+4")) // + binds tighter than |
    }

    @Test
    fun reportsRequiredBytes() {
        assertEquals(0, Expression.compile("42").requiredBytes)
        assertEquals(1, Expression.compile("A").requiredBytes)
        assertEquals(7, Expression.compile("256*F+G").requiredBytes)
        assertFailsWith<ExpressionException> { eval("B", 1) }
    }

    @Test
    fun rejectsInvalidFormulas() {
        for (bad in listOf("", "A+", "(A", "A)", "AB", "a", "A $ B", "sqrt(A)", "1..2", "0x")) {
            assertFailsWith<ExpressionException>(bad) { Expression.compile(bad) }
        }
    }
}

class SignalCatalogTest {
    private val standard = DefinitionJson.parse(
        """
        {"schemaVersion":1,"id":"std","kind":"standard","title":"Standard",
         "parameters":[
           {"service":"01","pid":"0c","provenance":"standard","signals":[
             {"id":"engine.rpm","formula":"(256*A+B)/4","unit":"rpm","min":0,"max":8000}]},
           {"service":"01","pid":"05","provenance":"standard","signals":[
             {"id":"engine.coolant_temp","formula":"A-40","unit":"degC"}]}]}
        """,
    )
    private val vehicle = DefinitionJson.parse(
        """
        {"schemaVersion":1,"id":"car","kind":"vehicle","title":"Car","extends":["std"],
         "ecus":[{"id":"ecm","name":"ECM","requestId":"7E0","responseId":"7E8"}],
         "parameters":[
           {"service":"01","pid":"05","ecu":"ecm","provenance":"verified","signals":[
             {"id":"engine.coolant_temp","formula":"A-50","unit":"degC"}]},
           {"service":"01","pid":"8B","ecu":"ecm","provenance":"verified","signals":[
             {"id":"dpf.regen_status","formula":"B","unit":"enum"},
             {"id":"dpf.soot_pct","formula":"C/2.55","unit":"percent"},
             {"id":"dpf.avg_distance_km","formula":"256*F+G","unit":"km"}]}]}
        """,
    )
    private val catalog = SignalCatalog.of(listOf(standard, vehicle))

    @Test
    fun decodesAndFlagsRange() {
        val rpm = assertNotNull(catalog.find(0x01, "0C"))
        assertEquals(listOf(SignalValue("engine.rpm", 799.0, "rpm")), rpm.decode(byteArrayOf(0x0C, 0x7C)))
        assertFalse(rpm.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).single().inRange)
    }

    @Test
    fun ecuSpecificEntryOverridesGenericOne() {
        assertEquals(67.0, catalog.find(1, "05")!!.decode(byteArrayOf(0x6B)).single().value)
        assertEquals(57.0, catalog.find(1, "05", ecu = "ecm")!!.decode(byteArrayOf(0x6B)).single().value)
        assertEquals(67.0, catalog.find(1, "05", ecu = "tcm")!!.decode(byteArrayOf(0x6B)).single().value)
        assertNull(catalog.find(1, "8B"))
        assertEquals("ecm", catalog.ecuByResponseId(0x7E8)?.id)
    }

    @Test
    fun shortReplyDropsOnlyTheSignalsItCannotCover() {
        val dpf = catalog.find(1, "8b", ecu = "ecm")!!
        val values = dpf.decode(byteArrayOf(0x73, 0x02, 0xFF.toByte()))
        assertEquals(listOf("dpf.regen_status", "dpf.soot_pct"), values.map { it.id })
        assertEquals(100.0, values[1].value, 1e-9)
    }

    @Test
    fun rejectsBrokenDefinitions() {
        assertFailsWith<DefinitionException> { DefinitionJson.parse("""{"schemaVersion":1,"id":"x","kind":"standard","title":"t","typo":1}""") }
        assertFailsWith<DefinitionException> { DefinitionJson.parse("""{"schemaVersion":99,"id":"x","kind":"standard","title":"t"}""") }
        val badFormula = standard.copy(
            parameters = listOf(standard.parameters[0].copy(signals = listOf(SignalDefinition("x", "A+", "u")))),
        )
        assertTrue(assertFailsWith<DefinitionException> { SignalCatalog.of(listOf(badFormula)) }.message!!.contains("'x'"))
        val unknownEcu = standard.copy(parameters = listOf(standard.parameters[0].copy(ecu = "nope")))
        assertFailsWith<DefinitionException> { SignalCatalog.of(listOf(unknownEcu)) }
    }
}
