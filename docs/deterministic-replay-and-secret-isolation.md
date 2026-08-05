# Deterministic Replay & Secret Isolation — Design Doc

**Status:** Proposed
**Author:** amol.palwe
**Date:** 2026-07-22
**Scope:** `ui-agent-bedrock-poc` (both PROVISIONING and AGGREGATION task types)

---

## 1. Problem statement

Our DE raised two concerns about promoting the POC toward a production posture:

1. **Non-determinism** — every run re-drives the *live* target UI with the LLM making
   fresh navigation decisions. The same aggregation job can take a different path (or fail
   differently) run-to-run, which is unacceptable for a scheduled/production connector.
2. **Admin credentials handed to the LLM at runtime** — the model receives plaintext
   credentials in its prompt context and is trusted to type them into the live admin app.

Both are legitimate. This doc specifies how we close them by wiring together capabilities
the POC **already has** and borrowing two proven patterns from Skyvern (our reference
production system for LLM-driven browser automation).

The guiding principle, adopted from Skyvern:

> **Plan once with the LLM. Replay deterministically. Fall back to the LLM only to self-heal
> a broken step. Never let the LLM see a real secret.**

---

## 2. Current architecture (as-is)

### 2.1 The canonical loop

Every run funnels through `AgentPipeline.run` (`pipeline/AgentPipeline.java`), which owns the
lifecycle of the Bedrock client, `BrowserSession`, and `ActionLogger`. The loop is
**OBSERVE → PLAN → ACT**:

- **OBSERVE** — `BrowserSession.listInteractables` scrapes visible interactables, tags each
  with a stable fingerprint id, and builds a numbered element list + screenshot.
- **PLAN** — `AgentLoop` (`AgentLoop.java:209-331`) sends the element list + screenshot to
  Bedrock Claude, which returns a JSON action batch.
- **ACT** — `AgentLoop.executeActions` runs the actions via Playwright; loop until `DONE`.

Task types (`PipelineConfig.TaskType`):
- **PROVISIONING** — navigate + AgentLoop until `DONE`.
- **AGGREGATION** — navigate + AgentLoop to reach the list page, then
  `detectTable → detectExpectedTotal → paginationLoop → writeCsv`
  (`AgentPipeline.java:254-323`).

### 2.2 Where non-determinism actually lives

On **every** run, the LLM makes live decisions for:

| Source | Location | Deterministic today? |
|---|---|---|
| Login + navigation to list page | `AgentLoop.run` via `AgentPipeline.java:155-163` | ❌ re-planned each run |
| Table + header detection | `AccountAggregator.detectTable` (Claude call at `AccountAggregator.java:517`) | ❌ live vision call |
| Expected-total detection | `AccountAggregator.detectExpectedTotal` (`:684`) | ❌ live vision call (fallback) |
| First "Next page" click | `AccountAggregator.askClaudeForNextPage` (`:1525-1586`) | ❌ live vision call |
| Pagination pages 2..N | intra-run learned selector/fingerprint cache | ⚠️ deterministic *within* a run, rebuilt from scratch each run |

The intra-run caches (`learnedSelector`, `learnedFingerprintString`) are **in-memory only** and
never persisted, so nothing carries across runs.

### 2.3 Where credentials flow today

- CLI / live path: `PipelineConfig.resolvedGoal()` (`PipelineConfig.java:130-136`) substitutes
  every `{Token}` → plaintext value **into the goal string** *before* it reaches the LLM.
  `AgentLoop.buildUserMessage` then interleaves that plaintext into the prompt. **The LLM sees
  the real admin password.**
- Video-analysis prompts (`prompts/AGGREGATION_PROMPT.md`) already tokenize credentials to
  `{Email}` / `{Password}` — but that only governs the offline video→goal step, not the live run.

### 2.4 What already exists and is unused for this purpose

The POC contains a **complete record/replay subsystem** (`replay/` package) that is currently
only wired for PROVISIONING and stops short of aggregation:

- **`ScriptRecorder`** — records successful AgentLoop actions into a `Script` (JSON), already
  tokenizing credential-like TYPE values (`ScriptRecorder.java:99-108`). Recording is enabled
  via `AgentLoop.enableRecording` (`AgentLoop.java:195`) and fires for **both** task types
  (`AgentLoop.java:493`).
- **`Script` / `ScriptStep`** — persisted plan: per-step stable id, **fingerprint string +
  level**, element label, querySelector-safe fallback selectors, expected URL pattern, and
  self-heal metadata (`missCount`, `lastReplayStrategy`).
- **`ScriptExecutor`** — the replay engine with a **6-level element-location fallback ladder**
  (`ScriptExecutor.java:166-226`):
  1. `FINGERPRINT` — element still present by stable id
  2. `RETAG` — re-scrape viewport, retry
  3. `SCROLL_INTO_VIEW` — scroll via fallback CSS selector, retag
  4. `PROGRESSIVE_SCROLL` — chunk-scroll from top, retag each chunk
  5. `FUZZY` — Jaccard fingerprint similarity ≥ 0.75 (`FingerprintMatcher`)
  6. `CLAUDE` — last-resort LLM recovery by element label
  On any miss above `FINGERPRINT`, `selfHeal` rewrites the step's fingerprint in place
  (`ScriptExecutor.java:403-405`, `Script.saveInPlace`).
- **`TokenValues`** — token substitution for replay TYPE steps, with `validateRequired`
  and a `MissingTokenException` guard (`TokenValues.java`).

**This is essentially Skyvern's "code mode + AI fallback" already built.** The gap is wiring,
not capability.

---

## 3. How Skyvern solves the same two problems (reference)

| Concern | Skyvern mechanism | POC analogue |
|---|---|---|
| Re-bind a past decision to the right DOM element | Content-based **element hash** (`hash_element` strips volatile ids/rects, SHA-256s tag + stable attrs + child structure); replay matches by hash, not xpath | **Already identical** — POC element ids are stable fingerprint hashes; `FingerprintMatcher` |
| Determinism across runs | Record a successful run → compile to deterministic replay (`core/script_generations/`); production uses "code mode", LLM only as repair | `ScriptRecorder` → `Script` JSON → `ScriptExecutor` replay |
| Self-healing when a cached step breaks | `ai_fallback=True` for cached runs; failing step falls back to LLM, then script reviewer regenerates | `ScriptExecutor` 6-level ladder + `selfHeal` rewrite-in-place |
| Secrets away from the LLM | LLM only sees `placeholder_*` tokens; real value swapped in at Playwright-execution time; masked in logs; `ImaginarySecretValue` guard if the model emits an unregistered token | **Partially** — replay TYPE steps use tokens, but the live/RECORD path substitutes plaintext into the prompt (gap closed in Phase 3) |
| Deterministic extraction | Extraction-result cache keyed on canonicalized DOM hash | `NetworkAggregator` (direct authenticated HTTP pagination) |

---

## 4. Target architecture (to-be)

```
                         ┌─────────────────────────────────────────────┐
   First run (RECORD) →  │ AgentLoop (LLM plans nav) → ScriptRecorder   │
                         │ → Script JSON persisted (creds tokenized)    │
                         └─────────────────────────────────────────────┘
                                              │
                                              ▼
   Production runs      ┌─────────────────────────────────────────────┐
   (REPLAY) →           │ ScriptExecutor replays nav deterministically │
                        │  · fingerprint match (no LLM)                │
                        │  · LLM only to self-heal a broken step       │
                        │  · secrets substituted at TYPE execution     │
                        └─────────────────────────────────────────────┘
                                              │  (reached list page)
                                              ▼
   AGGREGATION only     ┌─────────────────────────────────────────────┐
                        │ finishAggregation():                         │
                        │  · NETWORK mode → direct authenticated HTTP  │
                        │    pagination (no LLM), else                 │
                        │  · LLM_DOM detectTable/paginate/writeCsv     │
                        └─────────────────────────────────────────────┘
```

Steady-state target: a production aggregation run makes **zero live LLM navigation calls**
(replay) and, in NETWORK mode, **zero LLM extraction calls** — the LLM is invoked only when
replay must self-heal a drifted element.

---

## 5. Phased implementation plan

Each phase is independently shippable and leaves the build green.

### Phase 1 — Replay continues into the aggregation tail (both task types) — *core*

**Goal:** a REPLAY run that reaches the list page then completes aggregation exactly like a live
run. Today `runReplay` (`AgentPipeline.java:343-365`) always returns `provisioningSuccess` and
stops.

**Changes (`pipeline/AgentPipeline.java`):**
1. Extract the aggregation tail (current `:189-323` — NETWORK settle + aggregate, else LLM_DOM
   detect/paginate/writeCsv) into a private helper:
   ```java
   private static PipelineResult finishAggregation(
       PipelineConfig config, ProgressListener pl, BrowserSession browser,
       BedrockAnthropicClient bedrock, TokenUsage totalUsage, int agentSteps,
       NetworkAggregator networkAggregator, String finalUrl) throws Exception
   ```
   The live path calls it after the AgentLoop; behavior is byte-for-byte unchanged.
2. Rewrite `runReplay` to:
   - Start the `NetworkAggregator` sniffer **before** `executor.replay(...)` when
     `isAggregation() && isNetworkMode()` (replay navigates inside `ScriptExecutor`, so the
     sniffer must be attached first — mirrors the live ordering at `:130-134`).
   - On replay failure → return error (unchanged).
   - On success + PROVISIONING → `provisioningSuccess` (unchanged).
   - On success + AGGREGATION → call `finishAggregation(...)` with `replay.stepsTotal()` as the
     step count.

**Risk:** low — pure refactor + one new branch. No behavior change for existing live runs.

**Acceptance:** `--mode=REPLAY` on a recorded aggregation script produces a CSV identical to a
live run, with `Claude calls` reported by the replay summary at or near zero.

### Phase 2 — Expose RECORD / REPLAY on aggregation + CLI entry points

**Goal:** make the new capability reachable from every entry point.

**Changes:**
- `UiAgentPocApplication.java` — stop hardcoding `taskType(PROVISIONING)` (`:67`); accept a
  `--task=AGGREGATION|PROVISIONING` flag (or infer) so `--mode=RECORD/REPLAY` composes with
  aggregation. Extend `--help`.
- `aggregation/AggregationRunner.java` & `AggregationPlanRunner.java` — pass `mode` +
  `scriptPath` / `scriptName` through to `PipelineConfig`.
- UI handlers (`ui/RunHandler.java`, `ui/AggregationRunHandler.java`) — surface a
  "Record / Replay / Live" selector and a script picker; thread `PipelineMode` + script path
  into the config. (`ScriptLister` already lists saved scripts.)
- Confirm recording captures nav steps for aggregation (it already does — `AgentLoop.java:493`).

**Risk:** low-medium — touches UI wiring; no core-logic change.

**Acceptance:** a full RECORD-then-REPLAY cycle for an aggregation job works from both CLI and UI.

### Phase 3 — Secret isolation: the LLM never sees plaintext credentials — *core*

**Goal:** close the second DE concern. Adopt Skyvern's placeholder pattern on the live/RECORD path.

**Changes:**
- **Stop pre-substituting secrets into the goal.** Feed the *tokenized* goal (`{Email}`,
  `{Password}`) to `AgentLoop`; carry `TokenValues` into the loop instead of calling
  `resolvedGoal()` for the prompt. (`resolvedGoal()` stays available for non-secret tokens if
  needed, but secret tokens must remain tokenized.)
- **Substitute at execution time only.** In `AgentLoop.executeActions`, for a `TYPE` action whose
  text is (or contains) a registered token, resolve the real value via `TokenValues.substitute`
  *at the moment of the Playwright type call* — never place it back into conversation history.
- **Mask everywhere.** Ensure `ActionLogger` and any screenshot/telemetry path records the
  placeholder, not the value (flag `isSecret = resolvedText != action.text`, mirroring Skyvern's
  `handler.py`).
- **Guard rail.** If the model emits a `{Token}` that was never registered, raise a clear
  error (analogue of Skyvern's `ImaginarySecretValue`) rather than typing a literal brace string.

**Risk:** medium — changes prompt construction and the TYPE execution path; needs careful testing
so login flows still succeed (the model must be told these are placeholders, per Skyvern's
injected note).

**Acceptance:** with a network/prompt trace, no request body to Bedrock contains a real credential;
login still succeeds; logs show `{Password}` never the value.

### Phase 4 — Prefer NETWORK extraction + posture doc

**Goal:** make deterministic extraction the default and document the posture.

**Changes:**
- Default aggregation to `AggregationMode.NETWORK` where viable, with `LLM_DOM` fallback (config
  `network.agg.fallback.llm.dom=true` already exists). Keep it opt-out.
- Add a short "determinism posture" section to the README mapping the four phases to the DE's two
  concerns, plus this doc as the deep reference.

**Risk:** low — mostly config + docs; NETWORK mode already implemented.

---

## 6. Cross-cutting concerns

- **Script drift / staleness.** `ScriptExecutor` already tracks `missCount` and warns at
  `>3` misses or `>30%` miss rate (`ScriptExecutor.java:475-487`), and `selfHeal` rewrites
  fingerprints in place. Recommend surfacing these warnings to operators and treating a
  high-miss replay as a signal to re-record.
- **Secret storage.** Tokens today come from CLI `--token`, UI fields, or `TokenValues`. Phase 3
  does not add a vault; a future enhancement could plug a real secret manager (Skyvern supports
  Bitwarden/1Password/Azure/AWS) behind the same `TokenValues` seam.
- **NETWORK + replay ordering.** The sniffer must attach before replay navigates (Phase 1
  handles this). Document the invariant so it isn't reintroduced as a bug.
- **Cost/latency win.** As a side effect, replay + NETWORK removes the per-run vision calls for
  navigation, header detection, and first-page pagination — the dominant token costs today.

## 7. Testing & validation

- Phase 1: record an aggregation flow against a known app; replay 3×; assert identical CSV row
  counts and near-zero Claude calls in the replay summary.
- Phase 3: capture the Bedrock request payloads (or assert via a spy on `BedrockAnthropicClient`)
  that no registered secret value appears; assert login still reaches the list page.
- Regression: existing live PROVISIONING and live LLM_DOM aggregation runs unchanged.

## 8. Rollout

1. Land Phase 1 (behind existing `--mode` flags — no default change).
2. Land Phase 2 (entry points).
3. Land Phase 3 (secret isolation) — the one behavior change on the live path; validate carefully.
4. Land Phase 4 (default NETWORK + docs).

Each phase is reversible via config / flags; no phase changes the default behavior of an existing
live run until explicitly opted in (except Phase 3's secret masking, which is strictly safer).

---

## Appendix A — Key file reference

| Concern | File |
|---|---|
| Pipeline orchestration, `runReplay` | `pipeline/AgentPipeline.java` (`:343-365`, aggregation tail `:189-323`) |
| Task types / config | `pipeline/PipelineConfig.java` (`resolvedGoal` `:130-136`), `PipelineMode.java` |
| Agent loop, recording hook, TYPE exec | `AgentLoop.java` (`:195`, `:413-520`, `:493`) |
| Replay engine (6-level fallback, self-heal) | `replay/ScriptExecutor.java` |
| Recorded plan model | `replay/Script.java`, `replay/ScriptStep.java` |
| Recorder (credential tokenization) | `replay/ScriptRecorder.java` (`:99-108`) |
| Token substitution + guard | `replay/TokenValues.java` |
| Fingerprint matching | `replay/FingerprintMatcher.java` |
| Aggregation (table/pagination) | `aggregation/AccountAggregator.java` (`:517`, `:1525-1586`) |
| Deterministic network extraction | `aggregation/NetworkAggregator.java` |
| CLI entry / arg parsing | `UiAgentPocApplication.java` (`:66-67`, `:121-172`) |
