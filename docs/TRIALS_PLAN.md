# Trial runs plan (Seltos, Thar Roxx), two days

Status: v3 (adds the vision, addressing layer, twin and guided flow below; v2 sections still apply), 2026-11-23.

## Facts and constraints
- Cars: Kia Seltos (manual petrol), Mahindra Thar Roxx (2026, automatic diesel). Friends' cars, verbal consent, one ~25 km run each, no repeats. The user's iCar Pro 2S over BLE and his phone. Other adapters come after the runs.
- Friends see the app: it must look polished and show a car summary, details and fault codes. Data is exported for later analysis.
- Code facts (verified by the review): `ATSP6` lives in the adapter file `definitions/adapters/vgate-icar-pro-2s.json`, not in the vehicle definition. 29-bit CAN needs changes in several modules (parser, `Elm327Driver.request`, `ReadOnlyPolicy`, scrubber), so it is out of scope. The probe only asks 7E0-7E7 and today loops forever in `WaitingForIgnition` if nothing answers. Fault codes, readiness and the probe already exist in `FullScan` (03/07/0A, `19 02 FF`, `DtcDecoder.classify`). The UI is `HomeScreen.kt` (803 lines) plus `AdapterPicker.kt`, one list of stacked buttons with jargon. The scrubber keeps GPS at ~1 km precision, speed and timestamps.

## Goals of the runs
1. The app works on cars it was not tuned for with no code changes between cars.
2. Learn what is common and what differs (protocol, PIDs, units, fuel behaviour, counters, fault codes).
3. A good first impression: polished screens, clear summary, never a hang or blank screen.

## Work packages, in order
### P1. Never hang, never harm (day 1 AM, must)
- Probe time limit: stop after about 3 probes or 60 s. Then send `ATDPN`, record it, and show a friendly "Can't read this car yet" state with Retry and a short reason. (`VehicleSession.probeUntilVehicleAnswers`, `UiState`.)
- Protocol negotiation (the "understand the communication first" layer, like Car Scanner): a `ProtocolNegotiator` runs before the probe on any unknown car. Steps: `ATSP0` with a long timeout and `0100` (waiting through SEARCHING and BUS INIT), then `ATDPN` to learn what the adapter found, then lock it (`ATSP<n>`). If auto fails, try explicit protocols in a fixed order (6, 8 = 11-bit CAN 500/250; then 7, 9 = 29-bit CAN; then 5, 4, 3, 2, 1 legacy), each with `0100`, at most ~30 s in total. The result (protocol, timings, which attempts failed) is remembered per adapter and VIN prefix and written to the recording. 11-bit CAN is fully supported now; 29-bit CAN is a stretch goal (needs parser, driver, policy and scrubber changes) and legacy protocols end in the friendly state with the detected protocol recorded. The known Harrier path keeps `ATSP6` first.
- Unknown-car safety gate: when the VIN does not match a known definition, run only probe, mode 01 PID support, VIN/CALID (09), readiness and faults (03/07/0A on 7E0-7E7). Disabled for unknown cars: discovery sweep of 700-7F7, the 6,150-request sweep, body stage, `10 01`, passive capture, drive-mode and Harrier-only features. A refusal (NRC 33/7F) from a unit means "not available"; never retry; service 27 stays blocked.
- Gateway/CAN FD cars: if mode 01 gets no answer but the car is awake, record that and show the friendly state.

### P2. Vehicle identity and definition choice (day 1 PM, must)
- Pick the vehicle definition by VIN (WMI plus known prefixes); no Harrier fallback. Unknown car = generic profile built from the probe (supported PIDs, ECUs, VIN, CALID).
- WMI table seeded before the day: Tata, Mahindra, Kia India, Hyundai, Maruti, Toyota, Honda. Verify the Kia India and Mahindra prefixes beforehand against real VINs. Model year from position 10. Model from a short picker when the car does not report it (one tap, stored locally).
- Show gear, DPF, fuel and trip fuel only when the matching PID exists. No fuel estimate from MAF on diesels.
- Hide Harrier-only cards when the VIN is not `MAT`: drive mode buttons, odometer/range entry (kept for the user, shown for any car as optional), tyres, definition coverage, body scan.

### P3. Fault codes and readiness (day 1 PM, must)
- Reuse the FullScan PROBE, READINESS and FAULTS stages (03, 07, 0A, `19 02 FF` only where the unit answered the standard probe). Read-only, no clearing.
- Show confirmed and pending codes; permanent codes labelled "stored by the emissions system". Generic SAE J2012 descriptions for standard codes from a data file; manufacturer codes shown as the code plus "description not available". No severity wording we cannot support.

### P4. Polished UI, three screens (day 2 AM, must)
- One design system on the existing theme packs (dark and light, type scale, spacing, cards).
- Friends see the summary first. Live values are minimal: speed, rpm, coolant temperature, fuel level and a few key items, one tap from the summary.
- Screens: (1) Connect (adapter, big plain status), (2) Car summary (make/model/year, VIN last 4, protocol, fuel level, number of live signals, faults badge, readiness, support label "Full / Basic"), (3) Faults (list and detail). Restyle Live values. Raw data (ECUs, request ids, coverage, rates) moves to a hidden Details/developer page.
- Friendly error and empty states ("Couldn't reach the car. Turn the ignition on and try again.").
- Cut: trip summary. TalkBack labels and contrast checked.
- Do not ship a half-finished seven-screen UI.

### P5. Export, simple (day 2 PM, should)
- No three-table database. A random bundle id per export gives the same separation; the VIN and user notes stay in local vehicle memory (`FileVehicleMemoryStore`).
- "Share this car's findings": a zip with capabilities JSON (protocol, WMI, model year, make/model, supported PIDs, unit ids, CALID/part numbers, which identifiers answered with lengths, fault codes, readiness, app/adapter versions) and the scrubbed recording. In the exported copy: no GPS at all, timestamps relative to start, no VIN.
- Consent screen lists exactly what is in the file and says what is not (VIN, location, name). Friends are offered a copy or deletion on request.
- Destination: the repo is public, so bundles never go into it. For now: Android share sheet to a private Google Drive folder (or any app the user picks). No token in the app. Next step after the runs: a Cloudflare Worker with an R2 bucket on the ByteHeaven account (size limit, rate limit, no listing, private bucket, user pulls bundles with a read key).

### P6. Safety net and verification (day 1 and 2, must)
- Rollback: install the previous build alongside under an `applicationIdSuffix` as a fallback APK.
- Regression gate: replay the Harrier recordings (`AppRecordingReplayTest`, `tools/cli replay`) and the full check before installing.
- Simulator profiles in `core/sim`: a 29-bit car (must end in the friendly state), a slow-to-wake ECU, no PID 2F, a diesel without 5E, a manual with no 7E1; assert exactly which commands are sent on unknown cars.
- Harrier dry run in the evening of day 1 with the definition forced off.

## Instrumentation for one run per car
Recording markers (start, parked checks done, drive start/end), probe timings and `ATDPN`, `0100` bitmaps per unit, measured request rate, sleep behaviour at ignition off, battery voltage.

## Day-of checklist
Phone charged and on a cable, screen on, app exempt from battery restrictions, foreground service running. Ignition on, wait 5 s, connect. If it will not connect: re-seat the adapter, toggle Bluetooth, restart the app, then the fallback APK. Unplug the adapter after each run (it drains the battery).

## Schedule
- Day 1 AM: P1. Day 1 PM: P2, P3 and the simulator profiles; Harrier replay. Evening: Harrier dry run, generic path.
- Day 2 AM: P4. Day 2 PM: P5, fallback APK, Opus review of the diff, device checks, checklist.
- Cut order if late: export polish, Details page, Live values restyle. Never cut P1.

## Open questions for the user
- Is the repo private? (Plan assumes private; if public, bundles must not go in it.)
- Should friends see raw live values or only the summary? (Plan: summary and faults first, live values one tap away.)


---
# v3 additions (2026-11-23)

## Timeline
Three days, not two: day 1 and day 2 are office runs on the user's own Harrier (generic path, Drive Truth, twin calibration); day 3 is the friends' cars. The first run on each friend's car is calibration data for the twin, not a pass/fail test.

## Headline: Drive Truth (from the Opus product research)
At ignition-off the co-pilot speaks and a shareable card appears: cost of the drive in rupees and per km, idle minutes and idle cost, car-reported distance vs GPS distance (speedometer/odometer error), hidden events (cold hard revs, long idling), fault and readiness verdict. Honesty rules: money and fuel figures only when a fuel signal exists (MAF/MAP petrol, fuel-rate PIDs 5E/9D/A2 diesel; else "not available"), the method is labelled, GPS error only on good-accuracy segments, fuel price entered by the user, no tamper claims.
Supporting: Health Passport (60 s parked check, one verdict line, shareable image) and live event voice lines (idle-cost ticker, cold-engine revs; DPF regen guardian only where PIDs 7A/7B/8B exist and a regen happens).
Drive-mode analytics is deferred: no standard OBD signal for the mode; a generic learner (user labels each mode once by voice, torque/pedal/shift features, confidence score) is a later package.

## Addressing layer (P1b, replaces the "29-bit out of scope" note)
Design from the technical research: `BusAddressing` (Can11, Can29) hangs off `ObdProtocol`; it supplies id width, functional/physical request ids, response-id derivation, SH arguments, receive filter and flow-control commands. Parser width is detected from line parity; `Elm327Driver` gets a settable addressing, uses `ATCP18` for 29-bit, drops `require(!extendedIds)`. FullScan, BodyUnitReader, PassiveCapture, PollPlan, CapabilityProbe, FaultReader, RecordingScrubber and VirtualElm327 call the addressing instead of hard-coded 7E8/hex3. 29-bit functional request 18DB33F1, physical 18DA<ECU>F1, responses 18DAF1<ECU>; ECUs found from responders to 0100, never a blind sweep. Safety (29-bit): only 18DB33F1 and 18DA<TA>F1 are allowed, `ATSH`/`ATCP` arguments are validated (closing the gap that a raw ATSH bypasses requestIdViolation), no `10 01` on 29-bit until a body-unit address list exists. Legacy protocols (K-line, J1850) are detected and named only. 29-bit is verifiable only in the simulator until a real 29-bit car is available; the plan says so in the UI and docs.

## Digital twin (simulator) program
Layer 1 replay twin from recordings (Harrier: real timings, ignition-off CAN ERROR/NO DATA, buffer-full flood, three drive modes, DPF regen, refuel). Layer 2 parametric twin from car profile files (protocol/addressing, PID bitmaps, units, scaling, drive cycle, tank and refuels, faults) for other cars; approximate by nature. Layer 3 fault injection (BLE chunking, latency, drops, slow wake, bus sleep, gateway NRC 33, 29-bit car, silent car, adapter reset). Also exposed as the in-app demo car so the UI and voice can be developed and checked without driving.

## Guided flow and garage
Eight live-ticking steps: adapter found, ignition on, car answers (protocol), car identified, parked health check, ready to drive, drive detected, ignition-off then report. One plain sentence and a Retry for each failure. Garage by VIN (salted hash stored locally; fingerprint of CALID + PID bitmap + ECU set when the VIN is missing); known cars greeted by name with the last report; unknown car = three taps (name, model, fuel price).

## Revised order
1. Core generic connection (in progress): negotiator, time limit, safety gate, identity, faults.
2. Addressing layer + twin layers 1 and 3 (29-bit in sim).
3. Drive Truth engine (cost, idle, GPS vs car distance, events), Health Passport data.
4. UI: guided flow, garage, Passport, Drive card, Drive Truth card, Faults, minimal Live; premium design system.
5. Export (share sheet to private Drive), consent screen; fallback APK; Opus reviews at each stage.

---
# v3.1 after the second Opus review (2026-11-23)

- Drive Truth is a cut-down, honest version. No code turns fuel rate into litres or computes distance yet, so a pure `DriveTruth` reducer (own file, golden tests against the Harrier recordings in tools/cli) is built first, then fed live from SessionController. PID A2 is not defined; PID A6 (odometer) is not defined either and is the best car-distance source on a 2026 car: add both to the definitions as optional.
- Wording: "car-reported speed vs GPS, +x %", never "speedometer error" (PID 0D is the ECU speed, not the cluster). Needs a few km of good GPS; no integration across gaps over 2 s (use GPS speed and label it); passive capture off on friend runs.
- Rupees only for DIRECT_RATE (5E/9D, with a stated fuel density, diesel about 0.832 kg/L) or MAF. Speed-density (likely the Seltos 1.5 MPi) shows "approximate" or nothing. A diesel without 5E/9D says "fuel not reported by this car" and never falls back to MAF. Fuel price is entered by the user or shown with a visible default.
- Drive end: rpm 0 and speed 0 for about 60 s, or asleep; never at a traffic-light idle stop.
- 29-bit: for the 3 days it is detect, name and record only (friendly state). The BusAddressing interface and the full runtime come after the friends' run. Close the `ATSH` gap now: pin `ATSH` to 7DF, 7E0-7E7 and the body-unit list; keep `ATCP` off the allowlist.
- Twin: replay layer exists. Add Layer 3 profiles only (silent car, slow wake, no-5E/9D diesel, MAP-only petrol, 29-bit refusal, idle-stop rpm 0, polling gaps), each asserting the exact commands sent and the 'not available' states. No Layer 2, no demo restyle.
- Conflicts: nobody else touches VehicleSession.kt or ReadOnlyPolicy.kt until the core agent's work is committed; one UI agent owns UiState/SessionController/HomeScreen; others add new files. Own worktree per agent, merge order: core, DriveTruth reducer, UI, export. Harrier replay plus full check at every merge.
- UI rules: one dark theme (no three-theme restyle), connect status as one live sentence with ticking steps (no spinner over 60 s), summary leads with make/model/year, health verdict and fault count, every figure shows its method or "not reported by this car", no VIN or hex on the main screens, voice off by default on friend cars.
- Cut: full 29-bit runtime, twin layer 2, garage beyond a "last car" greeting, Passport image share, live event voice lines, DPF guardian, drive-mode learner, Cloudflare upload, Details page polish.
- Days: D1 core committed and gated by Opus; DriveTruth reducer; twin profiles; Harrier office run 1 (generic path). D2 UI (guided connect, summary, faults, Drive Truth card), consent export, fallback APK, ATSH fix; Opus gate on the integrated diff and an audit of every number shown; office run 2 checks the card against the dashboard odometer and the fill. D3 freeze in the morning, checklist, friends' runs.
