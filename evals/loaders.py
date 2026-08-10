"""
Load the generation artifact produced by the Java pipeline and join it to the dataset.

Nothing in this harness invokes the agent.  The Java benchmark runner analyses
the videos and writes ``eval-reports/eval-report_<timestamp>.json``; this module
reads that file, joins each entry back to its dataset case for ground truth, and
hands the pair to the tests.  The two halves are deliberately decoupled: video
analysis is slow and expensive, so re-judging an existing artifact must not
require re-running it.
"""

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

EVALS_DIR: Path = Path(__file__).parent
POC_ROOT: Path = EVALS_DIR.parent
DATASET_PATH: Path = POC_ROOT / "src" / "main" / "resources" / "eval" / "stage1-dataset.json"
REPORTS_DIR: Path = POC_ROOT / "eval-reports"

#: Set this to pin a specific artifact instead of taking the most recent one.
REPORT_ENV_VAR: str = "STAGE1_EVAL_REPORT"


class ArtifactNotFoundError(RuntimeError):
    """Raised when no usable generation artifact can be located."""


@dataclass(frozen=True)
class Stage1Case:
    """One dataset case joined to its result in the generation artifact."""

    case_id: str
    description: str
    target_url: str
    task_type: str
    mode: str
    ui_variety: str

    # Ground truth. Empty steps mark a case the agent is supposed to refuse.
    ground_truth_goal: str
    ground_truth_steps: list[str]
    expectation: str
    expected_rejection: str

    # What the Java pipeline produced.
    generated_goal: str
    generated_steps: list[str]
    triage_verdict: str
    triage_category: str
    triage_reason: str
    confidence_recommendation: str
    wrongly_rejected: bool
    crashed: bool
    total_tokens: int
    latency_ms: int

    #: Java's own judge scores, kept only so the port can be compared against
    #: the implementation it replaces. Empty once the Java judge is retired.
    java_judge: dict[str, Any] = field(default_factory=dict)

    @property
    def is_scored(self) -> bool:
        """True when the case has ground-truth steps and so is judged on quality."""
        return bool(self.ground_truth_steps)

    @property
    def expects_refusal(self) -> bool:
        """True when the correct behaviour is to reject the video, not transcribe it."""
        return bool(self.expected_rejection)


def resolve_report_path() -> Path:
    """
    Locate the generation artifact to judge.

    Returns:
        Path: The artifact honoured from ``STAGE1_EVAL_REPORT``, or the most
            recently modified report in ``eval-reports/``.

    Raises:
        ArtifactNotFoundError: If the pinned path does not exist, or if no
            report has been generated yet.

    """
    pinned: str | None = os.environ.get(REPORT_ENV_VAR)
    if pinned:
        path: Path = Path(pinned).expanduser()
        if not path.is_file():
            raise ArtifactNotFoundError(
                f"{REPORT_ENV_VAR} points at {path}, which does not exist."
            )
        return path

    candidates: list[Path] = sorted(
        REPORTS_DIR.glob("eval-report_*.json"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise ArtifactNotFoundError(
            f"No eval-report_*.json found in {REPORTS_DIR}. Run the Java benchmark first "
            f"(./gradlew runEval), or set {REPORT_ENV_VAR} to an artifact elsewhere."
        )
    return candidates[0]


def _load_dataset() -> dict[str, dict[str, Any]]:
    """
    Read the dataset and index it by case id.

    Returns:
        dict[str, dict[str, Any]]: Every benchmark case, keyed by its ``id``.

    """
    raw: dict[str, Any] = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    return {case["id"]: case for case in raw["benchmarks"]}


def load_cases(report_path: Path) -> list[Stage1Case]:
    """
    Join every entry in the artifact to its dataset case.

    Cases the artifact does not mention are skipped rather than failed, so a
    filtered Java run (``--case=s1_login``) produces a correspondingly filtered
    judge run instead of 71 spurious failures.

    Args:
        report_path (Path): The generation artifact to read.

    Returns:
        list[Stage1Case]: One entry per case present in the artifact, ordered as
            the artifact orders them.

    Raises:
        ArtifactNotFoundError: If the artifact contains no cases, or references
            a case id absent from the dataset.

    """
    report: dict[str, Any] = json.loads(report_path.read_text(encoding="utf-8"))
    entries: list[dict[str, Any]] = report.get("cases", [])
    if not entries:
        raise ArtifactNotFoundError(f"{report_path} contains no cases.")

    dataset: dict[str, dict[str, Any]] = _load_dataset()
    cases: list[Stage1Case] = []
    for entry in entries:
        case_id: str = entry["caseId"]
        spec: dict[str, Any] | None = dataset.get(case_id)
        if spec is None:
            raise ArtifactNotFoundError(
                f"{report_path} reports case '{case_id}', which is not in "
                f"{DATASET_PATH.name}. The artifact was produced against a different dataset."
            )
        ground_truth: dict[str, Any] = spec.get("groundTruth") or {}
        triage: dict[str, Any] = entry.get("triageGate") or {}
        verdict: dict[str, Any] = entry.get("verdict") or {}
        tokens: dict[str, Any] = entry.get("tokenUsage") or {}
        cases.append(
            Stage1Case(
                case_id=case_id,
                description=spec.get("description", ""),
                target_url=spec.get("targetUrl", ""),
                task_type=spec.get("taskType", ""),
                mode=spec.get("mode", ""),
                ui_variety=spec.get("uiVariety", ""),
                ground_truth_goal=ground_truth.get("navigationGoal") or "",
                ground_truth_steps=list(ground_truth.get("steps") or []),
                expectation=spec.get("expectation") or "",
                expected_rejection=spec.get("expectedRejection") or "",
                generated_goal=entry.get("generatedGoal") or "",
                generated_steps=list(entry.get("generatedSteps") or []),
                triage_verdict=triage.get("verdict") or "",
                triage_category=triage.get("category") or "",
                triage_reason=triage.get("reason") or "",
                confidence_recommendation=entry.get("confidenceRecommendation") or "",
                wrongly_rejected=bool(verdict.get("wronglyRejected")),
                crashed=bool(verdict.get("crashed")),
                total_tokens=int(tokens.get("inputTokens") or 0)
                + int(tokens.get("outputTokens") or 0),
                latency_ms=int(entry.get("durationMs") or 0),
                java_judge=entry.get("judgeScores") or {},
            )
        )
    return cases
