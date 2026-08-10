"""
Fixtures wiring the sp-agents judge to the Java pipeline's generation artifact.

This is a workflow evaluation with the agent half removed.  ``run_workflow``
would normally invoke a LangGraph workflow and capture its trace, but the agent
under test is Java, so the ``evaluate`` fixture builds an equivalent
``WorkflowTrace`` from the artifact instead and hands it to the same judge.
Everything downstream of the trace — the judge, the schema, the persisted
metrics, the LangSmith feedback — is the stock SDK path.
"""

from collections.abc import Callable
from pathlib import Path

import pytest
from langsmith import testing as t
from sp_agents.common import create_bedrock_llm
from sp_agents.evals import (
    EvalConfig,
    Judge,
    WorkflowBenchmark,
    WorkflowBenchmarkResult,
    WorkflowTrace,
)

from evals.loaders import Stage1Case, load_cases, resolve_report_path
from evals.types import Stage1Input, Stage1JudgeSchema

pytest_plugins: list[str] = ["sp_agents.evals.pytest_plugin"]

EVALS_DIR: Path = Path(__file__).parent


@pytest.fixture(scope="session")
def eval_config() -> EvalConfig:
    """Load the model configuration."""
    return EvalConfig.from_yaml(EVALS_DIR / "config.yaml")


@pytest.fixture(scope="session")
def judge(eval_config: EvalConfig) -> Judge[Stage1JudgeSchema]:
    """Build the LLM-as-judge from config.yaml and prompts/judge.txt."""
    return Judge(
        llm=create_bedrock_llm(
            model=eval_config.judge.model,
            model_kwargs={
                "temperature": eval_config.judge.temperature,
                "max_tokens": eval_config.judge.max_tokens,
            },
        ),
        prompt=(EVALS_DIR / "prompts" / "judge.txt").read_text(encoding="utf-8"),
        response_format=Stage1JudgeSchema,
    )


@pytest.fixture(scope="session")
def artifact_path() -> Path:
    """Resolve the generation artifact once, so every test judges the same run."""
    return resolve_report_path()


@pytest.fixture(scope="session", autouse=True)
def _announce_artifact(artifact_path: Path) -> None:
    """Print which artifact is being judged; silence here has caused stale-run confusion."""
    print(f"\nJudging generation artifact: {artifact_path}")  # noqa: T201


def _build_benchmark(case: Stage1Case) -> WorkflowBenchmark:
    """Describe the case to the judge: metadata as input, ground truth as expected output."""
    return WorkflowBenchmark(
        input_data=Stage1Input(
            case_id=case.case_id,
            description=case.description,
            target_url=case.target_url,
            task_type=case.task_type,
            mode=case.mode,
            ui_variety=case.ui_variety,
        ),
        expected_output={
            "navigation_goal": case.ground_truth_goal,
            "steps": case.ground_truth_steps,
        },
    )


def _build_trace(case: Stage1Case) -> WorkflowTrace:
    """
    Reconstruct the Java run as a WorkflowTrace.

    ``messages`` stays empty because the artifact records the agent's output but
    not the message list that produced it. The judge scores the structured
    output, so it loses nothing; a tool-call metric would, which is one reason
    Stage 1 does not compute one.
    """
    return WorkflowTrace(
        messages=[],
        total_tokens=case.total_tokens,
        turns=0,
        latency=case.latency_ms,
        input_data={"case_id": case.case_id, "target_url": case.target_url},
        output={
            "navigation_goal": case.generated_goal,
            "steps": case.generated_steps,
        },
    )


@pytest.fixture
def evaluate(
    request: pytest.FixtureRequest,
) -> Callable[[Judge[Stage1JudgeSchema], Stage1Case], WorkflowBenchmarkResult[Stage1JudgeSchema]]:
    """Return a callable that judges one case and persists its metrics."""

    def _eval(
        judge: Judge[Stage1JudgeSchema],
        case: Stage1Case,
    ) -> WorkflowBenchmarkResult[Stage1JudgeSchema]:
        benchmark: WorkflowBenchmark = _build_benchmark(case)
        trace: WorkflowTrace = _build_trace(case)

        t.log_inputs({"case_id": case.case_id, "generated_goal": case.generated_goal})
        t.log_reference_outputs({"navigation_goal": case.ground_truth_goal})

        judge_result: Stage1JudgeSchema = judge.review_workflow(trace, benchmark)
        result: WorkflowBenchmarkResult[Stage1JudgeSchema] = WorkflowBenchmarkResult(
            trace=trace,
            judge_result=judge_result,
            extras={
                "case_id": case.case_id,
                "ui_variety": case.ui_variety,
                "expectation": case.expectation or "HAPPY",
            },
        )
        result.persist(node_id=request.node.nodeid)
        return result

    return _eval


@pytest.fixture(scope="session")
def cases(artifact_path: Path) -> dict[str, Stage1Case]:
    """Every case in the artifact, indexed by id."""
    return {case.case_id: case for case in load_cases(artifact_path)}
