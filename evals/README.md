# Stage 1 evaluation harness

Scores the navigation goal the agent generates from a video against the
ground-truth goal authored for each benchmark case, using the `sp-agents`
LLM-as-judge.

Stage 2 (conversational navigation) is not evaluated here.

## Why this is split across two languages

The agent is Java: `VideoAnalysisEvaluator` extracts frames, calls Bedrock, and
writes `eval-reports/eval-report_<timestamp>.json`. The judge is Python, because
that is where `sp-agents` lives and where the shared judge contract, the
structured-output schema enforcement, the pytest metrics reporting and the
LangSmith integration already exist.

The two halves communicate through that JSON report and nothing else. This is
deliberate. Analysing 72 videos takes tens of minutes and real Bedrock spend, so
re-judging must not require re-generating — when the rubric changes, only the
cheap half runs again.

```
  Java                                    Python
  ────                                    ──────
  video ──▶ triage ──▶ generate goal
                           │
                           ▼
              eval-report_<ts>.json ──────▶ loaders.py  (join to ground truth)
                                                 │
                                                 ▼
                                            WorkflowTrace + WorkflowBenchmark
                                                 │
                                                 ▼
                                            Judge.review_workflow
                                                 │
                                                 ▼
                                            Stage1JudgeSchema  (clamp, recompute)
                                                 │
                                                 ▼
                                            evals/reports/stage1-judge.json
```

## Running it

Generate an artifact with the Java pipeline first, then judge it:

```bash
./gradlew runEval             # writes eval-reports/eval-report_<timestamp>.json
evals/run.sh                  # judges the most recent artifact
```

To generate a single case rather than all 72:

```bash
./gradlew runEval -Pargs="--case=s1_login"
```

To judge a specific artifact, or to forward arguments to pytest:

```bash
evals/run.sh eval-reports/eval-report_20260807_102811.json
evals/run.sh -- -k s1_login -v
```

`STAGE1_EVAL_REPORT` pins the artifact if you would rather set it in the
environment. `EVAL_PARALLEL` controls xdist workers (default 5).

### First-time setup

```bash
python3 -m venv evals/.venv
evals/.venv/bin/pip install -r evals/requirements.txt --index-url \
  "https://${ARTIFACTORY_USER}:${ARTIFACTORY_TOKEN}@sailpoint.jfrog.io/artifactory/api/pypi/sp-pypi-prod/simple"
```

The venv is gitignored; it is around 500MB and machine-specific.

## What passes and what fails

The dataset splits in two, and the harness follows that split.

**59 cases with ground-truth steps** are a quality question, and the LLM judge is
the only gate on them. It scores three dimensions between 0.0 and 1.0 —
`correctness` (workflow coverage), `order` (sequence of critical actions) and
`hallucination` (freedom from invention, inverted so high is good) — which
combine as:

```
overall = 0.40*correctness + 0.30*order + 0.30*hallucination
```

A case passes when `overall >= 0.70` **and** `correctness >= 0.70`. The second
condition exists so that clean ordering and no invention cannot carry a goal
that navigated somewhere else entirely.

Three conditions fail a scored case before the judge is ever called: the run
crashed, the triage gate rejected a video that had usable ground truth, or the
agent produced an empty goal. Failing these separately keeps "produced nothing"
distinct from "produced something bad", which a score alone cannot express.

**13 cases without ground-truth steps** are a refusal question, decided
deterministically with no judge call and no LLM cost. `INVALID` cases (a
terminal recording, a webcam, a slideshow) are held to zero tolerance: any plan
at all is a fabrication. `UNWORKABLE` cases (a real UI recording too degraded to
read) pass if the agent rejected the video *or* flagged it — triage `UNCERTAIN`,
a `CAUTION`/`REVIEW` confidence recommendation, or no plan. The failure mode
being guarded against there is confidence, not the attempt.

## Not trusting the judge

`Stage1JudgeSchema` re-derives the two numbers that decide the outcome rather
than taking the judge's word for them. Scores outside 0.0-1.0 are clamped,
`overall` is recomputed from the weights, and `test_passed` is recomputed from
the thresholds. Where the judge's own answer differed, the discrepancy is
appended to `issues` on the result.

`issues` does not fail a case. A judge that contradicts itself may still have
scored correctly, and failing on it would conflate a bad agent with a
bad judge. It is printed during the run and persisted in the report, because a
case that only passes alongside a correction is worth a human's attention.

The judge runs the same Sonnet 4.6 model as the generator. That is a real
weakness: a judge sharing the generator's blind spots will accept output a
different model would reject. These scores measure "did the goal match ground
truth", not "is this model any good"; if that distinction starts to matter,
point `judge.model` in `config.yaml` at a different family and compare.

## Relationship to the Java judge

`eval/shared/LlmJudge.java` still judges the benchmark and still gates the Java
report, and it also runs the real-time confidence check in the UI, which has no
ground truth and stays in Java regardless.

This harness is a port of its benchmark half, and a port is only trustworthy
once it has been shown to agree. While both run, `run.sh` finishes by calling
`compare_judges.py`, which names every case where the two reach different
pass/fail verdicts. Once a full run agrees, the Java benchmark judge can be
removed and the generation run switched to `--skip-judge`.

The weights and thresholds in `types.py` must stay numerically identical to the
constants in `LlmJudge.java` until then, or the comparison measures the
difference between two rubrics rather than between two implementations.

## Layout

| File | Purpose |
|---|---|
| `config.yaml` | Judge model, temperature, token budget |
| `prompts/judge.txt` | The rubric, adapted to `Judge.review_workflow`'s prompt envelope |
| `types.py` | Judge output schema, weights, thresholds, defensive validators |
| `loaders.py` | Locates the artifact and joins it to dataset ground truth |
| `conftest.py` | Judge fixture and the `evaluate` helper that builds the trace |
| `test_stage1.py` | The two test shapes: judged cases and refusal cases |
| `test_types.py` | Guards the clamp and recompute validators; no LLM calls |
| `compare_judges.py` | Diffs the Java and Python verdicts on one artifact |
