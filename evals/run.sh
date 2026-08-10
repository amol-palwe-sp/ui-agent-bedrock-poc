#!/usr/bin/env bash
#
# Run the Stage 1 judge over a generation artifact produced by the Java pipeline.
#
#   evals/run.sh                                  # judge the newest artifact
#   evals/run.sh eval-reports/eval-report_X.json  # judge a specific one
#   evals/run.sh -- -k s1_login                   # forward args to pytest
#
# sp-evals shells out to a bare `pytest`, so the venv has to be on PATH rather
# than merely being the interpreter that launched it.
set -euo pipefail

POC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${POC_ROOT}/evals/.venv"
cd "${POC_ROOT}"

if [[ ! -x "${VENV}/bin/sp-evals" ]]; then
  echo "No eval venv at ${VENV}. Create it with:" >&2
  echo "  python3 -m venv evals/.venv && evals/.venv/bin/pip install -r evals/requirements.txt" >&2
  exit 1
fi

if [[ "${1-}" != "" && "${1-}" != "--" ]]; then
  export STAGE1_EVAL_REPORT="$1"
  shift
fi
[[ "${1-}" == "--" ]] && shift

export PATH="${VENV}/bin:${PATH}"
export AWS_PROFILE="${AWS_PROFILE:-default}"
export AWS_REGION="${AWS_REGION:-us-east-1}"

mkdir -p evals/reports
PYTHON_REPORT="evals/reports/stage1-judge.json"

# --dry-run keeps results off LangSmith. Drop it once LANGSMITH_API_KEY is set
# and the runs are worth keeping.
sp-evals \
  --suite "UI Agent Stage 1" \
  --dry-run \
  --parallel "${EVAL_PARALLEL:-5}" \
  --report-json "${PYTHON_REPORT}" \
  "$@"

# Both judges scored this artifact only while the port is being validated; the
# comparison is a no-op once the Java benchmark judge is gone.
JAVA_REPORT="${STAGE1_EVAL_REPORT:-$(ls -t eval-reports/eval-report_*.json 2>/dev/null | head -1)}"
if [[ -n "${JAVA_REPORT}" && -f "${PYTHON_REPORT}" ]]; then
  "${VENV}/bin/python" -m evals.compare_judges "${JAVA_REPORT}" "${PYTHON_REPORT}" || true
fi
