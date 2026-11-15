# Handover (2026-11-15)

Read this first, then `docs/PLAN.md` (the approved implementation plan with milestones M0–M12) and `docs/adr/README.md`.

## Where things stand

- Repo `https://github.com/AnoopkumarNair/KnightLink`, branch `main`. Last pushed commit: `2ae0f79` (vehicle session runtime and session recording). CI is green on it (Linux + iOS simulator, debug APK artifact).
- Milestones M0 and M1 are done. M2 (Android runtime, first APK) is mostly done; what is left is listed under "Next steps".
- The demo app was installed on the user's S25 Ultra on 2026-11-15 and the user confirmed it worked (simulated vehicle, live values, running through the real stack on the phone).

## Uncommitted work in the main checkout (do not lose it)

The demo app, written by a subagent. `./gradlew check :app:android:assembleDebug` passes with it (48 app unit tests). It is NOT yet reviewed line by line and NOT committed.

- `app/android/**`: foreground-capable `SessionService`, `SessionController`, `DemoTransportFactory` (simulated vehicle with looping scenario), `DefinitionStore` reading bundled assets, recordings with retention and a "share latest (VIN removed)" flow, Compose `HomeScreen`, `SessionViewModel`. The old `SharedCoreDemo` files are deleted.
- `build-logic/src/main/kotlin/knightlink.android-app.gradle.kts`, `gradle/libs.versions.toml`, `README.md`.
- Also uncommitted: `docs/PLAN.md` and this file.

Review focus before committing: `SessionController` (scopes, one-connection guard, failure states), `SessionService` (lifecycle, notification, the `needsForegroundService` seam), `DemoTransportFactory` (mutex around the single-threaded simulator), `RecordingShare` (never shares an unscrubbed file). The README layout row for `app/android` still says "one-screen shell" and needs updating.

## Finished but not merged: Android Bluetooth transport

- Worktree `D:\Projects\KnightLink\.claude\worktrees\agent-aaad33eafe81a2bc2`, branch `worktree-agent-aaad33eafe81a2bc2`, commit `a7dfda2` on top of `2ae0f79`. Its `./gradlew check` passed there (61 new tests).
- Adds `platform/ble-android` (`BleTransport`, `GattLink`, `BleAdapterScanner`, `BlePermissions`), a `knightlink.android-library` convention, pure GATT logic in `core/transport` (`knightlink.core.transport.ble`: `GattProfileResolver`, `BleSerialProfile`, `chunkForWrite`), and `AdapterDefinition.BleProfile.toSerialProfile()` in `core/adapter`.
- `GattLink.kt` was reviewed and is sound (single teardown path, one GATT operation in flight, client released exactly once). One cosmetic issue: `requireDone` has ktlint-mangled formatting.
- None of the framework-touching Bluetooth code has run on a device.
- To merge: from the main checkout, `git diff 2ae0f79 worktree-agent-aaad33eafe81a2bc2 | git apply --reject --whitespace=nowarn`. Expect one reject in `settings.gradle.kts` (add `include(":platform:ble-android")` inside the existing `androidSdkConfigured` block). Then run the full check.

## Next steps, in order

1. Review the demo app, fix findings, then ask the user before committing and pushing.
2. Merge the Bluetooth module (above) and run `./gradlew check :app:android:assembleDebug`.
3. Wire Bluetooth into the app (one subagent task):
   - runtime permission flow using `BlePermissions`, and merge of the library manifest permissions;
   - device picker backed by `BleAdapterScanner` (name match must work alone; the adapter may not advertise its service);
   - `TransportFactory` implementation for `ConnectionTarget.Ble` returning `BleTransport` with the profile from the adapter definition;
   - `SessionService` calls `startForeground` with the `connectedDevice` type for a real link (the seam `needsForegroundService()` already exists);
   - enable the "Connect adapter (Bluetooth)" button; remember the last adapter.
4. First real-car session: install on the S25, connect to the Vgate iCar Pro 2S in the Harrier, run the capability probe, poll, then share the scrubbed recording back. Use the on-device checklist from the Bluetooth subagent's report (permissions denied/granted, scan by name, MTU, write type, echo service never subscribed, unplug mid-session, 40 connect/fail cycles for client leaks). Expect fixes from this session; replay the recording on the PC with `tools/cli replay` to reproduce problems.
5. Then M2 exit criteria from `docs/PLAN.md` (one-hour drive, drops recovered, trip replayable), and on to M3 (rules and alerts: DPF state machine, cooldown guard, TPMS, spoken alerts).

## Known open issues

- 29-bit CAN vehicles: the driver rejects them and the probe reports a misleading "every address failed". Planned for M5.
- The driver does not handle the "response pending" negative reply (0x78); slow ECUs look silent.
- Driver timeouts (3 s, 6 s for reset) are not configurable from the session config; slow BLE adapters may need that.
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
