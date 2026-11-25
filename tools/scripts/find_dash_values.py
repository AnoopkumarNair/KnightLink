"""Find dashboard values (odometer, range-to-empty, ...) in passively captured CAN broadcast frames.

usage: python find_dash_values.py REC.jsonl[.gz] ... --value odo=85548 --value dte=439 [--value odo2=85556]

Searches every frame heard in monitor mode (ATMA lines) for each value as an unsigned integer of 2, 3 or 4 bytes, big or little endian,
scaled by 1, 10 and 100 (0.1 km, 0.01 km), at every byte offset. Prints, per value, the identifiers and offsets that hit, with counts, so a
candidate can be confirmed by the same field moving by the right amount between the start and the end of a drive. Frame contents stay local.
"""
import argparse, collections, gzip, json, re

FRAME = re.compile(r'^[0-9A-F]{3}(?:[0-9A-F]{2}){0,8}$')


def frames(path):
    op = gzip.open if path.endswith('.gz') else open
    with op(path, 'rt', encoding='utf-8') as f:
        for line in f:
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if d.get('d') != 'rx':
                continue
            try:
                text = bytes.fromhex(d['x']).decode('ascii', 'ignore')
            except ValueError:
                continue
            for ln in text.replace('\n', '\r').split('\r'):
                c = ''.join(ln.split()).upper()
                if FRAME.match(c) and not c.startswith(('7E', '70', '71', '72', '73', '74', '75')):
                    yield int(c[:3], 16), bytes.fromhex(c[3:]), d.get('t')


def encodings(value):
    for scale in (1, 10, 100):
        v = value * scale
        for n in (2, 3, 4):
            if v < 256 ** n:
                for order in ('big', 'little'):
                    yield f'x{scale} {n}B {order}', v.to_bytes(n, order)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--value', action='append', required=True, help='name=number as shown on the dashboard')
    a = ap.parse_args()
    targets = {}
    for s in a.value:
        name, num = s.split('=')
        targets[name] = int(float(num))
    total = 0
    hits = {n: collections.Counter() for n in targets}
    for p in a.files:
        for fid, data, t in frames(p):
            total += 1
            for name, val in targets.items():
                for label, pat in encodings(val):
                    i = data.find(pat)
                    while i != -1:
                        hits[name][(f'{fid:03X}', i, label)] += 1
                        i = data.find(pat, i + 1)
    print(f'{total} broadcast frames read')
    for name, c in hits.items():
        print(f'\n{name} = {targets[name]}: {sum(c.values())} hits')
        for (fid, off, label), n in c.most_common(15):
            print(f'  id {fid} byte {off} {label}: {n}')


if __name__ == '__main__':
    main()
