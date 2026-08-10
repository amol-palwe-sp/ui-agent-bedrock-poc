# Stage 1 Eval — Report Walkthrough

A reading of `evals/reports/stage1-judge.json` for people who did not run it.

Stage 1 asks one question: **given a screen recording of someone doing a task in a web UI,
does the agent write down the right steps?** Nothing is executed here. We compare the
generated navigation goal against a human-written ground truth and let an LLM judge score
the match.

Just as important, the suite asks a second question that is easy to forget: **when the
recording is useless, does the agent say so instead of making something up?** That half is
covered in [The unhappy path](#the-unhappy-path-what-we-test-and-how).

---

## Headline

| | |
|---|---|
| Tests run | 36 |
| Passed | 36 |
| Failed | 0 |
| Recordings judged on quality | 25 |
| Recordings checked for refusal | 3 |
| Schema unit tests (no LLM) | 8 |
| Average overall score | **0.94** |
| Lowest overall score | 0.78 (`eval_014`) |
| Judge tokens | 1,146,606 |
| Judge wall time | 697s total, 28s average per case |

Everything passed, and the weakest case still cleared the bar. The value of this run is not
the pass rate — it is *where* points were lost, which is what the rest of this document
covers.

---

## How a case is scored

The judge returns three independent dimensions, each 0.00–1.00:

| Dimension | Weight | Question it answers |
|---|---|---|
| **Correctness** | 0.40 | Are all the critical ground-truth actions present, with the right values? |
| **Order** | 0.30 | Are those actions in a sequence that would actually work? |
| **Hallucination** | 0.30 | Did the agent invent steps, pages or controls that never happened? |

`overall = 0.40 × correctness + 0.30 × order + 0.30 × hallucination`

A case passes only when **overall ≥ 0.70 and correctness ≥ 0.70**. Correctness gates on its
own so a plan cannot pass on tidy sequencing while missing the actions that matter.

---

## The four expectation families

The dataset labels every recording with what *correct behaviour* looks like for it. This is
the single most useful idea for reading the report, because "passed" means something
different in each family.

| Family | The recording shows | Passing means | Judged how | Cases |
|---|---|---|---|---|
| **HAPPY** | A clean, legible workflow | The plan reproduces ground truth | LLM judge | 23 |
| **INCORRECT** | A real workflow where the user fumbles and corrects themselves | The plan captures the *corrected* intent and ignores the fumble | LLM judge | 2 |
| **INVALID** | Not a web UI at all | The agent refuses. Any plan is fabrication | Deterministic | 2 |
| **UNWORKABLE** | The right kind of recording, too degraded to read | The agent flags it. Attempting is fine; *confidence* is the failure | Deterministic | 1 |

`HAPPY` and `INCORRECT` have ground truth, so they go to the judge. `INVALID` and
`UNWORKABLE` deliberately have none — there is no correct plan to compare against — so they
are decided from the triage gate with no judge call and no LLM cost.

---

## The unhappy path: what we test, and how

This is the half of the suite that exists because of production risk rather than accuracy.
A slightly wrong plan gets caught in review. A confident, well-formed plan built from a
recording that never showed the workflow does not — it gets executed. These cases exist to
make that failure mode visible.

### `INCORRECT` — the user makes a mistake on camera

Real recordings are not clean. People type the wrong thing and fix it, or wander off to
another tab mid-task. The agent must transcribe what the user *meant*, not everything their
hands did.

| Case | The recording | Result |
|---|---|---|
| `u4a_typo_corrected` | Types a wrong username, clears it, retypes the correct one | **1.00** — plan contains one clean email entry, no trace of the typo |
| `u4a_unrelated_tab` | Switches to an unrelated tab mid-task, then comes back | **1.00** — plan contains no steps from the detour |

Both scored perfectly across all three dimensions. The agent produced the same three-step
sign-in for each, with no wrong-value step and no tab-switching step. This is a genuinely
good result — the noise-filtering worked exactly as intended.

### `INVALID` — not a web UI, so refuse outright

Zero tolerance. If the video is not a browser session, any plan at all is a fabrication.

| Case | The recording | Triage verdict | Plan produced | Result |
|---|---|---|---|---|
| `u4b_ide_recording` | Screen recording of an IDE | `REJECT`, confidence 95 | none | **PASS** |
| `u4b_terminal_recording` | Labelled as a terminal session | `REJECT`, confidence 99 | none | **PASS** |

The IDE case is a clean win — triage identified it as `NOT_A_WEB_UI` and named the file tree
and terminal panel as evidence.

**One thing to flag on `u4b_terminal_recording`.** It rejected, so it passed, but for a
different reason than the case intends. Triage returned category `NOT_A_SCREEN_RECORDING`,
describing *"camera footage of a physical office desk with papers, a glass of water, a plant,
and a person using a laptop"* — not a terminal at all. The dataset expected
`NOT_A_WEB_UI`, so `categoryMatched` is `false`. The video file behind this case looks
mismatched with its name and description. The refusal behaviour is still correct, but this
case is not currently testing what it claims to test.

### `UNWORKABLE` — right kind of video, unreadable

The distinction from `INVALID` matters. This *is* a web workflow, so attempting it is
reasonable. What we are guarding against is the agent reading illegible text and asserting a
confident plan anyway.

| Case | The recording | Triage verdict | Plan produced | Result |
|---|---|---|---|---|
| `u4c_tiny_low_contrast` | A real web form, but tiny and low contrast | `UNCERTAIN`, confidence 72 | 4 steps, flagged `REVIEW` | **PASS** |

This is the most interesting case in the suite, because it passed via the *hesitation* path
rather than the rejection path. Triage said the frames show a form being filled and
submitted, but that *"the form fields, labels, and button text are too small and low-contrast
to reliably read and transcribe into replayable steps."*

The agent then went ahead and produced a plan — but look at what it produced:

> click the first input field in the Hard-to-read form, then click the dropdown field, then
> click the first option in the dropdown, then click the submit button

Every step is positional (`the first input field`, `the first option`) rather than named. The
agent could not read the labels and did not invent any. It also attached a `REVIEW`
confidence recommendation. That combination — vague-but-honest steps plus an explicit flag —
is exactly the intended behaviour, and it is why the test passes on `flagged` rather than
`rejected`.

Worth noting the same `categoryMatched: false` caveat applies here: the dataset expected the
`UNREADABLE` category, and triage returned `NONE` with an `UNCERTAIN` verdict. The safety
outcome is right; the category label did not line up.

---

## The happy path: per-case results

Sorted worst to best.

| Case | UI variety | What it probes | Corr | Order | Halluc | Overall | What the judge docked |
|---|---|---|---|---|---|---|---|
| `eval_014` | real:aggregation-securelink | Log in, reach SecureLink user management | 0.75 | 0.90 | 0.70 | **0.78** | `{Username}` for `{UserId}`; three invented wait/navigate steps |
| `eval_011` | real:provisioning-securelink | Create user in SecureLink | 0.72 | 0.95 | 0.75 | **0.80** | Reused `{UserId}` for the *new* user's id — conflates two credentials |
| `eval_009` | real:provisioning-bamboohr | Create employee in BambooHR | 0.80 | 1.00 | 0.70 | **0.83** | Invented an `{EffectiveDate}` step; `{EmailAddress}` for `{Email}` |
| `s1_div_role` | discovery:div-role-widgets | `div[role=button]` and contenteditable posing as native inputs | 0.85 | 1.00 | 0.70 | **0.85** | Two focus-clicks before typing |
| `s1_text_inputs` | field:text | Plain text and email inputs | 0.90 | 1.00 | 0.70 | **0.87** | Three focus-clicks before typing |
| `eval_001` | real:provisioning-coupa | Create user in Coupa UAT | 0.85 | 1.00 | 0.80 | **0.88** | Checkbox steps used literal labels instead of ground-truth placeholders |
| `eval_010` | real:provisioning-webex | Add user in Webex Control Hub | 0.80 | 1.00 | 0.90 | **0.89** | `{EmailAddress}` / `{EmailAddress2}` for `{Email}` / `{UserEmail}` |
| `s1_textarea_richtext` | field:textarea-richtext | Textarea plus contenteditable rich text | 0.90 | 1.00 | 0.80 | **0.90** | Two focus-clicks; "field" vs "area" wording |
| `eval_007` | real:aggregation-coupa | Log in, reach Coupa user list | 0.85 | 1.00 | 0.90 | **0.91** | Omitted the explicit wait-for-redirect step |
| `eval_008` | real:aggregation-google | Log in, reach Google Admin user list | 0.80 | 1.00 | 1.00 | **0.92** | Stopped after sign-in; never reached the Users page |
| `s1_aria_combobox` | field:aria-combobox | Custom ARIA listbox combobox | 0.90 | 1.00 | 0.90 | **0.93** | Said "click Finance option" without naming the Team dropdown |
| `s1_typeahead` | field:typeahead | Type, then pick from filtered options | 0.90 | 1.00 | 0.90 | **0.93** | One focus-click before typing |
| `eval_013` | real:aggregation-webex | Log in, reach Webex user list | 0.95 | 1.00 | 0.85 | **0.93** | One invented page-load wait |
| `eval_004` | real:provisioning-google | Reactivate a user in Google Admin | 0.95 | 1.00 | 0.90 | **0.95** | `{FilterValue}` for `{Filter}` |
| `eval_003` | real:provisioning-google | Add a new user in Google Admin | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `eval_012` | real:aggregation-bamboohr | Log in, reach BambooHR People list | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_cascading_dropdowns` | field:cascading-dropdowns | Dependent dropdowns (country → state) | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_checkbox_group` | field:checkbox-group | Several checkboxes in one group | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_dup_labels` | tricky:dup-labels | Two identical "Next" buttons — pick the right one | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_login` | discovery:semantic-login | Baseline semantic login; secrets stay as placeholders | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_native_select` | field:native-select | Native `<select>` dropdown | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_radio_group` | field:radio-group | Radio-button group | 1.00 | 1.00 | 1.00 | **1.00** | — |
| `s1_toggle_switch` | field:toggle | `role=switch` toggle | 1.00 | 1.00 | 1.00 | **1.00** | — |

Plus the two `INCORRECT` cases at **1.00**, covered above.

---

## Synthetic harness vs real applications

The dataset deliberately mixes two populations, and they behave differently.

| Group | Cases | Correctness | Order | Hallucination | Overall |
|---|---|---|---|---|---|
| Synthetic harness (`s1_*`) | 12 | 0.95 | 1.00 | 0.92 | **0.96** |
| Real SaaS consoles (`eval_*`) | 11 | 0.86 | 0.99 | 0.86 | **0.90** |
| Noisy-user cases (`u4a_*`) | 2 | 1.00 | 1.00 | 1.00 | **1.00** |

The harness pages are small fixtures built to isolate one widget type each, so a miss there
points at a specific control the model cannot read. Seven of twelve came back perfect. The
real recordings are long multi-page flows in Coupa, Google Admin, BambooHR, Webex and
SecureLink, where there is far more to get wrong — and the 0.09 correctness gap is where the
product risk lives. Every case below 0.90 overall is a real application.

---

## Four patterns worth a decision

**1. Placeholder collisions are the most serious defect found.**
Twice, the agent reused one placeholder for two genuinely different values. On `eval_011` it
wrote `{UserId}` for both the login credential and the *new user's* id; on `eval_010` it used
`{EmailAddress}` for both the operator's login and the new user's address. These are not
cosmetic. At replay time they would write the wrong value into a real system. Both are real
SecureLink/Webex provisioning flows — the highest-stakes cases we have. This is the finding I
would act on first.

**2. Placeholder *naming* drift is mostly noise, and gets double-counted.**
Separately from the above, several cases lost points purely for calling something
`{EmailAddress}` where ground truth said `{Email}` (`eval_009`, `eval_014`, `eval_004`). On
`eval_001` the same checkbox actions appear in *both* the missing list and the hallucinated
list, because the agent wrote `AI Classifier checkbox` where ground truth wrote
`{AIClassifierCheckbox}` — one mismatch, two penalties. Worth deciding whether a
name-only difference should cost anything.

**3. Focus-clicks are still costing us points, and probably shouldn't.**
Four harness cases were penalised for steps like `click First name field` before typing into
it. The judge calls these "benign preparatory clicks" in its own reasoning and then docks
hallucination to 0.70–0.90 anyway. Either the rubric should exempt a click on a field the
very next step types into, or we accept it as a deliberate style preference — but right now
the prompt says one thing and the scoring does another.

**4. Real flows drift at the boundaries.**
`eval_007`, `eval_008` and `eval_010` each lost points at the *end* — a missing
wait-for-redirect, a missing navigation to the Users page, a missing Close button. Meanwhile
`eval_013` and `eval_014` were penalised for the opposite, *inventing* wait steps that were
not in ground truth. The agent is inconsistent about whether waits and page-loads belong in
a plan, and ground truth is not consistent either. Settling that convention would recover
points on both sides.

---

## Caveats when quoting this

**36 tests is not 36 recordings.** Twenty-five recordings were judged on quality, three were
refusal checks that pass by declining, and eight are schema unit tests that make no LLM call
at all. Reading "36/36" as 36 successfully transcribed workflows overstates what was
measured.

**Order barely discriminates.** It averages 0.994 and only two cases scored below 1.00
(`eval_011` at 0.95, `eval_014` at 0.90) while carrying 30% of the composite. It is doing
*some* work now, which is an improvement, but it still inflates every composite relative to
correctness and hallucination.

**Two unhappy cases pass on the right outcome via the wrong category.**
`u4b_terminal_recording` and `u4c_tiny_low_contrast` both have `categoryMatched: false` —
triage refused or flagged them correctly but classified them differently than the dataset
expected. Worth fixing the dataset (or the video, in the terminal case) so these cases test
what their names claim.

**Passing is a floor, not a target.** The gate is 0.70. An average of 0.94 says our failures
are shallow ones; the per-case notes above are the real output of this run.
