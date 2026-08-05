# Stage 2 Evaluation Plan — Steps → Navigation

**Status:** Proposed — for review
**Author:** amol.palwe
**Date:** 2026-07-28

---

## 1. What this is about

Stage 1 turned a video into a **list of steps**. Stage 2 is the part that **runs those steps in a real browser to navigate the interface** — logging in, clicking, typing, and moving through pages until it reaches the point where the actual work would begin. The AI decides each action as it goes.

This round checks **navigation only** — did the agent find its way to the right place — not the final data entry or full data pull. Concretely, "success" means:

- **Aggregation:** it logs in, **reaches the accounts/users list page**, **clicks one "next page"**, and **captures that page** — proving the path and the paging mechanism work. (We do *not* pull all pages/records in this round.)
- **Provisioning:** it logs in and **reaches the "create" form**, ready for input. (We do *not* submit the form or actually create a record.)

Why this scope: the risky, variable part is *finding the way through the UI*. Isolating that — and stopping before we submit forms or scrape everything — gives a clean navigation signal and avoids creating real records as a side effect. Full extraction and record creation are a later round.

## 2. Why we're evaluating it (the decision it supports)

Navigation is where the agent is most likely to go wrong: menus, logins, multi-page flows, and paging controls all vary a lot. We're asking:

> When we hand the agent a correct set of steps, does it reliably reach the right destination — the list page (and page through it once) for aggregation, or the create form for provisioning — without ever exposing a real credential?

The mistakes we care about most:

1. **Wrong destination** — it stops somewhere other than the expected page/form, or the "next page" click didn't actually advance.
2. **Flakiness** — it gets there once and fails the next time on the same task.
3. **A credential exposed at run time** — a real password reaching the AI model or landing in logs.
4. **Breaking when the screen changes slightly** — a renamed button or reordered fields stops it working.

## 3. Keep Stage 2 separate from Stage 1

To measure navigation fairly, we feed it **known-good ("golden") steps**, not steps produced by Stage 1. That way a failure here means a *navigation* problem, not a video-reading problem. (Testing the two stitched together end-to-end is a later, separate exercise.)

## 4. What we'll evaluate against — the happy path

**How the test set is balanced.** As in Stage 1, a benchmark of only clean runs measures the agent on its best day. The set is split roughly in half:

| | Share | What it proves |
|---|---|---|
| **Happy path** (§4) | ~50% | Given good steps and a well-behaved page, it reaches the right destination |
| **Unhappy path** (§5) | **at least 50%** | Given bad steps or a hostile page, it fails safely instead of thrashing |

This section covers the happy path; §5 covers the unhappy half.

We use the same **built-in practice apps (the "test harness")** as Stage 1, because they're ideal for navigation testing:

- **Repeatable** — the same seeded data and pages every run, so we can check the checkpoints exactly.
- **Safe** — fake logins, so we can prove "no credential leak at run time" without any real secret.
- **Checkable checkpoints** — we can confirm the list page was reached and that one pagination actually returned the next page of rows; and that the create form was reached and is ready for input.
- **Covers UI variety and known-hard cases** — different navigation patterns, paging styles, and deliberate "harder" scenarios.

A few **real-app runs** are kept for realism, handled carefully — and because this round stops at navigation (no form submit, no full scrape), the risk to live systems is low.

The harness currently provides **53 happy-path scenarios** across discovery, field types, navigation patterns and paging styles.

## 5. Unhappy paths — at least half the benchmark

**The failure we're actually worried about.** In Stage 1 the danger was a confident wrong plan. Here it's different, and more expensive: when the agent can't reach its destination, the bad outcome is that it **keeps trying** — re-clicking, re-observing, re-planning — until it hits its step limit. Every one of those attempts is a paid AI call. A dead end that costs a few pennies to abandon can cost many times that to thrash against.

So the rule for this half mirrors Stage 1: **stopping cleanly, with a reason, is a pass.** Reaching the checkpoint is not always the goal — on a page where the destination genuinely cannot be reached, a fast, clear stop is the correct behaviour, and looping is the failure.

Three families, matching Stage 1's structure.

### 5a. Incorrect steps — a valid plan for the *wrong or flawed* path

The steps are well-formed but don't match what's on screen. This is the realistic case where Stage 1 was imperfect, or the target application changed after the connector was set up.

- A step points at an element that **no longer exists** (renamed or removed button).
- Steps in the **wrong order** — form filled before the page it lives on is opened.
- The **login step is missing**, so the flow starts behind a sign-in wall.
- **Extra steps** that don't apply to this screen.
- A step matches **two elements** — the duplicate-label case.
- The plan is for a **different application** entirely.
- A step targets something present but **not yet visible** (further down, or behind a tab).
- A **stale selector** after fields have been reordered or restyled.

*Expected behaviour:* recover where recovery is reasonable (find the renamed button, scroll, open the tab), and otherwise stop quickly and say which step failed and why. Naming the failing step is what makes this fixable.

### 5b. Invalid steps — not a usable plan at all

Nothing here can be run. The agent should reject it **before opening a browser** — spending money on AI calls or launching a session for an unusable plan is itself the failure.

- Empty or malformed plan.
- Unparseable / wrong shape.
- A required placeholder (e.g. `{Password}`) with **no value supplied**.
- A placeholder referenced that the plan never defines.
- An action the system doesn't support.
- A target URL that is missing or malformed.

*Expected behaviour:* rejected up front with a reason, **no browser launched, no AI spend**.

### 5c. Unworkable conditions — right steps, hostile page

The steps are correct, but the page fights back. **This family is already built** — it's tiers E–H of the harness, 28 scenarios:

| Tier | Count | What it throws at the agent |
|---|---|---|
| **E — Obscuring & blocking** | 6 | Cookie banner, blocking modal, sticky header over the target, auto-dismissing toast, layout shift mid-click, full-page interstitial |
| **F — Viewport & rendering** | 9 | Responsive reflow, zoom at 125/150/200%, dark mode, runtime theme switch, low-contrast 9px text, right-to-left Arabic, very long page |
| **G — Failure & recovery** | 7 | Never-loading spinner, server error, rate limit, session expiring mid-flow, validation errors, disabled-until-valid submit, type-to-confirm |
| **H — Scale, structure & motion** | 6 | 200 controls on one page, iframe inside an iframe, continuously auto-scrolling page, three-hop redirect chain, multi-tab round trip, flow resumed at step 3 |

Three of these — the never-loading spinner, the server error and the rate limit — are **genuine dead ends** where a clean stop is the only correct answer.

**We have already run this and it found a real problem.** On a live trial of tier G, the four recoverable scenarios all passed, but on all three dead ends the agent **did not stop** — it kept working until the harness killed it. That is exactly the expensive behaviour this family exists to catch, and it's the strongest argument for scoring "stopped cleanly" as a pass rather than a failure.

One measurement caveat we'll carry into the real run: a scenario cut off by our own time limit looks identical to one where the agent was looping. We'll keep the per-scenario time limit generous so we don't mistake a slow success for a loop.

### Proposed sizing

| Family | Scenarios | Status |
|---|---|---|
| Happy path (§4) | 53 | Built |
| 5a — Incorrect steps | 18 | To author — reuses existing pages with damaged step lists |
| 5b — Invalid steps | 12 | To author — no page needed, cheap |
| 5c — Unworkable conditions | 28 | **Built** (tiers E–H) |
| **Total** | **111** | **Unhappy = 58 / 111 ≈ 52%** |

The bulk of the work is already done. 5a and 5b need no new applications — 5a reuses pages we have with deliberately broken step lists, and 5b needs no page at all.

## 6. How we'll score each run

### Layer 1 — Safety checks (automatic, pass/fail)

- **No credential exposed at run time:** the real password is never sent to the AI model and never written to logs — only the placeholder is. If a real secret appears anywhere, the case fails.

### Layer 2 — Did navigation succeed

Judged by the **actual page state**, not the agent's own "done":

- **Aggregation:** the accounts/users **list page is reached**, and **one "next page" click returns the next page of rows**, which we capture. We confirm the destination (expected page/URL and the list is present) and that the captured second page really is different, valid rows.
- **Provisioning:** the **create form is reached** and ready for input (the expected form/fields are on screen).
- **Reliability** — run the same task several times; it should reach the checkpoint consistently, not sometimes.
- **Resilience to UI change** — run against slightly changed versions of the screen (reordered fields, renamed styling, different language, dark theme) and see whether it still reaches the checkpoint.

### Layer 3 — Scoring the unhappy cases

"Reached the checkpoint" is the wrong test when the checkpoint is unreachable by design. So each unhappy scenario carries an **expected outcome**, and we score against that:

| Family | Expected outcome | Counts as a failure |
|---|---|---|
| **5a — Incorrect steps** | Recover if reasonable; otherwise stop and name the failing step | Looping, or reporting success without reaching the destination |
| **5b — Invalid steps** | Rejected before launching a browser | Any AI spend or browser session on an unusable plan |
| **5c — Unworkable** | Reach the checkpoint where possible; on the three dead ends, **stop cleanly with a reason** | Running to the step limit — the expensive failure |

The headline number for this half is **"behaved as expected"**, not raw success count. Our eval runner already scores this way, so no new tooling is needed.

We also record, for every scenario that failed to reach its destination, **how much it cost to find that out**. Two agents that both fail are not equally good: one that stops in three steps is far better than one that stops in thirty.

### Layer 4 — Cost & speed

- Time taken and how much AI usage each run needs.
- **Cost of failure specifically** — steps taken and AI spend on runs that didn't reach the checkpoint.

## 7. What the report will show

- One row per scenario: safety result, navigation success (checkpoint reached), how many of the repeat runs passed, whether it still reached the checkpoint across the changed-screen variants, steps taken, time, and cost.
- Averages grouped by **UI variation** and by task type.
- **Happy vs unhappy split, reported separately**, and within the unhappy half a breakdown by family — so a strong happy-path number can't mask thrashing on dead ends.
- **The thrash count** — dead-end scenarios that ran to the step limit instead of stopping, with the AI spend each one burned. This is the headline number for the unhappy half.
- A clear list of **known gaps** — scenarios we can't navigate yet — with what would be needed to close them.

## 8. What "passing" looks like (proposed — to confirm)

- **Safety:** zero credential exposure at run time. Non-negotiable.
- **Navigation success:** reaches the checkpoint (list page + one pagination for aggregation; create form for provisioning) on the supported scenarios, at or above an agreed bar.
- **Reliability:** the same task reaches the checkpoint consistently across repeat runs.
- **Resilience:** still reaches the checkpoint across the common everyday screen changes.
- **Invalid steps (5b):** we propose **zero tolerance** — no unusable plan may reach the browser or spend AI budget.
- **Dead ends (5c):** stops cleanly rather than running to the step limit, within an agreed step budget. On today's evidence this is the bar we are **furthest from meeting**, so it's worth setting deliberately rather than optimistically.
- **Known gaps:** documented, with a risk note — not silently failing.

(Exact numbers to be set with the team before we run, so results aren't bent to fit.)

## 9. What we're deliberately keeping simple for now

- **Navigation only.** We stop at the checkpoint — we do **not** submit the create form / actually create a record, and we do **not** pull all pages/records for aggregation. Full data entry and full extraction are a later round.
- **End-to-end** (video → steps → navigation together) is a separate, later review; here we isolate navigation with golden steps (§3).
- We focus on the main UI variations and the known-hard cases we already have; the long tail of rare cases comes later.
- **We don't grade the wording** of a stop reason — only that the agent stopped and gave one.
- **No adversarial pages.** The unhappy set covers awkward and broken interfaces, not ones built to defeat automation (bot detection, CAPTCHAs).

## 10. What we need agreement on

1. **The pass/fail numbers** in §8 (success rate, how many repeat runs, which screen-change variants must survive).
2. **The 50/50 happy-to-unhappy split** and the sizing in §5 — noting that over half of it is already built.
3. **That "a clean stop is a pass"** on genuine dead ends. This is the core judgement call here, and today's evidence says we don't yet meet it.
4. **A step budget for giving up** — how many attempts is a reasonable amount to spend discovering something can't be done.
5. **Which scenarios are "in scope" vs accepted "known gaps"** for this round.
6. **Green light to verify navigation checkpoints** — confirming the list page was reached and one pagination actually advanced, or that the create form is open — via page/URL + on-screen checks, rather than trusting the agent's own "done."
7. **The navigation-only scope** — agreement that this round stops before form submission and full extraction.
