#!/usr/bin/env bash
#
# Poll the SailPoint GenAI gateway for the result of a prompt the POC submitted.
#
# The POC's own poll budget gives up after llm.proxy.poll.timeout.ms. This script
# asks the same question by hand, with no deadline, so you can tell the difference
# between a request that is merely slow and one the gateway has parked forever.
# That distinction decides whether raising the timeout would help at all.
#
# Credentials come from application.properties, overridden by the environment.
# Neither the token nor the secret is ever printed.
#
# Usage:
#   scripts/gateway-poll.sh <requestId> [<requestId>...]   # check once
#   scripts/gateway-poll.sh --watch <requestId>            # re-check every 15s until terminal
#   scripts/gateway-poll.sh --full <requestId>             # print the whole stored completion
#
# Request ids are printed in the eval log on failure, e.g.
#   stage1-eval-ac861327-5e36-4304-8e00-860d5e844bde

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$REPO_ROOT/src/main/resources/application.properties"

WATCH_INTERVAL_SECONDS=15

# Reads a key from application.properties. Values may contain '=' so keep everything
# after the first one.
prop() {
    [ -f "$PROPS" ] || return 0
    grep -m1 "^$1=" "$PROPS" 2>/dev/null | cut -d= -f2- | tr -d '\r'
}

resolve() {
    local env_name="$1" prop_name="$2"
    local env_value="${!env_name:-}"
    if [ -n "$env_value" ]; then printf '%s' "$env_value"; else prop "$prop_name"; fi
}

BASE_URL="$(resolve LLM_PROXY_BASE_URL llm.proxy.base.url)"
CLIENT_ID="$(resolve LLM_PROXY_CLIENT_ID llm.proxy.client.id)"
CLIENT_SECRET="$(resolve LLM_PROXY_CLIENT_SECRET llm.proxy.client.secret)"

BASE_URL="${BASE_URL%/}"

die() { echo "error: $*" >&2; exit 1; }

[ -n "$BASE_URL" ]      || die "no llm.proxy.base.url in $PROPS and no LLM_PROXY_BASE_URL set"
[ -n "$CLIENT_ID" ]     || die "no llm.proxy.client.id in $PROPS and no LLM_PROXY_CLIENT_ID set"
[ -n "$CLIENT_SECRET" ] || die "no llm.proxy.client.secret in $PROPS and no LLM_PROXY_CLIENT_SECRET set"

MODE="once"
case "${1:-}" in
    --watch) MODE="watch"; shift ;;
    --full)  MODE="full";  shift ;;
    -h|--help|"") sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

[ $# -gt 0 ] || die "give at least one requestId"

# --- token -----------------------------------------------------------------
# Fetched once and reused; it is valid for hours and this script runs for minutes.
fetch_token() {
    local response
    response=$(curl -sS --fail-with-body \
        -X POST "$BASE_URL/oauth/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'grant_type=client_credentials' \
        --data-urlencode "client_id=$CLIENT_ID" \
        --data-urlencode "client_secret=$CLIENT_SECRET" 2>&1) || {
            # The failure body can echo the credentials, so report only the status.
            die "token request to $BASE_URL/oauth/token failed; check llm.proxy.client.id / client.secret"
        }
    printf '%s' "$response" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])' \
        || die "token response had no access_token"
}

echo "gateway : $BASE_URL"
TOKEN="$(fetch_token)"
echo "auth    : ok"
echo

# --- poll ------------------------------------------------------------------
# Prints one line per request, or the full completion under --full. Exits non-zero
# from check_one when the request has not reached a terminal state yet, which is
# what --watch loops on.
check_one() {
    local request_id="$1" mode="$2"
    local body
    body=$(curl -sS -G "$BASE_URL/genai-gateway/v1/llm-batch-completions" \
        --data-urlencode "filters=id eq \"$request_id\"" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'X-SailPoint-Experimental: true') || { echo "$request_id  <curl failed>"; return 1; }

    REQ_ID="$request_id" MODE="$mode" python3 - "$body" <<'PY'
import json, os, sys

request_id = os.environ["REQ_ID"]
mode = os.environ["MODE"]
raw = sys.argv[1]

try:
    statuses = json.loads(raw).get("statuses") or []
except Exception:
    print(f"{request_id}  <unparseable response> {raw[:200]}")
    sys.exit(1)

if not statuses:
    print(f"{request_id}  NOT_FOUND  (gateway has no record of this id)")
    sys.exit(1)

entry = statuses[0]
status = (entry.get("status") or "").upper()
error = entry.get("error") or ""
result = entry.get("result") or ""

terminal = status == "COMPLETED" or any(w in status for w in ("FAIL", "ERROR", "CANCEL"))

detail = ""
if status == "COMPLETED" and result:
    try:
        completion = json.loads(result)
        usage = completion.get("usage") or {}
        detail = (f"  stop={completion.get('stop_reason')}"
                  f"  in={usage.get('input_tokens')}  out={usage.get('output_tokens')}")
    except Exception:
        detail = "  <result present but not parseable as JSON>"

print(f"{request_id}  {status}{detail}" + (f"  error={error}" if error else ""))

if mode == "full" and result:
    print()
    try:
        print(json.dumps(json.loads(result), indent=2))
    except Exception:
        print(result)

sys.exit(0 if terminal else 1)
PY
}

if [ "$MODE" = "watch" ]; then
    request_id="$1"
    started=$(date +%s)
    while true; do
        elapsed=$(( $(date +%s) - started ))
        printf '[%4ds] ' "$elapsed"
        if check_one "$request_id" "once"; then
            echo
            echo "reached a terminal state after ${elapsed}s"
            exit 0
        fi
        sleep "$WATCH_INTERVAL_SECONDS"
    done
fi

exit_code=0
for request_id in "$@"; do
    check_one "$request_id" "$MODE" || exit_code=1
done
exit "$exit_code"
