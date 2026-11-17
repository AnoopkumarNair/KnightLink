# KnightLink — Implementation Plan

## Context

KnightLink is a car-companion app: it reads the car through an OBD adapter, learns the car and the driver's habits, and advises by voice. The SRS (`KNIGHTLINK-SRS-2026-V2.0`) has solid protocol groundwork from a 2020 Tata Harrier + Vgate iCar Pro 2S, but its architecture is a single Flutter app with one extracted package, Harrier facts headed for code, an unspecified background runtime, and several unachievable or unsafe targets. The goal here is a product-grade build: any car, any adapter, Android first then iOS, with modules that can be tested and replaced independently. `D:\Projects\KnightLink` is empty, so this is greenfield.

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

Each ends with a demonstrable exit test. v0.1 (first drivable release) = end of M3.

| # | Milestone | Contents | Exit |
|---|---|---|---|
| M0 | Foundation | Repo, build-logic, module skeleton, CI, ADRs for the decisions above, fixture import (`fixtures/raw/` + btsnoop converter in `tools/cli`), interface contracts | CI green on empty modules, iOS targets compile |
| M1 | Protocol core + simulator | transport API, ELM327 driver, protocol, signals, definition schema, Harrier profile, tpms decoder, `core/sim`, CLI replay | All SRS OBD/TPMS tests green; real Harrier log replays headless |
| M2 | Android runtime | BLE transport, foreground service, session state machine, scheduler, reconnect, recorder, minimal dashboard, KnightSim phone app. Added 2026-11-17: connection log + session events in recordings; tolerant ELM init; adapter-reset detection with re-init on the same link; silence watchdog; "waiting for ignition"; status words and per-adapter timeouts; full SAE J1979 service 01 set as `standard` definitions (diesel aftertreatment, EGT, NOx, SCR, VGT, rail, EGR, torque, fuel rate, PID A4 gear); multi-PID batching (up to 6 per request, lengths from definitions); measured rates in the recording | One-hour drive: all drops recovered, trip recorded and replayable on desk |
| M3 | Rules and alerts | DPF state machine, cooldown guard, TPMS evaluator, alert manager, platform TTS, audio focus. Added 2026-11-17: spoken connection status ("adapter connected", "waiting for ignition", "connection lost, retrying"); push-to-talk (steering-wheel media button or on-screen) with a deterministic answer set: overall status, DPF/soot, temperatures, fuel, "any warnings?", "is it safe to switch off?"; **speaking visualiser**: while the app is on screen, speech is animated full-screen from the real audio envelope, in the style the active profile names (see "Voice first") | **v0.1** — correct spoken alerts on a real regen; zero false alerts across recorded drives; the fixed questions answered hands-free |
| M4 | Arrival engine | Geofences, parking-manoeuvre detector, Maps notification ETA plugin, arrival rules from SRS §3.3 | `TDD-DEST` scenarios + a week of commutes with no traffic-light warnings |
| M5 | Any car, any adapter | Staged discovery, generic OBD fallback profile, SPP + Wi-Fi transports, adapter quirks, definitions feed, DTC read and guarded clear. Added 2026-11-17: generic read-only body-ECU sweep (parked); UDS identification DIDs (F18x) to name each ECU; DTCs via 03/07/0A and UDS `19 02` with status byte, freeze frames (02, `19 04`), readiness monitors ("will it pass PUC?"), Mode 06; SAE J2012 generic descriptions; every result **spoken in plain language** (what it is, how serious, what to do) and shown second; 0x78 response-pending handling; protocol auto-detect and 29-bit CAN; chipset detection (STN, vLinker) and quirks as data; ISO-TP manual flow-control fallback; WWH-OBD fallback; unknown BLE adapters found by `ATZ` probing of notify/write pairs; community candidate signals (see "Vehicle knowledge sources") verified on the car before use; connection-diagnostics page | Works on at least two friends' cars without code changes |
| M6 | Voice control | Push-to-talk, STT, deterministic intent router + tool bus, then offline wake word | SRS §5.2 intent matrix passes hands-free |
| M7 | Brain, personas, themes | LLM providers (template, cloud, LAN, on-device), fact guard, original persona packs, theme packs (more visualiser styles, each persona choosing its voice, wording and visualiser together) | Persona lines never contradict telemetry; fallback under 150 ms |
| M8 | Insights | Driving-habit analytics (idling, cold-engine load, interrupted regens, economy trends), fuel-fill detection and per-tank economy (local only), health report export. Added 2026-11-17: fuel cost and trip summaries (spoken at trip end, on request), GPS track with trips, CSV export of decoded values | Weekly report generated from recorded trips |
| M9 | Store release | Privacy policy, permission declarations, opt-in crash reporting, battery/thermal pass, Play beta | Public beta |
| M10 | iOS | iOS app shell, CoreBluetooth validation, capability gaps handled | TestFlight build (needs a Mac) |
| M11 | Community vehicle profiles | Opt-in upload of learned profiles, small ingest backend, review + confidence scoring, publish through the signed feed. Added 2026-11-17: verified signals are also offered upstream to OBDb (CC BY-SA) as pull requests, with the user's consent | A profile learned on one phone reaches another via the feed |
| M12 | Community fuel insights | Opt-in anonymous trip/fill summaries, server-side aggregation, station suggestions along a route | Suggestions shown only where sample size supports them |

### Voice first (added 2026-11-17)

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

## First implementation step (on approval)

M0, then straight into M1:
- `settings.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/` convention plugins (`kmp-library`, `android-app`, `quality`)
- Module skeleton under `core/`, `tools/cli`, `app/android`
- `.github/workflows/ci.yml` (Linux + macOS jobs)
- `docs/adr/0001…0006` recording the decisions in the table above
- `definitions/schema/*.json`, `definitions/vehicles/tata/harrier-2020-kryotec-at.json`, `definitions/adapters/vgate-icar-pro-2s.json`, `definitions/tpms/sensairy.json`
- `fixtures/` with golden frames from SRS §2.3/§2.4, plus the user's raw logs once supplied
- M1 code test-first in `core/protocol`, `core/adapter`, `core/signals`, `core/tpms`, `core/sim`

## Needed from the user

- Copy the Phase 0 raw OBD logs and btsnoop captures into `D:\Projects\KnightLink\fixtures\raw\` (the answer on what survives was mixed; if only the SRS frames exist, M1 starts from those and new captures are taken in M2).
- A GitHub repository (private is fine) for CI and, later, the definitions feed.
- Names for the original personas can wait until M7.

## Verification

- `./gradlew check` — all unit, contract and scenario tests, lint, coverage, definition validation.
- `./gradlew :tools:cli:run --args="replay fixtures/raw/<log>"` — decoded signal timeline matches the SRS values (soot 89.02 → 100 → 17.65 %, EGTs, fuel rate, UDS NRC 0x31).
- Second phone running KnightSim: the main app connects, shows live values, and fires the scripted DPF/TPMS alerts.
- In the Harrier: connect, drive, confirm reconnect after adapter power-cycle, then export the trip and replay it on the desk with identical results.
- CI macOS job proves shared modules still compile and pass on iOS.
