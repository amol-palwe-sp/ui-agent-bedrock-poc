r"""
Compare the Python judge's verdicts against the Java judge's on the same artifact.

The Java ``LlmJudge`` still gates the benchmark; this harness is a port of it,
and a port is only trustworthy once it has been shown to agree.  Run both over
the same generation artifact and this script names every case where they differ,
which is the evidence for deciding when the Java benchmark judge can be retired.

Usage::

    python -m evals.compare_judges eval-reports/eval-report_<ts>.json \\
                                   evals/reports/stage1-judge.json
"""

import json
import sys
from pathlib import Path
from typing import Any

#: Below this the two judges are scoring the same case the same way, and the
#: gap is model nondeterminism rather than a difference in the rubric.
SCORE_DRIFT_TOLERANCE: float = 0.10

#: The Java report and the Python report, in that order.
EXPECTED_ARGS: int = 2


def _load_java_verdicts(path: Path) -> dict[str, dict[str, Any]]:
    """
    Index the Java judge's scores by case id, skipping cases it did not score.

    Args:
        path (Path): The Java benchmark report.

    Returns:
        dict[str, dict[str, Any]]: The ``judgeScores`` block per scored case.

    """
    report: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    verdicts: dict[str, dict[str, Any]] = {}
    for entry in report.get("cases", []):
        scores: dict[str, Any] = entry.get("judgeScores") or {}
        if scores.get("overall") is None or scores.get("testPassed") is None:
            continue
        verdicts[entry["caseId"]] = scores
    return verdicts


def _load_python_verdicts(path: Path) -> dict[str, dict[str, Any]]:
    """
    Index the Python judge's scores by case id.

    Args:
        path (Path): The metrics report written by ``sp-evals --report-json``.

    Returns:
        dict[str, dict[str, Any]]: The per-test metrics for each scored case.

    """
    report: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return {
        test["case_id"]: test
        for test in report.get("tests", [])
        if "case_id" in test and "overall" in test
    }


def main(argv: list[str]) -> int:
    """
    Print an agreement summary for the two judge reports named on the command line.

    Args:
        argv (list[str]): ``[java_report_path, python_report_path]``.

    Returns:
        int: 0 when the two judges reach the same verdict on every shared case,
            1 when they disagree or the arguments are wrong. Non-zero is a
            signal to look, not necessarily a defect.

    """
    if len(argv) != EXPECTED_ARGS:
        print(__doc__)  # noqa: T201
        return 1

    java: dict[str, dict[str, Any]] = _load_java_verdicts(Path(argv[0]))
    python: dict[str, dict[str, Any]] = _load_python_verdicts(Path(argv[1]))
    shared: list[str] = sorted(set(java) & set(python))

    if not shared:
        print(  # noqa: T201
            "No case was scored by both judges — nothing to compare. Either the artifact "
            "holds only refusal cases, which neither judge scores, or the Java run was made "
            "with --skip-judge, or the two reports cover different cases."
        )
        return 0 if not java and not python else 1

    verdict_diffs: list[str] = []
    score_diffs: list[str] = []
    for case_id in shared:
        java_passed: bool = bool(java[case_id]["testPassed"])
        python_passed: bool = bool(python[case_id]["test_passed"])
        java_overall: float = float(java[case_id]["overall"])
        python_overall: float = float(python[case_id]["overall"])
        detail: str = (
            f"  {case_id}: java {'pass' if java_passed else 'fail'} ({java_overall:.2f})  "
            f"python {'pass' if python_passed else 'fail'} ({python_overall:.2f})"
        )
        if java_passed != python_passed:
            verdict_diffs.append(detail)
        elif abs(java_overall - python_overall) > SCORE_DRIFT_TOLERANCE:
            score_diffs.append(detail)

    print(  # noqa: T201
        f"\n{len(shared) - len(verdict_diffs)}/{len(shared)} cases reached the same pass/fail "
        f"verdict."
    )
    if verdict_diffs:
        print("\nDisagreed on pass/fail — these block retiring the Java judge:")  # noqa: T201
        print("\n".join(verdict_diffs))  # noqa: T201
    if score_diffs:
        print(  # noqa: T201
            f"\nAgreed on pass/fail but scored more than {SCORE_DRIFT_TOLERANCE:.2f} apart:"
        )
        print("\n".join(score_diffs))  # noqa: T201

    only_java: set[str] = set(java) - set(python)
    only_python: set[str] = set(python) - set(java)
    if only_java:
        print(f"\nScored only by Java: {', '.join(sorted(only_java))}")  # noqa: T201
    if only_python:
        print(f"\nScored only by Python: {', '.join(sorted(only_python))}")  # noqa: T201

    return 1 if verdict_diffs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
