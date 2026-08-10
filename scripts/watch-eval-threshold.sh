#!/usr/bin/env bash
#
# Watches a running eval and reports each case as it resolves, paired with the frame
# count that produced it.
#
# The GenAI gateway parks large prompts in ACCEPTED forever rather than rejecting
# them, so the only way to find its payload ceiling is to observe which frame counts
# come back and which never do. This turns a running eval into that measurement.
#
# Usage: scripts/watch-eval-threshold.sh <terminal-log-file> [poll-seconds]

set -uo pipefail

LOG_FILE="${1:?give the path to the eval terminal log}"
POLL_SECONDS="${2:-30}"

python3 - "$LOG_FILE" "$POLL_SECONDS" <<'PY'
import re, sys, time

log_file, poll_seconds = sys.argv[1], int(sys.argv[2])

# "Extracted N frames" always precedes the case it belongs to, so the most recent
# one seen when a verdict appears is that verdict's payload size.
FRAMES = re.compile(r"Extracted (\d+) frames")
CASE = re.compile(r"LOG:INFO:▶ \[(\d+)/(\d+)\] (\S+)")
PASS = re.compile(r"LOG:SUCCESS:(\S+) → (\S+) ✅")
FAIL = re.compile(r"LOG:ERROR:(\S+) → (\S+) ❌")
STUCK = re.compile(r"did not complete request (\S+) within (\d+)ms \(last status: (\w+)\)")

reported = set()
print(f"{'case':24s} {'frames':>6s}  {'verdict':8s} detail", flush=True)
print("-" * 72, flush=True)

while True:
    try:
        lines = open(log_file, errors="replace").read().splitlines()
    except OSError:
        time.sleep(poll_seconds)
        continue

    frames = None
    stuck_status = None
    done = False

    for line in lines:
        m = FRAMES.search(line)
        if m:
            frames = int(m.group(1))
            continue

        m = STUCK.search(line)
        if m:
            stuck_status = m.group(3)
            continue

        m = PASS.search(line)
        if m and m.group(1) not in reported:
            reported.add(m.group(1))
            print(f"{m.group(1):24s} {str(frames or '?'):>6s}  PASS     score={m.group(2)}", flush=True)
            stuck_status = None
            continue

        m = FAIL.search(line)
        if m and m.group(1) not in reported:
            reported.add(m.group(1))
            why = f"parked in {stuck_status}" if stuck_status else "errored"
            print(f"{m.group(1):24s} {str(frames or '?'):>6s}  FAIL     {why}", flush=True)
            stuck_status = None
            continue

        if "DONE:" in line or "BUILD SUCCESSFUL" in line or "BUILD FAILED" in line:
            done = True

    if done:
        print("-" * 72, flush=True)
        print("EVAL_RUN_FINISHED", flush=True)
        break

    time.sleep(poll_seconds)
PY
