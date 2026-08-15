#!/usr/bin/env python3
"""Talk to the bike's ELM327 Bluetooth OBD adapter directly from a laptop.

Windows exposes a paired SPP device as an outgoing "Standard Serial over
Bluetooth link (COMx)" port; this drives it with the same command discipline
the app's BluetoothOBDManager uses, so captures taken here are comparable
with the app's diagnostics exports.

Usage:
  python tools/obd_terminal.py ports                 # list candidate COM ports
  python tools/obd_terminal.py probe                 # verify ELM327 + read RPM/speed/coolant
  python tools/obd_terminal.py cmd ATRV 010C 22D10D  # send raw commands, print responses
  python tools/obd_terminal.py ids 10                # 10s capture, histogram of CAN IDs seen
  python tools/obd_terminal.py monitor 30            # 30s full-bus ATMA capture to a file
  python tools/obd_terminal.py monitor 30 --id 130   # 30s capture filtered to CAN ID 130 (ATCRA)

Add --port COM5 to skip autodetection. Captures land in tools/captures/.

The full-bus monitor WILL hit BUFFER FULL constantly on a running engine -
clone adapters' internal UART (typically fixed at 38400 baud) can't carry a
500 kbit/s bus. That's the point of --id: filtering happens inside the
adapter, so a single ID streams gap-free. Hunt workflow: `ids` first to see
what's broadcasting, then `monitor --id` per candidate while moving the bike
(lean, gear change, fuel level) and diff the payload bytes.
"""

import argparse
import re
import sys
import time
from datetime import datetime
from pathlib import Path

import serial
from serial.tools import list_ports

for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

CAPTURE_DIR = Path(__file__).resolve().parent / "captures"
PROMPT = b">"
READ_TIMEOUT_S = 3.0


def candidate_ports():
    """Outgoing Bluetooth SPP ports (the incoming one has an all-zero MAC)."""
    out = []
    for p in list_ports.comports():
        if "Bluetooth" not in (p.description or ""):
            continue
        if "000000000000" in (p.hwid or ""):
            continue
        out.append(p)
    return out


def open_port(port_name):
    if port_name is None:
        ports = candidate_ports()
        if not ports:
            sys.exit("No outgoing Bluetooth serial port found - is the adapter paired?")
        if len(ports) > 1:
            names = ", ".join(p.device for p in ports)
            sys.exit(f"Several candidates ({names}) - pick one with --port.")
        port_name = ports[0].device
    print(f"[{port_name}] opening...")
    # Baud rate is nominal over BT SPP; the timeout only bounds single reads,
    # the real deadlines are enforced per response below.
    return serial.Serial(port_name, 38400, timeout=0.1)


def send(ser, cmd, deadline_s=READ_TIMEOUT_S, quiet=False):
    """One request/response round trip, ended by the '>' prompt or the deadline."""
    ser.reset_input_buffer()
    ser.write((cmd + "\r").encode("ascii"))
    ser.flush()
    buf = bytearray()
    deadline = time.monotonic() + deadline_s
    while time.monotonic() < deadline:
        chunk = ser.read(256)
        if chunk:
            buf += chunk
            if PROMPT in buf:
                break
    text = buf.decode("ascii", errors="replace").replace(">", "").strip()
    if not quiet:
        print(f"  {cmd:<10} -> {text!r}")
    return text


def init_elm(ser):
    resp = send(ser, "ATZ", deadline_s=5.0)
    if "ELM327" not in resp.upper():
        sys.exit(f"Adapter did not identify as ELM327 (got {resp!r}).")
    for cmd in ("ATE0", "ATL0", "ATSP0"):
        send(ser, cmd)


def cmd_ports(_args):
    ports = candidate_ports()
    if not ports:
        print("No outgoing Bluetooth serial ports. Pair the adapter first "
              "(Settings > Bluetooth & devices; PIN is usually 1234 or 0000).")
    for p in ports:
        print(f"{p.device}: {p.description} [{p.hwid}]")


def cmd_probe(args):
    with open_port(args.port) as ser:
        init_elm(ser)
        send(ser, "ATRV")   # adapter's battery-voltage reading
        send(ser, "010C")   # RPM
        send(ser, "010D")   # speed
        send(ser, "0105")   # coolant
    print("OK - adapter answers.")


def cmd_raw(args):
    with open_port(args.port) as ser:
        init_elm(ser)
        for c in args.commands:
            send(ser, c)


def run_monitor(ser, seconds, can_id, spaces_off=True):
    """ATMA capture with buffer-full restarts; returns the captured lines.

    spaces_off (ATS0) saves ~25% serial bandwidth, but glues the CAN ID to the
    data bytes - anything that needs to tokenize lines (the ids histogram)
    must capture with spaces kept on instead.
    """
    send(ser, "ATH1")
    if spaces_off:
        send(ser, "ATS0")
    if can_id:
        send(ser, f"ATCRA{can_id}")
    frames = []
    line = bytearray()
    deadline = time.monotonic() + seconds
    last_report = 0
    try:
        ser.reset_input_buffer()
        ser.write(b"ATMA\r")
        ser.flush()
        while time.monotonic() < deadline:
            chunk = ser.read(1024)
            if not chunk:
                continue
            for byte in chunk:
                if byte in (0x0D, 0x0A):
                    text = line.decode("ascii", errors="replace").strip()
                    line.clear()
                    if not text or text == "ATMA":
                        continue
                    frames.append(text)
                    if "BUFFER" in text and "FULL" in text:
                        ser.write(b"ATMA\r")
                        ser.flush()
                else:
                    line.append(byte)
            if len(frames) - last_report >= 500:
                last_report = len(frames)
                print(f"  ...{len(frames)} frames")
    finally:
        # Any byte stops monitor mode; then undo the capture-only settings so
        # the adapter is left exactly as the app's poll loop expects it.
        ser.write(b" ")
        ser.flush()
        time.sleep(0.3)
        ser.reset_input_buffer()
        if can_id:
            send(ser, "ATAR", quiet=True)  # drop the CRA filter
        send(ser, "ATH0", quiet=True)
        if spaces_off:
            send(ser, "ATS1", quiet=True)
    return frames


def cmd_monitor(args):
    with open_port(args.port) as ser:
        init_elm(ser)
        label = f"id {args.id}" if args.id else "full bus"
        print(f"Capturing {label} for {args.seconds}s...")
        frames = run_monitor(ser, args.seconds, args.id)
    CAPTURE_DIR.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    suffix = f"_id{args.id}" if args.id else ""
    out = CAPTURE_DIR / f"can_{stamp}{suffix}.txt"
    out.write_text("\n".join(frames) + "\n", encoding="utf-8")
    print(f"{len(frames)} lines -> {out}")


def cmd_watch(args):
    """Poll one command and print only the moments its response changes -
    made for mapping stateful DIDs by hand: start it, work the control
    (shift gears, squeeze a brake), read the raw byte off the timeline."""
    with open_port(args.port) as ser:
        init_elm(ser)
        if args.header:
            send(ser, f"ATSH{args.header}")
        print(f"Watching {args.command} for {args.seconds}s "
              f"(every {args.interval}s, header {args.header or 'default'})...")
        last = None
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            resp = send(ser, args.command, quiet=True).replace("\r", " ").strip()
            if resp != last:
                stamp = datetime.now().strftime("%H:%M:%S")
                print(f"  {stamp}  {resp!r}")
                last = resp
            time.sleep(args.interval)
    print("Done.")


def cmd_ids(args):
    with open_port(args.port) as ser:
        init_elm(ser)
        print(f"Sampling the bus for {args.seconds}s...")
        frames = run_monitor(ser, args.seconds, None, spaces_off=False)
    counts = {}
    for f in frames:
        # With spaces on, the first token is the CAN ID (3 hex digits for
        # 11-bit, 8 for 29-bit); anything else is adapter chatter (BUFFER
        # FULL, STOPPED) and gets skipped.
        token = f.split()[0] if f.split() else ""
        if re.fullmatch(r"[0-9A-Fa-f]{3}|[0-9A-Fa-f]{8}", token):
            counts[token] = counts.get(token, 0) + 1
    if not counts:
        print("No CAN IDs seen - engine/ignition on? headers (ATH1) acked?")
        return
    print(f"{'CAN ID':<10}{'frames':>8}")
    for can_id, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"{can_id:<10}{n:>8}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", help="COM port (default: autodetect)")
    sub = ap.add_subparsers(dest="action", required=True)
    sub.add_parser("ports").set_defaults(func=cmd_ports)
    sub.add_parser("probe").set_defaults(func=cmd_probe)
    p = sub.add_parser("cmd")
    p.add_argument("commands", nargs="+")
    p.set_defaults(func=cmd_raw)
    p = sub.add_parser("monitor")
    p.add_argument("seconds", type=int)
    p.add_argument("--id", help="CAN ID to filter on in the adapter (ATCRA), e.g. 130")
    p.set_defaults(func=cmd_monitor)
    p = sub.add_parser("ids")
    p.add_argument("seconds", type=int)
    p.set_defaults(func=cmd_ids)
    p = sub.add_parser("watch")
    p.add_argument("command")
    p.add_argument("--seconds", type=int, default=90)
    p.add_argument("--interval", type=float, default=0.4)
    p.add_argument("--header", help="ATSH header to set first, e.g. 7E0")
    p.set_defaults(func=cmd_watch)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
