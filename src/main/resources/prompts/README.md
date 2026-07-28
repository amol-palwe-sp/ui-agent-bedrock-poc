# Prompts Reference

This folder documents every LLM prompt used in the UI Agent POC.  
All prompts are sent to Claude via AWS Bedrock (`BedrockAnthropicClient`).

---

## Files

| File | Prompt | Task type | Source class |
|------|--------|-----------|-------------|
| [`PROVISIONING_PROMPT.md`](./PROVISIONING_PROMPT.md) | Watches a provisioning recording and generates a `./gradlew run` command | PROVISIONING | `VideoToGoalPrompt` |
| [`AGGREGATION_PROMPT.md`](./AGGREGATION_PROMPT.md) | Watches an aggregation recording and returns a JSON navigation goal + pagination pattern | AGGREGATION | `VideoAnalysisPrompt` / `AggregationVideoAnalysisPrompt` |
| [`LLM_JUDGE_PROMPT.md`](./LLM_JUDGE_PROMPT.md) | Scores the generated goal against ground truth (benchmark) or holistically (real-time) | Both | `LlmJudge` |

---

## Flow overview

```
Screen recording (MP4)
        │
        ▼ extract frames (VideoFrameExtractor)
   [JPEG frames]
        │
        ├─── PROVISIONING ──► VideoToGoalPrompt ──► GoalExtractor
        │                                                  │
        │                                           ./gradlew run --args='...'
        │
        └─── AGGREGATION ───► VideoAnalysisPrompt ──► VideoAnalysisResult.parse()
                                                            │
                                                     { targetUrl, navigationGoal,
                                                       tokens[], paginationPattern }
                                                            │
                                                    [optional] LlmJudge
                                                            │
                                                     quality scores (0–10)
```

---

## Credential handling

| Mode | How credentials appear in output |
|------|----------------------------------|
| `LITERAL` | Transcribed exactly as seen (`"admin@corp.com"`) |
| `PLACEHOLDER` | Replaced with `{Token}` syntax (`"{Email}"`) — **always used for SaaS systems** |

All eval benchmark cases for real SaaS systems use `PLACEHOLDER` mode.
