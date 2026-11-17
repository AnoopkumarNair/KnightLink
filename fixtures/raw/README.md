# Raw captures

Put original capture files here: ELM327 session logs (`log.txt`) and Android Bluetooth HCI snoop logs (`btsnoop_hci.log`).

Everything in this folder is ignored by git, because raw captures can contain a VIN, Bluetooth MAC addresses and location, except this file and the scrubbed captures named in `.gitignore`. Scrubbed excerpts that tests depend on may also go in `fixtures/golden/`. Every committed capture is scanned for VIN-shaped text by the archtest `DataFilesTest`.

## `harrier-2026-11-17-apartment-drive.jsonl`

The first real-car session, 2020 Tata Harrier BS6 2.0 Kryotec AT, app build `0b12b3f`, Vgate iCar Pro 2S over Bluetooth LE.

- 193 s: link up in 1.3 s, adapter init, capability probe, then polling. The engine was started after connecting and the car driven around an apartment complex: coolant 24 to 42 degC, up to about 1970 rpm and 25 km/h. Ignition off at 189.6 s, disconnected at 192.9 s.
- Schema 2 (session events included). Made with `tools/cli scrub` from the app's recording; the original stays off the repository.
- ECUs found: `7E0` with 41 service 01 PIDs, `7E1` with `01 30 31`, `7E4` with `01 04 05 0C 0D 1C 21 33 46 85 9B`. The tests replay it by command through the probe and expect exactly those lists (`RealCarRecordingTest`).
- **The VIN is not in it.** The adapter returned only the first CAN frame of the VIN reply (see below), which holds the first three characters; the scrubber replaced that one reply with `NO DATA`, as it does for any VIN reply it cannot read with confidence. The scrubber found no other VIN.

### What it shows about the response-count digit

The app of that build appended the ELM327 response-count digit `1` to every request. For a reply that fits one CAN frame that is fine, and it is what lets the adapter return at once instead of waiting out its timeout. For a **multi-frame (ISO-TP) reply it is not**: the adapter stops after the first frame and never sends flow control, so the rest of the reply is never sent. In this recording the requests `01781` (EGT, 11 bytes), `018B1` (DPF, 9 bytes) and `09021` (VIN) were answered only with `7E8 10 0B 41 78 ...`, `7E8 10 09 41 8B ...` and `7E8 10 14 49 02 01 ...`, and no `7E8 21 ...` frame follows. Car Scanner sends the same requests without the digit on the same car and gets all frames.

The stack now sends the digit only for replies the definitions say fit one frame (service byte, PID and data in at most 7 bytes), never for a batched request that is longer, and never for the service 09 items. The simulator (`VirtualElm327`) cuts a multi-frame reply to its first frame when a count is given, as the real adapter does. A by-command replay matches a request without its count digit, so this recording, which holds the digits, still answers the requests of the new stack.
