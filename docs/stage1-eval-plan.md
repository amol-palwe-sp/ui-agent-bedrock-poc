# Stage 1 Evaluation Plan — Video → Steps

**Status:** Proposed — for review  
**Author:** amol.palwe  
**Date:** 2026-08-05

---

## 1. What this is about

Our agent turns a **screen-recording of someone doing a task** into a **list of steps** the system can later run (log in, click here, type there, go to the users page, and so on).

This document explains how we'll check whether that "video → steps" conversion is good enough, and what "good enough" means. It covers **only** that first conversion step. Actually running the steps in a browser is a separate stage and is not part of this review.

## 2. Why we're evaluating it (the decision it supports)

Setting up a connector happens **once**, and a **person reviews the generated steps before they're saved**. So we are not asking "is the agent perfect on its own?" We're asking:

> Can a reviewer approve the generated steps with only small edits, and are we safe from mistakes a reviewer is likely to miss?

The two mistakes we care about most, because they're easy to overlook and expensive:

1. **A missing or wrong step** — the plan looks fine but skips something or points at the wrong button, so it breaks when run.
2. **A variable not turned into a placeholder** — a value that should be a reusable field (like a password or email) is written out directly instead of appearing as a placeholder like `{Password}`, so the connector can't be reused with different data.

Everything below is designed to catch those two things first, then to measure overall quality.

## 3. What we'll evaluate against — the happy path

**How the test set is balanced.** A benchmark made only of clean, well-behaved recordings tells us how the agent does on its best day, which is not the question we need answered. Real users will hand it messy, half-finished and occasionally nonsensical recordings. So the set is split roughly in half:

| | Share | What it proves |
|---|---|---|
| **Happy path** (§3) | ~50% | It reads a good recording correctly, across a wide variety of interfaces |
| **Unhappy path** (§4) | **at least 50%** | It doesn't quietly produce a confident, wrong answer when the input is bad |

**The key idea: Stage 1's difficulty comes from the _UI variation_ in the video, not from which product it is.** Reading "click the Next button" is the same job whether the screen is Coupa or Google. What actually makes it easier or harder is how the interface looks and behaves — the kind of field, how pages change, how results are paged, the theme and language. So we organise the test set around **UI variations**, not around systems.

We'll draw from two sources:

1. **Our built-in practice apps (the "test harness").** This is a set of self-contained dummy applications that deliberately covers a wide range of UI variations. It is controlled, safe (uses fake logins), and comprehensive.
2. **A few real-app videos** (Coupa, Google, BambooHR, etc.) kept for realism.

For each case we need a short screen-recording of the task plus its hand-written **"correct" steps** — the known-good answer we compare the agent's output to.

**The UI variations we'll cover:**

- **Field types** — plain text, dropdowns, type-ahead search, checkboxes, radios, toggles, one-time codes, and masked/password fields.
- **Navigation** — multi-page wizards, pop-up dialogs, tabs, and pages that load or spin before the next action.
- **Paging through results** — next button, numbered pages, load-more, infinite scroll, and page-size selectors.
- **Look & language** — dark theme and non-English labels.
- **Tricky cases** — two buttons with the same label ("Next"), and a cursor or tooltip covering the target.

**Where we are today:** 72 cases are authored in [`src/main/resources/eval/stage1-dataset.json`](../src/main/resources/eval/stage1-dataset.json) — 55 happy-path and 17 unhappy. Of these, **29 currently have recorded videos and are runnable today; 43 are seeded with empty video paths** and switch on the moment a recording is dropped in. The 26 cases with recordings also carry hand-authored Layer 1 assertions (§5); the rest do not yet, and the harness says so out loud rather than counting them as passes.

## 4. Unhappy paths — at least half the benchmark

**The failure we're actually worried about.** If someone hands the agent a recording of the wrong thing, or a recording too poor to read, the dangerous outcome is not that it fails. It's that it produces a **confident, plausible-looking plan anyway** — and a reviewer, seeing a tidy list of steps, approves it. A clear "I can't use this, and here's why" is a *better* result than a neat plan built on guesswork.

So for these cases we invert the usual test. **Success is refusing, flagging, or degrading honestly — not producing steps.** A case that yields a polished plan from an unusable video is a *failure*, even though the output looks good.

We group the unhappy half into three families, by what is wrong with the input.

### 4a. Incorrect videos — a real recording of the *wrong or flawed* process

The video is a genuine browser recording, but the person demonstrating made mistakes or wandered. The agent should still produce a usable plan, but it must capture the **intended task**, not blindly transcribe everything that happened — and it should flag what it wasn't sure about.

- Switched to an unrelated tab mid-task and came back.
- Skipped a step (jumped from step 2 to step 4).
- Made a mistake and corrected it — typed a wrong value, cleared it, retyped.
- Clicked the wrong button, then used browser Back to return.
- Recording **starts mid-process** — already logged in, first steps never shown.
- Recording **stops before the task finished**.
- Repeated the same action twice by accident.
- A long idle pause in the middle.
- An unrelated detour (checked email, opened a chat) partway through.
- **Two different tasks** captured in one recording.
- Submitted a form, hit a validation error, then fixed it.
- Used a keyboard shortcut with no visible on-screen action.

*Expected behaviour:* a plan for the intended task, with corrections and detours **excluded**, and a note flagging any gap it had to infer.

### 4b. Invalid videos — not a usable input at all

There is nothing here to convert. The only correct answer is a **clear rejection with a reason**. Producing any plan is an automatic fail.

- Not a browser at all — a desktop application, an IDE, a terminal.
- A still desktop with no interaction.
- **Too short** — a second or two, nothing meaningful happens.
- **Too long** — a 30-minute-plus session covering many unrelated things.
- Corrupt or unreadable file.
- Wrong file type entirely — audio, or a single image.
- Zero-byte or truncated file.
- A slideshow of screenshots rather than a recording.
- A mobile-phone screen recording, not a desktop browser.
- Blank or black screen throughout.
- Webcam footage of a person.

*Expected behaviour:* reject, name the reason, produce **no steps**.

### 4c. Unworkable videos — right task, unreadable capture

A real browser recording of the correct task, but the capture quality makes it unreliable to read. This is the subtlest family: the agent has *just enough* to guess, which is exactly when it's most likely to guess wrong.

- Rapid zooming in and out.
- Fast scrolling — content flies past between frames.
- Very low resolution or heavy compression.
- Tiny text at low contrast.
- Cursor moving too fast to follow.
- Browser window resized partway through.
- Heavy animation or motion in the page.
- A picture-in-picture or notification overlay covering the target.
- Multiple monitors captured, the relevant one small.
- Severe frame-rate drops.

*Expected behaviour:* either a plan **marked low-confidence** with the unreadable parts flagged, or an honest refusal. What must **not** happen is a confident plan with invented labels.

### Proposed sizing

| Family | Cases | Notes |
|---|---|---|
| Happy path (§3) | 55 | 29 recorded; 26 awaiting video |
| 4a — Incorrect | 24 | Cheap to record on the practice apps |
| 4b — Invalid | 18 | Mostly synthetic; no recording needed for several |
| 4c — Unworkable | 18 | Re-record existing happy tasks under degraded capture |
| **Total** | **115** | **Unhappy = 60 / 115 ≈ 52%** |

Seeded so far: 17 of the 60 unhappy cases — 4 incorrect, 8 invalid, 5 unworkable — with empty video paths. Cases without a recording are skipped rather than failed, so the benchmark runs today and each case switches on the moment its video is dropped in.

## 5. How we'll score each generated plan

**The dividing principle: code checks facts, the LLM makes judgements.**

Some questions have a verifiable right answer — was this step present, were the steps in this order, did a variable come out as `{Email}`. Those are checked by code, because code is repeatable and can't be argued with. Other questions are inherently matters of meaning — is "click Save button" the same as "click the Save button", would a reviewer accept this wording. Those go to the LLM judge, because fuzzy word-counting answers them badly.

**Why word-overlap was removed from the pass path.** The previous approach matched steps by token-Jaccard similarity (≥ 0.60). This is the wrong tool: `"click the cancel button"` and `"click the accept button"` share three of four tokens and score 0.75 — they match, and the wrong step goes unreported. Since the LLM judge is already in the loop, it is the right place for this judgement. The scored counts (recall, precision, order) are still computed and printed as a **non-gating diagnostic** — useful for spotting regressions between runs, but deciding nothing about pass or fail.

A case now passes on **two independent conditions**:

1. **All per-case assertions pass** (Layer 1 — structural facts, no judgment required)
2. **The LLM judge's overall verdict ≥ 7 / 10** (Layer 2 — semantic quality)

---

### Layer 1 — Per-case assertions (deterministic, pass/fail)

Each eval case is authored with a small set of hard assertions alongside its video and ground truth. These are facts with a definite right answer:

- **Step count** — `exactStepCount` where the count is genuinely unambiguous, or `minStepCount`/`maxStepCount` on the longer real-system flows where the model may reasonably merge a focus-click into the typing that follows.
- **Required steps present** — each declared phrase must appear in some generated step (normalised containment of a phrase the author wrote down — not word overlap computed across cases).
- **Forbidden steps absent** — no generated step may contain the phrase. This is what makes the 4a family testable: `u4a_typo_corrected` forbids `clear`/`retype`, so transcribing the correction instead of understanding the intent fails outright.
- **Placeholder tokens emitted** — every variable declared in the case (e.g. `Email`, `Password`) must appear as `{Email}`, `{Password}` somewhere in the output. We don't check what any value is — just that the token is present.
- **Well-formed output** — the output is a readable, structured list the system can parse.
- **Rejection with reason** — for invalid-video cases (4b), the system must produce a deliberate refusal message, not an error or an empty response.

These assertions are authored per case and checked mechanically. Nothing is inferred or approximated. Placeholder assertions are folded in automatically from each case's declared variables, so a case cannot skip the safety check by omitting its assertion block.

A case that asserts nothing clears Layer 1 without being checked, which is a real gap rather than a pass — so the coverage is printed rather than assumed:

```bash
./gradlew runEval -Pargs="--benchmarks=./src/main/resources/eval/stage1-dataset.json --lint-assertions"
```

This lists what each case checks without extracting frames or calling the model. Today it reports **28 of the 59 scored cases have assertions; 31 would clear Layer 1 unchecked** — those 31 being precisely the cases still waiting on a recording.

---

### Layer 2 — The LLM judge (headline quality score, 0–10)

We ask Claude to compare the generated steps to the known-good answer and return structured scores. This is the layer that answers the question the whole exercise is really about: *would a reviewer accept this plan with only small edits?*

The judge's `overall` score is the headline result. The `reasoning` field is the most actionable output the evaluation produces — a number tells you a case scored 7/10; the reasoning tells you which step was wrong and why.

**Judge output schema** — each field description is the rubric the model receives:

```json
{
  "correctness":        "0-10: does the generated goal cover the same actions as ground truth, with nothing important missing?",
  "order":              "0-10: are the actions in the same chronological order as ground truth?",
  "hallucination":      "0-10: 10 = no invented actions not in ground truth; 0 = many invented actions",
  "label_quality":      "0-10: do UI element labels (field names, button labels) match what ground truth calls them?",
  "placeholder":        "0-10 in PLACEHOLDER mode — every entered or selected value must be a {Token}, never a literal. In LITERAL mode: give 10, not applicable.",
  "overall":            "0-10: holistic quality — would a reviewer approve this plan with only small edits?",
  "reasoning":          "2-3 sentences a reviewer can act on, naming the specific missing or wrong steps",
  "hallucinated_steps": ["action phrase present in generated but absent from ground truth"],
  "missing_steps":      ["ground-truth action phrase not found in generated output"],
  "confidence_score":   "0-100: overall trustworthiness (used in real-time mode without ground truth)",
  "warnings":           ["structural problems: empty goal, impossible action order, clearly missing required steps"],
  "recommendation":     "TRUST | REVIEW | CAUTION"
}
```

The judge prompt is in [`src/main/resources/prompts/04_LLM_JUDGE.md`](../src/main/resources/prompts/04_LLM_JUDGE.md).

**One structural weakness: the judge runs on the same model that generated the steps.** The risk is correlated blindness — a model can misread a screen and then confirm its own misreading. The Layer 1 assertions are the check on this, because a missing step is missing regardless of what any model believes. Using a different model for judging would be a genuine improvement worth revisiting after we've validated the grader.

---

### Scoring the unhappy cases — expected verdicts instead of expected steps

For unhappy cases the right answer is "produce nothing" or "flag this." Layer 1 assertions verify the structural behaviour (no plan, or a refusal with a reason). The LLM judge is used to check whether a refusal was well-stated:

| Family | Expected verdict | Counts as a failure |
|---|---|---|
| **4a — Incorrect** | A plan for the intended task, with mistakes and detours left out, gaps flagged | Faithfully reproducing the mistakes, or silently inventing the missing steps |
| **4b — Invalid** | Rejected, with a reason. No steps. | **Any** plan at all |
| **4c — Unworkable** | Low-confidence plan with unreadable parts flagged, or an honest refusal | A confident plan containing invented labels |

The single number we care about across the unhappy half is **"behaved as expected"** — not how many produced a plan.

One caution on 4b: "no steps produced" is easy to hit for the wrong reason — a crash also produces no steps. So a rejection only passes if it is a **deliberate refusal with a stated reason**, not an error or an empty response.

#### How the system actually refuses

The step-generation prompt has no way to decline; asked to describe a video, it describes one. The refusal comes from a separate pre-flight **relevance gate**, which was already built and runs in the product before every generation. It sees a small sample of frames and answers with one of six categories — usable, not a screen recording, not a web UI, out of domain, no interactions, unreadable — and rejects on all but the first.

The eval harness runs the same gate the product does, in the same position. For 4c, the deciding signal is the confidence check: a plan flagged `REVIEW` or `CAUTION` counts as an honest degradation and passes, while `TRUST` on an unreadable recording is the over-confidence we're hunting.

---

### One important caveat about the AI grader

Before we trust the grader's scores for any sign-off, a person will hand-score a small sample (~25 cases) and we'll check that the grader agrees with the human. If it agrees well, we trust it. If it doesn't, we fix the grader before relying on its numbers. Until then, the scores are a **useful guide for improving the agent**, not a final verdict.

That sample must include unhappy cases, not just happy ones. Judging "was this refusal correct?" is a different skill from judging "are these steps right?", and we shouldn't assume the grader is good at both because it's good at one.

---

## 6. What the report shows

### In the report today

- **Per case: a PASS/FAIL plus the reason it failed**, naming which of the two conditions broke.
- **The grader's overall verdict** as the headline quality score, with the per-field scores (`correctness`, `hallucination`, `order`, `label_quality`, `placeholder`) beside it as the drill-down.
- **The grader's written reasoning** on each case. This is the part reviewers will actually use.
- **A run-level tally for each condition separately** — "12 failed the assertion layer" is actionable; "12 failed" isn't.
- **The thresholds the run used**, printed in the report itself, so a number can never quietly change between runs.
- **How many cases exercised the placeholder gate**, and how many were graded — the denominators that stop a clean-looking percentage from being misleading.
- **Non-applicable checks as "N/A", never 0 or 10** — e.g. `paginationScore: null` for provisioning, `placeholderScore: null` for LITERAL mode.
- **Recall, precision and order as a diagnostics block** — non-gating, but useful for spotting regressions between prompt changes.
- **Label accuracy in the same diagnostics block** — decides nothing, but a very low value is a useful hint about where to look.
- For each case, the **specific missing and invented steps, quoted** rather than counted.
- **Cost per case** — tokens in/out and dollar cost.
- Averages grouped by **UI variation**, so we can see which kinds of interface it reads well and which trip it up.
- **Happy vs unhappy pass rates, reported separately**, with a breakdown by family (incorrect / invalid / unworkable).
- **The over-confidence count** — unusable videos that still produced a confident plan. This is the headline safety number for the unhappy half.
- **The count of usable videos wrongly rejected** by the relevance gate.

### Still to build

- **Grouping by task type** (aggregation vs provisioning). Task type is shown per case today but not aggregated.
- **The grader's reasoning surfaced in the console summary**, not only in the JSON.
- **A direct check on the 4a family** for whether a detour step was included in the output (currently inferred indirectly from precision).

## 7. What "passing" looks like (proposed — to confirm)

- **Safety (Layer 1):** all per-case assertions pass, including placeholder tokens and well-formed output. Non-negotiable.
- **Quality (happy path, Layer 2):** the grader's `overall` ≥ 7 / 10. Deliberately not a word-overlap threshold — semantic quality is a judgment call, not an arithmetic one.
- **No unexplained grader/assertion disagreement:** where the judge passes a case that a Layer 1 assertion fails (or vice versa), that case gets looked at rather than averaged away.
- **Invalid videos (4b):** zero tolerance — no invalid video may yield a plan.
- **Incorrect and unworkable (4a, 4c):** "behaved as expected" above an agreed bar, scored per family.

## 8. What we're deliberately keeping simple for now

To get a useful signal quickly, this first version does **not** yet include:

- Actually running the plans against real systems to confirm they work end-to-end.
- Exhaustive coverage of every rare edge case and very long (15+ step) tasks.
- Multiple accepted "correct" answers per task and formal agreement measurement between reviewers.
- **Grading the *wording* of a refusal.** For now we check that the agent refused and gave a reason; we don't score how well-written that reason is.
- **Deliberately hostile or tampered videos.**
- **A separate model for grading.** The grader currently shares the generator's model. Using an independent model is a real improvement worth making after the human-agreement check.

## 9. What we need agreement on

1. **Human review stays in the loop for Stage 1?** (This is the assumption behind the whole plan.)
2. **The unhappy-path bars** in §7 — in particular whether "no invalid video may produce a plan" is accepted as non-negotiable.
3. **The 50/50 happy-to-unhappy split** and the proposed sizing in §4.
4. **That "a correct refusal is a pass"** is the right way to score the unhappy half.
5. **The grader as headline, assertions as guard** — accepting that we keep both rather than simplifying to one score.
6. **The grader threshold: overall ≥ 7 / 10.** This is the one remaining number that gates pass/fail. (The word-overlap thresholds — recall ≥ 0.70, precision ≥ 0.70, order ≥ 0.80 — are no longer gates; they are printed as diagnostics.)
7. **Green light to hand-score the ~25-case sample** that lets us trust the AI grader, with unhappy cases included in that sample.
8. **Green light to build the test set from the practice apps** — record the videos and write the known-good steps for the UI variations in §3, plus the unhappy recordings in §4.
