# KnightLink

An AI based car companion that interacts with your car through an OBD adapter.

It reads the car over OBD-II / UDS, learns the vehicle and the driver's habits, and advises by voice. Reference setup: 2020 Tata Harrier, Vgate iCar Pro 2S, Android. The design target is any car and any adapter, on Android first and iOS later.

## Status

Milestone M0 (foundation) and the decoding half of M1 (protocol core) are in place: raw adapter bytes are framed, reassembled and decoded into engineering values using definition files, and checked against captures from the reference vehicle. There is no app yet.

## Layout

| Path | What it is |
|---|---|
| `core/common` | Small shared utilities |
| `core/transport` | `ByteStream` / `Transport` contracts for BLE, classic Bluetooth, TCP, simulator |
| `core/protocol` | ELM327 line parsing and stream framing, ISO-TP reassembly, OBD/UDS responses |
| `core/signals` | Definition file model, safe formula evaluator, signal catalog |
| `core/tpms` | Definition-driven decoder for BLE tyre sensor advertisements |
| `core/adapter` | ELM327 driver (single-writer command queue, init script, header management) |
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

## Adding a vehicle

Add a JSON file under `definitions/vehicles/<make>/`, list the layers it extends, and add golden frames under `fixtures/golden/`. `./gradlew check` validates both. See `docs/adr/README.md` (0002) for the rules.
