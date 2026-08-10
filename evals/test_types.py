"""
Guard the judge schema's defensive layer.

The judge is the only gate on a scored case, so the clamp and recompute
validators in ``types.py`` are the last line between a malformed LLM response
and a wrong verdict.  These tests use no LLM and run in milliseconds.
"""

import pytest
from pydantic import ValidationError

from evals.types import MIN_CORRECTNESS, MIN_OVERALL, Stage1JudgeSchema


def _judge_output(**overrides: object) -> dict[str, object]:
    """A well-formed judge response, with fields overridden per test."""
    return {
        "correctness": 1.0,
        "order": 1.0,
        "hallucination": 1.0,
        "overall": 1.0,
        "missing_steps": [],
        "hallucinated_steps": [],
        "judge_reasoning": "The generated goal matches ground truth on every critical action.",
        "test_passed": True,
    } | overrides


def test_clean_output_passes_through_unchanged() -> None:
    """A consistent judge response records no issues."""
    verdict = Stage1JudgeSchema.model_validate(_judge_output())
    assert verdict.overall == 1.0
    assert verdict.test_passed
    assert verdict.issues == []


def test_overall_is_recomputed_from_the_weights() -> None:
    """The composite comes from the formula, not from whatever the judge reported."""
    verdict = Stage1JudgeSchema.model_validate(
        _judge_output(correctness=0.5, order=1.0, hallucination=1.0, overall=0.95)
    )
    assert verdict.overall == pytest.approx(0.80)
    assert any("weighted formula" in issue for issue in verdict.issues)


def test_strong_composite_cannot_carry_weak_correctness() -> None:
    """A goal that went to the wrong place fails even when order and invention are clean."""
    verdict = Stage1JudgeSchema.model_validate(
        _judge_output(correctness=0.5, order=1.0, hallucination=1.0, test_passed=True)
    )
    assert verdict.overall >= MIN_OVERALL
    assert verdict.correctness < MIN_CORRECTNESS
    assert not verdict.test_passed
    assert any("test_passed" in issue for issue in verdict.issues)


def test_judge_cannot_fail_a_case_that_clears_both_thresholds() -> None:
    """An unexplained failure verdict is overridden the same way an unearned pass is."""
    verdict = Stage1JudgeSchema.model_validate(
        _judge_output(correctness=0.9, order=0.9, hallucination=0.9, overall=0.9, test_passed=False)
    )
    assert verdict.test_passed
    assert any("test_passed" in issue for issue in verdict.issues)


def test_out_of_range_score_is_clamped_rather_than_rejected() -> None:
    """A 5.0 on a 0-1 scale is corrected and recorded, not allowed to void the case."""
    verdict = Stage1JudgeSchema.model_validate(
        _judge_output(correctness=5.0, order=-1.0, hallucination=1.0)
    )
    assert verdict.correctness == 1.0
    assert verdict.order == 0.0
    assert any("clamped" in issue for issue in verdict.issues)


def test_evidence_that_contradicts_a_perfect_score_is_recorded() -> None:
    """Listing a missing step while scoring correctness 1.0 is a self-contradiction."""
    verdict = Stage1JudgeSchema.model_validate(
        _judge_output(missing_steps=["click the Sign in button"])
    )
    assert any("missing_steps" in issue for issue in verdict.issues)


def test_issues_supplied_by_the_judge_are_discarded() -> None:
    """The harness owns this field; anything the model writes there is dropped."""
    verdict = Stage1JudgeSchema.model_validate(_judge_output(issues=["looks fine to me"]))
    assert verdict.issues == []


def test_missing_reasoning_is_rejected() -> None:
    """A score with no explanation is not a usable judgement."""
    payload = _judge_output()
    del payload["judge_reasoning"]
    with pytest.raises(ValidationError):
        Stage1JudgeSchema.model_validate(payload)
