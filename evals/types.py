"""
Judge output schema and workflow input model for the Stage 1 evaluation.

The schema here is both the structured-output contract handed to the judge LLM
and the surface the tests assert on.  It is a port of the Java ``LlmJudge``
contract in ``eval/shared/LlmJudge.java``; the weights and thresholds below must
stay numerically identical to the constants in that class for as long as both
judges run side by side.
"""

import math
from typing import Any, Self

from pydantic import BaseModel, Field, model_validator

# Composite weights. The judge is told this formula in prompts/judge.txt; we
# recompute it here rather than trust the arithmetic it reports.
W_CORRECTNESS: float = 0.40
W_ORDER: float = 0.30
W_HALLUCINATION: float = 0.30

# A case passes only when the composite clears MIN_OVERALL *and* correctness
# clears MIN_CORRECTNESS on its own, so that strong order and hallucination
# scores cannot carry a goal that went to the wrong place.
MIN_OVERALL: float = 0.70
MIN_CORRECTNESS: float = 0.70

# The judge is asked to round to 2dp, so its overall may legitimately differ
# from the recomputed value in the last place. Only flag real disagreement.
_ROUNDING_SLACK: float = 0.011

_SCORE_FIELDS: tuple[str, ...] = ("correctness", "order", "hallucination", "overall")


class Stage1Input(BaseModel):
    """
    Case metadata shown to the judge as ``<input_data>``.

    This is context, not something the agent is scored on. It exists so the
    judge can tell one case apart from another in a trace and so the case id
    lands in the persisted metrics payload.
    """

    case_id: str
    description: str
    target_url: str
    task_type: str
    mode: str
    ui_variety: str


class Stage1JudgeSchema(BaseModel):
    """
    Three scored dimensions, a recomputed composite, and evidence lists.

    Every field description is sent verbatim to the judge as part of the
    structured-output schema, so they are written as instructions.
    """

    correctness: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "Workflow coverage against ground truth, 0.0-1.0. "
            "1.0: every critical ground-truth action is present, matched by meaning, and the "
            "goal ends at the same destination. "
            "0.7-0.9: only synonym labels, extra waits or terminal guardrail phrasing differ. "
            "0.4-0.6: a critical action is missing or the goal stops one hop short. "
            "0.1-0.3: wrong destination, missing authentication, or a different workflow. "
            "0.0: empty or entirely unrelated."
        ),
    )
    order: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "Sequence of critical actions against ground truth, 0.0-1.0. "
            "1.0: every critical pair preserves ground-truth precedence. "
            "0.7-0.9: only adjacent swaps between mutually independent actions. "
            "0.4-0.6: one critical pair inverted. "
            "0.1-0.3: a page acted on before it is opened, an action before authentication, "
            "or two or more critical pairs inverted. "
            "0.0: sequence bears no relation to ground truth."
        ),
    )
    hallucination: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "Freedom from invention, 0.0-1.0 — note this is inverted, high is good. "
            "1.0: nothing appears that ground truth does not contain or imply. "
            "0.7-0.9: extra clicks that stay on the ground-truth path and change no state. "
            "0.4-0.6: one invented control or field. "
            "0.1-0.3: invented pages or applications, or state mutated where ground truth stops. "
            "0.0: the goal is largely fabricated."
        ),
    )
    overall: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "Weighted composite, 0.0-1.0. Compute it exactly as "
            "0.40*correctness + 0.30*order + 0.30*hallucination and round to 2 decimal places. "
            "Do not free-hand this number: if it disagrees with your per-dimension scores, "
            "revise the per-dimension scores instead."
        ),
    )
    missing_steps: list[str] = Field(
        default_factory=list,
        description=(
            "Short action phrases quoted from GROUND TRUTH that are absent from the generated "
            "goal. Quote ground truth's own wording, not a paraphrase. Empty when none. "
            "A non-empty list means correctness cannot be 1.0."
        ),
    )
    hallucinated_steps: list[str] = Field(
        default_factory=list,
        description=(
            "Short action phrases quoted from the GENERATED goal that are absent from ground "
            "truth. Quote the generated wording. Empty when none. "
            "A non-empty list means hallucination cannot be 1.0."
        ),
    )
    judge_reasoning: str = Field(
        description=(
            "Exactly 2 to 4 complete sentences of plain prose — no bullets, no markdown. "
            "State the main match or mismatch against ground truth first, then the order or "
            "hallucination issue if there is one. Name the specific action at fault."
        ),
    )
    test_passed: bool = Field(
        description=(
            f"True only when overall >= {MIN_OVERALL} AND correctness >= {MIN_CORRECTNESS}. "
            "False otherwise. Do not make an independent judgement that contradicts the scores."
        ),
    )
    issues: list[str] = Field(
        default_factory=list,
        description=(
            "Always return an empty list. This field is filled by the evaluation harness to "
            "record where your output had to be corrected; anything you put here is discarded."
        ),
    )

    @model_validator(mode="before")
    @classmethod
    def _clamp_scores(cls, data: Any) -> Any:
        """
        Clamp out-of-range scores into 0.0-1.0 so one bad float cannot void the run.

        Args:
            data (Any): The raw judge response, normally a dict.

        Returns:
            Any: The same payload with scores clamped and ``issues`` reset to the
                clamps that were applied.

        """
        if not isinstance(data, dict):
            return data
        issues: list[str] = []
        for name in _SCORE_FIELDS:
            raw: Any = data.get(name)
            if not isinstance(raw, (int, float)) or isinstance(raw, bool):
                continue
            if raw < 0.0 or raw > 1.0:
                issues.append(f"{name} {raw} was outside 0.0-1.0 and was clamped")
                data[name] = min(1.0, max(0.0, float(raw)))
        # Discard anything the judge wrote here; the harness owns this field.
        data["issues"] = issues
        return data

    @model_validator(mode="after")
    def _recompute_verdict(self) -> Self:
        """
        Recompute the composite and the pass flag, recording any disagreement.

        The judge is asked to do this arithmetic itself so that its reasoning
        stays consistent with its scores, but the harness never depends on it
        having done so correctly. Where the two differ, the recomputed value
        wins and the discrepancy is recorded in ``issues`` — a case that
        accumulates issues is one whose judge output should not be trusted at
        face value, even when it passes.

        Returns:
            Self: This instance, with ``overall`` and ``test_passed`` replaced by
                the recomputed values.

        """
        computed_overall: float = round(
            W_CORRECTNESS * self.correctness
            + W_ORDER * self.order
            + W_HALLUCINATION * self.hallucination,
            2,
        )
        if abs(computed_overall - self.overall) > _ROUNDING_SLACK:
            self.issues.append(
                f"judge reported overall {self.overall:.2f} but the weighted formula gives "
                f"{computed_overall:.2f}; using the formula"
            )
        self.overall = computed_overall

        computed_passed: bool = (
            computed_overall >= MIN_OVERALL and self.correctness >= MIN_CORRECTNESS
        )
        if computed_passed != self.test_passed:
            self.issues.append(
                f"judge reported test_passed={self.test_passed} but overall "
                f"{computed_overall:.2f} / correctness {self.correctness:.2f} against thresholds "
                f"{MIN_OVERALL} / {MIN_CORRECTNESS} gives {computed_passed}; using the thresholds"
            )
        self.test_passed = computed_passed

        if self.missing_steps and math.isclose(self.correctness, 1.0):
            self.issues.append(
                "judge listed missing_steps but scored correctness 1.0; scores and evidence "
                "disagree"
            )
        if self.hallucinated_steps and math.isclose(self.hallucination, 1.0):
            self.issues.append(
                "judge listed hallucinated_steps but scored hallucination 1.0; scores and "
                "evidence disagree"
            )
        return self
