# KnightLink — Implementation Plan

**Last Updated:** 2026-11-19 (Post-M3 VoiceMaker & Offline Voice Engine Delivery)  
**Status:** M0–M3 Complete & Verified; M4 (Arrival Engine) & M5 (Full Discovery & Diagnostics) Queued  
**Current Deliverable:** `app/android/build/outputs/apk/debug/android-debug.apk` (18.9 MB, 183 unit tests passing)

---

## Executive Summary & Current Status

KnightLink is a voice-first automotive companion application for real-time telemetry, vehicle health monitoring, predictive maintenance, and intelligent voice interaction. It reads vehicle diagnostics through an OBD adapter over BLE, learns vehicle behavior and driver habits, and communicates via spoken voice with reactive cockpit visualizations.

### Milestone Progress Overview

| Milestone | Scope | Status | Verification / Exit Criteria |
| :--- | :--- | :--- | :--- |
| **M0** | Foundation, project architecture, convention plugins, CI, ADRs | **Complete** | CI green on JVM and iOS targets |
| **M1** | Protocol core, ELM327 driver, ISO-TP, signals catalog, simulator (`core/sim`) | **Complete** | 107/107 tests green; real Harrier logs replay headless |
| **M2** | Android runtime, BLE transport, foreground service, reconnect state machine, sleep/wake recovery, recorder | **Complete** | Real-car driving session verified on Tata Harrier (2020 BS6 Kryotec AT); connection recovery tested |
| **M3** | **Rules, Alerts, VoiceMaker Handover & Cockpit Visualizer UI** | **Complete** | **v0.1 release ready**: 183 unit tests passing; 50 FPS reactive VoiceBox visualizers (K.I.T.T., J.A.R.V.I.S., Optimus); offline TTS with audio ducking; debug APK assembled |
| **M4** | Arrival Engine: Geofences, parking-manoeuvre detector, Maps notification ETA plugin, cooldown arrival rules | *Queued* | `TDD-DEST` scenarios + week of commutes with zero false traffic-light warnings |
| **M5** | Any car, any adapter: Staged discovery, parked DID sweep, Mode 03/07/0A DTCs, UDS `19 02`, plain-language fault explanations | *Queued* | Interoperability verified on at least two distinct vehicles |
| **M6** | Voice Control: Steering-wheel media button / push-to-talk, offline wake word, deterministic intent router & tool bus | *Planned* | SRS §5.2 intent matrix executed 100% hands-free |
| **M7** | Brain & Personas: Hybrid on-device LiteRT-LM / BYO-cloud LLM, telemetry fact guard, persona customization | *Planned* | Persona responses never contradict telemetry snapshot; <150 ms fallback |
| **M8** | Insights & Analytics: Cold-engine load, interrupted regens, trip economy, fuel fill-up tracking | *Planned* | Automated weekly health and efficiency digest |
| **M9** | Store Release: Play Store beta, privacy policies, permission rationale, thermal & battery pass | *Planned* | Public Store beta |
| **M10** | iOS Shell: CoreBluetooth validation, Compose Multiplatform for iOS | *Planned* | TestFlight build on macOS CI |
| **M11–M12** | Community Profiles & Crowd-sourced Fuel Insights | *Future* | Decentralized anonymous contributions |

---

**Decisions confirmed with the user**

| Topic | Decision |
|---|---|
| Distribution | Public store release with **original** personas; personas are data packs (private KITT-style packs can be side-loaded) |
| Stack | **Kotlin Multiplatform** core + Compose Multiplatform UI |
| Cloud | **Local-first**; vehicle/adapter/persona definitions update from a signed static feed. No accounts. Opt-in crowd-sourcing (vehicle profiles, then anonymous trip/fuel data) arrives later as M11/M12 behind interfaces designed in now |
| Interaction | **Voice first (~99 % of use)**: the driver talks and listens; the screen is a glanceable status and a settings/sharing surface. Every feature is designed as a spoken exchange first (added 2026-11-17) |
| First release | **Reliable core first**: connection, trip recording, deterministic spoken alerts, spoken status and push-to-talk answers to a fixed set of questions. Wake word, LLM and themes later |
| iOS | No Mac yet: shared code kept iOS-compatible, macOS CI runner compiles iOS targets on every change |
| Display | Phone on a mount. Android Auto out of scope |
| Test hardware | Second Android phone (simulator), access to friends' cars |

## What changes from the SRS

Kept: verified Harrier frames and init sequence, physical-header polling rule, deterministic-safety vs generative-character split, three-layer arrival idea, simulator concept, spec-driven tests.

Changed:
1. **Flutter → KMP.** The core runs natively inside an Android foreground service; no Dart/native bridge.
2. **Vehicle knowledge is data, not code.** Harrier PIDs, formulas, ECU addresses, GATT UUIDs, TPMS payload layout, sensor IDs all live in definition files. A build check fails if `core/` contains a vehicle- or adapter-specific constant.
3. **Polling is budgeted.** ELM327 over BLE yields roughly 10–25 reads/s total, so "20 Hz thermal gauges" becomes a priority scheduler with a measured budget.
4. **Discovery is progressive.** Stages 1–4 at connect (seconds); the UDS DID sweep runs only when parked, resumable, over several sessions. No "under 15 s" promise.
5. **Safety interlocks.** Read-only by default. Mode 04 clear and any UDS write/routine require speed 0, on-screen confirmation, and can never be triggered by LLM output alone. Active sweeps never run while moving.
6. **Maps ETA is a best-effort plugin.** Correct package is `com.google.android.apps.maps` only (the SRS also lists Files by Google). Parsers are versioned, locale-aware, fixture-tested, and the arrival engine works without it.
7. **Platform features are optional capabilities** (notification listener, classic Bluetooth, media-button hook, background wake word), so iOS can lack them cleanly. TPMS sensors are identified by payload ID/pairing, not MAC (iOS hides MACs).
8. **Simulator shares the core's code** (Kotlin), replacing the separate Python simulator that would drift.
9. **LLM never states unverified numbers.** Output is checked against the telemetry snapshot; on mismatch or deadline miss the template line is spoken.
10. **Added:** trip recording from the first drivable build, driving-habit insights engine, definitions feed, schema versioning, CI, crash reporting, privacy.

## Architecture

Layered, dependencies point downward only. Each box is a Gradle module with a small public API; platform code sits behind interfaces.

```
app/android (service, DI, permissions)   app/sim-android   app/ios (later)   tools/cli (JVM)
feature/*  cockpit · dpf · tpms · trips · diagnostics · onboarding · settings      ui/designsystem · ui/themes
voice/*    api · tts · stt · wakeword · intents(tool bus)          brain/*  api · providers · personas · factguard
core/runtime      session state machine; the one object the service hosts
core/arrival      EtaSource plugins → ArrivalPredictor      core/insights   post-trip habit analytics
core/rules        DPF state machine, cooldown guard, TPMS leak, alert manager (priority, dedupe, quiet periods)
core/recorder     trips, samples, raw frames, events (SQLDelight)      core/definitions  feed client, signature, cache
core/telemetry    snapshot StateFlow + event stream, derived signals (boost, L/h, cold psi)
core/scheduler    priority tiers, budget, per-ECU batching           core/tpms   definition-driven advert decoders
core/vehicle      VehicleProfile, staged discovery, fingerprint      core/signals  canonical signal catalog + formula evaluator
core/protocol     ISO-TP, OBD services 01/03/07/09/0A (04 gated), UDS 22/19, NRC, DTC   — pure
core/adapter      AdapterDriver; ELM327 driver (command queue, init script, chipset detection, quirks table)
core/transport    ByteStream API · transport-ble (Kable) · transport-spp (Android) · transport-tcp
core/sim          virtual ECUs + virtual ELM327 + TPMS adverts, scenario DSL, log replay
core/common       Result types, Clock, dispatchers, units
```

**Extension seams** (adding a thing = adding a file or a module, never editing the core):

| To add | Implement / provide |
|---|---|
| New car | Definition file, or let discovery generate a profile |
| New adapter chipset | `AdapterDriver` + quirks entry |
| New link type | `Transport` |
| New TPMS brand | TPMS definition (payload layout) |
| New ETA source | `EtaSource` |
| New alert | `Rule` (pure function of snapshot + history) |
| New LLM backend | `LlmProvider` |
| New persona / theme | Persona pack / theme pack (data) |

**Definitions**: JSON, schema-versioned, layered `SAE standard → OEM → model → learned per-VIN overrides`. Formulas use a small safe expression language (bytes A..n, bit fields, arithmetic) — no code execution. Each entry carries provenance (`standard | verified | community | inferred`). The Harrier profile is the first file, built from SRS §2. The feed is a static manifest signed with Ed25519, with `minAppVersion` and rollback to last-good cache.

**Runtime**: Android foreground service (`connectedDevice`, plus `location`; `microphone` only once voice ships) hosts `KnightRuntime`: `Idle → Connecting → Initialising → Identifying → Polling ⇄ Degraded → Reconnecting`. UI observes StateFlows and can die freely. Single-writer command queue to the adapter; UI updates conflated; DB writes batched.

**Stack**: Kotlin 2.x, Gradle version catalog + `build-logic` convention plugins, coroutines/Flow, kotlinx.serialization, SQLDelight, Ktor client, Kable, Koin, Kermit, Compose Multiplatform. Quality: detekt, ktlint, Kover, module-dependency check. App ID `in.byteheaven.knightlink` (from the SRS).

## Test strategy

1. **Unit (commonTest, seconds on JVM)** — decoders, ISO-TP, ELM parser, formula evaluator, rules. Golden fixtures from real logs; property tests that split byte streams at arbitrary boundaries (BLE chunks unpredictably).
2. **Contract suites** — every `Transport` and `AdapterDriver` implementation runs the same shared tests.
3. **Scenario tests** — full headless runtime against `core/sim` with a virtual clock. SRS IDs `TDD-OBD-001..008`, `TDD-TPMS-001`, `TDD-DEST-001..003`, `TDD-VOICE-001` map one-to-one; scenarios `harrier_cold_commute`, `dpf_active_regen`, `rapid_tire_deflation`, `arriving_home_with_active_regen`, `unknown_car_first_discovery`.
4. **Replay regression** — every recorded drive can be exported and replayed on the desk through `tools/cli`; field bugs become permanent tests.
5. **Device** — instrumented tests for service lifecycle/permissions; hardware-in-the-loop with the second phone running KnightSim as a fake BLE adapter + TPMS advertiser.
6. **UI** — Compose screenshot tests per theme.
7. **LLM** — fake providers in gating tests; a separate non-gating evaluation harness for repetition and fact accuracy (`TDD-LLM-001` is split accordingly, since n-gram bounds on a real model are not deterministic).

**Where the app runs during development** (an emulator is optional, not required):

| Environment | Used for | Bluetooth |
|---|---|---|
| JVM tests + `tools/cli` on the PC | ~90 % of testing: protocol, rules, runtime, replay | None needed; `core/sim` in memory |
| `app/desktop` (Compose Desktop dev harness) | Fast UI iteration on the PC with simulated or replayed data | None |
| Android emulator | UI on Android, service lifecycle, CI instrumented tests. Connects to the PC simulator over `transport-tcp` (`10.0.2.2`) | Emulators cannot reach real BLE adapters |
| S25 Ultra + second phone (KnightSim) | Real BLE stack, reconnects, TPMS adverts | Real |
| S25 Ultra in the Harrier | Final truth; every drive is recorded for replay | Real |

CI (GitHub Actions): Linux job runs lint, tests, coverage, definition-schema validation, APK build; macOS job compiles iOS targets and runs core tests on the iOS simulator.

## Milestones

Each ends with a demonstrable exit test. **v0.1 (first drivable & voice-enabled release) = end of M3 (Achieved).**

| # | Milestone | Contents | Status | Exit Criteria / Verification |
|---|---|---|---|---|
| **M0** | Foundation | Repo, convention plugins (`kmp-library`, `android-app`, `quality`), module skeleton, CI, ADRs (0001–0006), fixture import, interface contracts | **Complete** | CI green on empty modules, iOS targets compile on macOS CI |
| **M1** | Protocol core + simulator | Transport API, ELM327 driver, protocol, signals, definition schema, Harrier profile, TPMS decoder, `core/sim` virtual ECUs, CLI replay | **Complete** | All SRS OBD/TPMS tests green; 107/107 simulation tests pass; real Harrier log replays headless |
| **M2** | Android runtime | BLE transport (`GattLink`, `BleAdapterScanner`), foreground service with wake lock, session state machine, vehicle memory store (`FileVehicleMemoryStore`), reconnect & sleep/wake recovery, recorder, minimal dashboard, KnightSim app | **Complete** | Real-car driving session verified on Tata Harrier (2020 BS6 Kryotec AT); connection recovery tested |
| **M3** | Rules, alerts & voice runtime | DPF state machine, cooldown guard, TPMS evaluator, alert manager, **VoiceMaker KnightPack pipeline** (`.knightpack` ZIP unpacker, SHA-256 check), **LexiconEngine** (automotive PID acronym pronunciations), **KnightVoicePlayer** (zero-latency static `AudioTrack` alert playback with 50 FPS loudness envelope streaming), **AndroidSpeechEngine** (offline on-device TTS with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` ducking), **VoiceBox Visualizer Suite** (K.I.T.T. 3-stage LED bar, J.A.R.V.I.S. circular HUD, Optimus Prime VU meters in portrait & landscape cockpit modes), pre-bundled `KITT.knightpack` (6.4 MB) | **Complete** | **v0.1 ready**: 183 app unit tests pass (100%); 107 core sim tests pass; ktlint & arch checks pass; `android-debug.apk` assembled (18.9 MB) |
| **M4** | Arrival engine | Geofences, parking-manoeuvre detector, Maps notification ETA plugin, arrival rules from SRS §3.3 | *Queued* | `TDD-DEST` scenarios + a week of commutes with no traffic-light warnings |
| **M5** | Any car, any adapter | Staged discovery, generic OBD fallback profile, SPP + Wi-Fi transports, adapter quirks, definitions feed, DTC read and guarded clear. Added 2026-11-17: generic read-only body-ECU sweep (parked); UDS identification DIDs (F18x) to name each ECU; DTCs via 03/07/0A and UDS `19 02` with status byte, freeze frames (02, `19 04`), readiness monitors ("will it pass PUC?"), Mode 06; SAE J2012 generic descriptions; every result **spoken in plain language** (what it is, how serious, what to do) and shown second; 0x78 response-pending handling; protocol auto-detect and 29-bit CAN; chipset detection (STN, vLinker) and quirks as data; ISO-TP manual flow-control fallback; WWH-OBD fallback; unknown BLE adapters found by `ATZ` probing of notify/write pairs; community candidate signals verified on the car before use; connection-diagnostics page | *Queued* | Works on at least two friends' cars without code changes |
| **M6** | Voice control | Push-to-talk (steering-wheel media button or on-screen), STT, deterministic intent router + tool bus, then offline wake word | *Planned* | SRS §5.2 intent matrix passes hands-free |
| **M7** | Brain, personas, themes | LLM providers (template, cloud, LAN, on-device LiteRT-LM), fact guard against telemetry snapshot, original persona packs, theme packs (more visualiser styles, each persona choosing its voice, wording and visualiser together) | *Planned* | Persona lines never contradict telemetry; fallback under 150 ms |
| **M8** | Insights | Driving-habit analytics (idling, cold-engine load, interrupted regens, economy trends), fuel-fill detection and per-tank economy (local only), health report export. Added 2026-11-17: fuel cost and trip summaries (spoken at trip end, on request), GPS track with trips, CSV export of decoded values | *Planned* | Weekly report generated from recorded trips |
| **M9** | Store release | Privacy policy, permission declarations, opt-in crash reporting, battery/thermal pass, Play beta | *Planned* | Public beta |
| **M10** | iOS | iOS app shell, CoreBluetooth validation, capability gaps handled | *Planned* | TestFlight build (needs a Mac) |
| **M11** | Community vehicle profiles | Opt-in upload of learned profiles, small ingest backend, review + confidence scoring, publish through the signed feed. Added 2026-11-17: verified signals are also offered upstream to OBDb (CC BY-SA) as pull requests, with the user's consent | *Future* | A profile learned on one phone reaches another via the feed |
| **M12** | Community fuel insights | Opt-in anonymous trip/fill summaries, server-side aggregation, station suggestions along a route | *Future* | Suggestions shown only where sample size supports them |

---

### VoiceMaker Handover & KnightPack Architecture (Delivered in M3)

To ensure privacy and allow side-loading rich persona experiences without bundling proprietary or copyrighted assets into the base repository or public store releases, KnightLink integrates the **KnightPack (`.knightpack`)** specification authored by companion project VoiceMaker (`D:\Projects\VoiceMaker`).

```
┌────────────────────────────────────────────────────────────────────────┐
│                        KnightPack (.knightpack ZIP)                    │
│  • manifest.json     • persona.json          • visualizer.json        │
│  • audio_fx.json     • lexicon.json          • car_dialogue.json      │
│  • audio/*.wav (17 golden safety alerts)     • audio/*.env.json       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Extracted to context.filesDir/packs/{id}/
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           KnightPackManager                            │
│  • SHA-256 verification of unpacked files                              │
│  • Directory isolation & active pack resolution                        │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
                    ▼                                ▼
┌──────────────────────────────────────┐ ┌───────────────────────────────┐
│     Path 1: Golden Safety Alerts     │ │  Path 2: Dynamic Voice Engine │
│ • KnightVoicePlayer                  │ │ • AndroidSpeechEngine         │
│ • Static AudioTrack (<10 ms latency) │ │ • Offline Android TTS -> WAV  │
│ • Streams 50 FPS loudness envelope   │ │ • Acronym LexiconEngine filter│
│ • Immediate alert preemption         │ │ • Audio focus & ducking       │
└───────────────────┬──────────────────┘ └───────────────┬───────────────┘
                    │                                    │
                    └──────────────────┬─────────────────┘
                                       ▼
                       ┌───────────────────────────────┐
                       │   VoiceBoxRenderer (Compose)  │
                       │ • KittVoiceBox (3-column LED) │
                       │ • JarvisVoiceBox (rings HUD)  │
                       │ • OptimusVoiceBox (VU meters) │
                       │ • 0% FFT runtime overhead     │
                       └───────────────────────────────┘
```

#### Key Architecture Components Delivered:

1. **`KnightPackManager` (`knightlink.app.voice.KnightPackManager`)**:
   - Manages pack extraction targeting `context.filesDir/packs/{persona_id}/`.
   - Validates existence of mandatory manifest, persona, visualizer, and audio descriptors.
   - Enforces SHA-256 checksum integrity verification.
   - Pre-bundles `KITT.knightpack` (6.4 MB) in Android assets so the application operates immediately with K.I.T.T. without requiring initial user sideloading.

2. **`LexiconEngine` (`knightlink.app.voice.LexiconEngine`)**:
   - Applies phonetic regex substitutions to speech text, converting engineering abbreviations into clear automotive vocalizations:
     - `RPM` $\to$ `"revolutions per minute"`
     - `KPH` $\to$ `"kilometers per hour"`
     - `PSI` $\to$ `"P S I"`
     - `DPF` $\to$ `"D P F"`
     - `EGT` $\to$ `"exhaust gas temperature"`
     - `TPMS` $\to$ `"T P M S"`
     - `ATF` $\to$ `"automatic transmission fluid"`

3. **`KnightVoicePlayer` (`knightlink.app.voice.KnightVoicePlayer`)**:
   - High-priority safety alert playback using `android.media.AudioTrack` in `MODE_STATIC` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`.
   - Reads 17 pre-rendered 48 kHz mono PCM WAV files with companion 50 FPS `.env.json` loudness envelopes.
   - Broadcasts real-time envelope levels to `visualizerLoudness: StateFlow<Float>` without running real-time FFT on the phone during driving.
   - Supports immediate preemption: triggers ducking or cancellation of any ongoing background announcement.

4. **`AndroidSpeechEngine` (`knightlink.app.voice.AndroidSpeechEngine`)**:
   - Provides fully offline, on-device voice generation using Android TextToSpeech synthesized directly to temporary PCM/WAV storage.
   - Requests transient audio ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) to lower music and navigation during vehicle announcements.
   - Computes RMS loudness envelopes dynamically for spoken responses so the visualizer reacts identically to alerts and spoken answers.

5. **Cockpit Visualizer Suite (`knightlink.app.ui.voice`)**:
   - **`KittVoiceBox.kt`**: Authentic 1982 Knight 2000 3-column segmented LED bar meter with centerline ballistics, phosphor decay glow, and AMOLED pure black optimization.
   - **`JarvisVoiceBox.kt`**: Circular concentric holographic HUD arcs with rotating orbital tick marks.
   - **`OptimusVoiceBox.kt`**: Dual power split-frequency VU meters.
   - **`VoiceBoxRenderer.kt`**: Universal Compose component automatically adapting between portrait and landscape cockpit orientations on Samsung Galaxy S25 Ultra mounts.

---

### Voice first (refined post-M3)

The driver uses KnightLink almost entirely by voice, so each feature is specified as a spoken exchange before any screen:
- **Speak state changes that matter, stay quiet otherwise.** Connection ("connected", "waiting for ignition", "lost the adapter, retrying"), alerts and trip summaries are spoken; routine refreshes never are. Speech goes through the alert manager's priority, de-duplication and quiet periods.
- **Answer questions, don't show tables.** "How's the DPF?", "any warnings?", "can I switch off now?", "what's the coolant temperature?" get one short sentence with the number and what it means. Until M6 these are a fixed, deterministic set behind push-to-talk; M6 widens the set and adds the wake word; M7 adds free-form phrasing, still fact-checked against telemetry.
- **Fault codes are explained, not listed**: severity first, then what it is and what to do; the code itself is on screen for a mechanic.
- **Hands-free failure handling**: the driver never has to look at the phone to know the link dropped or recovered; the shareable connection log and recordings cover the desk side.
- **The screen is secondary**: a large glanceable status, the last spoken message, and settings/sharing. Car Scanner-style dense dashboards, HUD mode and custom gauges are low priority.
- **The voice has a face.** When the phone screen is on and the app is in front, every spoken line is animated by a visualiser driven by the speech's actual loudness envelope (TTS rendered to PCM with `synthesizeToFile`, played through `AudioTrack`, envelope computed per ~20 ms frame, so the animation is in sync and works offline). It also shows listening (push-to-talk open) and thinking states. The style is part of the profile, as data in a theme pack: e.g. `bar-meter` (three vertical LED columns, centre column tallest, symmetric fill from the middle, colour ramp), `ring` (concentric segmented arcs pulsing with the voice), `particle-sphere` (wireframe sphere with a particle halo). Shipped packs are **original designs**; replicas of film or TV props (KITT's voice box, J.A.R.V.I.S.) are trademarked/copyrighted looks and stay private side-loaded packs, as with personas. Screen-off or app-in-background: speech only, no animation work. Respect "reduce motion".
- Long answers are interruptible (media button or "stop"); nothing important is spoken only once without a way to repeat it ("say that again").

### Vehicle knowledge sources (added 2026-11-17)

Layered as in "Definitions" above; each entry keeps its provenance.

| Source | What it gives | Status |
|---|---|---|
| SAE J1979 standard (service 01, 09) | Most emissions and engine data on every OBD car, including the Harrier's DPF, EGT, NOx, SCR, VGT, rail pressure, fuel rate and, where supported, current gear (PID A4) | Author once from the standard as `standard`; the probe's support bitmaps decide what each car polls |
| Our own scans | Which ECUs answer, which standard PIDs each supports, VIN/CALID/ECU names (09, UDS F18x), and in a parked DID sweep which service 22 identifiers answer | Gives *presence*, not *meaning*: a DID sweep returns bytes without a name, unit or formula |
| OBDb (`github.com/OBDb`, CC BY-SA 4.0, 160+ models) | Names and formulas for OEM-specific service 21/22 signals. `Tata` (make level) lists TCM `7E1` gearbox signals (commanded/engaged gear, fluid temperature, over-temperature counters) and many ECM signals; `Hyundai` lists its own TCM DIDs (current gear, ATF temperature). `Tata-Harrier` exists but was empty on 2026-11-17 | Import as `community` candidates in separate, attributed CC BY-SA definition files; try them read-only on the car (parked first) and promote to `verified` only when values behave plausibly (gear follows driving, temperature rises with use) |
| Our own captures and the user's existing apps' **own traffic** on the user's car | Confirmation of candidates; new signals by correlation | `verified` / `inferred` |
| Proprietary databases of other apps (for example Car Scanner's obfuscated profile database) | — | **Not used**: protected by its owner; no decoding, no copying |
| M11 community profiles | Learned, verified profiles from other users' cars | Opt-in upload to our ingest, signed feed; verified signals offered upstream to OBDb |

**Harrier 2020 BS6 candidate sweep (researched 2026-11-17).** Engine: Kryotec 170 = Fiat/Stellantis 2.0 Multijet II family with a Bosch EDC17C69 (tuner listings; the same engine family as the Jeep Compass and MG Hector diesels). Gearbox (automatic): Hyundai-sourced 6-speed torque converter (press reports). After-treatment: DPF + SCR with AdBlue (RDCM at `7E4`). Platform: OmegaArc, derived from Land Rover D8. Candidate sets per ECU, all read-only (`22` reads and `19` fault reads in the default session, parked only, never `2E`/`2F`/`31`/`14`), each kept only after its values behave plausibly:

| ECU (address) | First | Candidates to try |
|---|---|---|
| Engine `7E0` (EDC17C69) | SAE J1979 service 01 set | OBDb `Jeep-Compass`, `MG-Hector`, `Fiat`, `Jeep` (same engine family), then `Tata` |
| Gearbox `7E1` (Hyundai 6AT) | PID `A4` (gear) if supported | OBDb `Hyundai` (`21 A0/A1`, `22 01A0/01A4`: ATF temperature, current gear) and `Tata` (`22 1E12/1E1F/1E69`: commanded/engaged gear, fluid temperature) |
| Reductant `7E4` | service 01 `9B` etc. (already verified) | `Tata` `7E4` entries |
| Body: IPC `700`, BCM `701`, head unit `703`, climate `705`, ESP `750`, steering `752`, VECU `7E3` (addresses as reported by other tools; confirm with our own sweep) | UDS identification DIDs (`F187`, `F18A`, `F191`, `F195`) to name each ECU and supplier; `19 02` faults | `Tata` make-level entries for those headers; `Land-Rover` only where an ECU identifies as D8-derived |

A DID that answers is not yet a DID that means the same thing as in another brand; verification on the car is what promotes a candidate.

What only other people's data can supply: the *meaning* (name, scaling, unit) of manufacturer-specific identifiers, manufacturer fault-code texts (B/C/U and P1xxx codes), which body ECU is which on cars that don't report names, and adapter quirks we haven't met. Everything else our own scans establish.

### M7 detail: local and cloud LLM

- **On-device model**: not bundled in the APK. A model manager downloads a small quantised model (roughly 1–2 GB class) on Wi-Fi, verifies its checksum, checks device RAM/NPU suitability first, and can delete or swap it. Runs through an on-device inference runtime behind `LlmProvider`; the exact model and runtime are picked by benchmark on the S25 Ultra at M7, not fixed now.
- **Bring-your-own cloud key**: one `OpenAiCompatibleProvider` covers OpenRouter, OpenAI and a LAN Ollama server; separate thin providers for Anthropic and Gemini. Keys are stored encrypted with the Android Keystore and never leave the device except to the chosen provider.
- **Capability probe**: on adding a key/model the app runs a short test (tool calling, streaming, structured output, latency, context size) and records a capability descriptor. Features are gated on capabilities, not provider names, and the settings screen shows what the key unlocks (for example free-form questions about the car, trip post-mortems, richer banter) versus what the on-device model or templates already cover.
- Safety alerts stay deterministic regardless of provider.

### Designed in now for M11/M12 (so nothing is rebuilt later)

- Definitions already carry `provenance` and `confidence`; M11 adds `community` entries with contributor counts. Learned profiles export as a **contribution bundle** with the VIN reduced to its non-identifying prefix (manufacturer, model, year) and no location.
- Upload goes through a `ContributionSink` interface in a separate `core/contrib` module; until M11 the only implementation is "export to file". The core never depends on a backend.
- Trip and fill records are stored locally from M8 in the shape M12 will summarise. Uploads are opt-in, off by default, with no account: rotating anonymous ID, start/end of each route trimmed, coarse route cells instead of GPS traces, and the server publishes only aggregates above a minimum sample count.
- Honest limit on M12: economy differences between pumps are small next to traffic, load and driving style, so results are normalised per vehicle model and route type and need many samples before a station is ranked.
- Backend choice (small serverless API + database) is deferred to M11 and recorded as an ADR then. "No accounts" still holds; "no servers" ends at M11.

## Completed Verification & Delivered Artifacts (v0.1 / M3)

The project has achieved the **v0.1 first drivable and voice-enabled milestone**, verified across the full multiplatform test suite:

1. **Android App Unit Tests**:
   - `.\gradlew :app:android:testDebugUnitTest`: **183 / 183 tests passed (0 failures, 0 errors, 100%)**.
   - Covers `KnightPackManagerTest`, `LexiconEngineTest`, `FileVehicleMemoryStoreTest`, `UiStateTest`, and session services.
2. **Core Simulation Suite**:
   - `.\gradlew :core:sim:jvmTest`: **107 / 107 tests passed (100%)**.
   - Validates virtual ECUs, ISO-TP reassembly, ELM327 command queues, session recovery, and golden frame replays.
3. **Voice Multiplatform API**:
   - `.\gradlew :voice:api:jvmTest`: **100% passed**.
4. **Architecture & Lint Guarding**:
   - `.\gradlew :tools:archtest:test`: **Passed**. Ensures zero VIN, Bluetooth MAC, or vehicle literals leak into shared core modules.
   - `.\gradlew :app:android:ktlintCheck`: **Passed (100% clean)**.
5. **Assembled Android APK**:
   - **File**: `app/android/build/outputs/apk/debug/android-debug.apk`
   - **Size**: **18.9 MB** (includes bundled 6.4 MB `KITT.knightpack` asset).

---

## Immediate Next Steps (M4 & M5)

### M4: Arrival Engine (Target: Safe Parking & Cooldown Guidance)
- **Geofences & Commute Tracking**: Register home/office geofences locally without cloud sync.
- **Parking Manoeuvre Detector**: Detect low-speed parking transitions (speed < 10 km/h with steering angle changes).
- **Navigation ETA Integration**: Safe listener for `com.google.android.apps.maps` notifications to predict trip arrival window.
- **Arrival Rules**:
  - If DPF regeneration is active when approaching destination, advise the driver to extend the drive for 3–5 minutes.
  - If turbocharger/EGT temperatures exceed safety thresholds upon parking, trigger spoken cooldown timer before engine shutdown.

### M5: Any Car, Any Adapter (Target: Universal Fleet Interoperability)
- **Progressive Staged Discovery**:
  - Stage 1–4 at connect: adapter identity, supported standard PIDs, VIN/CALID, and ECU sweep.
  - Parked UDS DID sweep: incremental, resumable background sweeps across ECUs (saving progress to `VehicleMemoryStore`).
- **Comprehensive DTC Reading & Plain-Language Explanation**:
  - Interrogate Mode 03, 07, 0A and UDS `19 02` with status mask filtering (confirmed vs pending).
  - Translate raw SAE J2012 fault codes into plain-language spoken advice: what it is, severity rating, and whether it is safe to continue driving.
- **Adapter Chipset Quirks Engine**:
  - Support STN, vLinker, and ELM327 clones with custom timeout profiles and manual flow control.
