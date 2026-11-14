# KnightLink

An AI based car companion that interacts with your car through an OBD adapter.

It reads the car over OBD-II / UDS, learns the vehicle and the driver's habits, and advises by voice. Reference setup: 2020 Tata Harrier, Vgate iCar Pro 2S, Android. The design target is any car and any adapter, on Android first and iOS later.

## Status

Milestones M0 (foundation) and M1 (protocol core and simulator) are in place: the full path from adapter bytes to engineering values runs against a simulated vehicle and is checked against captures from the reference vehicle. Replay of real drive logs waits for the first recordings. M2 has started: a read-only capability probe and an Android app shell that proves the shared core runs on a phone. The app does not connect to an adapter yet.

## Layout

| Path | What it is |
|---|---|
| `core/common` | Small shared utilities |
| `core/transport` | `ByteStream` / `Transport` contracts for BLE, classic Bluetooth, TCP, simulator |
| `core/protocol` | ELM327 line parsing and stream framing, ISO-TP reassembly, OBD/UDS responses |
| `core/signals` | Definition file model, safe formula evaluator, signal catalog |
| `core/tpms` | Definition-driven decoder for BLE tyre sensor advertisements |
| `core/adapter` | ELM327 driver (single-writer command queue, init script, header management) |
| `core/scheduler` | Polling scheduler (shares the adapter's limited request rate fairly) and polling loop |
| `core/telemetry` | Turns diagnostic replies into readings; builds the poll list from a vehicle's definitions |
| `core/vehicle` | Read-only capability probe: finds the ECUs, their supported PIDs, VIN and names, and compares them with a vehicle definition |
| `core/sim` | Virtual ECUs and a virtual ELM327 adapter, driven by scenario files in `fixtures/sim/` |
| `app/android` | Android application (currently a one-screen shell) |
| `tools/cli` | Developer tool: run a simulated vehicle through the real stack, or decode captured frames |
| `definitions/` | Vehicle, adapter and sensor knowledge as data |
| `fixtures/` | Golden frames used by tests; `raw/` holds local, git-ignored captures |
| `docs/adr/` | Architecture decisions |
| `build-logic/` | Gradle convention plugins |

Vehicle-specific knowledge belongs in `definitions/`, never in `core/`.

## Build and test

Requires JDK 21. Building the Android app additionally needs the Android SDK (platform 36 and build-tools 36): create a git-ignored `local.properties` in the repository root with `sdk.dir=/path/to/Android/Sdk` (on Windows: `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`), or set `ANDROID_HOME`.

```bash
./gradlew check
```

Debug APK (`app/android`, application id `in.byteheaven.knightlink`):

```bash
./gradlew :app:android:assembleDebug
adb install -r app/android/build/outputs/apk/debug/android-debug.apk
```

The app consumes the JVM artefacts of the shared modules, so `core/*` has no Android dependency. When no SDK is configured the build skips `app/android` (it says so), and `./gradlew check` still covers everything else with only a JDK.

This compiles every module for JVM and iOS and runs all tests, including validation of every file under `definitions/` and the golden frames under `fixtures/golden/`. iOS tests run on macOS only (CI does this).

`check` also runs ktlint on every module (rules in `.editorconfig`; `./gradlew ktlintFormat` fixes most findings) and the architecture guard tests in `tools/archtest` (no vehicle-specific names or BLE UUID/MAC literals in `core/`, allowed module dependencies, no real VINs in data files). `./gradlew koverXmlReport` writes a merged coverage report for the core modules to `build/reports/kover/report.xml`.

## Try it without a car

Run a simulated Harrier through a DPF regeneration (virtual ECUs, virtual adapter, real driver, scheduler and definitions):

```bash
./gradlew -q :tools:cli:run --args="simulate fixtures/sim/harrier-dpf-regen.json 24"
```

Probe the simulated car the way the app would probe an unknown one (read-only: adapter commands and OBD services 01 and 09 only), and compare what it reports with the vehicle definition. Add `--json` for the report as JSON (VIN redacted):

```bash
./gradlew -q :tools:cli:run --args="probe fixtures/sim/harrier-dpf-regen.json"
```

Decode frames captured from an adapter:

```bash
./gradlew -q :tools:cli:run --args="decode vehicles/tata/harrier-2020-kryotec-at '7E8 03 41 05 6B'"
```

## Adding a vehicle

Add a JSON file under `definitions/vehicles/<make>/`, list the layers it extends, and add golden frames under `fixtures/golden/`. `./gradlew check` validates both. See `docs/adr/README.md` (0002) for the rules.
