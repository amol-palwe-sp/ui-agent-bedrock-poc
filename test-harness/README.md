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

```bash
# 1. Start the harness (separate terminal)
npm start

# 2. In the POC root terminal, run the eval
cd test-harness

node run-eval.js                  # all 55 scenarios (~60–90 min with Bedrock)
node run-eval.js --tier=A         # single tier
node run-eval.js --tier=A,B       # multiple tiers
node run-eval.js --skip-gaps      # skip roadmap-gap scenarios (faster)
node run-eval.js --dry-run        # print commands only, no execution
node run-eval.js --timeout=120    # seconds per scenario (default 300)
node run-eval.js --headless       # headless browser (faster, no GUI)

# Convenience aliases via npm
npm run eval              # all scenarios
npm run eval:quick        # Tier A only, no gaps
npm run eval:dry          # dry run
```

The HTML report opens in any browser. Each scenario row shows expected verdict vs actual outcome (DONE / TERMINATE / MAX_STEPS / TIMEOUT / CRASH), steps taken, elapsed time, and token usage. A JSON snapshot is also written alongside the HTML so results are not lost on crash.

## Notes

- The `/secure/*` area is gated by a fake login (cookie session). Use it to test
  auth-gated provisioning end to end.
- Account data is generated deterministically in `lib/data.js` (247 rows).
- Structural mutations live in `lib/mutate.js`; the accounts API and auth live in `server.js`.
- Change ports with `PORT` / `ALT_PORT` env vars.
