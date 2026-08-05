# Prompts — Complete Reference

All LLM prompts used in the UI Agent POC, one file per prompt.  
Every call goes to Claude via AWS Bedrock (`BedrockAnthropicClient`).

---

## Prompt index

| File | # | Name | One-line purpose |
|------|----|------|-----------------|
| `01_PROVISIONING_VIDEO_ANALYSIS.md` | 01 | Provisioning Video Analysis | Video frames → single Gradle `./gradlew run` command with full goal |
| `02_AGGREGATION_VIDEO_ANALYSIS.md` | 02 | Aggregation Video Analysis | Video frames → JSON with URL, navigation steps, pagination pattern |
| `03_AGENT_LOOP.md` | 03 | Agent Loop | Live page screenshot + elements → next 1–3 browser actions (repeated per step) |
| `04_LLM_JUDGE.md` | 04 | LLM Judge | Score a generated goal for correctness, order, hallucination, label quality |
| `05_VIDEO_RELEVANCE_TRIAGE.md` | 05 | Video Relevance Triage | Pre-flight classifier: is this video a usable UI workflow or should it be rejected? |
| `06_AGGREGATION_INLINE.md` | 06 | Aggregation Inline Fallbacks | Two narrow fallback calls during live scraping: total count detection, next-page detection |

---

## Where each prompt fits in the pipeline

```
  MP4 upload
      │
      ▼
[05] Video Relevance Triage  ── REJECT ──► 🚫  (stop, user sees reason + suggestion)
      │
    ACCEPT
      │
      ▼
  Frame extraction (VideoFrameExtractor — no LLM)
      │
      ├── PROVISIONING ──► [01] Provisioning Video Analysis
      │                          │
      │                       ```goal block
      │                          │
      │                       GoalExtractor (parse steps)
      │
      └── AGGREGATION ──► [02] Aggregation Video Analysis
                                 │
                              JSON: { targetUrl, navigationGoal, paginationPattern, tokens[] }
                                 │
                              VideoAnalysisResult.parse()
      │
      ▼
  [04] LLM Judge (optional, runs after generation)
       ├── Benchmark mode: score vs ground truth from benchmarks.json / stage1-dataset.json
       └── Real-time mode: holistic self-assessment (when "AI Confidence Eval" toggle is ON)

  ──────────────── Stage 2 — Browser execution ──────────────────

  AgentPipeline.run()
      │
      └── [03] Agent Loop  (called once per step, up to max_steps)
               │
               └── Playwright executes actions on live browser
                        │
                        └── AGGREGATION tail:
                             AccountAggregator
                              ├── JS-based table detection (no LLM)
                              ├── [06a] Total count vision fallback  (if JS fails)
                              ├── CSS-based next-page navigation (no LLM)
                              └── [06b] Next-page vision fallback  (if CSS fails)
```

---

## Credential handling

| Mode | How credentials appear | Where set |
|------|----------------------|-----------|
| `LITERAL` | Transcribed as seen on screen (`"admin@corp.com"`) | Used for test/harness cases |
| `PLACEHOLDER` | Replaced with `{Token}` syntax (`"{Email}"`) | **Always used for real SaaS systems** |

In Stage 2, `{Token}` values are substituted at Playwright execution time inside `AgentLoop`.
The model only ever sees the placeholder string — real credentials never enter any prompt, history line, or log.

---

## Call frequency per user action

| Prompt | Calls per "Generate" click | Calls per "Run" click |
|--------|--------------------------|----------------------|
| 05 Relevance Triage | 1 (unless `force=true`) | 0 |
| 01 or 02 Video Analysis | 1 | 0 |
| 04 LLM Judge (real-time) | 1 (if eval toggle ON) | 0 |
| 03 Agent Loop | 0 | 1 per step (up to `agent.max_steps`, default 15) |
| 06a Total Count | 0 | 0–1 (only if JS count detection fails) |
| 06b Next-Page | 0 | 0–N (once per page, only if CSS selectors all fail) |
