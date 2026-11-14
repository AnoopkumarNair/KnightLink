# KnightLink

An AI based car companion that interacts with your car through an OBD adapter.

It reads the car over OBD-II / UDS, learns the vehicle and the driver's habits, and advises by voice. Reference setup: 2020 Tata Harrier, Vgate iCar Pro 2S, Android. The design target is any car and any adapter, on Android first and iOS later.

## Status

Milestones M0 (foundation) and M1 (protocol core and simulator) are in place: the full path from adapter bytes to engineering values runs against a simulated vehicle and is checked against captures from the reference vehicle. Replay of real drive logs waits for the first recordings. There is no Android app yet; that is M2.

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
| `core/sim` | Virtual ECUs and a virtual ELM327 adapter, driven by scenario files in `fixtures/sim/` |
| `tools/cli` | Developer tool: run a simulated vehicle through the real stack, or decode captured frames |
| `definitions/` | Vehicle, adapter and sensor knowledge as data |
| `fixtures/` | Golden frames used by tests; `raw/` holds local, git-ignored captures |
| `docs/adr/` | Architecture decisions |
| `build-logic/` | Gradle convention plugins |

Vehicle-specific knowledge belongs in `definitions/`, never in `core/`.

## Build and test

Requires JDK 21.

```bash
./gradlew check
```

This compiles every module for JVM and iOS and runs all tests, including validation of every file under `definitions/` and the golden frames under `fixtures/golden/`. iOS tests run on macOS only (CI does this).

## Try it without a car

Run a simulated Harrier through a DPF regeneration (virtual ECUs, virtual adapter, real driver, scheduler and definitions):

```bash
./gradlew -q :tools:cli:run --args="simulate fixtures/sim/harrier-dpf-regen.json 24"
```

Decode frames captured from an adapter:

```bash
./gradlew -q :tools:cli:run --args="decode vehicles/tata/harrier-2020-kryotec-at '7E8 03 41 05 6B'"
```

## Adding a vehicle

Add a JSON file under `definitions/vehicles/<make>/`, list the layers it extends, and add golden frames under `fixtures/golden/`. `./gradlew check` validates both. See `docs/adr/README.md` (0002) for the rules.
