# Stage 1 Evaluation Plan — Video → Steps

**Status:** Proposed — for review. The scoring changes in §5 are built; the benchmark content in §4 is not yet.
**Author:** amol.palwe
**Date:** 2026-08-04

> **Where this stands.** The scoring and reporting design in §5 is implemented — three independent pass conditions, "N/A" for checks that didn't apply, Label Accuracy demoted to a diagnostic. What is still open is **agreement on the numbers** (§9.6) and **the unhappy half of the benchmark, which is currently zero cases** (§4). So the machinery is ready and the test set is the remaining work. Reports generated before 2026-08-04 use the retired blended rule and won't match this document.

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

This section covers the happy path. §4 covers the unhappy half.

**The key idea: Stage 1's difficulty comes from the _UI variation_ in the video, not from which product it is.** Reading "click the Next button" is the same job whether the screen is Coupa or Google. What actually makes it easier or harder is how the interface looks and behaves — the kind of field, how pages change, how results are paged, the theme and language. So we organise the test set around **UI variations**, not around systems.

We'll draw from two sources:

1. **Our built-in practice apps (the "test harness").** This is a set of self-contained dummy applications we already have that deliberately covers a wide range of UI variations. It's ideal here because it is:
   - **Controlled** — we can record exactly the variation we want to test.
   - **Safe** — it uses fake logins, so we can exercise the "all variables became placeholders" check without any real credential.
   - **Comprehensive** — many field types, navigation patterns, and paging styles already live in one place.
2. **A few real-app videos** (Coupa, Google, BambooHR, etc.) kept for realism, so we're not only testing on practice apps.

For each case we still need a short screen-recording of the task plus its hand-written **"correct" steps** — that known-good answer is what we compare the agent's output to. Recording on the practice apps is quick and safe, so building this set is mostly authoring effort, not new tooling.

**The UI variations we'll cover** (only things actually **visible on screen** matter for reading a video):

- **Field types** — plain text, dropdowns, type-ahead search, checkboxes, radios, toggles, one-time codes, and masked/password fields (which must become a placeholder, never be read).
- **Navigation** — multi-page wizards, pop-up dialogs, tabs, and pages that load or spin before the next action.
- **Paging through results** — next button, numbered pages, load-more, infinite scroll, and page-size selectors.
- **Look & language** — dark theme and non-English labels.
- **Tricky cases** — two buttons with the same label ("Next"), and a cursor or tooltip covering the target.

**Where we are today:** 55 happy-path cases are already written, spread across those axes — 19 field types, 10 navigation patterns, 9 paging styles, 11 real-app recordings, plus discovery, theme, language and tricky cases. That is the "large variety of expected inputs" this stage needs, and it is essentially complete. The gap is the unhappy half, which is currently **zero cases**.

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

*Expected behaviour:* a plan for the intended task, with corrections and detours **excluded**, and a note flagging any gap it had to infer (especially the "starts mid-process" and "stops early" cases, where it must not invent the missing steps).

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
| Happy path (§3) | 55 | Already authored |
| 4a — Incorrect | 24 | Cheap to record on the practice apps |
| 4b — Invalid | 18 | Mostly synthetic; no recording needed for several |
| 4c — Unworkable | 18 | Re-record existing happy tasks under degraded capture |
| **Total** | **115** | **Unhappy = 60 / 115 ≈ 52%** |

The case counts are higher than the number of patterns listed above — roughly two cases per pattern, each on a different underlying task — so we're not judging a whole family on a single recording.

Two things make this affordable. Most of 4b needs no screen recording at all — a truncated file, a wrong file type or an audio file can simply be generated. And most of 4c is a **re-record of a task we already have**, under bad capture settings, so the known-good answer is already written.

## 5. How we'll score each generated plan

For every video, the agent produces a set of steps. We check it in three layers.

**The dividing principle: code checks facts, the AI grader makes judgements.** Some questions have a verifiable right answer — was this step present, were the steps in this order, did a variable come out as `{Email}`. Those are counted by code, because code is repeatable and can't be argued with. Other questions are inherently matters of degree — is "click Save button" the same as "click the Save button", would a reviewer accept this. Those go to the AI grader, because arithmetic on words answers them badly (see "When the two disagree" below).

**The AI grader gives the headline quality score; the counted facts guard it.** We deliberately keep both rather than collapsing to one, for three reasons:

- **The grader is not independent.** It runs on the *same model* that generated the steps. A model can repeat its own misreading and then agree with itself. The counted checks fail differently, which is the entire point of keeping them.
- **A grader score can't detect small regressions.** It's a 0–10 whole number and it saturates — in our example run it returned 10 on every dimension. "Did recall go from 0.82 to 0.89 after this prompt change?" is a question only the counted metrics can answer.
- **The grader is optional and can be absent.** It can be skipped with a flag, and its call can fail. When that happens the case is scored on the counted facts alone and the grader condition is recorded as "not run" — deliberately *not* as a zero, which would have failed every case in a run where the grader was switched off. Nothing load-bearing should depend on it alone.

They cost nothing to compute — no AI calls, just arithmetic — so keeping them buys independence and regression sensitivity for free.

### Layer 1 — Safety checks (automatic, pass/fail)

These are simple, rule-based checks. If either fails, the case fails — no exceptions.

- **All variables became placeholders:** every field that the task defines as a variable (e.g. Email, Password) must appear as a placeholder like `{Email}`/`{Password}` in the generated steps. We don't check what any value is — we just confirm each variable was emitted as a placeholder rather than dropped or written out literally. This is a fixed rule, not an opinion.
- **Well-formed:** the output is a proper, readable list of steps we can actually use.

### Layer 2 — The counted facts (computed by code, 0.000–1.000)

Repeatable by construction: the same input always gives the same number. These are the questions with a verifiable right answer.

| Metric | What it measures | How it's calculated | Worked example |
|---|---|---|---|
| **Step Recall** | Did it capture **all** the steps shown in the video? | Matched ground-truth steps ÷ total ground-truth steps | 4 of 5 steps found → **0.800** |
| **Step Precision** | Did it **invent** steps that weren't shown? | Matched generated steps ÷ total generated steps | Wrote 5 steps, 4 legitimate → **0.800** |
| **Step Order** | Were the steps in the **right sequence**? | Kendall Tau over matched positions, rescaled to 0–1 | Perfect → **1.000**; one adjacent swap in 5 steps → **0.900**; fully reversed → **0.000** |
| **Placeholder** | Were variables emitted as `{Token}` rather than dropped or written out? | Variables found as placeholders ÷ variables declared | `enter "{Email}"` → **1.000**; literal value → **0.000** |
| **Pagination** | Did it identify **how the list pages**? | type 50% + selector 30% + description 20% | Right type, right selector → **0.861** |

**Two steps "match" if their word overlap is ≥ 0.60.** Before comparing, quoted values are masked out, so `enter "Ada"` and `enter "Bob"` are treated as the same step structurally — whether the *value* was right is judged separately. This is deliberate: a long or legitimately different value shouldn't make a correct step look wrong.

**One normalisation worth knowing about.** Recordings almost always show a click into a field before typing in it, while our known-good answers treat that as a single "enter" step. Left alone, that extra click counts as an invented step and unfairly sinks precision. So a click immediately followed by typing into the *same* target is merged into one step. A click on a *different* target — "click Add", then "enter name" — is genuine navigation and is kept.

#### Two things we changed, and why

Both of these are now in the code. They are the reason older reports won't line up with this document.

**1. Label Accuracy is demoted to a diagnostic — it no longer contributes to any score.** It compares button and field names by word overlap, which turns out to measure the wrong thing: it penalises harmless wording differences (see the worked example below) and it can't recognise two genuinely equivalent labels. "Did it use the right label" is a judgement about meaning, so it belongs to the AI grader. The number is still reported, in a separate diagnostics block, because a very low value is a useful hint about *where* to look — but it decides nothing.

**2. We retired the single blended "Overall" score as the pass rule.** Six metrics were collapsed into one weighted number, with a pass at ≥ 0.70. Two problems: it hid *which* thing broke, and the weights silently changed by task type, so the same 0.90 meant different things on different rows. For the record, the weighting was:

| Metric | Aggregation | Provisioning |
|---|---|---|
| Step Recall | 30% | **50%** |
| Step Precision | 20% | 20% |
| Step Order | 15% | 15% |
| Label Accuracy | 15% | 15% |
| Placeholder | 10% | **not applicable** |
| Pagination | 10% | **not applicable** |

For provisioning there is no list to page through, and tokenising credentials happens later in the product, so under that scheme both were switched off and their combined 20% folded into Step Recall.

In place of the blend, a case now passes on **three independent conditions**, each reported on its own:

1. **The safety gate** (Layer 1) — every declared variable present as a placeholder. An absolute veto.
2. **The counted facts** — recall, precision and order each above their own threshold, rather than averaged into one figure where a weak one hides behind strong ones.
3. **The grader's overall verdict** — above its own bar (see the table below), with the validation caveat in "One important caveat about the AI grader" applying until a human sample confirms we can trust it.

This is stricter than the old blend, and more diagnosable: a failure now names the condition it failed. A concrete case from testing shows the difference — perfect recall and precision but collapsed step order scored **0.860** under the blend and *passed*; it now fails, and says why.

A composite score is still reported, but only as a **movement indicator between runs**. It decides nothing. Checks that don't apply are excluded from it and the remaining weights renormalise, so a skipped check neither helps nor hurts — previously they were scored 0, which quietly depressed every provisioning case.

The thresholds are implemented with these **provisional** defaults, chosen to sit at the same level of strictness as the old 0.70 bar so the change is a redistribution rather than a tightening. They are the numbers we need to agree on (§9.6); every report prints the values it ran with, so changing them is a one-line edit and never a silent one.

| Condition | Provisional bar | Why this number |
|---|---|---|
| Step Recall | ≥ 0.70 | Same level as the retired blend |
| Step Precision | ≥ 0.70 | Same level as the retired blend |
| Step Order | ≥ 0.80 | Higher, because a plan that replays out of order is unusable rather than merely imperfect. Matches the existing "misordered" flag |
| Grader overall | ≥ 7.0 / 10 | The 0–10 equivalent of 0.70 |

One behaviour worth calling out, because it's easy to get wrong in either direction: a case is **not** failed for a grader that was skipped or errored. That's an infrastructure gap, recorded separately as "not run", not a verdict on the output.

Note what condition 3 makes newly possible: a plan whose counted facts are flawless can now fail on the grader's judgement alone. That is the intended behaviour — a plan can be complete, correctly ordered and still not something a reviewer would accept — but it is also the sharpest edge in the new rule, and the reason the grader-validation exercise below matters before we use these numbers for sign-off.

### Layer 3 — The AI grader (the headline quality score, 0–10)

We ask an AI model to compare the generated steps to the known-good answer and score **correctness, hallucination, label quality, order, placeholder handling and overall** out of 10 — plus a **short written explanation**, and its own list of the missing and extra steps it found.

This is the layer that answers the question the whole exercise is really about: *would a reviewer accept this plan with only small edits?* It's the right tool for that because it understands meaning — it recognises that two differently-worded steps are the same action, which no word-counting formula can.

The written explanation is the single most useful thing the evaluation produces. A number tells you a case scored 0.875; the explanation tells you it was because of the word "the", which is what you actually need in order to act.

#### The grader's one structural weakness

**It runs on the same model that generated the steps.** Both use the same configured Bedrock model, so this is a model marking its own work. The risk isn't laziness, it's *correlated blindness*: if the model misreads a screen in a particular way, the grader may make the same misreading and confirm it. The counted facts in Layer 2 are the check on this, because a missing step is missing whatever any model believes.

We treat this as a known limitation to be managed, not a reason to discard the grader. Managing it means (a) always reporting the counted facts alongside the grader's verdict, and (b) the human-agreement exercise below. Using a *different* model as the grader would be a genuine improvement and is worth considering later.

#### When the two disagree — a real example from our last run

From an actual report (`eval-report_20260804_125342.json`). This case is why Label Accuracy was demoted.

The known-good answer said `click the Save button`. The agent wrote `click Save button` — the word "the" is the only difference.

- **Label Accuracy scored 0.750.** It compared `{click, the, save, button}` against `{click, save, button}`: three words shared out of four total. Averaged with the other step, which matched perfectly, the case's label score was **0.875**.
- **The grader gave label quality 10/10**, noting the difference "does not affect the meaning or the actions described."

**The grader is right and the metric is wrong.** Dropping "the" is not a labelling error, and no reviewer would call it one. A metric that penalises it is adding noise, which is why it no longer feeds any score. This is also the general shape of the division of labour: when a question is about *meaning*, the grader wins; when it's about *presence, count or order*, the counted facts win.

### Where "not applicable" was being written as a score — fixed

This was a reporting flaw rather than a scoring one, but it was the thing most likely to cause a wrong conclusion, and it affected **both** layers. Three examples, all from the same example report (`eval-report_20260804_125342.json`):

- **`paginationScore: 0` did not mean pagination failed.** Provisioning tasks have no pagination, and the metric returned 0 as a "not applicable" marker while being excluded from the weighting. In that run pagination showed 0 and the blended Overall was still 0.981 — we confirmed the arithmetic as `1.000×50% + 1.000×20% + 1.000×15% + 0.875×15%`, with pagination contributing nothing. Printed as a bare 0 beside real scores, it read as total failure.
- **`placeholderScore: 1` did not always mean the credential check passed.** When a case declares no variables — as in that example — it returns 1.0 meaning "nothing to verify," which is not the same as "credentials were correctly tokenised."
- **The grader had the identical problem.** It is explicitly instructed that in LITERAL mode "no placeholder required — give 10." So a grader placeholder score of 10 could also mean "not checked." Moving to a grader-only report would not have fixed this.

**The fix was presentational: show "N/A" where a check didn't apply, never a number.** It is now in place:

- Non-applicable checks serialise as `null` and print as `N/A`, on **both** layers — including the grader's placeholder score in LITERAL mode.
- Averages are taken over the **applicable cases only**. Previously the pagination average was dragged toward zero by every provisioning case that never had pagination to check, making a working system look broken.
- Each report states **how many cases actually exercised the credential gate**, and how many were graded. Without the first figure, a 100% safety-gate result looks reassuring even in a run where no case ever tested the gate — which is exactly the situation the example report was in.
- Every case carries a `verdict` block naming which of the three conditions failed, so a failure no longer has to be reverse-engineered from a blended number.

That same provisioning case now scores a clean **1.000** with `paginationScore: null` and `placeholderScore: null`, instead of 0.981 with a bare `0` sitting next to real scores.

### Scoring the unhappy cases — expected verdicts instead of expected steps

Everything above compares a plan to a known-good answer. That doesn't work when the right answer is "produce nothing" — there are no steps to score, so Layers 2 and 3 don't apply. For the unhappy half we replace them with an **expected verdict** per case (the safety gate in Layer 1 still applies):

| Family | Expected verdict | Counts as a failure |
|---|---|---|
| **4a — Incorrect** | A plan for the intended task, with mistakes and detours left out, gaps flagged | Faithfully reproducing the mistakes, or silently inventing the missing steps |
| **4b — Invalid** | Rejected, with a reason. No steps. | **Any** plan at all |
| **4c — Unworkable** | Low-confidence plan with unreadable parts flagged, or an honest refusal | A confident plan containing invented labels |

The single number we care about across the unhappy half is **"behaved as expected"** — not how many produced a plan. This deliberately mirrors how we already score the Stage 2 harness, where a clean stop on a dead end counts as a pass.

One caution on 4b: "no steps produced" is easy to hit for the wrong reason — a crash also produces no steps. So a rejection only passes if it is a **deliberate refusal with a stated reason**, not an error or an empty response.

### One important caveat about the AI grader

Before we trust the grader's scores for any sign-off, a person will hand-score a small sample (~25 cases) and we'll check that the grader agrees with the human. If it agrees well, we trust it. If it doesn't, we fix the grader before relying on its numbers. Until then, the scores are a **useful guide for improving the agent**, not a final verdict.

That sample must include unhappy cases, not just happy ones. Judging "was this refusal correct?" is a different skill from judging "are these steps right?", and we shouldn't assume the grader is good at both because it's good at one.

## 6. What the report shows

### In the report today

- **Per case: a PASS/FAIL plus the reason it failed**, naming which of the three conditions broke rather than leaving it to be inferred from a number.
- **The grader's overall verdict** as the headline quality score, with the counted facts (recall, precision, order, placeholder, pagination) beside it as the drill-down.
- **A run-level tally for each of the three conditions separately.** "12 failed" isn't actionable; "12 failed the safety gate" is.
- **The thresholds the run used**, printed in the report itself, so a number can never quietly change between runs.
- **How many cases exercised the credential gate**, and how many were graded — the denominators that stop a clean-looking percentage from being read as more than it is.
- **Not-applicable checks as "N/A", never 0 or 10**, on both the counted facts and the grader.
- **Label Accuracy in a separate diagnostics block**, so nobody reads it as a pass/fail signal.
- For each case, the **specific missing and invented steps, quoted** rather than counted.
- **The grader's written explanation** on each case. This is the part reviewers will actually use.
- **Cost per case** — tokens in/out and dollar cost. The example run cost $0.04 for one case, which is the basis for estimating a full 115-case run.
- Averages grouped by **UI variation**, so we can see which kinds of interface it reads well and which trip it up.
- A list of the most common problems (missing steps, low label similarity, wrong pagination, etc.).

### Still to build

These depend on the unhappy cases existing (§4), so they're blocked on the test set rather than on the reporting code:

- **Happy vs unhappy split, reported separately**, so a strong happy-path score can't mask weak refusal behaviour — with a breakdown by family (incorrect / invalid / unworkable) inside the unhappy half.
- **The over-confidence count** — unusable videos that still produced a plan. This is the headline safety number for the unhappy half, and the single figure most worth watching.
- **Grouping by task type** (aggregation vs provisioning). Task type is shown per case today but not aggregated, so we can't yet say "provisioning reads worse than aggregation" from the report alone.
- **The grader's explanation surfaced in the console summary**, not only in the JSON. Today reading the explanations means opening the JSON file.

## 7. What "passing" looks like (proposed — to confirm)

- **Safety:** every variable appears as a placeholder, everything well-formed. Non-negotiable.
- **Quality (happy path):** the three conditions in §5 each met on their own — the safety gate, each counted fact above its own threshold, and the grader's overall verdict above its bar. Deliberately *not* a single averaged number, so a weak dimension can't hide behind strong ones. This rule is implemented and running; the four numbers it uses (§5) are provisional and are what we need signed off.
- **No unexplained grader/metric disagreement:** where the grader passes a case that the counted facts fail (or the reverse), that case gets looked at rather than averaged away. Persistent disagreement in one direction is evidence the grader needs fixing, not evidence the agent is fine.
- **Invalid videos (4b):** we propose **zero tolerance** — no invalid video may yield a plan. This is the one unhappy bar we'd suggest making non-negotiable, because it's the case where a reviewer has the least chance of catching the error.
- **Incorrect and unworkable (4a, 4c):** "behaved as expected" above an agreed bar, scored per family so a weak family can't hide inside an average.

## 8. What we're deliberately keeping simple for now

To get a useful signal quickly, this first version does **not** yet include:

- Actually running the plans against real systems to confirm they work end-to-end.
- Exhaustive coverage of every rare edge case and very long (15+ step) tasks — we cover the main UI variations now (§3) and expand the long tail later.
- Multiple accepted "correct" answers per task and formal agreement measurement between reviewers.
- **Grading the *wording* of a refusal.** For now we check that the agent refused and gave a reason; we don't score how well-written that reason is.
- **Deliberately hostile or tampered videos.** The unhappy set covers accidents and poor quality, not someone actively trying to trick the agent.
- **A separate model for grading.** The grader currently shares the generator's model (§5). Using an independent model would be a real improvement, but it's a change we'd make after the human-agreement check tells us how much the shared model is actually costing us.

These make the evaluation stronger and more defensible, and we'll add them in a later phase if we want a formal, audit-ready certification. For now, the goal is a fast, honest quality check plus a hard safety gate.

## 9. What we need agreement on

1. **Human review stays in the loop for Stage 1?** (This is the assumption behind the whole plan.)
2. **The unhappy-path bars** in §7 — in particular whether "no invalid video may produce a plan" is accepted as non-negotiable. (The happy-path thresholds are item 6.)
3. **The 50/50 happy-to-unhappy split** and the proposed sizing in §4 — this roughly doubles the benchmark, from 55 cases to ~115.
4. **That "a correct refusal is a pass"** is the right way to score the unhappy half. This is the core judgement call in this document: it means a run where the agent produces fewer plans can score *higher*.
5. **The grader as headline, counted facts as guard** (§5) — accepting that we keep both rather than simplifying to one score, because the grader shares the generator's model and can't detect small regressions on its own.
6. **The provisional thresholds above** (recall ≥ 0.70, precision ≥ 0.70, order ≥ 0.80, grader ≥ 7.0/10). The blended "Overall ≥ 0.70" rule is now dropped in favour of the three separately-reported conditions, and Label Accuracy is now a diagnostic that feeds no score — both are implemented, so what's left is agreeing the numbers.
7. **Green light to hand-score the ~25-case sample** that lets us trust the AI grader, with unhappy cases included in that sample.
8. **Green light to build the test set from the practice apps** — record the videos and write the known-good steps for the UI variations in §3, plus the unhappy recordings in §4.
