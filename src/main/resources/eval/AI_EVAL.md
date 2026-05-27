# UI Agent — AI Evaluation Framework

High-level overview of how we evaluate video-to-navigation-script quality in the UI Agent Bedrock POC.

---

## What problem this solves

When a user uploads a screen recording, the UI Agent uses Claude (via AWS Bedrock) to produce a **navigation goal** — a comma-separated script of UI actions the browser agent will execute later.

We need two kinds of quality signals:

| Mode | When | Question answered |
|------|------|-------------------|
| **Real-time confidence** | Every new video upload (Provisioning or Aggregation UI) | *"Should I trust this script, or review it first?"* |
| **Benchmark eval** | Regression testing against known videos with ground truth | *"How good is the model/prompt after a change?"* |

---

## Architecture at a glance

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         VIDEO UPLOAD (user)                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    Extract frames → Claude → Parse result
                                    │
              ┌─────────────────────┴─────────────────────┐
              │                                           │
     PROVISIONING flow                              AGGREGATION flow
     (VideoToGoalPrompt)                            (VideoAnalysisPrompt JSON)
     GoalExtractor → steps                         VideoAnalysisResult → steps
              │                                           │
              └─────────────────────┬─────────────────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              │                                           │
     Real-time confidence                         Benchmark eval (optional)
     (no ground truth)                            (benchmarks.json + judge)
              │                                           │
     ConfidenceResult                             EvalReport (JSON + console)
     score, TRUST/REVIEW/CAUTION                  per-case metrics + pass/fail
```

**Package layout** (`com.sailpoint.poc.uiagent.eval`):

| Package | Role |
|---------|------|
| `eval/realtime/` | Confidence scoring for new uploads |
| `eval/benchmark/` | Offline eval cases, metrics, runner |
| `eval/shared/` | `StepSimilarity`, `LlmJudge` |
| `eval/report/` | Aggregate JSON + console reports |

**Resources:** `src/main/resources/eval/benchmarks.json` — ground truth for benchmark cases.

---

## Mode 1 — Real-time confidence (new video upload)

### When it runs

After a successful generate call on:

- **Provisioning** — `POST /api/generate` (`GenerateHandler`)
- **Aggregation** — `POST /api/aggregation/generate` (`AggregationGenerateHandler`)

The user can **enable or disable** eval via the **AI Confidence Eval** toggle on the upload card. When disabled, no extra LLM call is made.

**Provisioning vs strict goal format:** Claude must still return the `goal` Gradle block for a *valid* script, but confidence eval runs even when that format check fails (same idea as aggregation: show score + warnings, not a hard “generate failed”). Goal-format issues appear as `Goal format: …` warnings alongside pre-check and judge output.

### What we evaluate

We judge the **assembled navigation goal string** — the same comma-separated script the agent will run — not a numbered step list. That matches what actually gets executed.

Example:

```
click View menu, then click Scientific option, then click 3 button, then click 0 button, then click sin button, then click = button
```

*(The halt clause `this completes all steps — do not perform any further actions` is added by the prompt/UI for execution; it is stripped from benchmark metrics but may still appear in raw Claude output.)*

### How the confidence score is calculated

Confidence uses **two layers** combined into one score and a recommendation.

#### Layer A — Automated pre-checks (no LLM, instant)

These catch **structural** problems only — rules with few false positives:

| Check | Condition | Meaning |
|-------|-----------|---------|
| Step count | `< 2` steps | Output is empty or useless |
| Step count | `> 25` steps | Likely hallucination / runaway output |
| Action types | No `click`, `enter`, `type`, or `fill` in goal | Not a real navigation script |
| Pagination | AGGREGATION and no `paginationPattern` | Required for aggregation flows |
| Pagination type | AGGREGATION and type is `unknown` | Could not identify paging |
| Step format | Any step longer than 200 characters | Malformed output |
| Step format | Numbered list pattern (`1. 2. 3.`) | Wrong output format |
| Step format | Goal missing `, then ` connector | Wrong format contract |
| Placeholder leaks | **AGGREGATION + PLACEHOLDER only** — literal values in `enter "..."` / `select "..."` where `{Token}` was expected | Model failed placeholder mode |

**Not checked for PROVISIONING placeholder compliance:** literals in the goal are expected at generation time. The UI later tokenizes steps into `{Email}`, `{Password}`, etc. Checking raw Claude output for `{Token}` on provisioning would always fail incorrectly.

Each failed pre-check adds a **warning** and reduces the final score (up to **40 points** penalty: `8 points × number of warnings`, capped at 40).

#### Layer B — LLM judge (one extra Claude call, text-only)

`LlmJudge.judgeWithoutGroundTruth()` sends:

- Task type (PROVISIONING / AGGREGATION)
- Target URL (if known)
- Step count
- Full **generated navigation goal** string
- Pagination pattern (AGGREGATION only)

The judge returns a **confidence_score (0–100)** and sub-scores (correctness, order, hallucination, label quality, placeholder). Instructions are tuned to give a **holistic** assessment — not noisy warnings about individual labels, `UNKNOWN` passwords, or assumed missing navigation.

**Placeholder sub-score:** scored only for **AGGREGATION + PLACEHOLDER**. For PROVISIONING and LITERAL, placeholder is treated as N/A (score 10).

#### Final score and recommendation

```
finalScore = max(0, judgeConfidenceScore - preCheckPenalty)

recommendation:
  TRUST   — score ≥ 85 AND no warnings AND no suspected hallucinations
  REVIEW  — score 60–84 OR any warnings present
  CAUTION — score < 60 OR suspected hallucinations present
```

### What the UI shows

On the **Generated Script** card (Provisioning and Aggregation pages):

- **Score ring** (0–100) with color by recommendation (green / amber / red)
- **TRUST / REVIEW / CAUTION** badge
- **Reasoning** — 1–2 sentence explanation from the LLM judge (`confidenceReasoning`); shown whenever the recommendation is REVIEW or CAUTION
- **Warnings** list (from pre-checks + goal format + judge, when relevant)
- **“No issues detected”** only when recommendation is TRUST and warnings are empty
- Token cost includes the judge call when eval is enabled

---

## Mode 2 — Benchmark eval (offline / regression)

### When it runs

- **CLI:** `./gradlew runEval` (all cases or `--case=eval_001`)
- **UI:** Eval tab → **Run Eval** → live log via SSE → report in **Report History**

Configuration (`application.properties`):

- `eval.benchmarks.path` — path to `benchmarks.json`
- `eval.output.dir` — where `eval-report_{timestamp}.json` is written
- `eval.skip.judge` — skip LLM judge for faster/cheaper runs

### Ground truth

Each entry in `benchmarks.json` defines:

- Video path, target URL, task type, mode
- **Ground truth navigation goal** (full assembled string)
- Ordered **steps** (for automated metrics)
- **Pagination pattern** (AGGREGATION only)
- **Token definitions** (PLACEHOLDER cases)

Cases are loaded dynamically in the Eval UI (`GET /api/eval/cases`) — no hardcoded list in the frontend.

### Per-case pipeline

1. Load case from `benchmarks.json`
2. Extract frames from video (`VideoFrameExtractor`)
3. Call Claude:
   - **PROVISIONING** → `VideoToGoalPrompt` + `GoalExtractor`
   - **AGGREGATION** → `VideoAnalysisPrompt` + `VideoAnalysisResult.parse`
4. **Automated metrics** (no LLM) — compare generated steps vs ground truth steps
5. **LLM judge** (optional) — compares **ground truth goal** vs **generated goal** (not step-by-step lists)
6. Build `EvalResult` → aggregate **EvalReport**

### Automated metrics (benchmark)

Fuzzy matching uses **Jaccard similarity** on tokenized step text (threshold **0.6** via `StepSimilarity` / `FingerprintMatcher`).

| Metric | Weight (AGGREGATION) | Weight (PROVISIONING) | What it measures |
|--------|----------------------|------------------------|------------------|
| Step recall | 30% | **50%** | % of GT steps found in output |
| Step precision | 20% | 20% | % of output steps that match GT (low = hallucinations) |
| Step order | 15% | 15% | Kendall Tau on matched step order |
| Label accuracy | 15% | 15% | Average similarity on matched pairs |
| Placeholder | 10% | **0%** (skipped) | AGGREGATION+PLACEHOLDER only: `{Token}` vs literals |
| Pagination | 10% | **0%** (skipped) | AGGREGATION only: type + selector + description |

**Pass threshold:** `overallScore >= 0.70`

**Special handling:**

- **Halt clause** filtered out of generated steps before metrics (system-appended, not in GT).
- **PROVISIONING:** pagination and placeholder weights roll into step recall (50% total).

### LLM judge (benchmark)

Compares two **navigation goal strings**:

- Ground truth goal (from `benchmarks.json`)
- Generated goal (from Claude)

Scores 0–10 on correctness, order, hallucination, label quality, placeholder (AGGREGATION+PLACEHOLDER only), and overall — plus short reasoning.

---

## Task types and prompts (important distinction)

| Task type | Claude prompt | Output format | Placeholder in raw output? |
|-----------|---------------|---------------|----------------------------|
| **PROVISIONING** | `VideoToGoalPrompt` | `goal` block → steps | No — literals from video; UI adds `{Token}` later |
| **AGGREGATION** (LITERAL) | `VideoAnalysisPrompt` | JSON | Literals OK |
| **AGGREGATION** (PLACEHOLDER) | `VideoAnalysisPrompt` | JSON with `{Token}` | Yes — required |

This is why benchmark **placeholder** and **real-time placeholder** checks apply only to **AGGREGATION + PLACEHOLDER**, not to provisioning uploads.

---

## API and UI surfaces

| Endpoint / surface | Purpose |
|--------------------|---------|
| `GET /api/eval/cases` | List cases from `benchmarks.json` (Eval UI dropdown) |
| `GET /api/eval/reports` | List past benchmark reports |
| `POST /api/eval/run` | Start benchmark run (background + SSE) |
| `POST /api/eval/stop` | Stop running benchmark |
| `GET /api/eval/stream` | SSE log/progress for benchmark run |
| `POST /api/generate` | Provisioning generate + optional confidence in response |
| `POST /api/aggregation/generate` | Aggregation generate + optional confidence in response |
| UI `/eval` | Run eval, metric reference, report history |

---

## Configuration summary

| Property | Default | Description |
|----------|---------|-------------|
| `eval.benchmarks.path` | `./src/main/resources/eval/benchmarks.json` | Ground truth cases |
| `eval.output.dir` | `./eval-reports` | Benchmark report output |
| `eval.skip.judge` | `false` | Skip LLM judge in CLI benchmark runs |

---

## Quick reference — confidence vs benchmark

| | Real-time confidence | Benchmark eval |
|--|----------------------|----------------|
| **Ground truth** | None | `benchmarks.json` |
| **Trigger** | After each generate (if toggle on) | Manual / CI (`runEval` or Eval UI) |
| **Primary input to judge** | Generated navigation goal | GT goal vs generated goal |
| **Output** | Score + TRUST/REVIEW/CAUTION + warnings | Per-case metrics + JSON report |
| **Cost** | +1 Claude call per upload | N cases × (Claude video + optional judge) |

---

## Related docs

- Detailed implementation spec: `src/main/resources/eval.md` (original requirements)
- Ground truth data: `src/main/resources/eval/benchmarks.json`
- Run benchmark: `./gradlew runEval` or Eval tab in UI (`http://localhost:8080/eval`)
