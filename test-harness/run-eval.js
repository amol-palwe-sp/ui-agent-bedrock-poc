#!/usr/bin/env node
'use strict';

/**
 * UI-Agent Test Harness — Automated Eval Runner
 *
 * Runs every scenario in the test harness against the POC agent (./gradlew run),
 * parses agent output for pass/fail signals, and writes a self-contained HTML
 * report to ../eval-reports/harness-<timestamp>.html
 *
 * Usage:
 *   node run-eval.js                   # all scenarios (sequential)
 *   node run-eval.js --tier=A          # one tier (A, AF, B, C, D)
 *   node run-eval.js --tier=A,B        # multiple tiers
 *   node run-eval.js --skip-gaps       # skip roadmap-gap scenarios
 *   node run-eval.js --dry-run         # print commands, don't execute
 *   node run-eval.js --timeout=180     # seconds per scenario (default 300)
 *   node run-eval.js --headless        # pass headless=true to the POC
 */

const { spawn, spawnSync } = require('child_process');
const fs           = require('fs');
const path         = require('path');

// ── Configuration ─────────────────────────────────────────────────────────────

const ARGS        = parseArgs(process.argv.slice(2));
const DRY_RUN     = ARGS['dry-run'] ?? false;
const SKIP_GAPS   = ARGS['skip-gaps'] ?? false;
const TIMEOUT_MS  = (parseInt(ARGS['timeout'] ?? '300', 10)) * 1000;
const HEADLESS    = ARGS['headless'] ?? false;
const TIER_FILTER = ARGS['tier'] ? String(ARGS['tier']).split(',').map(s => s.trim().toUpperCase()) : null;

const BASE        = 'http://localhost:4599';
const POC_DIR     = path.resolve(__dirname, '..');
const REPORT_DIR  = path.join(POC_DIR, 'eval-reports');

// ── Scenario definitions ───────────────────────────────────────────────────────
// verdict: 'pass' | 'ladder' | 'gap' | 'terminate'
// 'pass'      → native action path; expected to DONE
// 'ladder'    → generic CLICK/TYPE path; expected to DONE (usually)
// 'gap'       → no code path; expected to TERMINATE or MAX_STEPS
// 'terminate' → a genuine dead end; TERMINATE is the CORRECT result and scores
//               as a pass. Looping to MAX_STEPS here is the real failure because
//               it burns tokens without progress.

const SCENARIOS = [

  // ── Tier A: Discovery & structure ──────────────────────────────────────────
  { tier: 'A', name: 'semantic-form', verdict: 'pass',
    url: `${BASE}/a-discovery/semantic-form.html`,
    goal: 'enter "test.user@demo.local" in the Email field, then enter "Demo!Pass123" in the Password field, then click the Sign in button' },

  { tier: 'A', name: 'div-role-widgets', verdict: 'pass',
    url: `${BASE}/a-discovery/div-role-widgets.html`,
    goal: 'click the Username field and enter "jdoe", then click the Notes area and enter "hello", then click the Submit button' },

  { tier: 'A', name: 'aria-combobox', verdict: 'pass',
    url: `${BASE}/a-discovery/aria-combobox.html`,
    goal: 'select "Engineering" from the Department dropdown, then click Save' },

  { tier: 'A', name: 'dup-labels', verdict: 'pass',
    url: `${BASE}/a-discovery/dup-labels.html`,
    goal: 'click the second Next button' },

  { tier: 'A', name: 'random-ids', verdict: 'pass',
    url: `${BASE}/a-discovery/random-ids.html`,
    goal: 'enter "hello world" in the Search field, then click the Search button' },

  { tier: 'A', name: 'same-origin-iframe', verdict: 'pass',
    url: `${BASE}/a-discovery/same-origin-iframe.html`,
    goal: 'enter "in-frame text" in the Message field inside the frame, then click Send' },

  { tier: 'A', name: 'cross-origin-iframe', verdict: 'gap',
    url: `${BASE}/a-discovery/cross-origin-iframe.html`,
    goal: 'enter text in the field inside the embedded frame, then click Submit' },

  { tier: 'A', name: 'shadow-dom-form', verdict: 'gap',
    url: `${BASE}/a-discovery/shadow-dom-form.html`,
    goal: 'enter "shadow value" in the Full name field, then click Save' },

  // ── Tier AF: Field types ───────────────────────────────────────────────────
  { tier: 'AF', name: 'text-inputs', verdict: 'pass',
    url: `${BASE}/a-fields/text-inputs.html`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, then click Save' },

  { tier: 'AF', name: 'textarea-richtext', verdict: 'pass',
    url: `${BASE}/a-fields/textarea-richtext.html`,
    goal: 'enter "line one" in the Description field, then enter "rich body" in the Rich notes area, then click Save' },

  { tier: 'AF', name: 'native-select', verdict: 'pass',
    url: `${BASE}/a-fields/native-select.html`,
    goal: 'select "Finance" from the Department dropdown, then click Save' },

  { tier: 'AF', name: 'aria-combobox-select', verdict: 'pass',
    url: `${BASE}/a-fields/aria-combobox-select.html`,
    goal: 'select "Marketing" from the Team dropdown, then click Save' },

  { tier: 'AF', name: 'typeahead-autocomplete', verdict: 'ladder',
    url: `${BASE}/a-fields/typeahead-autocomplete.html`,
    goal: 'type "eng" in the Team search field, then click the Engineering option, then click Save' },

  { tier: 'AF', name: 'cascading-dropdowns', verdict: 'ladder',
    url: `${BASE}/a-fields/cascading-dropdowns.html`,
    goal: 'select "United States" from Country, then select "California" from State, then click Save' },

  { tier: 'AF', name: 'checkbox-group', verdict: 'ladder',
    url: `${BASE}/a-fields/checkbox-group.html`,
    goal: 'check Email and SMS notification options, then click Save' },

  { tier: 'AF', name: 'radio-group', verdict: 'ladder',
    url: `${BASE}/a-fields/radio-group.html`,
    goal: 'select the Premium plan, then click Continue' },

  { tier: 'AF', name: 'toggle-switch', verdict: 'ladder',
    url: `${BASE}/a-fields/toggle-switch.html`,
    goal: 'turn on the Enable notifications toggle, then click Save' },

  { tier: 'AF', name: 'segmented-otp', verdict: 'ladder',
    url: `${BASE}/a-fields/segmented-otp.html`,
    goal: 'enter 1 in Digit 1, 2 in Digit 2, 3 in Digit 3, 4 in Digit 4, 5 in Digit 5, 6 in Digit 6, then click Verify' },

  { tier: 'AF', name: 'masked-input', verdict: 'ladder',
    url: `${BASE}/a-fields/masked-input.html`,
    goal: 'enter "4155550123" in the Phone field, then click Save' },

  { tier: 'AF', name: 'star-rating', verdict: 'ladder',
    url: `${BASE}/a-fields/star-rating.html`,
    goal: 'click the 4 stars radio button to set the rating to 4 stars, then click Submit' },

  { tier: 'AF', name: 'transfer-list', verdict: 'ladder',
    url: `${BASE}/a-fields/transfer-list.html`,
    goal: 'click the Reports option in Available, then click the Move right button, then click Save' },

  { tier: 'AF', name: 'multiselect-chips', verdict: 'gap',
    url: `${BASE}/a-fields/multiselect-chips.html`,
    goal: 'select Engineering and Finance in the Groups field, then click Save' },

  { tier: 'AF', name: 'date-picker', verdict: 'gap',
    url: `${BASE}/a-fields/date-picker.html`,
    goal: 'set the Start date to August 15 2026 using the calendar, then click Save' },

  { tier: 'AF', name: 'date-range-time', verdict: 'gap',
    url: `${BASE}/a-fields/date-range-time.html`,
    goal: 'set From to 2026-08-10, To to 2026-08-20, Time to 14:30, then click Save' },

  { tier: 'AF', name: 'slider-range', verdict: 'gap',
    url: `${BASE}/a-fields/slider-range.html`,
    goal: 'set the Budget slider to 750, then click Save' },

  { tier: 'AF', name: 'file-upload', verdict: 'gap',
    url: `${BASE}/a-fields/file-upload.html`,
    goal: 'upload a file in the Attachment field, then click Save' },

  { tier: 'AF', name: 'drag-reorder', verdict: 'gap',
    url: `${BASE}/a-fields/drag-reorder.html`,
    goal: 'drag Task C above Task A in the list, then click Save' },

  // ── Tier B: Navigation steps ───────────────────────────────────────────────
  { tier: 'B', name: 'multipage-wizard', verdict: 'pass',
    url: `${BASE}/b-navigation/multipage-wizard-1.html`,
    goal: 'enter "Ada" in Name, then click Next, then select "Finance" from Department, then click Next, then click Finish' },

  { tier: 'B', name: 'spa-router', verdict: 'ladder',
    url: `${BASE}/b-navigation/spa-router.html`,
    goal: 'click Products, then click Settings, then enter "dark" in the Preference field, then click Save preferences' },

  { tier: 'B', name: 'modal-overlay', verdict: 'pass',
    url: `${BASE}/b-navigation/modal-overlay.html`,
    goal: 'click Open dialog, then enter "confirmed" in the Reason field, then click Confirm' },

  { tier: 'B', name: 'native-alert-confirm', verdict: 'pass',
    url: `${BASE}/b-navigation/native-alert-confirm.html`,
    goal: 'click the Delete item button, then click the Continue button' },

  { tier: 'B', name: 'popup-newtab', verdict: 'pass',
    url: `${BASE}/b-navigation/popup-newtab.html`,
    goal: 'click Open helper window, then enter "code123" in the Code field, then click Apply' },

  { tier: 'B', name: 'slow-spinner-skeleton', verdict: 'pass',
    url: `${BASE}/b-navigation/slow-spinner-skeleton.html`,
    goal: 'click Load profile, then wait for the form to appear, then enter "Ada" in the Name field, then click Save' },

  { tier: 'B', name: 'conditional-branch-wizard', verdict: 'ladder',
    url: `${BASE}/b-navigation/conditional-branch-wizard.html`,
    goal: 'select "Business" account type, then click Next, then enter "Acme Inc" in Company, then click Finish' },

  { tier: 'B', name: 'tabbed-nav', verdict: 'ladder',
    url: `${BASE}/b-navigation/tabbed-nav.html`,
    goal: 'click the Billing tab, then enter "4242" in the Card field, then click Save' },

  { tier: 'B', name: 'accordion-tree-nav', verdict: 'ladder',
    url: `${BASE}/b-navigation/accordion-tree-nav.html`,
    goal: 'expand the Administration section, then click Users, then click Add' },

  { tier: 'B', name: 'hover-megamenu', verdict: 'gap',
    url: `${BASE}/b-navigation/hover-megamenu.html`,
    goal: 'hover over Products to open the menu, then click Analytics' },

  // ── Tier C: Replay resilience (record v1 only; replay is manual) ───────────
  { tier: 'C', name: 'provisioning-v1-baseline', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=1`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'Record target. Replay v2–v7 manually once this script is saved.' },

  { tier: 'C', name: 'provisioning-v2-reorder', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=2`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'DOM reordered — same goal, survives via aria/structural fingerprint.' },

  { tier: 'C', name: 'provisioning-v3-classes', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=3`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'CSS classes renamed — id/aria fingerprint should still match.' },

  { tier: 'C', name: 'provisioning-v4-ids', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=4`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'Element IDs changed — forces fallback from level-1 to aria fingerprint.' },

  { tier: 'C', name: 'provisioning-v5-wrappers', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=5`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'Extra wrapper divs inserted — structural hash changes.' },

  { tier: 'C', name: 'provisioning-v6-i18n', verdict: 'ladder',
    url: `${BASE}/c-replay/provisioning.html?variant=6`,
    goal: 'enter "Ada" in Vorname, "Lovelace" in Nachname, "ada@demo.local" in E-Mail, select "Engineering" from Abteilung, then click Benutzer erstellen',
    note: 'Labels translated to German — text fingerprint breaks, only structural survives.' },

  { tier: 'C', name: 'provisioning-v7-theme', verdict: 'pass',
    url: `${BASE}/c-replay/provisioning.html?variant=7`,
    goal: 'enter "Ada" in First name, "Lovelace" in Last name, "ada@demo.local" in Email, select "Engineering" from Department, then click Create user',
    note: 'Dark theme — visual change only, no structural impact.' },

  // ── Tier D: Aggregation / pagination (navigation proof, not CSV output) ────
  { tier: 'D', name: 'table-numbered', verdict: 'pass',
    url: `${BASE}/d-aggregation/table-numbered.html`,
    goal: 'click Page 2 button, then click Page 3 button, then click Page 4 button' },

  { tier: 'D', name: 'grid-nextbtn', verdict: 'pass',
    url: `${BASE}/d-aggregation/grid-nextbtn.html`,
    goal: 'click the Next button to advance to the next page, then click Next again' },

  { tier: 'D', name: 'load-more', verdict: 'pass',
    url: `${BASE}/d-aggregation/load-more.html`,
    goal: 'click the Load more button three times to load additional accounts' },

  { tier: 'D', name: 'infinite-scroll', verdict: 'pass',
    url: `${BASE}/d-aggregation/infinite-scroll.html`,
    goal: 'scroll down to load more accounts, then scroll down again to load more' },

  { tier: 'D', name: 'virtualized-list', verdict: 'ladder',
    url: `${BASE}/d-aggregation/virtualized-list.html`,
    goal: 'scroll down in the accounts list to reveal more rows' },

  { tier: 'D', name: 'cursor-pagination', verdict: 'ladder',
    url: `${BASE}/d-aggregation/cursor-pagination.html`,
    goal: 'click the Next button to load the next page of accounts, then click Next again' },

  { tier: 'D', name: 'url-param-pagination', verdict: 'ladder',
    url: `${BASE}/d-aggregation/url-param-pagination.html`,
    goal: 'click the Next button to go to page 2, then click Next to go to page 3' },

  { tier: 'D', name: 'page-size-selector', verdict: 'ladder',
    url: `${BASE}/d-aggregation/page-size-selector.html`,
    goal: 'select 100 from the Rows per page dropdown, then click Page 1 button' },

  { tier: 'D', name: 'merged-headers', verdict: 'ladder',
    url: `${BASE}/d-aggregation/merged-headers.html`,
    goal: 'click Page 2 button, then click Page 3 button to navigate through accounts' },

  // ── Tier E: Obscuring & blocking (P0) ──────────────────────────────────────
  { tier: 'E', name: 'cookie-consent-banner', verdict: 'ladder',
    url: `${BASE}/e-obscuring/cookie-consent-banner.html`,
    goal: 'accept the cookie banner, then enter "Ada Lovelace" in Full name, then enter "ada@demo.local" in Email, then click Submit application',
    note: 'Consent banner covers the submit button.' },

  { tier: 'E', name: 'modal-blocks-target', verdict: 'ladder',
    url: `${BASE}/e-obscuring/modal-blocks-target.html`,
    goal: 'dismiss the promotional offer, then enter "Acme Ltd" in Account name, then click Create account',
    note: 'Promo modal intercepts all clicks.' },

  { tier: 'E', name: 'sticky-header-overlap', verdict: 'ladder',
    url: `${BASE}/e-obscuring/sticky-header-overlap.html`,
    goal: 'scroll down to the Approval code field, then enter "AC-9931" in it, then click Approve',
    note: 'Sticky toolbar covers the scroll target.' },

  { tier: 'E', name: 'toast-autodismiss', verdict: 'ladder',
    url: `${BASE}/e-obscuring/toast-autodismiss.html`,
    goal: 'enter "quarterly review" in the Note field, then click Save, then click Confirm',
    note: 'Timed toast covers Confirm for 5s — genuine race, may vary per run.' },

  { tier: 'E', name: 'layout-shift-click', verdict: 'ladder',
    url: `${BASE}/e-obscuring/layout-shift-click.html`,
    goal: 'enter "500" in the Amount field, then click Send transfer',
    note: 'Injected banner shifts buttons ~2.5s after load.' },

  { tier: 'E', name: 'full-page-interstitial', verdict: 'ladder',
    url: `${BASE}/e-obscuring/full-page-interstitial.html`,
    goal: 'close the announcement, then enter "ada" in Search users, then click Search',
    note: 'Whole-viewport overlay with only a small close control.' },

  // ── Tier F: Viewport & rendering (P1 / P2) ─────────────────────────────────
  { tier: 'F', name: 'responsive-reflow', verdict: 'ladder',
    url: `${BASE}/f-viewport/responsive-reflow.html`,
    goal: 'enter "alovelace" in Username, then select "Admin" from Role, then click Save user',
    note: 'Below 900px viewport the actions collapse behind a burger menu.' },

  { tier: 'F', name: 'zoom-125', verdict: 'ladder',
    url: `${BASE}/f-viewport/zoom.html?level=125`,
    goal: 'enter "Access policy" in Policy name, then select "Ada" from Owner, then click Create policy',
    note: 'CSS scale 1.25 — emulated browser zoom.' },

  { tier: 'F', name: 'zoom-150', verdict: 'ladder',
    url: `${BASE}/f-viewport/zoom.html?level=150`,
    goal: 'enter "Access policy" in Policy name, then select "Ada" from Owner, then click Create policy',
    note: 'CSS scale 1.5.' },

  { tier: 'F', name: 'zoom-200', verdict: 'ladder',
    url: `${BASE}/f-viewport/zoom.html?level=200`,
    goal: 'enter "Access policy" in Policy name, then select "Ada" from Owner, then click Create policy',
    note: 'CSS scale 2.0 — heaviest coordinate shift.' },

  { tier: 'F', name: 'dark-mode-prefers', verdict: 'pass',
    url: `${BASE}/f-viewport/dark-mode-prefers.html`,
    goal: 'enter "Platform team" in Group name, then select "Private" from Visibility, then click Create group',
    note: 'Honours prefers-color-scheme.' },

  { tier: 'F', name: 'light-dark-toggle', verdict: 'pass',
    url: `${BASE}/f-viewport/light-dark-toggle.html`,
    goal: 'click Dark theme, then enter "Q3 summary" in Report title, then click Generate report',
    note: 'Theme switches mid-flow; DOM unchanged, visuals differ.' },

  { tier: 'F', name: 'low-contrast-tiny-text', verdict: 'ladder',
    url: `${BASE}/f-viewport/low-contrast-tiny-text.html`,
    goal: 'enter "billing-svc" in Service name, then select "Production" from Environment, then enter "ada@demo.local" in Owner email, then click Register service',
    note: 'Vision-degradation proxy for low-quality recordings.' },

  { tier: 'F', name: 'rtl-layout', verdict: 'ladder',
    url: `${BASE}/f-viewport/rtl-layout.html`,
    goal: 'enter "Ada Lovelace" in the Full name field, then click Save',
    note: 'Arabic RTL — positional reasoning is mirrored.' },

  { tier: 'F', name: 'long-page-scroll', verdict: 'ladder',
    url: `${BASE}/f-viewport/long-page-scroll.html`,
    goal: 'scroll down to Section 20, then enter "FIN-2026" in the Final approval code field, then click Submit approval',
    note: '20 sections; target only in the last one.' },

  // ── Tier G: Failure & recovery ─────────────────────────────────────────────
  { tier: 'G', name: 'infinite-spinner', verdict: 'terminate',
    url: `${BASE}/g-failure/infinite-spinner.html`,
    goal: 'click Load data, then enter "test" in the account details field once it loads',
    note: 'Never resolves. TERMINATE is correct; MAX_STEPS means it looped.' },

  { tier: 'G', name: 'server-error-500', verdict: 'terminate',
    url: `${BASE}/g-failure/server-error-500.html`,
    goal: 'enter "acme" in Tenant name, then click Provision tenant, then confirm the tenant was created',
    note: 'Submit navigates to a real HTTP 500.' },

  { tier: 'G', name: 'rate-limit-429', verdict: 'terminate',
    url: `${BASE}/g-failure/rate-limit-429.html`,
    goal: 'enter "ada" in Search directory, then click Search, then read the first result',
    note: 'HTTP 429 with Retry-After: 120 — waiting is not viable.' },

  { tier: 'G', name: 'session-timeout-midflow', verdict: 'ladder',
    url: `${BASE}/g-failure/session-timeout-midflow.html`,
    goal: 'enter "Demo!Pass123" in the Password field, then click Sign in, then enter "Ada" in First name, then enter "Lovelace" in Last name, then select "Engineering" from Department, then click Continue to review',
    note: 'Session expires after 12s, forcing an unexpected re-auth redirect.' },

  { tier: 'G', name: 'validation-errors', verdict: 'ladder',
    url: `${BASE}/g-failure/validation-errors.html`,
    goal: 'enter "ada@demo.local" in Work email, then enter "123" in Employee ID, then click Create employee, then correct any validation errors shown and submit again',
    note: 'First submit always fails; agent must read errors and fix.' },

  { tier: 'G', name: 'disabled-until-valid', verdict: 'ladder',
    url: `${BASE}/g-failure/disabled-until-valid.html`,
    goal: 'enter "Apollo" in Project name, then enter "Ada" in Project owner, then enter "BUD-77" in Budget code, then check the accept terms checkbox, then click Create project',
    note: 'Submit stays disabled until all prerequisites are met.' },

  { tier: 'G', name: 'confirm-by-typing', verdict: 'ladder',
    url: `${BASE}/g-failure/confirm-by-typing.html`,
    goal: 'enter "DELETE acme-production" in the Confirmation phrase field, then click Delete workspace',
    note: 'Exact literal match required to unlock the button.' },

  // ── Tier H: Scale, structure & motion (P0 / P1) ────────────────────────────
  { tier: 'H', name: 'many-elements-200', verdict: 'ladder',
    url: `${BASE}/h-scale/many-elements-200.html`,
    goal: 'click the Toggle feature 137 button',
    note: '200 controls — watch token usage for context blowup.' },

  { tier: 'H', name: 'nested-iframe', verdict: 'ladder',
    url: `${BASE}/h-scale/nested-iframe.html`,
    goal: 'enter "4417" in the Security PIN field inside the nested frame, then click Verify PIN',
    note: 'iframe inside an iframe — tests whether frame traversal recurses.' },

  { tier: 'H', name: 'fast-autoscroll', verdict: 'ladder',
    url: `${BASE}/h-scale/fast-autoscroll.html`,
    goal: 'click Pause auto-scroll, then enter "DEST-12" in Destination code, then click Confirm destination',
    note: 'P0 motion — viewport never settles until paused.' },

  { tier: 'H', name: 'auto-redirect-chain', verdict: 'ladder',
    url: `${BASE}/h-scale/auto-redirect-chain.html`,
    goal: 'wait for the redirects to finish, then enter "s3-bucket" in Resource name, then enter "audit" in Justification, then click Request access',
    note: 'P0 fast navigation — 3 hops in ~3s destroy in-flight actions.' },

  { tier: 'H', name: 'multi-tab-workflow', verdict: 'ladder',
    url: `${BASE}/h-scale/multi-tab-workflow.html`,
    goal: 'click Open token generator, then click Generate token in the new tab, then return to the first tab and enter "TKN-4417-DEMO" in Access token, then enter "audit" in Purpose, then click Submit token',
    note: 'P1 — requires switching BACK to the original tab.' },

  { tier: 'H', name: 'midflow-resume', verdict: 'ladder',
    url: `${BASE}/h-scale/midflow-resume.html`,
    goal: 'create the user account for ada@demo.local with profile Ada Lovelace in Engineering, then select "Administrator" as Access level, then enter "global" in Scope, then click Continue to review',
    note: 'P0 proxy — goal describes steps 1-4 but page is already at step 3.' },
];

// ── Main ───────────────────────────────────────────────────────────────────────

async function main() {
  const scenarios = filterScenarios(SCENARIOS);

  if (scenarios.length === 0) {
    console.error('No scenarios matched the given filters.');
    process.exit(1);
  }

  if (DRY_RUN) {
    console.log(`DRY RUN — ${scenarios.length} scenario(s)\n`);
    for (const s of scenarios) {
      console.log(`[${s.tier}] ${s.name}`);
      console.log(`  ./gradlew run --args='--url=${s.url} --goal=${s.goal}'\n`);
    }
    return;
  }

  // Ensure harness is reachable.
  if (!await isHarnessUp()) {
    console.error(`\nTest harness is not running at ${BASE}.\nStart it with:  cd test-harness && npm start\n`);
    process.exit(1);
  }

  checkJavaOrExit();

  fs.mkdirSync(REPORT_DIR, { recursive: true });

  const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const reportJson = path.join(REPORT_DIR, `harness-${timestamp}.json`);
  const reportHtml = path.join(REPORT_DIR, `harness-${timestamp}.html`);

  console.log(`\nUI-Agent Eval Runner`);
  console.log(`Scenarios  : ${scenarios.length}`);
  console.log(`Timeout    : ${TIMEOUT_MS / 1000}s per scenario`);
  console.log(`Report     : ${reportHtml}\n`);
  console.log('─'.repeat(72));

  const results = [];
  const startAll = Date.now();

  for (let i = 0; i < scenarios.length; i++) {
    const s = scenarios[i];
    const prefix = `[${i + 1}/${scenarios.length}][${s.tier}] ${s.name}`;
    process.stdout.write(`${prefix.padEnd(52)} `);

    const start = Date.now();
    const result = await runScenario(s);
    const elapsed = Date.now() - start;

    result.elapsedMs = elapsed;
    results.push(result);

    const icon   = result.asExpected ? '✅' : '❌';
    const timing = `${(elapsed / 1000).toFixed(1)}s`;
    const steps  = result.stepsExecuted != null ? `${result.stepsExecuted} steps` : '';
    console.log(`${icon}  ${result.outcome.padEnd(12)} ${timing.padStart(6)}  ${steps}`);

    // Write incremental JSON after each run so progress is not lost on crash.
    fs.writeFileSync(reportJson, JSON.stringify({ timestamp, scenarios: results }, null, 2));
  }

  const totalMs = Date.now() - startAll;
  console.log('─'.repeat(72));
  printSummary(results, totalMs);

  const html = buildHtmlReport(results, timestamp, totalMs);
  fs.writeFileSync(reportHtml, html, 'utf8');
  console.log(`\nReport saved: ${reportHtml}`);
}

// ── Scenario runner ────────────────────────────────────────────────────────────

/**
 * Fail fast when no JDK is resolvable.
 *
 * Without this every scenario spawns gradlew, dies in ~100ms and is recorded as
 * a CRASH — an 81-row report that looks like an agent failure but is really a
 * missing JAVA_HOME. On macOS /usr/bin/java always exists as a stub that exits
 * non-zero, so probing the exit code is the reliable check.
 */
function checkJavaOrExit() {
  const probe = spawnSync('java', ['-version'], { encoding: 'utf8' });
  if (!probe.error && probe.status === 0) return;

  console.error('\nNo usable Java runtime found — gradlew cannot start.');
  console.error(`JAVA_HOME is ${process.env.JAVA_HOME ? `"${process.env.JAVA_HOME}"` : 'not set'}.`);

  const candidates = [
    '/Library/Java/JavaVirtualMachines',
    path.join(process.env.HOME ?? '', 'Library/Java/JavaVirtualMachines'),
  ].flatMap((dir) => {
    try {
      return fs.readdirSync(dir).map((d) => path.join(dir, d, 'Contents/Home'));
    } catch { return []; }
  }).filter((p) => fs.existsSync(path.join(p, 'bin/java')));

  if (candidates.length) {
    console.error('\nThis project targets Java 17. Detected JDKs:');
    for (const c of candidates) console.error(`  ${c}`);
    const preferred = candidates.find((c) => /17/.test(c)) ?? candidates[0];
    console.error(`\nRe-run with, for example:\n  export JAVA_HOME="${preferred}"\n  node run-eval.js\n`);
  } else {
    console.error('\nNo JDK found. Install Java 17 and set JAVA_HOME.\n');
  }
  process.exit(1);
}

function runScenario(scenario) {
  return new Promise((resolve) => {
    const extraArgs = HEADLESS ? ' browser.headless=true' : '';
    const fullArgs  = `--url=${scenario.url} --goal=${scenario.goal}${extraArgs}`;

    const child = spawn('./gradlew', ['run', `--args=${fullArgs}`], {
      cwd:   POC_DIR,
      env:   { ...process.env },
      shell: false,
    });

    let stdout = '';
    let stderr = '';
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
    }, TIMEOUT_MS);

    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', (code) => {
      clearTimeout(timer);

      const outcome  = detectOutcome(stdout, stderr, code, timedOut);
      const steps    = extractSteps(stdout);
      const tokens   = extractTokens(stdout);
      const termMsg  = extractTerminateMessage(stdout);

      resolve({
        tier:            scenario.tier,
        name:            scenario.name,
        url:             scenario.url,
        goal:            scenario.goal,
        expectedVerdict: scenario.verdict,
        note:            scenario.note ?? null,
        outcome,
        asExpected:      isAsExpected(scenario.verdict, outcome),
        stepsExecuted:   steps,
        tokenUsage:      tokens,
        terminateMessage: termMsg,
        exitCode:        code,
        timedOut,
        stdoutSnippet:   tailLines(stdout, 25),
        // Gradle launcher failures (missing JDK, bad wrapper) write only to
        // stderr. Without this a crash surfaces as an empty snippet and the
        // real cause is invisible in the report.
        stderrSnippet:   tailLines(stderr, 25),
      });
    });
  });
}

// ── Output parsing ─────────────────────────────────────────────────────────────

function detectOutcome(stdout, stderr, exitCode, timedOut) {
  if (timedOut)                                                  return 'TIMEOUT';
  if (exitCode !== 0 && !stdout.includes('Stopped after'))       return 'CRASH';
  if (stdout.includes('TERMINATE'))                              return 'TERMINATE';
  if (stdout.includes('Stopped after goal achieved'))            return 'DONE';
  if (stdout.includes('Max steps reached'))                      return 'MAX_STEPS';
  if (exitCode === 0)                                            return 'DONE';
  return 'UNKNOWN';
}

/**
 * Whether the observed outcome is the correct behaviour for this scenario.
 *
 * This is deliberately not "outcome === DONE". For a genuine dead end
 * (verdict 'terminate') a clean TERMINATE is the right answer and looping to
 * MAX_STEPS is the failure — scoring those as failures would penalise the agent
 * for behaving correctly. Likewise a documented roadmap gap is "as expected"
 * when the agent gives up rather than silently claiming success.
 */
function isAsExpected(verdict, outcome) {
  switch (verdict) {
    case 'pass':
    case 'ladder':
      return outcome === 'DONE';
    case 'terminate':
      return outcome === 'TERMINATE';
    case 'gap':
      return outcome === 'TERMINATE' || outcome === 'MAX_STEPS';
    default:
      return outcome === 'DONE';
  }
}

function extractSteps(stdout) {
  const matches = [...stdout.matchAll(/--- Step (\d+) \/ \d+ ---/g)];
  return matches.length > 0 ? parseInt(matches[matches.length - 1][1], 10) : null;
}

/**
 * Parse the agent's end-of-run summary, which TokenUsage#toString renders as:
 *   ========== TOTAL TOKEN USAGE ==========
 *   input=12345 tokens ($0.037035) | output=678 tokens ($0.010170) | total=$0.047205
 */
function extractTokens(stdout) {
  const m = stdout.match(
    /TOTAL TOKEN USAGE[\s\S]{0,400}?input=(\d[\d,]*)\s*tokens[^|]*\|\s*output=(\d[\d,]*)\s*tokens[^|]*(?:\|\s*total=\$([\d.]+))?/i,
  );
  if (!m) return null;
  return {
    input:   parseInt(m[1].replace(/,/g, ''), 10),
    output:  parseInt(m[2].replace(/,/g, ''), 10),
    costUsd: m[3] ? parseFloat(m[3]) : null,
  };
}

function extractTerminateMessage(stdout) {
  const m = stdout.match(/"message"\s*:\s*"([^"]+)"/);
  return m ? m[1] : null;
}

function tailLines(text, n) {
  return text.split('\n').slice(-n).join('\n');
}

// ── Filtering ──────────────────────────────────────────────────────────────────

function filterScenarios(all) {
  let list = all;
  if (TIER_FILTER)  list = list.filter(s => TIER_FILTER.includes(s.tier));
  if (SKIP_GAPS)    list = list.filter(s => s.verdict !== 'gap');
  return list;
}

// ── Health check ───────────────────────────────────────────────────────────────

function isHarnessUp() {
  return new Promise((resolve) => {
    const http = require('http');
    const req  = http.get(BASE, (res) => { resolve(res.statusCode === 200); });
    req.on('error', () => resolve(false));
    req.setTimeout(3000, () => { req.abort(); resolve(false); });
  });
}

// ── CLI args ───────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = {};
  for (const a of argv) {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    if (m) out[m[1]] = m[2] ?? true;
  }
  return out;
}

// ── Summary ────────────────────────────────────────────────────────────────────

function printSummary(results, totalMs) {
  const counts = {};
  for (const r of results) counts[r.outcome] = (counts[r.outcome] ?? 0) + 1;

  const total    = results.length;
  const expected = results.filter(r => r.asExpected).length;
  const done     = counts['DONE'] ?? 0;

  console.log(`\nAs expected : ${expected}/${total}  (${Math.round((expected / total) * 100)}%)`);
  console.log(`Reached DONE: ${done}/${total}`);
  console.log(`Outcomes    : ${JSON.stringify(counts)}`);

  const unexpected = results.filter(r => !r.asExpected);
  if (unexpected.length) {
    console.log(`\nUnexpected results:`);
    for (const r of unexpected) {
      console.log(`  [${r.tier}] ${r.name.padEnd(30)} expected ${r.expectedVerdict.padEnd(10)} got ${r.outcome}`);
    }
  }
  console.log(`\nTotal time: ${(totalMs / 1000).toFixed(0)}s`);
}

// ── HTML report ────────────────────────────────────────────────────────────────

function buildHtmlReport(results, timestamp, totalMs) {
  const byTier = {};
  for (const r of results) {
    if (!byTier[r.tier]) byTier[r.tier] = [];
    byTier[r.tier].push(r);
  }

  const total    = results.length;
  const expected = results.filter(r => r.asExpected).length;
  const done     = results.filter(r => r.outcome === 'DONE').length;
  const unexp    = total - expected;
  const pct      = total > 0 ? Math.round((expected / total) * 100) : 0;
  const runDate  = new Date(timestamp.replace(/-/g, ':')).toLocaleString();

  const TIER_LABELS = {
    A:  'Tier A — Discovery & structure',
    AF: 'Tier A-Fields — Input types',
    B:  'Tier B — Navigation',
    C:  'Tier C — Replay resilience',
    D:  'Tier D — Aggregation / pagination',
    E:  'Tier E — Obscuring & blocking (P0)',
    F:  'Tier F — Viewport & rendering (P1 / P2)',
    G:  'Tier G — Failure & recovery',
    H:  'Tier H — Scale, structure & motion (P0 / P1)',
  };

  const verdictBadge = (v) => ({
    pass:      '<span class="pill ok">Pass</span>',
    ladder:    '<span class="pill warn">Ladder</span>',
    gap:       '<span class="pill gap">Roadmap gap</span>',
    terminate: '<span class="pill stop">Expect TERMINATE</span>',
  }[v] ?? v);

  const outcomeBadge = (o) => ({
    DONE:      '<span class="pill ok">DONE</span>',
    // Neutral colour: whether TERMINATE is good or bad depends on the scenario,
    // which the row background already conveys.
    TERMINATE: '<span class="pill stop">TERMINATE</span>',
    MAX_STEPS: '<span class="pill warn">MAX_STEPS ⏱</span>',
    TIMEOUT:   '<span class="pill warn">TIMEOUT ⏱</span>',
    CRASH:     '<span class="pill gap">CRASH</span>',
    UNKNOWN:   '<span class="pill warn">UNKNOWN</span>',
  }[o] ?? `<span class="pill warn">${o}</span>`);

  // For a crash the useful diagnostic is usually on stderr, not stdout.
  const cellNote = (r) => {
    if (r.terminateMessage) return escHtml(r.terminateMessage);
    if (r.outcome !== 'CRASH') return '';
    const diag = (r.stderrSnippet || '').trim() || (r.stdoutSnippet || '').trim();
    return diag ? `<code>${escHtml(diag.slice(-300))}</code>` : '';
  };

  const tierSections = Object.entries(byTier).map(([tier, rows]) => {
    const tierOk = rows.filter(r => r.asExpected).length;
    const rows_html = rows.map(r => `
      <tr class="${r.asExpected ? 'row-expected' : 'row-unexpected'}">
        <td>${r.asExpected ? '✅' : '❌'}</td>
        <td><strong>${r.name}</strong>${r.note ? `<br><small>${escHtml(r.note)}</small>` : ''}</td>
        <td>${verdictBadge(r.expectedVerdict)}</td>
        <td>${outcomeBadge(r.outcome)}</td>
        <td>${r.stepsExecuted ?? '—'}</td>
        <td>${r.elapsedMs ? (r.elapsedMs / 1000).toFixed(1) + 's' : '—'}</td>
        <td>${r.tokenUsage
          ? `in:${r.tokenUsage.input.toLocaleString()} out:${r.tokenUsage.output.toLocaleString()}`
            + (r.tokenUsage.costUsd != null ? `<br><small>$${r.tokenUsage.costUsd.toFixed(4)}</small>` : '')
          : '—'}</td>
        <td style="max-width:260px;font-size:11px">${cellNote(r)}</td>
      </tr>`).join('');

    return `
      <section>
        <h3>${TIER_LABELS[tier] ?? tier} <span class="tier-stat">${tierOk}/${rows.length} as expected</span></h3>
        <table>
          <thead><tr><th></th><th>Scenario</th><th>Expected</th><th>Actual</th><th>Steps</th><th>Time</th><th>Tokens</th><th>Notes</th></tr></thead>
          <tbody>${rows_html}</tbody>
        </table>
      </section>`;
  }).join('');

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <title>UI-Agent Eval Report — ${runDate}</title>
  <style>
    :root{--ok:#1a8f4c;--warn:#b7791f;--gap:#c23b3b;--brand:#2f6fed;--bg:#f5f7fb;--card:#fff;--line:#e2e7f0;--ink:#1b2333;--muted:#5b6577}
    *{box-sizing:border-box}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--ink);line-height:1.5}
    header{background:var(--brand);color:#fff;padding:16px 24px}header h1{margin:0;font-size:20px}header p{margin:4px 0 0;opacity:.8;font-size:13px}
    main{max-width:1100px;margin:24px auto;padding:0 20px}
    .summary{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:24px}
    .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px 20px;box-shadow:0 1px 3px rgba(20,30,60,.08)}
    .card.big{flex:1;min-width:120px;text-align:center}.card.big .num{font-size:36px;font-weight:700}.card.big .lbl{color:var(--muted);font-size:13px}
    .card.big.ok .num{color:var(--ok)}.card.big.warn .num{color:var(--warn)}.card.big.gap .num{color:var(--gap)}
    .progress-bar{background:#e2e7f0;border-radius:999px;height:10px;overflow:hidden;margin-top:4px}
    .progress-bar .fill{height:100%;background:var(--ok);border-radius:999px}
    section{margin-bottom:28px}h3{font-size:15px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);border-bottom:2px solid var(--line);padding-bottom:6px;display:flex;justify-content:space-between}
    .tier-stat{color:var(--brand);font-weight:700}
    table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line)}
    th{background:#f0f3fa;font-size:11px;text-transform:uppercase;letter-spacing:.03em;color:var(--muted)}
    .row-expected{background:#f6fdf9}.row-unexpected{background:#fff5f5}
    .pill{display:inline-block;font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px}
    .pill.ok{background:#e4f6ec;color:var(--ok)}.pill.warn{background:#fbf1dd;color:var(--warn)}
    .pill.gap{background:#fbe3e3;color:var(--gap)}.pill.stop{background:#ece7fb;color:#5b3fc4}
    small{color:var(--muted)}
  </style>
</head>
<body>
  <header>
    <h1>UI-Agent Test Harness — Eval Report</h1>
    <p>Generated ${runDate} &nbsp;·&nbsp; ${total} scenarios &nbsp;·&nbsp; ${(totalMs/1000).toFixed(0)}s total</p>
  </header>
  <main>
    <div class="summary">
      <div class="card big ok"><div class="num">${expected}</div><div class="lbl">As expected</div></div>
      <div class="card big gap"><div class="num">${unexp}</div><div class="lbl">Unexpected</div></div>
      <div class="card big"><div class="num">${done}</div><div class="lbl">Reached DONE</div></div>
      <div class="card" style="flex:3;min-width:220px">
        <div style="font-weight:600;margin-bottom:8px">Behaved as expected — ${pct}%</div>
        <div class="progress-bar"><div class="fill" style="width:${pct}%"></div></div>
        <div style="font-size:12px;color:var(--muted);margin-top:8px">
          ${expected} of ${total} scenarios matched their expected outcome.
          For dead-end scenarios a clean <strong>TERMINATE</strong> counts as correct — looping to
          MAX_STEPS is the real failure.
        </div>
      </div>
    </div>
    ${tierSections}
  </main>
</body>
</html>`;
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── Entry ──────────────────────────────────────────────────────────────────────

main().catch(err => { console.error(err); process.exit(1); });
