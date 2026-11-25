"""Find dashboard values (odometer, range-to-empty, ...) in the UDS replies (62 xx xx ...) of a body unit in a KnightLink recording.

usage: python find_did_values.py REC.jsonl[.gz] ... --value odo=85548 --value dte=439 [--src 708] [--list]

Reads the adapter's raw replies (rx, headers on: `708 10 1B 62 F1 81 ...`), reassembles ISO-TP (single, first and consecutive frames) per
response identifier, keeps the positive ReadDataByIdentifier replies (62 DID data) and also the app's own notes
(`700 instrument cluster: 22 F4A6 -> positive, 4 data bytes: ...`). For each value it lists every DID whose data holds it as an unsigned
integer of 2, 3 or 4 bytes, big or little endian, scaled by 1, 10 and 100, at any offset. --list prints every DID seen with its last data.
Compare a drive's start and end: the right field moves by the distance driven (odometer up, range down). Data stays local.
"""
import argparse, collections, gzip, json, re

NOTE = re.compile(r'^(?P<unit>[0-9A-F]{3}) [^:]*: 22 (?P<did>[0-9A-F]{4}) -> positive, \d+ data bytes: (?P<data>[0-9A-F]*)')
LINE = re.compile(r'^[0-9A-F]{3}(?:[0-9A-F]{2}){1,8}$')


def records(path):
    op = gzip.open if path.endswith('.gz') else open
    with op(path, 'rt', encoding='utf-8') as f:
        for line in f:
            try:
                yield json.loads(line)
            except ValueError:
                continue


def replies(path, src):
    """Yields (t, did, data) for every positive 22 reply from response id `src` (an int)."""
    pending = {}  # source id -> [expected length, bytes so far]
    rest = ''
    for d in records(path):
        kind, t = d.get('d'), d.get('t')
        if kind == 'ev':
            m = NOTE.match(d.get('x', '').split('body-scan: ', 1)[-1])
            if m and int(m.group('unit'), 16) + 8 == src:
                yield t, int(m.group('did'), 16), bytes.fromhex(m.group('data'))
            continue
        if kind != 'rx':
            continue
        try:
            text = rest + bytes.fromhex(d['x']).decode('ascii', 'ignore')
        except ValueError:
            continue
        parts = text.replace('\n', '\r').split('\r')
        rest = parts.pop()  # a line cut between two reads goes on in the next one
        if rest.strip().endswith('>'):
            rest = ''
        for ln in parts:
            c = ''.join(ln.split()).upper()
            if not LINE.match(c) or int(c[:3], 16) != src:
                continue
            frame = bytes.fromhex(c[3:])
            kind_nibble = frame[0] >> 4
            payload = None
            if kind_nibble == 0:
                payload = frame[1:1 + (frame[0] & 0x0F)]
            elif kind_nibble == 1 and len(frame) >= 2:
                pending[src] = [((frame[0] & 0x0F) << 8) | frame[1], bytearray(frame[2:])]
            elif kind_nibble == 2 and src in pending:
                pending[src][1] += frame[1:]
                if len(pending[src][1]) >= pending[src][0]:
                    n, buf = pending.pop(src)
                    payload = bytes(buf[:n])
            if payload and len(payload) >= 3 and payload[0] == 0x62:
                yield t, (payload[1] << 8) | payload[2], payload[3:]


def encodings(value):
    for scale in (1, 10, 100):
        v = value * scale
        for n in (2, 3, 4):
            if v < 256 ** n:
                for order in ('big', 'little'):
                    yield f'x{scale} {n}B {order}', v.to_bytes(n, order)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('files', nargs='+')
    ap.add_argument('--value', action='append', default=[], help='name=integer, e.g. odo=85548')
    ap.add_argument('--src', default='708', help='response identifier in hex (default 708, the instrument cluster)')
    ap.add_argument('--list', action='store_true', help='print every DID seen with its last data')
    a = ap.parse_args()
    src = int(a.src, 16)
    values = []
    for v in a.value:
        name, _, num = v.partition('=')
        values.append((name, int(num)))
    seen = collections.OrderedDict()
    hits = collections.defaultdict(lambda: collections.Counter())
    for path in a.files:
        for t, did, data in replies(path, src):
            seen[did] = (path, t, data)
            for name, value in values:
                for label, needle in encodings(value):
                    i = data.find(needle)
                    while i >= 0:
                        hits[name][(did, i, label)] += 1
                        i = data.find(needle, i + 1)
    if a.list or not values:
        print(f'{len(seen)} DIDs answered by {a.src}:')
        for did, (path, t, data) in sorted(seen.items()):
            print(f'  {did:04X} ({len(data)} bytes) {data.hex().upper()}')
    for name, value in values:
        print(f'{name} = {value}:')
        if not hits[name]:
            print('  no DID holds it')
        for (did, offset, label), count in sorted(hits[name].items(), key=lambda kv: -kv[1]):
            print(f'  DID {did:04X} offset {offset} {label}: {count} replies')


if __name__ == '__main__':
    main()
