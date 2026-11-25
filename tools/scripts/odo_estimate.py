#!/usr/bin/env python3
"""Integrated distance of a KnightLink recording, by the polled vehicle speed (PID 0D) and by the phone's GPS.

Validates the app's odometer estimate against the dashboard: prints the distance from the speed (trapezoids between replies at most
5 s apart, like OdometerEstimator), from the GPS (fixes at most 50 m inaccurate, no jumps over 250 km/h, no standing jitter under 3 km/h),
the dashboard readings typed ('odo ... start/end'), the app's own 'odoest' lines, and the scale (end - start) / distance when both exist.

Usage: python tools/scripts/odo_estimate.py <recording.jsonl> [--start 85564] [--end 85593]
"""
import argparse
import json
import math
import sys

# Data bytes of each standard mode 01 PID (SAE J1979), to walk a multi-PID reply. Unknown PID: the rest of the reply is skipped.
PID_LEN = {
    0x00: 4, 0x01: 4, 0x02: 2, 0x03: 2, 0x04: 1, 0x05: 1, 0x06: 1, 0x07: 1, 0x08: 1, 0x09: 1, 0x0A: 1, 0x0B: 1, 0x0C: 2, 0x0D: 1,
    0x0E: 1, 0x0F: 1, 0x10: 2, 0x11: 1, 0x12: 1, 0x13: 1, 0x14: 2, 0x15: 2, 0x16: 2, 0x17: 2, 0x18: 2, 0x19: 2, 0x1A: 2, 0x1B: 2,
    0x1C: 1, 0x1D: 1, 0x1E: 1, 0x1F: 2, 0x20: 4, 0x21: 2, 0x22: 2, 0x23: 2, 0x2C: 1, 0x2D: 1, 0x2E: 1, 0x2F: 1, 0x30: 1, 0x31: 2,
    0x32: 2, 0x33: 1, 0x3C: 2, 0x3D: 2, 0x3E: 2, 0x3F: 2, 0x40: 4, 0x41: 4, 0x42: 2, 0x43: 2, 0x44: 2, 0x45: 1, 0x46: 1, 0x47: 1,
    0x48: 1, 0x49: 1, 0x4A: 1, 0x4B: 1, 0x4C: 1, 0x4D: 2, 0x4E: 2, 0x4F: 4, 0x50: 4, 0x51: 1, 0x52: 1, 0x53: 2, 0x54: 2, 0x55: 2,
    0x56: 2, 0x57: 2, 0x58: 2, 0x59: 2, 0x5A: 1, 0x5B: 1, 0x5C: 1, 0x5D: 2, 0x5E: 2, 0x5F: 1, 0x60: 4, 0x61: 1, 0x62: 1, 0x63: 2,
}
for pid in list(range(0x24, 0x2C)) + list(range(0x34, 0x3C)):
    PID_LEN[pid] = 4

MAX_SPEED_GAP_MS = 5000
MAX_GPS_ACCURACY_M = 50.0
MAX_GPS_KMH = 250.0
MAX_GPS_GAP_MS = 60000
MIN_GPS_KMH = 3.0


def frames_by_sender(text):
    """ISO-TP payloads per sender (7E8 ...) from one adapter reply with headers on ('7E803410D28')."""
    lines = [l.strip().replace(" ", "") for l in text.replace("\r", "\n").split("\n")]
    out = {}
    for line in lines:
        if len(line) < 5 or not all(c in "0123456789ABCDEFabcdef" for c in line):
            continue
        sender, body = line[:3].upper(), bytes.fromhex(line[3:]) if len(line[3:]) % 2 == 0 else None
        if body is None or not body:
            continue
        state = out.setdefault(sender, {"total": None, "data": b""})
        kind = body[0] >> 4
        if kind == 0:  # single frame
            state["data"], state["total"] = body[1:1 + (body[0] & 0x0F)], None
        elif kind == 1 and len(body) >= 2:  # first frame
            state["total"] = ((body[0] & 0x0F) << 8) | body[1]
            state["data"] = body[2:]
        elif kind == 2:  # consecutive frame
            state["data"] += body[1:]
    return {k: (v["data"][: v["total"]] if v["total"] else v["data"]) for k, v in out.items()}


def speed_of(payload):
    """The 0D value of a mode 01 reply (0x41 ...), or None."""
    if not payload or payload[0] != 0x41:
        return None
    i = 1
    while i < len(payload):
        pid = payload[i]
        n = PID_LEN.get(pid)
        if n is None or i + 1 + n > len(payload):
            return None
        if pid == 0x0D:
            return payload[i + 1]
        i += 1 + n
    return None


def haversine_km(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(min(1.0, max(0.0, h))))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording")
    ap.add_argument("--start", type=float, help="dashboard odometer at the start (else the recording's 'odo ... start')")
    ap.add_argument("--end", type=float, help="dashboard odometer at the end (else the recording's 'odo ... end')")
    args = ap.parse_args()

    speed_km = gps_km = 0.0
    last_speed = None  # (t, kmh)
    last_fix = None  # (t, lat, lon)
    samples = fixes = bad_lines = 0
    odo = {"start": None, "end": None}
    odoest = []
    with open(args.recording, encoding="utf-8") as f:
        for raw in f:
            try:
                rec = json.loads(raw)
            except ValueError:
                bad_lines += 1
                continue
            t, kind, x = rec.get("t"), rec.get("d"), rec.get("x", "")
            if t is None:
                continue
            if kind == "rx":
                try:
                    text = bytes.fromhex(x).decode("ascii", "replace")
                except ValueError:
                    bad_lines += 1
                    continue
                payload = frames_by_sender(text).get("7E8")
                kmh = speed_of(payload) if payload else None
                if kmh is None or kmh >= 255:
                    continue
                samples += 1
                if last_speed and 0 < t - last_speed[0] <= MAX_SPEED_GAP_MS:
                    speed_km += (last_speed[1] + kmh) / 2 * (t - last_speed[0]) / 3_600_000
                last_speed = (t, kmh)
            elif kind == "ext":
                parts = x.split()
                if not parts:
                    continue
                if parts[0] == "gps" and len(parts) >= 3:
                    try:
                        lat, lon = float(parts[1]), float(parts[2])
                        acc = float(parts[5]) if len(parts) > 5 and parts[5] != "-" else None
                    except ValueError:
                        continue
                    if acc is not None and acc > MAX_GPS_ACCURACY_M:
                        continue
                    fixes += 1
                    if last_fix and 0 < t - last_fix[0] <= MAX_GPS_GAP_MS:
                        km = haversine_km(last_fix[1:], (lat, lon))
                        kmh = km / ((t - last_fix[0]) / 3_600_000)
                        if kmh > MAX_GPS_KMH:
                            continue  # a jump: keep the last good fix
                        if kmh >= MIN_GPS_KMH:
                            gps_km += km
                    last_fix = (t, lat, lon)
                elif parts[0] == "odo" and len(parts) >= 3 and parts[1] != "-":
                    odo[parts[2]] = float(parts[1])
                elif parts[0] in ("odoest", "odoest-final"):
                    odoest.append((t, x))

    start = args.start if args.start is not None else odo["start"]
    end = args.end if args.end is not None else odo["end"]
    print(f"speed samples: {samples}, GPS fixes: {fixes}" + (f", unreadable lines: {bad_lines}" if bad_lines else ""))
    print(f"distance by speed: {speed_km:.2f} km")
    print(f"distance by GPS:   {gps_km:.2f} km")
    if start is not None:
        print(f"dashboard start: {start:.0f} km -> estimate now {start + speed_km:.1f} km (speed), {start + gps_km:.1f} km (GPS)")
    if start is not None and end is not None:
        dash = end - start
        print(f"dashboard end:   {end:.0f} km, driven {dash:.0f} km by the dashboard")
        if speed_km > 0 and dash > 0:
            print(f"scale (dashboard / speed) = {dash / speed_km:.4f}")
        if gps_km > 0 and dash > 0:
            print(f"scale (dashboard / GPS)   = {dash / gps_km:.4f}")
    if odoest:
        print(f"app estimate: {odoest[-1][1]} (t={odoest[-1][0] / 1000:.0f} s, {len(odoest)} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
