# Handover (2026-11-15)

Read this first, then `docs/PLAN.md` (the approved implementation plan with milestones M0–M12) and `docs/adr/README.md`.

## Where things stand

- Repo `https://github.com/AnoopkumarNair/KnightLink`, branch `main`. Last pushed commit: `c542dfd` (demo app: session service, live values screen, recordings sharing; reviewed, with five fixes).
- Milestones M0 and M1 are done. M2 (Android runtime, first APK) is code-complete and waiting for its first real-car session.
- The demo app ran on the user's S25 Ultra on 2026-11-15. A Bluetooth build was handed to the user the same day for the first session in the car.

## Uncommitted work in the main checkout (reviewed, passes the full check)

`./gradlew check :app:android:assembleDebug` passes (83 app unit tests). Commit only with the user's go-ahead.

- The Bluetooth transport, merged from the old worktree branch (`a7dfda2`): `platform/ble-android` (`BleTransport`, `GattLink`, `BleAdapterScanner`, `BlePermissions`), the `knightlink.android-library` convention, `knightlink.core.transport.ble` in `core/transport`, `BleProfileMapping` in `core/adapter`, and `settings.gradle.kts`. The formatting of `requireDone` in `GattLink.kt` is tidied.
- Bluetooth wired into the app: `AppTransportFactory` (Demo goes to `DemoTransportFactory`; Ble builds `BleTransport` from the configured adapter definition's `ble` profile), `ACTION_START_BLE` in `SessionService` (always reaches `startForeground`, including for a refused start), the permission flow (`PermissionPolicy`: ask, then explain, then open settings), the `AdapterPicker` dialog (`ScanResults` reducer, 20 s scan timeout), `LastAdapterStore` (address and name in app-private SharedPreferences), and the connection card with "Connect to <name>" and "Choose another adapter".
- None of the Bluetooth framework code has run on a device yet.

## Next steps, in order

1. First real-car session with the Bluetooth APK (checklist below). Get the scrubbed recording back with "Share latest (VIN removed)" and replay it with `tools/cli replay`. Expect a round of fixes. If the phone is on USB or wireless adb, read `adb logcat` for `SessionService` and GATT errors.
2. Commit and push the Bluetooth work once the user agrees (preferably after the first session's fixes).
3. Then the M2 exit criteria from `docs/PLAN.md` (one-hour drive, drops recovered, trip replayable), and on to M3 (rules and alerts: DPF state machine, cooldown guard, TPMS, spoken alerts).

On-device checklist for the first session: permissions (grant, deny once and get "Try again", deny twice and get "Open settings"); Bluetooth off, then the picker shows "Turn on Bluetooth"; scan finds the adapter by its name `IOS-Vlink`, listed once; connect goes Connecting, Initialising, Probing, Live; vehicle card and live values fill in; unplug the adapter and get Reconnecting, plug it back in and get Live; swipe the app away and the session continues with its notification; Disconnect from the notification; reconnect with "Connect to <name>"; share the recording and check that it holds no VIN and no device address; about 40 connect/disconnect cycles to check for GATT client leaks.

First car attempt (2026-11-15): the scan found `IOS-Vlink` by name, but tapping it in the picker never started a session (no adapter saved, no recording, no crash). The cause is unknown; the logcat had rolled over. Since then each picker row has a Connect button, and the app logs `Adapter picked`, `Bluetooth start command received` and every `Status:` change (tag `KnightLink` / `SessionService`; no address, no VIN). Read `adb logcat` straight after the next attempt.

Comparison with Car Scanner 2.2.42 (decompiled locally for interoperability; nothing copied). It is .NET MAUI with Plugin.BLE, and works with the reference adapter. It connects with `autoConnect=false` over LE, waits 1 s, never requests an MTU, writes in chunks of 17 bytes or fewer with the library default (write with response when the characteristic supports it), and subscribes to notifications. For adapters it does not know, it tries every notify/write pair and keeps the first whose reply to `ATZ` contains "ELM" or `>`, which is how it skips the echo service. KnightLink now matches the first part: write with response preferred, `settleAfterConnect` 1 s, no MTU request by default (`BleTransport.Config.requestedMtu = null`). The `ATZ` probe is a possible fallback for adapters without a definition (not done).

Car Scanner's recordings on the phone (`Android/data/com.ovz.carscanner/files/records/*.brc`, binary, hold the full VIN; keep them off the repo) show it reading fault codes with UDS `19 02 FF` from 700 (IPC), 701 (BCM, SRS), 750 (ESP), 7E0 (engine) and 7E1. That is input for the Harrier definition's ECU list. Its connection logs (`cache/*/log.txt`) are not readable over adb.

Known gaps in the Bluetooth wiring: "Connect to <name>" does not check whether Bluetooth is on (the session just retries); if `startForeground` throws after `startForegroundService`, Android may still raise `ForegroundServiceDidNotStartInTimeException`; some phone makers' Android builds may report a first denial as "blocked".

Connection robustness (2026-11-17, uncommitted, passes the full check): the driver, session and app were hardened for the next car session. Details in the code comments; in short: every link is recorded from `open()` and the recording and the phone's connection log hold session events (`ev` lines, schema 2); `Elm327Driver` warms the adapter up, tolerates unknown non-essential commands (listed in `CapabilityReport.adapter.unsupportedCommands`), retries on STOPPED, waits longer while SEARCHING, and initialises the adapter again after a detected reset; the session re-initialises on silence (`Recovering`), keeps the link while the car does not answer (`WaitingForIgnition`) and only then reconnects. Timeouts, pauses, essential commands and the probe/poll timing scripts live in the adapter definition (`timing`, `probeScript`, `pollScript`, `essentialCommands`). After the next session: "Share connection log" and "Share latest (VIN removed)" in the app, then `tools/cli replay` prints the recorded events first.

## Vehicle safety guarantees (audited 2026-11-17)

- **Read-only, enforced in one place.** `ReadOnlyPolicy` (`core/adapter`) is checked in `Elm327Driver.exchangeOnce`, the only write to the adapter, before any byte leaves the phone. Diagnostic requests: only OBD 01/02/03/06/07/09/0A, UDS 22 and UDS 19 with a reporting sub-function. Refused: 04, 08, 10, 11, 14, 27, 28, 2E, 2F, 31, 34–37, 3D, 3E, 85 and everything else. Adapter commands: an allowlist of volatile settings and queries. `ATPP` (EEPROM), `AT@3`, `ATCV`, `ATWM`/`ATSW` (adapter-originated bus messages), `ATBI`, monitor modes, K-line inits, chipset (`ST`/`VT`) commands and a bare carriage return (repeats the previous command) are refused. Adapter definitions are validated against the same policy when they load. Tests: `ReadOnlyGuardTest`.
- **What the app actually sends today:** services 01 and 09 only. The probe sends `0100` to 7E0–7E7, support bitmaps, and 09 VIN/CALID/ECU name; polling sends service 01 PIDs from the definitions. Adapter commands: `ATD ATZ ATE0 ATL0 ATS0 ATH1 ATSP6 ATAT1 ATST32/64 ATSH ATI AT@1 ATRV ATDPN`. `ATSP6` means no multi-protocol search on the bus.
- **Bus load.** One request in flight at a time, each waiting for its reply or timeout. A hard ceiling of at least 20 ms between diagnostic requests (≤ 50/s; `Elm327Driver.Config.minRequestInterval`, cannot be set below 10 ms). The reference adapter measures about 22 requests/s, about 50–100 CAN frames/s with replies, a few percent of a 500 kbit/s bus, like any scan tool. Re-init and watchdog recovery only send adapter commands. "Waiting for ignition" re-probes at most every 4 s.

## Known open issues

- 29-bit CAN vehicles: the driver rejects them and the probe reports a misleading "every address failed". Planned for M5.
- The driver does not handle the "response pending" negative reply (0x78); slow ECUs look silent.
- Driver timeouts are configurable now (adapter definition `timing`, `VehicleSession.Config.driver`); the values for slow BLE adapters still have to be found in the car.
- No overall time limit on the capability probe.
- Telemetry keeps last values while reconnecting; the rules engine (M3) must check reading age.
- Only OBD service 01 parameters are probed and polled; UDS (service 22) identifiers in a definition are ignored.
- TPMS byte offsets in `definitions/tpms/sensairy.json` are marked `inferred`; they need a raw advertisement capture to confirm.
- The VIN scrubber only finds the VIN in the standard service 09 reply.
- Demo mode runs as an ordinary (not foreground) service by design, so Android may stop it in the background.
- Bluetooth: no pairing/bonding handling; no location-toggle check on Android 11 and older.
- `VirtualElm327.received` grows without bound in a long demo.
- `fixtures/raw/` is still empty: the Phase 0 logs from the SRS were never supplied.

## Working rules agreed with the user

- Subagent-driven development: implementation goes to lower-priced models (Sonnet for design-bearing work, Haiku for mechanical work) with a written brief; the main session reviews every diff, runs the full check, and fixes or sends back. Look for improvements at every stage.
- Commit and push only when the user says so.
- Leave these local, uncommitted items alone and out of commits: `.agents/`, `.vscode/`, and the modified `gradle/wrapper/gradle-wrapper.properties`.
- The user wants visible, testable progress. Prefer getting a build onto the phone over further groundwork.

## Environment notes (this Windows PC)

- JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`. In Git Bash: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot"`. The user's terminal tabs have a stale environment; set `$env:JAVA_HOME` on the same line as the command.
- Android SDK: `C:\Users\Anu\AppData\Local\Android\Sdk`; `local.properties` in the repo root points at it (git-ignored). `adb` is under `platform-tools`.
- GitHub CLI: `C:\Program Files\GitHub CLI\gh.exe`, signed in.
- Gradle wrapper: this PC cannot download Gradle distributions from Java; 8.14.3 is pre-seeded. Do not change the Gradle version.
- No Python. No emulator.
- The phone has not been visible over USB on this PC (the user appears to connect through Parsec). APKs have been delivered as files: `app\android\build\outputs\apk\debug\android-debug.apk`.
- Two agents building in the same checkout at once can crash the shared Kotlin daemon and leave empty jars; recover with `./gradlew --rerun-tasks`. Prefer a worktree for the second agent.
- Leftover scratch worktrees under `.claude\worktrees\` (git-ignored): `agent-a316387c009bdecad` (recorder, already on main) and the remains of `agent-a5097ebd2b41e6950` can be deleted; keep `agent-aaad33eafe81a2bc2` until the Bluetooth module is merged.
