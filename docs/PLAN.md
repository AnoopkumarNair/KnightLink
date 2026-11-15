# KnightLink — Implementation Plan

## Context

KnightLink is a car-companion app: it reads the car through an OBD adapter, learns the car and the driver's habits, and advises by voice. The SRS (`KNIGHTLINK-SRS-2026-V2.0`) has solid protocol groundwork from a 2020 Tata Harrier + Vgate iCar Pro 2S, but its architecture is a single Flutter app with one extracted package, Harrier facts headed for code, an unspecified background runtime, and several unachievable or unsafe targets. The goal here is a product-grade build: any car, any adapter, Android first then iOS, with modules that can be tested and replaced independently. `D:\Projects\KnightLink` is empty, so this is greenfield.

**Decisions confirmed with the user**

| Topic | Decision |
|---|---|
| Distribution | Public store release with **original** personas; personas are data packs (private KITT-style packs can be side-loaded) |
| Stack | **Kotlin Multiplatform** core + Compose Multiplatform UI |
| Cloud | **Local-first**; vehicle/adapter/persona definitions update from a signed static feed. No accounts. Opt-in crowd-sourcing (vehicle profiles, then anonymous trip/fuel data) arrives later as M11/M12 behind interfaces designed in now |
| First release | **Reliable core first**: connection, dashboard, trip recording, deterministic spoken alerts. Voice/LLM/themes later |
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
| M2 | Android runtime | BLE transport, foreground service, session state machine, scheduler, reconnect, recorder, minimal dashboard, KnightSim phone app | One-hour drive: all drops recovered, trip recorded and replayable on desk |
| M3 | Rules and alerts | DPF state machine, cooldown guard, TPMS evaluator, alert manager, platform TTS, audio focus | **v0.1** — correct spoken alerts on a real regen; zero false alerts across recorded drives |
| M4 | Arrival engine | Geofences, parking-manoeuvre detector, Maps notification ETA plugin, arrival rules from SRS §3.3 | `TDD-DEST` scenarios + a week of commutes with no traffic-light warnings |
| M5 | Any car, any adapter | Staged discovery, generic OBD fallback profile, SPP + Wi-Fi transports, adapter quirks, definitions feed, DTC read and guarded clear | Works on at least two friends' cars without code changes |
| M6 | Voice control | Push-to-talk, STT, deterministic intent router + tool bus, then offline wake word | SRS §5.2 intent matrix passes hands-free |
| M7 | Brain, personas, themes | LLM providers (template, cloud, LAN, on-device), fact guard, original persona packs, theme packs, voice visualiser | Persona lines never contradict telemetry; fallback under 150 ms |
| M8 | Insights | Driving-habit analytics (idling, cold-engine load, interrupted regens, economy trends), fuel-fill detection and per-tank economy (local only), health report export | Weekly report generated from recorded trips |
| M9 | Store release | Privacy policy, permission declarations, opt-in crash reporting, battery/thermal pass, Play beta | Public beta |
| M10 | iOS | iOS app shell, CoreBluetooth validation, capability gaps handled | TestFlight build (needs a Mac) |
| M11 | Community vehicle profiles | Opt-in upload of learned profiles, small ingest backend, review + confidence scoring, publish through the signed feed | A profile learned on one phone reaches another via the feed |
| M12 | Community fuel insights | Opt-in anonymous trip/fill summaries, server-side aggregation, station suggestions along a route | Suggestions shown only where sample size supports them |

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
