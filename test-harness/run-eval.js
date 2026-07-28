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

const { spawn }    = require('child_process');
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
// verdict: 'pass' | 'ladder' | 'gap'
// 'pass'   → native action path; expected to DONE
// 'ladder' → generic CLICK/TYPE path; expected to DONE (usually)
// 'gap'    → no code path; expected to TERMINATE or MAX_STEPS

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

    const icon   = outcomeIcon(result.outcome);
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
        stepsExecuted:   steps,
        tokenUsage:      tokens,
        terminateMessage: termMsg,
        exitCode:        code,
        timedOut,
        stdoutSnippet:   tailLines(stdout, 25),
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

function extractSteps(stdout) {
  const matches = [...stdout.matchAll(/--- Step (\d+) \/ \d+ ---/g)];
  return matches.length > 0 ? parseInt(matches[matches.length - 1][1], 10) : null;
}

function extractTokens(stdout) {
  const m = stdout.match(/TOTAL TOKEN USAGE[\s\S]{0,600}?(?:input|in)[\s:\s]*(\d[\d,]*)[^\n]*[\s\S]{0,200}?(?:output|out)[\s:\s]*(\d[\d,]*)/i);
  if (m) return { input: parseInt(m[1].replace(/,/g, ''), 10), output: parseInt(m[2].replace(/,/g, ''), 10) };
  return null;
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

function outcomeIcon(outcome) {
  return { DONE: '✅', TERMINATE: '⛔', MAX_STEPS: '⏱ ', TIMEOUT: '⏱ ', CRASH: '💥', UNKNOWN: '❓' }[outcome] ?? '❓';
}

function printSummary(results, totalMs) {
  const counts = {};
  for (const r of results) counts[r.outcome] = (counts[r.outcome] ?? 0) + 1;
  const total  = results.length;
  const passed = (counts['DONE'] ?? 0);
  console.log(`\nResults: ${passed}/${total} DONE  |  ${JSON.stringify(counts)}`);
  console.log(`Total time: ${(totalMs / 1000).toFixed(0)}s`);
}

// ── HTML report ────────────────────────────────────────────────────────────────

function buildHtmlReport(results, timestamp, totalMs) {
  const byTier = {};
  for (const r of results) {
    if (!byTier[r.tier]) byTier[r.tier] = [];
    byTier[r.tier].push(r);
  }

  const total   = results.length;
  const done    = results.filter(r => r.outcome === 'DONE').length;
  const term    = results.filter(r => r.outcome === 'TERMINATE').length;
  const fail    = results.filter(r => ['MAX_STEPS','TIMEOUT','CRASH'].includes(r.outcome)).length;
  const pct     = total > 0 ? Math.round((done / total) * 100) : 0;
  const runDate = new Date(timestamp.replace(/-/g, ':')).toLocaleString();

  const TIER_LABELS = { A: 'Tier A — Discovery & structure', AF: 'Tier A-Fields — Input types',
    B: 'Tier B — Navigation', C: 'Tier C — Replay resilience', D: 'Tier D — Aggregation / pagination' };

  const verdictBadge = (v) => ({
    pass:   '<span class="pill ok">Pass</span>',
    ladder: '<span class="pill warn">Ladder</span>',
    gap:    '<span class="pill gap">Roadmap gap</span>',
  }[v] ?? v);

  const outcomeBadge = (o) => ({
    DONE:      '<span class="pill ok">DONE ✅</span>',
    TERMINATE: '<span class="pill gap">TERMINATE ⛔</span>',
    MAX_STEPS: '<span class="pill warn">MAX_STEPS ⏱</span>',
    TIMEOUT:   '<span class="pill warn">TIMEOUT ⏱</span>',
    CRASH:     '<span class="pill gap">CRASH 💥</span>',
    UNKNOWN:   '<span class="pill warn">UNKNOWN</span>',
  }[o] ?? `<span class="pill warn">${o}</span>`);

  const tierSections = Object.entries(byTier).map(([tier, rows]) => {
    const tierDone = rows.filter(r => r.outcome === 'DONE').length;
    const rows_html = rows.map(r => `
      <tr class="row-${r.outcome.toLowerCase()}">
        <td><strong>${r.name}</strong>${r.note ? `<br><small>${r.note}</small>` : ''}</td>
        <td>${verdictBadge(r.expectedVerdict)}</td>
        <td>${outcomeBadge(r.outcome)}</td>
        <td>${r.stepsExecuted ?? '—'}</td>
        <td>${r.elapsedMs ? (r.elapsedMs / 1000).toFixed(1) + 's' : '—'}</td>
        <td>${r.tokenUsage ? `in:${r.tokenUsage.input.toLocaleString()} out:${r.tokenUsage.output.toLocaleString()}` : '—'}</td>
        <td style="max-width:280px;font-size:11px">${r.terminateMessage ? escHtml(r.terminateMessage) : (r.outcome === 'CRASH' ? escHtml(r.stdoutSnippet.slice(-300)) : '')}</td>
      </tr>`).join('');

    return `
      <section>
        <h3>${TIER_LABELS[tier] ?? tier} <span class="tier-stat">${tierDone}/${rows.length}</span></h3>
        <table>
          <thead><tr><th>Scenario</th><th>Expected</th><th>Actual</th><th>Steps</th><th>Time</th><th>Tokens</th><th>Notes</th></tr></thead>
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
    .row-done{background:#f6fdf9}.row-terminate{background:#fff5f5}.row-max_steps,.row-timeout{background:#fffbf0}.row-crash{background:#fff0f0}
    .pill{display:inline-block;font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px}
    .pill.ok{background:#e4f6ec;color:var(--ok)}.pill.warn{background:#fbf1dd;color:var(--warn)}.pill.gap{background:#fbe3e3;color:var(--gap)}
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
      <div class="card big ok"><div class="num">${done}</div><div class="lbl">DONE</div></div>
      <div class="card big gap"><div class="num">${term}</div><div class="lbl">TERMINATE</div></div>
      <div class="card big warn"><div class="num">${fail}</div><div class="lbl">FAIL / TIMEOUT</div></div>
      <div class="card" style="flex:3;min-width:200px">
        <div style="font-weight:600;margin-bottom:8px">Pass rate (DONE) — ${pct}%</div>
        <div class="progress-bar"><div class="fill" style="width:${pct}%"></div></div>
        <div style="font-size:12px;color:var(--muted);margin-top:8px">${done} of ${total} scenarios reached DONE</div>
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
