# KnightLink architecture: the car digital twin

Status: DRAFT v1 for Opus review, 2026-11-23. Supersedes the "trial runs" scoping in TRIALS_PLAN.md where they differ.

## Two parts
- **Part 1 (this week, 2-3 cars):** gather data at all costs, robustly, and keep it in a structured **car twin**. Everything else (Drive Truth, cost, voice, insights) is an add-on that reads the twin.
- **Part 2 (production):** query the twin with local or remote AI ("what did my last trip cost?", "is my DPF healthy?"), community sharing, packs, store release.

Rule: no car is hard-coded. Code holds generic mechanisms only. Everything car-specific (signals, scalings, modes, quirks, features) is data in a **definition hierarchy**.

## Where car data comes from (honest)
- Universal: SAE J1979 (standard PIDs), J2012/ISO 15031 (fault codes), ISO 15765 (CAN transport), ISO 14229 (UDS). Works on every compliant car; it is the baseline tier.
- Manufacturer-specific data (raw DIDs, cluster/odometer, gear, modes): not published openly. Workshop service information exists but is paid, restricted and per OEM; there is no single site that has it for all makes. What fills the gap: our own scans and learning, community databases (OBDb for signal definitions, opendbc-style CAN databases), and user contributions. Treat anything imported as a *candidate* until verified read-only on the car.

## Layered design
1. **Transport** (BLE now; SPP, Wi-Fi later): bytes in, bytes out. Exists.
2. **Adapter and bus**: adapter definitions as data (quirks, init); `ProtocolNegotiator`; `BusAddressing` (11-bit now, 29-bit later) behind one interface. Exists (negotiator); addressing later.
3. **Evidence log**: the recording. Append-only observations with timestamps and provenance (what was asked, what the car said). The ground truth; the twin can always be rebuilt from it. Exists.
4. **Scan engine**: staged, resumable, time-boxed stages (identify, standard capability probe, faults/readiness, body units where allowed, drive learning). Each stage writes evidence and *facts* with confidence. Exists in pieces (FullScan, probe, body stage, FaultReader).
5. **Car twin** (new, the centre): one versioned document per car instance.
   - Identity: VIN (local only), WMI/year, node in the hierarchy, user-confirmed make/model/fuel/transmission.
   - Capabilities: protocol and addressing, units found (request/response ids), supported PIDs per unit, identifiers that answered (with length), refusals (so we never retry them).
   - Signal catalogue: every available signal with source (standard/pack/learned/user), decoder, unit, confidence, last value time.
   - User facts: odometer entries (with date), tank size, fuel price, name.
   - Learned models: gear ratios, drive-mode signatures, tank size from refuels, odometer-counter offset, consumption.
   - Coverage report: per domain state (below).
   - History: sessions, refuels, trips, fault events.
   Existing `VehicleMemory` is the embryo; it becomes a section of the twin, migrated not rewritten.
6. **Definition hierarchy** (data): `standard` -> `make` -> `model family` -> `generation/year` -> `variant (engine/transmission)` -> instance. Child overrides parent; resolution from VIN (WMI, VDS, year) plus probe evidence; falls back to the nearest ancestor with a confidence. Nodes can carry: signals, scalings, features (drive-mode support, DPF, AdBlue), quirks, candidate identifiers. Signed packs; community candidates carry verification status. The Harrier file becomes one node; its special features (drive modes, body scan, DPF) become node features that any car can declare.
7. **Learners** (plug-ins that read the evidence stream and emit *claims* with evidence and confidence; the twin promotes a claim when rules are met): gear-ratio, refuel/tank-size, odometer-counter finder (a counter that tracks integrated speed), distance (car vs GPS), drive-mode (user labels once), consumption/fuel-rate calibrator, signal typer for unknown identifiers (counter/temperature/state-like). Learners never send requests themselves except through the scan engine's policy.
8. **Orchestrator** (state machine for the guided flow): see below.
9. **Twin API** (Part 2 foundation): typed read API over the twin and evidence (signals, trips, refuels, faults, coverage). Add-ons (Drive Truth, cost, insights) and AI tools use this API only, never raw frames.
10. **Sync/community** (Part 2): a privacy-scrubbed projection of the twin (hierarchy node, capabilities, coverage, answered identifiers, fault codes; no VIN, no location), uploaded by user action; merge by agreement across cars.

## Guided flow for Part 1
1. Connect the adapter to the car; open the app; tap Scan.
2. Scan finds and analyses the adapter (identify, protocol capability, battery voltage).
3. App asks: turn the ignition on / start the car and wait before driving.
4. Identify the car: read VIN and units; match a twin in the garage (existing) or create one (new). New: ask make/model (prefilled from the hierarchy), fuel type, transmission, current odometer; existing: confirm the odometer and fuel, update if different.
5. Parked scan continues (time-boxed, standard first, extra stages only where the node allows) with a visible progress; when ready, notify: "Ready to drive. I will keep learning while you drive."
6. While driving the learners run; the coverage meter updates; when enough is gathered, notify ("learning complete for now").
7. At the end: what was collected, what is missing, and what usually could be connected (from the hierarchy node: signals other cars of this model offer that this one did not answer), and what to try next time.

## Coverage report (the answer to "what is missing")
Per domain, one state: Collected / Partial / Not available on this car (tested and refused, with the reason) / Not tried yet. Domains: identity, engine basics, fuel level and rate, distance and odometer, faults and readiness, gearbox, drive mode, tyres, body units, emissions (DPF/AdBlue). The "usually could be connected" list comes from the hierarchy (what sibling cars offer), plus a hint such as "needs the engine running" or "behind a gateway".

## Part 1 scope (3 days)
- Twin document and store (extends VehicleMemory), evidence unchanged.
- Hierarchy v1: standard, make, model family; Harrier as a node; resolution by VIN; features declared in nodes instead of code (drive-mode UI, body scan, DPF).
- Orchestrator with the flow above; coverage report; ready-to-drive and learning-complete notifications.
- Learners v1: odometer-counter finder, tank size from refuels, gear ratio (exists), distance (car vs GPS).
- Everything built already (negotiator, safety gate, faults, identity, body scan) plugs in as scan stages.
- Drive Truth and the polished UI read the twin API; cut list from TRIALS_PLAN v3.1 still applies. 29-bit stays detect-only.

## Part 2 (after)
Twin API with typed queries, AI tool-calling over it (on-device or remote), community sync and promotion by agreement, Cloudflare intake, packs distribution, drive-mode and DPF features, store release.

## Risks
Scope creep into the twin before data collection works; mitigate by building the twin as a thin typed store over what is already collected, gate each stage with the Harrier replay and the simulator, and keep add-ons out of Part 1 until the flow is solid.
