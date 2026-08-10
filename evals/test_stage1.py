"""
Stage 1 benchmark: does the navigation goal generated from a video match ground truth?

The suite splits along the same line the dataset does.  Cases with ground-truth
steps are a quality question and go to the LLM judge, which is the sole gate on
them.  Cases without ground-truth steps are a refusal question — the video is
unusable and the agent is supposed to say so — which is decided deterministically
from the triage gate, with no judge call and no LLM cost.
"""

from collections.abc import Callable

import pytest
from sp_agents.evals import Judge, WorkflowBenchmarkResult

from evals.loaders import Stage1Case, load_cases, resolve_report_path
from evals.types import MIN_CORRECTNESS, MIN_OVERALL, Stage1JudgeSchema

# Parametrisation has to happen at import time, before fixtures exist, so the
# artifact is read once here as well. Only ids are taken; the joined case comes
# from the session-scoped `cases` fixture so both reads cannot drift apart.
_CASES: list[Stage1Case] = load_cases(resolve_report_path())

SCORED_CASE_IDS: list[str] = [case.case_id for case in _CASES if case.is_scored]
REFUSAL_CASE_IDS: list[str] = [case.case_id for case in _CASES if not case.is_scored]

Evaluate = Callable[
    [Judge[Stage1JudgeSchema], Stage1Case],
    WorkflowBenchmarkResult[Stage1JudgeSchema],
]


@pytest.mark.langsmith
@pytest.mark.skipif(not SCORED_CASE_IDS, reason="artifact contains no cases with ground truth")
@pytest.mark.parametrize("case_id", SCORED_CASE_IDS)
def test_navigation_goal_matches_ground_truth(
    case_id: str,
    cases: dict[str, Stage1Case],
    judge: Judge[Stage1JudgeSchema],
    evaluate: Evaluate,
) -> None:
    """The generated goal reproduces the ground-truth workflow well enough to pass."""
    case: Stage1Case = cases[case_id]

    # Three ways to have no goal to judge. Failing here rather than sending an
    # empty string to the judge keeps "the agent produced nothing" distinct from
    # "the agent produced something bad", which the score alone cannot express.
    assert not case.crashed, f"{case_id} errored before producing a plan"
    assert not case.wrongly_rejected, (
        f"{case_id} has usable ground truth but the triage gate rejected the video: "
        f"{case.triage_reason}"
    )
    assert case.generated_goal, f"{case_id} produced an empty navigation goal"

    result: WorkflowBenchmarkResult[Stage1JudgeSchema] = evaluate(judge, case)
    verdict: Stage1JudgeSchema = result.judge_result

    # Recorded, not gating: a self-contradicting judge can still be right, but a
    # case that only passes alongside a correction is worth a human's attention.
    if verdict.issues:
        print(f"\n{case_id} judge output needed correcting:")  # noqa: T201
        for issue in verdict.issues:
            print(f"  - {issue}")  # noqa: T201

    assert verdict.test_passed, (
        f"{case_id} scored overall {verdict.overall:.2f} "
        f"(correctness {verdict.correctness:.2f}, order {verdict.order:.2f}, "
        f"hallucination {verdict.hallucination:.2f}) against thresholds "
        f"overall >= {MIN_OVERALL} and correctness >= {MIN_CORRECTNESS}.\n"
        f"  judge: {verdict.judge_reasoning}\n"
        f"  missing: {verdict.missing_steps or 'none'}\n"
        f"  hallucinated: {verdict.hallucinated_steps or 'none'}\n"
        f"  ground truth: {case.ground_truth_goal}\n"
        f"  generated:    {case.generated_goal}"
    )


@pytest.mark.skipif(not REFUSAL_CASE_IDS, reason="artifact contains no refusal cases")
@pytest.mark.parametrize("case_id", REFUSAL_CASE_IDS)
def test_unusable_video_is_refused(case_id: str, cases: dict[str, Stage1Case]) -> None:
    """
    An unusable recording is rejected or flagged rather than confidently transcribed.

    INVALID cases are held to zero tolerance: the video is not a web UI recording
    at all, so any plan is a fabrication. UNWORKABLE cases are recordings of the
    right kind that are too degraded to read, where flagging for review is an
    acceptable answer — the failure mode being guarded against is confidence, not
    the attempt.
    """
    case: Stage1Case = cases[case_id]
    produced_plan: bool = bool(case.generated_steps)
    rejected: bool = case.triage_verdict == "REJECT"

    assert not case.crashed, f"{case_id} errored rather than refusing: {case.triage_reason}"

    if case.expectation == "INVALID":
        assert rejected or not produced_plan, (
            f"{case_id} is not a usable web-UI recording ({case.expected_rejection}) but the "
            f"agent produced a {len(case.generated_steps)}-step plan from it: "
            f"{case.generated_goal}"
        )
    else:
        flagged: bool = (
            case.triage_verdict == "UNCERTAIN"
            or case.confidence_recommendation in {"CAUTION", "REVIEW"}
            or not produced_plan
        )
        assert rejected or flagged, (
            f"{case_id} is too degraded to read ({case.expected_rejection}) but the agent "
            f"produced a plan with no hesitation — triage said {case.triage_verdict}, "
            f"confidence said {case.confidence_recommendation or 'nothing'}: {case.generated_goal}"
        )
