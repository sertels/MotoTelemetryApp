#!/usr/bin/env python3
"""Diff CAN captures taken in different vehicle states to locate signal bytes.

Feed it two or more capture files from obd_terminal.py's monitor mode, each
taken while the bike held a different known state (gear N vs 1st, upright vs
leaning). For every CAN ID and byte position it reports the bytes that are
STABLE WITHIN each capture but DIFFERENT ACROSS captures - the signature of a
state byte (gear, side stand, ...) as opposed to counters and noise.

Usage:
  python tools/can_diff.py N=can_..._114557.txt 1st=can_..._114449.txt
  python tools/can_diff.py --stability 0.8 N=... 1st=... 2nd=...

Lines carrying the ELM327's '<DATA ERROR' marker are dropped (corrupt
checksum); truncated lines are padded and only compared where bytes exist.
"""

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

LINE_RE = re.compile(r"^([0-9A-Fa-f]{3})([0-9A-Fa-f]{2,16})$")


def parse_capture(path):
    """{can_id: [payload bytes as list of 2-char hex or None], ...}"""
    frames = defaultdict(list)
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or "DATA ERROR" in line or "BUFFER" in line or "STOPPED" in line:
            continue
        m = LINE_RE.match(line)
        if not m:
            continue
        can_id, payload = m.group(1).upper(), m.group(2).upper()
        if len(payload) % 2:  # truncated mid-byte - drop the half byte
            payload = payload[:-1]
        data = [payload[i:i + 2] for i in range(0, len(payload), 2)]
        data += [None] * (8 - len(data))
        frames[can_id].append(data[:8])
    return frames


def dominant(counter, total, threshold):
    """The value covering >= threshold of samples, or None if too noisy."""
    if not total:
        return None
    value, count = counter.most_common(1)[0]
    return value if count / total >= threshold else None


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("captures", nargs="+", metavar="LABEL=FILE")
    ap.add_argument("--stability", type=float, default=0.9,
                    help="fraction of frames a byte must hold its dominant "
                         "value to count as stable (default 0.9)")
    args = ap.parse_args()

    labeled = []
    for spec in args.captures:
        if "=" not in spec:
            sys.exit(f"Expected LABEL=FILE, got {spec!r}")
        label, path = spec.split("=", 1)
        labeled.append((label, parse_capture(path)))

    all_ids = sorted(set().union(*(set(f) for _, f in labeled)))
    hits = []
    for can_id in all_ids:
        for byte_idx in range(8):
            dominants = []
            for label, frames in labeled:
                samples = [f[byte_idx] for f in frames.get(can_id, []) if f[byte_idx] is not None]
                dominants.append((label, dominant(Counter(samples), len(samples), args.stability),
                                  len(samples)))
            values = [d for _, d, _ in dominants]
            # Interesting = stable everywhere, seen everywhere, and not identical everywhere.
            if all(v is not None for v in values) and len(set(values)) > 1:
                hits.append((can_id, byte_idx, dominants))

    if not hits:
        print("No byte is both stable within captures and different across them - "
              "try lowering --stability, or the states didn't actually differ on the bus.")
        return
    print(f"{'CAN ID':<8}{'byte#':<7}" + "".join(f"{label:<12}" for label, _ in labeled))
    for can_id, byte_idx, dominants in hits:
        row = f"{can_id:<8}{byte_idx:<7}"
        for _, value, n in dominants:
            row += f"{value} (n={n})".ljust(12)
        print(row)


if __name__ == "__main__":
    main()
