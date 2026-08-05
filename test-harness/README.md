# UI-Agent Test Harness

A self-contained suite of **dummy applications** for proving the reliability of the
UI-Agent POC across script generation, navigation, and account aggregation.

Everything is deterministic (seeded data, fixed credentials, no external services)
so runs are repeatable and safe to demo.

## Run it

```bash
cd test-harness
npm install            # if your npm points at a private registry, add: --registry=https://registry.npmjs.org/
npm start
```

- Catalog:   http://localhost:4599/
- Alt origin: http://localhost:4600/  (only used by the cross-origin-iframe scenario)
- Login fixtures: `test.user@demo.local` / `Demo!Pass123`

Open the catalog — every scenario lists its target mechanism, a copy-paste
`./gradlew run` goal, and its expected verdict.

## Verdict legend

| Badge | Meaning |
|-------|---------|
| **Pass** | Native code path exists — should succeed reliably |
| **Ladder** | No dedicated action; works through the generic CLICK/TYPE/SCROLL ladder |
| **Roadmap gap** | No support today — expected-fail, documents a known limitation |
| **Expect TERMINATE** | A genuine dead end. Stopping cleanly is the *correct* result and scores as a pass; looping to MAX_STEPS is the real failure because it burns tokens without progress |

## Tiers

- **A — Discovery & structure** (8): element scraping across semantic HTML, `div[role]`
  widgets, ARIA comboboxes, duplicate labels, randomized IDs, same-origin iframe, and the
  two known blind spots: **cross-origin iframe** and **shadow DOM**.
- **A-Fields** (20): one page per input type — text, textarea/rich, native select,
  combobox, typeahead, cascading selects, checkbox group, radio, toggle, OTP, masked input,
  star rating, transfer list, plus the gaps: multi-select chips, date picker, date-range/time,
  slider, file upload, drag-reorder.
- **B — Navigation** (10): multi-page wizard, SPA router, modal, native alert/confirm,
  popup/new-tab, spinner/skeleton, branching wizard, tabs, accordion tree, and the
  hover-only mega-menu (gap).
- **C — Replay resilience** (1 app × 7 variants): record a script on `?variant=1`, then
  replay on `?variant=2..7`. Each variant mutates structure while preserving the logical
  fields, so you can measure survival per fingerprint level.
- **D — Aggregation / pagination** (9): numbered pages, next-button grid, load-more,
  infinite scroll, virtualized list, cursor pagination, URL-param pagination,
  page-size selector, merged/grouped headers. All backed by 247 seeded accounts.
- **E — Obscuring & blocking** (6): cookie-consent banner, blocking modal, sticky-header
  overlap, auto-dismiss toast, layout shift on click, full-page interstitial. Each puts
  something between the agent and its target.
- **F — Viewport & rendering** (9): responsive reflow, CSS zoom at 125/150/200 %,
  `prefers-color-scheme` dark, runtime theme toggle, low-contrast 9px text, Arabic RTL,
  and a 20-section long-scroll page.
- **G — Failure & recovery** (7): infinite spinner, HTTP 500, HTTP 429, mid-flow session
  timeout, validation errors requiring correction, disabled-until-valid submit, and
  type-to-confirm. The first three are dead ends where **TERMINATE is the correct outcome**.
- **H — Scale, structure & motion** (6): 200 interactive elements, nested (2-level) iframe,
  continuous auto-scroll, a 3-hop redirect chain, a multi-tab flow requiring a switch *back*
  to the first tab, and a mid-flow resume where the page is already at step 3.

### Zoom, theme and viewport

Zoom is emulated with a CSS `transform: scale()` on `/f-viewport/zoom.html?level=125|150|200`
rather than a real browser zoom, because `BrowserConfig` has no `deviceScaleFactor` setting.
Element geometry still scales, so the coordinate-click fallback is genuinely exercised.
For `dark-mode-prefers` and `responsive-reflow`, vary the Playwright colour scheme and
`browser.viewport.width` to change what the page renders.

## Tier C variants (mutation applied by the server)

| `?variant=` | Mutation | Stresses |
|-------------|----------|----------|
| 1 | none (baseline — record here) | — |
| 2 | reorder sibling fields | structural hash / ordinal |
| 3 | rename CSS classes | class-based selectors |
| 4 | prefix element `id`s | level-1 id fingerprint → fallback |
| 5 | wrap fields in extra `<div>`s | ancestor/structural fingerprint |
| 6 | translate labels to German (i18n) | text/aria label fingerprint |
| 7 | dark theme on `<body>` | visual robustness |

## Automated eval runner

`run-eval.js` runs every scenario against the POC agent and writes a self-contained HTML report to `../eval-reports/`.

**Prerequisite — a JDK must be on `PATH`.** `gradlew` needs Java 17; without it every
scenario dies in ~100 ms and the report fills with misleading `CRASH` rows. The runner now
preflights this and exits with the JDKs it found, but you can set it up front:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

```bash
# 1. Start the harness (separate terminal)
npm start

# 2. In the POC root terminal, run the eval
cd test-harness

node run-eval.js                  # all 81 scenarios (~2–3 h with Bedrock)
node run-eval.js --tier=A         # single tier
node run-eval.js --tier=E,G       # multiple tiers
node run-eval.js --skip-gaps      # skip roadmap-gap scenarios (faster)
node run-eval.js --dry-run        # print commands only, no execution
node run-eval.js --timeout=300    # seconds per scenario (default 300)
node run-eval.js --headless       # headless browser (faster, no GUI)

# Convenience aliases via npm
npm run eval              # all scenarios
npm run eval:quick        # Tier A only, no gaps
npm run eval:dry          # dry run
```

The HTML report opens in any browser. Each row shows the expected verdict against the actual
outcome (DONE / TERMINATE / MAX_STEPS / TIMEOUT / CRASH), plus steps, elapsed time, and token
usage with estimated cost. The headline number is **"behaved as expected"**, not raw DONE
count, so dead-end scenarios are scored correctly. A JSON snapshot is written alongside the
HTML so results survive a crash.

**Keep `--timeout` generous.** A scenario killed by the runner is recorded as `TIMEOUT`, which
is indistinguishable from the agent looping. Anything under ~150 s produces false negatives on
the slower Tier E–H pages; leave the 300 s default unless you are iterating on one tier.

## Notes

- The `/secure/*` area is gated by a fake login (cookie session). Use it to test
  auth-gated provisioning end to end.
- Account data is generated deterministically in `lib/data.js` (247 rows).
- Structural mutations live in `lib/mutate.js`; the accounts API and auth live in `server.js`.
- Change ports with `PORT` / `ALT_PORT` env vars.
