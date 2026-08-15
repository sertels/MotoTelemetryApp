#!/usr/bin/env python3
"""Unattended two-phase CAN capture for a real ride (laptop in a backpack).

Start it, put the laptop in the backpack, ride. It survives Bluetooth dropouts
(reconnects and resumes the current phase) and writes every frame straight to
disk, so even a mid-ride crash keeps everything captured up to that moment.

  python tools/ride_capture.py --port COM6 --lean-minutes 10 --bus-minutes 10

Phase 1  ATCRA-filtered capture of --lean-id (default 092, the 100Hz frame
         that is all-zeros at standstill) - the lean-angle hunt needs the
         gap-free rate only a filtered capture gives.
Phase 2  full-bus capture - brake events, TPMS (3A1/3A2), odometer (3FF),
         everything else. Clone adapters drop frames here; slow IDs still
         get through.

Output: tools/captures/ride_<stamp>_phase1_idXXX.txt and ..._phase2_bus.txt.
"# t=12.3s" marker lines are written once a second so frames can be lined up
against ride events afterward; both can_diff.py and the analysis scripts
ignore non-hex lines.

Before riding: the phone app must NOT grab the adapter (turn off its OBD
auto-start or phone Bluetooth), and the laptop must not sleep on lid close.
"""

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path

import serial

for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

CAPTURE_DIR = Path(__file__).resolve().parent / "captures"
PROMPT = b">"


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def send(ser, cmd, deadline_s=3.0):
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
    return buf.decode("ascii", errors="replace").replace(">", "").strip()


def connect(port):
    """Open + init, retrying forever - mid-ride the adapter may be briefly
    unreachable (backpack shielding, adapter brownout) and giving up would
    end the whole capture."""
    attempt = 0
    while True:
        attempt += 1
        try:
            ser = serial.Serial(port, 38400, timeout=0.1)
            resp = send(ser, "ATZ", deadline_s=5.0)
            if "ELM327" not in resp.upper():
                ser.close()
                raise IOError(f"not an ELM327: {resp!r}")
            for cmd in ("ATE0", "ATL0", "ATSP0"):
                send(ser, cmd)
            log(f"connected on {port} (attempt {attempt})")
            return ser
        except Exception as e:
            log(f"connect failed ({e}); retrying in 5s")
            time.sleep(5)


def capture_phase(port, seconds, can_id, out_path):
    """One capture phase, resilient: on I/O failure, reconnect and resume
    with whatever time is left. Frames are appended and flushed once a
    second so nothing is lost if the process dies."""
    label = f"id {can_id}" if can_id else "full bus"
    log(f"phase start: {label}, {seconds}s -> {out_path.name}")
    deadline = time.monotonic() + seconds
    frames_total = 0
    with out_path.open("a", encoding="utf-8") as out:
        while time.monotonic() < deadline:
            ser = connect(port)
            try:
                send(ser, "ATH1")
                send(ser, "ATS0")
                if can_id:
                    send(ser, f"ATCRA{can_id}")
                ser.reset_input_buffer()
                ser.write(b"ATMA\r")
                ser.flush()
                line = bytearray()
                last_flush = time.monotonic()
                last_byte = time.monotonic()
                while time.monotonic() < deadline:
                    chunk = ser.read(1024)
                    now = time.monotonic()
                    if not chunk:
                        # A long silence on a bus that should be shouting
                        # means the link (or ATMA) died without an exception -
                        # force a reconnect rather than recording nothing.
                        if now - last_byte > 15:
                            raise IOError("no data for 15s")
                        continue
                    last_byte = now
                    for byte in chunk:
                        if byte in (0x0D, 0x0A):
                            text = line.decode("ascii", errors="replace").strip()
                            line.clear()
                            if not text or text == "ATMA":
                                continue
                            out.write(text + "\n")
                            frames_total += 1
                            if "BUFFER" in text and "FULL" in text:
                                ser.write(b"ATMA\r")
                                ser.flush()
                        else:
                            line.append(byte)
                    if now - last_flush >= 1.0:
                        out.write(f"# t={seconds - (deadline - now):.1f}s\n")
                        out.flush()
                        last_flush = now
                        if frames_total and frames_total % 5000 < 50:
                            log(f"  {frames_total} frames")
            except Exception as e:
                log(f"phase I/O error ({e}); reconnecting")
            finally:
                # Best-effort teardown; on a dead socket these just fail quietly.
                try:
                    ser.write(b" ")
                    ser.flush()
                    time.sleep(0.3)
                    if can_id:
                        send(ser, "ATAR")
                    send(ser, "ATH0")
                    send(ser, "ATS1")
                except Exception:
                    pass
                try:
                    ser.close()
                except Exception:
                    pass
    log(f"phase done: {frames_total} frames")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", required=True)
    ap.add_argument("--lean-minutes", type=float, default=10.0)
    ap.add_argument("--bus-minutes", type=float, default=10.0)
    ap.add_argument("--lean-id", default="092")
    args = ap.parse_args()

    CAPTURE_DIR.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    if args.lean_minutes > 0:
        capture_phase(args.port, int(args.lean_minutes * 60), args.lean_id,
                      CAPTURE_DIR / f"ride_{stamp}_phase1_id{args.lean_id}.txt")
    if args.bus_minutes > 0:
        capture_phase(args.port, int(args.bus_minutes * 60), None,
                      CAPTURE_DIR / f"ride_{stamp}_phase2_bus.txt")
    log("ride capture complete - safe to close the laptop.")


if __name__ == "__main__":
    main()
