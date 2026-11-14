# Architecture decisions

One short record per decision that is expensive to reverse. Add a new numbered file when a decision changes; do not rewrite old ones.

| # | Decision | Status |
|---|---|---|
| 0001 | Kotlin Multiplatform core with Compose Multiplatform UI (replaces Flutter from the SRS) | Accepted |
| 0002 | Vehicle, adapter and sensor knowledge lives in definition files, not code | Accepted |
| 0003 | Local-first; definitions update from a signed static feed; no accounts | Accepted |
| 0004 | Safety alerts are deterministic; the LLM only phrases, and never triggers vehicle writes | Accepted |
| 0005 | Read-only by default; write services are interlocked | Accepted |
| 0006 | Reliable core ships before voice, personas and themes | Accepted |

## 0001 Kotlin Multiplatform

**Context.** Polling, rules and speech must keep running in an Android foreground service with the screen off, and the app needs BLE, classic Bluetooth, a notification listener, wake word and geofencing. Flutter would need a headless Dart engine in the service and a native plugin for each of these.

**Decision.** Shared logic is Kotlin Multiplatform (`core/*`), compiled for JVM and iOS. UI is Compose Multiplatform. Platform code sits behind interfaces declared in shared modules.

**Consequences.** The core runs natively inside the Android service. iOS targets cannot be built on Windows, so CI builds and tests them on macOS. Pure modules target `jvm` + iOS only; the Android app consumes the JVM artefact, so the core has no Android SDK dependency.

## 0002 Definitions as data

**Context.** The goal is any car and any adapter. The SRS hard-codes Harrier PIDs, ECU addresses, GATT UUIDs and sensor MAC addresses.

**Decision.** Everything specific to a vehicle, adapter or sensor brand is a JSON file under `definitions/`, with a schema version, a provenance (`standard`, `verified`, `community`, `inferred`) and formulas in a small expression language that cannot execute code. Layers merge: standard, then vehicle, later learned per-VIN overrides. Parsing is strict; unknown keys fail.

**Consequences.** Supporting a new car is a data change that CI validates. No full VIN, MAC address or location is ever stored in a definition file.

## 0003 Local-first with a definitions feed

**Decision.** All user data stays on the device. Definitions and persona/theme packs update from a static manifest signed with Ed25519, with a minimum app version and rollback to the last good copy. No accounts.

**Consequences.** No backend to run until crowd-sourcing (M11/M12). Those features must be opt-in, anonymous, and reach the core only through the `ContributionSink` interface.

## 0004 Deterministic safety, generative character

**Decision.** Detection and alert decisions are pure functions of telemetry. An LLM may rephrase an alert within a deadline; numbers in its output are checked against the telemetry snapshot, and on a mismatch or timeout the template line is spoken.

## 0005 Read-only by default

**Decision.** Normal operation uses read services only. Clearing fault codes and any UDS write or routine require vehicle speed 0 and an on-screen confirmation, and cannot be started by LLM output alone. Active ECU/DID sweeps never run while the vehicle is moving.

## 0006 Reliable core first

**Decision.** Milestone order is protocol core, Android runtime, rules and alerts (first drivable release), arrival engine, any-car discovery, voice, LLM personas and themes, insights, store release, iOS, then community features.
