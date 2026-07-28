package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.replay.FingerprintMatcher;
import com.microsoft.playwright.options.LoadState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core account aggregation logic.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Phase 3 — Detect the accounts table or ARIA grid via JS (primary) or Claude vision (fallback).</li>
 *   <li>Phase 4 — Pagination loop: scrape rows per page, advance via CSS selectors (primary)
 *       or Claude vision (fallback), stop on max-pages / duplicate-page guard.</li>
 * </ol>
 *
 * <p>Handles both standard HTML {@code <table>} and ARIA-grid components (e.g. Google Admin
 * Console, Material UI, Salesforce Lightning) that render rows using {@code role="row"} and
 * {@code role="gridcell"} / {@code role="cell"} instead of {@code <tr>/<td>}.
 */
public final class AccountAggregator {

    // -------------------------------------------------------------------------
    // CSS selectors tried in order when looking for a "next page" control
    // -------------------------------------------------------------------------
    private static final String[] NEXT_PAGE_SELECTORS = {
        "button[aria-label=\"Next\"]",
        "button[aria-label=\"Next Page\"]",
        "button[aria-label=\"Go to next page\"]",
        "a[aria-label=\"Next\"]",
        "[class*=\"next-page\"]:not([disabled])",
        "[class*=\"pagination\"] button:last-child:not([disabled])",
        "a[rel=\"next\"]",
    };

    // -------------------------------------------------------------------------
    // JavaScript — table/grid detection
    // -------------------------------------------------------------------------

    /**
     * Detects the best table or ARIA grid on the page.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Standard {@code <table>} with {@code <tbody>} rows.</li>
     *   <li>ARIA grid: {@code role="grid"} / {@code role="table"} / {@code role="treegrid"}
     *       containing {@code role="row"} children.</li>
     * </ol>
     *
     * Returns: {@code { found, type, selector, headers[], rowCount }}
     * where {@code type} is {@code "table"} or {@code "aria-grid"}.
     */
    private static final String JS_DETECT_TABLE =
            """
            () => {
              // ── 1. Standard <table> ──────────────────────────────────────
              const tables = Array.from(document.querySelectorAll('table'));
              let best = null, bestCount = 0;
              tables.forEach(t => {
                const tbody = t.querySelector('tbody');
                const cnt = tbody
                  ? tbody.querySelectorAll('tr').length
                  : Math.max(0, t.querySelectorAll('tr').length - 1);
                if (cnt > bestCount) { bestCount = cnt; best = t; }
              });

              if (best && bestCount > 0) {
                const headers = [];
                const physicalHeaderTexts = [];
                const headerRow = best.querySelector('thead tr') || best.querySelector('tr');
                if (headerRow) {
                  headerRow.querySelectorAll('th, td').forEach(cell => {
                    // Prefer data-content-text (set by some component libraries) over raw textContent
                    const raw = cell.getAttribute('data-content-text') || cell.textContent;
                    const t = raw.replace(/\\s+/g, ' ').trim();
                    physicalHeaderTexts.push(t);
                    if (t) headers.push(t);
                  });
                }
                return JSON.stringify({
                  found: true, type: 'table', selector: 'table',
                  headers, rowCount: bestCount, physicalHeaderTexts
                });
              }

              // ── 2. ARIA grid / role-based list ───────────────────────────
              const gridRoots = Array.from(document.querySelectorAll(
                '[role="grid"],[role="table"],[role="treegrid"]'
              ));

              let bestGrid = null, bestGridCount = 0;
              gridRoots.forEach(g => {
                const cnt = g.querySelectorAll('[role="row"]').length;
                if (cnt > bestGridCount) { bestGridCount = cnt; bestGrid = g; }
              });

              if (bestGrid && bestGridCount > 0) {
                // Look for a header row (aria-rowindex=1 or first row containing columnheader cells)
                const allRows = Array.from(bestGrid.querySelectorAll('[role="row"]'));
                const headerRow = allRows.find(r =>
                  r.querySelector('[role="columnheader"]') !== null
                );
                const headers = [];
                if (headerRow) {
                  headerRow.querySelectorAll('[role="columnheader"]').forEach(cell => {
                    const h = cell.textContent.replace(/\\s+/g, ' ').trim();
                    if (h) headers.push(h);
                  });
                }

                // Build a unique CSS selector for this grid root
                const id = bestGrid.id ? '#' + bestGrid.id : null;
                const cls = bestGrid.className && typeof bestGrid.className === 'string'
                  ? '.' + bestGrid.className.trim().split(/\\s+/)[0] : null;
                const roleAttr = '[role="' + bestGrid.getAttribute('role') + '"]';
                const selector = id || (cls ? cls + roleAttr : roleAttr);

                return JSON.stringify({
                  found: true, type: 'aria-grid', selector,
                  headers,
                  rowCount: bestGridCount - (headerRow ? 1 : 0)
                });
              }

              return JSON.stringify({ found: false });
            }
            """;

    // -------------------------------------------------------------------------
    // JavaScript — row scraping (table + ARIA grid)
    // -------------------------------------------------------------------------

    /**
     * Scrapes data rows from whichever container type was detected.
     * {@code args}: {@code { selector, tableType, headers[], allHeaders[], columnMap[] }}
     *
     * <p>Two cell-alignment strategies, chosen per invocation:
     * <ol>
     *   <li><b>Index-map path</b> (when {@code columnMap} is present): for each header
     *       {@code headers[i]}, reads {@code cells[columnMap[i]]} directly.  No per-row
     *       content-sniffing needed — all structural-column decisions were made once in Java
     *       from the header row.  {@code columnMap[i] == -1} means the header was unmatched
     *       and yields an empty string (CleanCSV will drop it).</li>
     *   <li><b>Positional fallback</b> (when {@code columnMap} is null — ARIA grids and the
     *       Claude-only detection path): iterates cells in order, filtering out structural-only
     *       cells ({@code <img>}, {@code <svg>}, {@code <input type="checkbox">} with no text),
     *       then maps positionally to headers.</li>
     * </ol>
     *
     * <p>Cell value cleaning pipeline (shared, applied after cell selection):
     * <ol>
     *   <li>Collapse whitespace.</li>
     *   <li>Strip any known header name from the start of the value (case-insensitive).
     *       Uses {@code allHeaders} so prefix-stripping works even when headers are misaligned.</li>
     *   <li>Deduplicate repeated value — e.g. {@code "Akshay KhandelwalAkshay Khandelwal"}
     *       → {@code "Akshay Khandelwal"}.</li>
     * </ol>
     */
    private static final String JS_SCRAPE_TABLE =
            """
            (args) => {
              const selector   = args.selector;
              const tableType  = args.tableType  || 'table';
              const headers    = (args.headers    && args.headers.length    > 0) ? args.headers    : null;
              const allHeaders = (args.allHeaders && args.allHeaders.length > 0) ? args.allHeaders : (headers || []);
              const columnMap  = (args.columnMap  && args.columnMap.length   > 0) ? args.columnMap  : null;

              // ── cleanCell ──────────────────────────────────────────────────
              function cleanCell(rawText) {
                let v = rawText.replace(/\\s+/g, ' ').trim();

                // Step 1: strip any known header prefix (try longest first to avoid partial match)
                const sortedHeaders = allHeaders.slice().sort((a, b) => b.length - a.length);
                for (const hdr of sortedHeaders) {
                  if (!hdr) continue;
                  const prefix = hdr.replace(/\\s+/g, ' ').trim();
                  if (v.toLowerCase().startsWith(prefix.toLowerCase())) {
                    const stripped = v.substring(prefix.length).trim();
                    if (stripped.length > 0) { v = stripped; break; }
                  }
                }

                // Step 2: deduplicate repeated value
                // Pattern A: "XYZ XYZ" — word-split, find repeated prefix
                const words = v.split(' ');
                if (words.length >= 2) {
                  let found = false;
                  for (let i = 1; i <= Math.floor(words.length / 2); i++) {
                    const firstPart  = words.slice(0, i).join(' ');
                    const secondPart = words.slice(i).join(' ');
                    if (firstPart === secondPart) {
                      v = firstPart;
                      found = true;
                      break;
                    }
                  }
                  // Pattern B: no-space boundary — "AkshayAkshay"
                  if (!found && v.length % 2 === 0) {
                    const half = v.length / 2;
                    if (v.substring(0, half) === v.substring(half)) {
                      v = v.substring(0, half);
                    }
                  }
                }

                return v.trim();
              }
              // ──────────────────────────────────────────────────────────────

              const rows = [];

              if (tableType === 'table') {
                const table = document.querySelector(selector);
                if (!table) return JSON.stringify([]);
                const tbody = table.querySelector('tbody');
                const rowEls = tbody
                  ? Array.from(tbody.querySelectorAll('tr'))
                  : Array.from(table.querySelectorAll('tr')).slice(1);

                rowEls.forEach(row => {
                  const cells = row.querySelectorAll('td');
                  if (cells.length === 0) return;
                  const rowData = {};
                  if (columnMap && headers) {
                    // Index-map path: look up each header's cell by its mapped physical index.
                    headers.forEach((header, i) => {
                      const physIdx = columnMap[i];
                      rowData[header] = (physIdx >= 0 && physIdx < cells.length)
                        ? cleanCell(cells[physIdx].textContent)
                        : '';
                    });
                  } else {
                    // Positional fallback: filter structural-only cells then map by position.
                    const dataCells = Array.from(cells).filter(cell => {
                      const hasText = cell.textContent.trim().length > 0;
                      const isStructuralOnly = !hasText && cell.querySelector('img, svg, input[type="checkbox"]');
                      return !isStructuralOnly;
                    });
                    dataCells.forEach((cell, idx) => {
                      const key = (headers && headers[idx]) ? headers[idx] : ('col' + idx);
                      rowData[key] = cleanCell(cell.textContent);
                    });
                  }
                  if (Object.values(rowData).some(v => v.length > 0)) rows.push(rowData);
                });

              } else {
                // ARIA grid
                const root = document.querySelector(selector);
                if (!root) return JSON.stringify([]);

                const allRows = Array.from(root.querySelectorAll('[role="row"]'));
                const dataRows = allRows.filter(r =>
                  r.querySelector('[role="columnheader"]') === null
                );

                dataRows.forEach(row => {
                  const cells = row.querySelectorAll(
                    '[role="gridcell"],[role="cell"],[role="rowheader"]'
                  );
                  if (cells.length === 0) return;
                  const rowData = {};
                  if (columnMap && headers) {
                    headers.forEach((header, i) => {
                      const physIdx = columnMap[i];
                      rowData[header] = (physIdx >= 0 && physIdx < cells.length)
                        ? cleanCell(cells[physIdx].textContent)
                        : '';
                    });
                  } else {
                    const dataCells = Array.from(cells).filter(cell => {
                      const hasText = cell.textContent.trim().length > 0;
                      const isStructuralOnly = !hasText && cell.querySelector('img, svg, input[type="checkbox"]');
                      return !isStructuralOnly;
                    });
                    dataCells.forEach((cell, idx) => {
                      const key = (headers && headers[idx]) ? headers[idx] : ('col' + idx);
                      rowData[key] = cleanCell(cell.textContent);
                    });
                  }
                  if (Object.values(rowData).some(v => v.length > 0)) rows.push(rowData);
                });
              }

              return JSON.stringify(rows);
            }
            """;

    // -------------------------------------------------------------------------
    // JavaScript — next-page selector check
    // -------------------------------------------------------------------------

    private static final String JS_NEXT_PAGE_CHECK =
            """
            (selectors) => {
              for (const sel of selectors) {
                try {
                  const el = document.querySelector(sel);
                  if (!el) continue;
                  const r  = el.getBoundingClientRect();
                  if (r.width === 0 || r.height === 0) continue;
                  const st = window.getComputedStyle(el);
                  if (st.display === 'none' || st.visibility === 'hidden') continue;
                  if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
                  return JSON.stringify({ found: true, selector: sel });
                } catch (e) { continue; }
              }
              return JSON.stringify({ found: false });
            }
            """;

    /**
     * Broader next-page availability check that finds any next-page-like element on the page
     * using text/aria-label/class pattern matching (not a fixed selector list).
     *
     * <p>Returns three states:
     * <ul>
     *   <li>{@code { found: false }} — no next-page control detected at all (single-page table
     *       or all data is already showing)</li>
     *   <li>{@code { found: true, enabled: false, reason: "..." }} — control is present but
     *       disabled/aria-disabled/greyed-out — we have reached the last page</li>
     *   <li>{@code { found: true, enabled: true }} — control is present and interactive</li>
     * </ul>
     *
     * <p>Used as a pre-check before the fingerprint / Claude path so that we can stop without
     * spending an LLM call when the last page is clearly signalled by a disabled Next button.
     */
    private static final String JS_NEXT_PAGE_AVAILABLE =
            """
            () => {
              // Patterns that identify a "next page" interactive element.
              const NEXT_TEXT_RE   = /^(next|>|›|→|chevron_right|keyboard_arrow_right)$/i;
              const NEXT_LABEL_RE  = /next\\s*(page)?/i;
              const NEXT_CLASS_RE  = /next[-_]?page|pagination[-_]?next|pager[-_]?next/i;

              function isVisibleEl(el) {
                if (!el) return false;
                const r  = el.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) return false;
                const st = window.getComputedStyle(el);
                return st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
              }

              function isDisabledEl(el) {
                if (el.disabled) return true;
                if (el.getAttribute('aria-disabled') === 'true') return true;
                // Check for a "disabled" CSS class (common in SPA component libraries).
                const cls = (el.className && typeof el.className === 'string') ? el.className : '';
                return /\\bdisabled\\b/i.test(cls);
              }

              function matchesNext(el) {
                const text      = (el.textContent || '').replace(/\\s+/g, ' ').trim();
                const ariaLabel = (el.getAttribute('aria-label') || '').trim();
                const title     = (el.getAttribute('title') || '').trim();
                const cls       = (el.className && typeof el.className === 'string') ? el.className : '';
                return NEXT_TEXT_RE.test(text)
                    || NEXT_LABEL_RE.test(ariaLabel)
                    || NEXT_LABEL_RE.test(title)
                    || NEXT_CLASS_RE.test(cls);
              }

              const candidates = Array.from(document.querySelectorAll(
                'button, a, [role="button"], [role="link"], [tabindex]'
              ));

              for (const el of candidates) {
                if (!isVisibleEl(el)) continue;
                if (!matchesNext(el)) continue;
                // Found a next-page-like control.
                const enabled = !isDisabledEl(el);
                return JSON.stringify({
                  found: true,
                  enabled,
                  label: (el.getAttribute('aria-label') || el.textContent || '').trim().substring(0, 60)
                });
              }
              return JSON.stringify({ found: false });
            }
            """;

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final BrowserSession browser;
    private final BedrockAnthropicClient bedrock;
    private TokenUsage accumulatedUsage = TokenUsage.ZERO;
    private int pagesScraped = 0;

    /** Tracks whether the detected container is a standard table or ARIA grid. */
    private String detectedTableType = "table";

    /**
     * CSS selector learned after the first successful Claude-assisted pagination click.
     * Populated only when the element carries an {@code aria-label} or other attribute
     * qualifier; {@code null} when the element has no useful attribute (e.g. a plain
     * {@code <a>Next</a>}).  Tried before the hardcoded selector list each page.
     */
    private String learnedSelector = null;

    /**
     * Fingerprint string of the next-page element, learned after the first successful
     * Claude-assisted click.  Works for elements that have no CSS-addressable attributes
     * (e.g. plain {@code <a>Next</a>}).
     *
     * <p>On pages 2..N the fingerprint is looked up via a fresh {@link BrowserSession#listInteractables()}
     * scrape — a cheap local JS call, no LLM.  When the fingerprint disappears (last page or
     * DOM change), Claude is called once to confirm (option B).
     */
    private String learnedFingerprintString = null;

    public AccountAggregator(BrowserSession browser, BedrockAnthropicClient bedrock) {
        this.browser = browser;
        this.bedrock = bedrock;
    }

    public TokenUsage accumulatedUsage() { return accumulatedUsage; }
    public int pagesScraped()            { return pagesScraped;     }

    // -------------------------------------------------------------------------
    // Phase 3 — Table detection
    // -------------------------------------------------------------------------

    /**
     * Detects the accounts table or ARIA grid on the current page.
     *
     * <p>Three-step flow:
     * <ol>
     *   <li><b>Step 3.1 — JS structure detection:</b> finds {@code selector}, {@code tableType},
     *       and {@code rowCount}.  JS-extracted headers are intentionally ignored because
     *       ARIA grids often expose hidden columns (checkbox, avatar) that pollute the header
     *       list and shift all data columns by one.</li>
     *   <li><b>Step 3.2 — Claude vision header extraction (always runs):</b> takes a viewport
     *       screenshot and asks Claude for the exact visible column names, skipping checkbox /
     *       avatar / action columns.  This is the authoritative source for headers.</li>
     *   <li><b>Step 3.3 — Combine:</b> {@code selector} + {@code tableType} from JS (or Claude
     *       when JS found nothing); {@code headers} always from Claude (JS headers used only as
     *       last-resort fallback if Claude parsing fails).</li>
     * </ol>
     */
    public TableDetectionResult detectTable() {

        // ── Step 3.1 — JS: find selector + tableType + rowCount ──────────────
        System.out.println("  [Phase 3.1] JS table/grid detection...");

        String jsSelector  = null;
        String jsTableType = null;
        List<String> jsHeaders = new ArrayList<>();
        List<String> jsPhysicalHeaderTexts = new ArrayList<>();
        boolean jsFound = false;

        try {
            // The list table is rendered client-side, so right after a replay's final navigation
            // the probe can either throw ("Execution context was destroyed") or return found=false
            // because the rows haven't painted yet. Retry on BOTH conditions, settling between
            // attempts, so replay detects the same table the live path sees a moment later.
            final int maxAttempts = 5;
            JSONObject result = new JSONObject().put("found", false);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    String json = (String) browser.page().evaluate(JS_DETECT_TABLE);
                    result = new JSONObject(json);
                } catch (Exception evalErr) {
                    boolean navRace = evalErr.getMessage() != null
                            && evalErr.getMessage().contains("Execution context was destroyed");
                    if (!navRace) {
                        throw evalErr;
                    }
                    result = new JSONObject().put("found", false);
                }

                boolean ready = result.optBoolean("found", false)
                        && (result.optInt("rowCount", 0) > 0
                            || !toStringList(result.optJSONArray("headers")).isEmpty());
                if (ready || attempt == maxAttempts) {
                    break;
                }
                System.out.printf(
                        "  [Phase 3.1] Table not rendered yet — waiting to settle (attempt %d/%d)...%n",
                        attempt, maxAttempts);
                waitForPageSettle();
            }
            if (result.optBoolean("found", false)) {
                jsSelector             = result.optString("selector", "table");
                jsTableType            = result.optString("type",     "table");
                jsHeaders              = toStringList(result.optJSONArray("headers"));
                jsPhysicalHeaderTexts  = toStringListPreserveBlanks(result.optJSONArray("physicalHeaderTexts"));
                int rowCount = result.optInt("rowCount", 0);
                System.out.printf("  [Phase 3.1] Found %s — selector='%s', dataRows=%d%n",
                        jsTableType, jsSelector, rowCount);
                if (rowCount > 0 || !jsHeaders.isEmpty()) {
                    jsFound = true;
                    detectedTableType = jsTableType;
                } else {
                    System.out.println("  [Phase 3.1] Container found but no data rows — will rely on Claude for structure too.");
                }
            } else {
                System.out.println("  [Phase 3.1] No table/grid found by JS — Claude will determine structure.");
            }
        } catch (Exception e) {
            System.err.println("  [Phase 3.1] JS error: " + e.getMessage() + " — Claude will determine structure.");
        }

        // ── Step 3.2 — Claude vision: always extract headers visually ─────────
        System.out.println("  [Phase 3.2] Asking Claude for visual column headers...");
        byte[] screenshot = browser.viewportScreenshotJpeg(85);

        String headerSystemPrompt =
                "You are a web table analyst.\n"
                + "Look at the screenshot of a web page showing a list of user accounts.\n"
                + "Identify the EXACT column header names as they appear visually in the table or grid.\n\n"
                + "Rules:\n"
                + "- Read headers exactly as shown on screen (same capitalization, spacing)\n"
                + "- Include ALL visible data columns (Name, Email, Status, Last sign in, etc.)\n"
                + "- Do NOT include checkbox columns, avatar/photo columns, or action button columns\n"
                + "- Do NOT include columns with no visible header text\n"
                + "- Return headers in LEFT to RIGHT order as they appear on screen\n"
                + "- Return ONLY a JSON array of strings — no markdown, no prose\n\n"
                + "Example output:\n"
                + "[\"Name\", \"Email\", \"Status\", \"Last sign in\", \"Email usage\"]";

        String headerUserPrompt =
                "What are the exact column header names visible in the accounts/users table?\n"
                + "Return ONLY a JSON array of strings.";

        List<String> claudeHeaders = new ArrayList<>();
        String rawHeaderResponse = null;

        InvokeResult headerResult = bedrock.invokeWithVision(headerSystemPrompt, headerUserPrompt, screenshot);
        accumulatedUsage = accumulatedUsage.add(headerResult.usage());
        rawHeaderResponse = headerResult.text();

        try {
            // Claude may return a bare array or an array wrapped in markdown fences
            String cleaned = rawHeaderResponse.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\s*", "").replaceAll("```\\s*$", "").trim();
            }
            // Accept either a JSON array directly, or a JSON object with a "headers" key
            if (cleaned.startsWith("[")) {
                claudeHeaders = toStringList(new JSONArray(cleaned));
            } else {
                JSONObject obj = new JSONObject(cleaned);
                claudeHeaders = toStringList(obj.optJSONArray("headers"));
            }
            System.out.printf("  [Phase 3.2] Claude headers: %s%n", claudeHeaders);
        } catch (Exception e) {
            System.err.println("  WARNING: Could not parse Claude headers — falling back to JS headers. Cause: " + e.getMessage());
            System.err.println("  [Phase 3.2] Raw response: " + rawHeaderResponse);
        }

        // ── Step 3.3 — Combine: selector from JS (or full Claude fallback) ───
        if (jsFound) {
            // Happy path: JS gave us structure, Claude gave us clean headers
            List<String> finalHeaders = claudeHeaders.isEmpty() ? jsHeaders : claudeHeaders;
            if (claudeHeaders.isEmpty() && !jsHeaders.isEmpty()) {
                System.out.println("  WARNING: Using JS headers as fallback — may be misaligned");
            }
            List<Integer> columnIndexMap = buildColumnIndexMap(finalHeaders, jsPhysicalHeaderTexts);
            System.out.printf("  [Phase 3] Final: selector='%s', type='%s', headers=%s%n",
                    jsSelector, jsTableType, finalHeaders);
            if (!columnIndexMap.isEmpty()) {
                System.out.printf("  [Phase 3] Column index map: %s  (physical header texts: %s)%n",
                        columnIndexMap, jsPhysicalHeaderTexts);
            }
            return new TableDetectionResult(jsSelector, finalHeaders, true, rawHeaderResponse, columnIndexMap);
        }

        // JS found nothing — ask Claude for the full structure (selector + tableType) too
        System.out.println("  [Phase 3.2b] JS found no table — asking Claude for selector + tableType...");
        String structureSystemPrompt =
                "You are a web page analyst. The page shows a list or table of user accounts. "
                + "Your task: identify the CSS selector for the container element and the table type.\n\n"
                + "Rules:\n"
                + "- If it uses standard <table>, return selector 'table' and tableType 'table'.\n"
                + "- If it uses an ARIA grid (role='grid'), return the grid's CSS selector and tableType 'aria-grid'.\n"
                + "Reply ONLY with valid JSON — no markdown, no prose:\n"
                + "{ \"selector\": \"<css>\", \"tableType\": \"table|aria-grid\" }";

        String structureUserPrompt =
                "Identify the CSS selector and type for the user/account table or grid. "
                + "Return ONLY the JSON object.";

        InvokeResult structureResult = bedrock.invokeWithVision(structureSystemPrompt, structureUserPrompt, screenshot);
        accumulatedUsage = accumulatedUsage.add(structureResult.usage());
        String rawStructureResponse = structureResult.text();

        try {
            JSONObject parsed = BedrockAnthropicClient.parseModelJson(rawStructureResponse);
            String selector  = parsed.optString("selector",  "table").trim();
            String tableType = parsed.optString("tableType", "table").trim();
            if (selector.isBlank()) selector = "table";
            detectedTableType = tableType;

            List<String> finalHeaders = claudeHeaders.isEmpty() ? jsHeaders : claudeHeaders;
            if (claudeHeaders.isEmpty() && !jsHeaders.isEmpty()) {
                System.out.println("  WARNING: Using JS headers as fallback — may be misaligned");
            }
            System.out.printf("  [Phase 3] Final (Claude structure): selector='%s', type='%s', headers=%s%n",
                    selector, tableType, finalHeaders);
            return new TableDetectionResult(selector, finalHeaders, false, rawStructureResponse, Collections.emptyList());
        } catch (Exception e) {
            System.err.println("  [Phase 3.2b] Could not parse Claude structure response: " + e.getMessage());
            System.err.println("  [Phase 3.2b] Raw: " + rawStructureResponse);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // JavaScript — total-count detection
    // -------------------------------------------------------------------------

    /**
     * Searches the full page text for common "N of M" / "M results" patterns.
     *
     * <p>Patterns tried (in order):
     * <ol>
     *   <li>{@code \d+\s*[-–]\s*\d+\s+of\s+(\d[\d,]*)}</li>
     *   <li>{@code of\s+(\d[\d,]*)\s+(results|users|accounts|records|entries|members|people)}</li>
     *   <li>{@code (\d[\d,]*)\s+(results|total|users|accounts|records|entries|members|people)}</li>
     * </ol>
     *
     * Returns the raw matched string plus the captured total, or null when nothing matches.
     */
    private static final String JS_DETECT_TOTAL_TEXT =
            """
            () => {
              const bodyText = document.body.innerText || '';
              const patterns = [
                { re: /\\b(\\d[\\d,]*)\\s*[-\\u2013]\\s*(\\d[\\d,]*)\\s+of\\s+(\\d[\\d,]*)\\b/i,   group: 3 },
                { re: /\\bof\\s+(\\d[\\d,]*)\\s+(results|users|accounts|records|entries|members|people)\\b/i, group: 1 },
                { re: /\\b(\\d[\\d,]*)\\s+(results|total|users|accounts|records|entries|members|people)\\b/i, group: 1 },
              ];
              for (const { re, group } of patterns) {
                const m = re.exec(bodyText);
                if (m) {
                  const raw = m[group].replace(/,/g, '');
                  const n = parseInt(raw, 10);
                  if (!isNaN(n) && n > 0) return JSON.stringify({ found: true, total: n, matched: m[0] });
                }
              }
              return JSON.stringify({ found: false });
            }
            """;

    // -------------------------------------------------------------------------
    // Phase 3b — Expected-total oracle
    // -------------------------------------------------------------------------

    /**
     * Attempts to detect the expected total account count displayed on the current page.
     *
     * <p>Step A — cheap JS regex scan of page text for patterns like
     * "1–50 of 324", "of 324 results", "324 users".
     *
     * <p>Step B — Claude vision fallback when JS finds nothing. Uses the same
     * {@link #accumulatedUsage()} accumulation pattern as other vision fallbacks in this class.
     *
     * @param table the detected table result (used only for logging context in Step B)
     * @return the parsed total, or {@code null} when the page displays no total indicator
     */
    public Integer detectExpectedTotal(TableDetectionResult table) {

        // Step A: JS regex — zero cost
        System.out.println("  [Phase 3b] Scanning page text for total-count indicator...");
        try {
            String json = (String) browser.page().evaluate(JS_DETECT_TOTAL_TEXT);
            JSONObject result = new JSONObject(json);
            if (result.optBoolean("found", false)) {
                int total = result.optInt("total", -1);
                if (total > 0) {
                    System.out.printf("  [Phase 3b] JS matched \"%s\" → total=%d%n",
                            result.optString("matched", ""), total);
                    return total;
                }
            }
        } catch (Exception e) {
            System.err.println("  [Phase 3b] JS scan failed: " + e.getMessage());
        }

        System.out.println("  [Phase 3b] No JS match — asking Claude to detect total from screenshot...");

        // Step B: Claude vision fallback
        byte[] screenshot = browser.viewportScreenshotJpeg(85);
        String systemPrompt =
                "You are a UI analyst. Look at the screenshot and find any text that shows "
                + "the TOTAL number of users/accounts/records in this list — for example: "
                + "'1-50 of 324 users', 'Showing 324 results', '324 total accounts'. "
                + "If you can find such a total count, return ONLY valid JSON: "
                + "{ \"found\": true, \"total\": <integer> }. "
                + "If no total count is visible, return: { \"found\": false }.";
        String userPrompt =
                "Is there a total record count displayed on this page? Return only JSON.";

        try {
            InvokeResult visionResult = bedrock.invokeWithVision(systemPrompt, userPrompt, screenshot);
            accumulatedUsage = accumulatedUsage.add(visionResult.usage());

            String cleaned = visionResult.text().trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\s*", "").replaceAll("```\\s*$", "").trim();
            }
            JSONObject parsed = new JSONObject(cleaned);
            if (parsed.optBoolean("found", false)) {
                int total = parsed.optInt("total", -1);
                if (total > 0) {
                    System.out.printf("  [Phase 3b] Claude detected total=%d%n", total);
                    return total;
                }
            }
            System.out.println("  [Phase 3b] Claude found no total count indicator");
        } catch (Exception e) {
            System.err.println("  [Phase 3b] Claude vision fallback failed: " + e.getMessage());
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Phase 4 — Pagination loop
    // -------------------------------------------------------------------------

    public List<Map<String, String>> paginationLoop(
            TableDetectionResult table,
            PaginationPattern pagination,
            int maxPages,
            Integer expectedTotal) throws InterruptedException {

        // Infinite-scroll UIs have no "next" button — use a scroll-and-stabilize strategy.
        if ("infinite_scroll".equalsIgnoreCase(pagination.type())) {
            List<Map<String, String>> allRows = infiniteScrollLoop(table, maxPages, expectedTotal);
            detectAndDropEmptyColumns(table.headers(), allRows);
            return allRows;
        }

        List<Map<String, String>> allRows = new ArrayList<>();
        // Tracks a fingerprint of every page seen so far.
        // Comparing only against the immediately preceding page misses wrap-arounds where
        // the last real page has a different row count than the page the Next button loops
        // back to (e.g. Google Admin: 7 pages of 30 + 1 page of 20, then Next wraps to
        // page 1 with 30 rows — size mismatch → old guard misses → 30 duplicate rows added).
        Set<String> seenPageFingerprints = new LinkedHashSet<>();
        pagesScraped = 0;

        while (pagesScraped < maxPages) {
            int currentPage = pagesScraped + 1;

            List<Map<String, String>> pageRows = scrapeCurrentPage(table, currentPage);
            pagesScraped++;

            // Build a fingerprint from the first few rows (cheap, size-independent).
            String pageFingerprint = buildPageFingerprint(pageRows);
            if (!pageFingerprint.isEmpty() && !seenPageFingerprints.add(pageFingerprint)) {
                // This page's content has been seen before — pagination has wrapped around.
                System.out.printf(
                        "WARNING: Duplicate page detected on page %d (fingerprint already seen). Stopping.%n",
                        currentPage);
                break;
            }

            allRows.addAll(pageRows);

            // Completeness early-exit: once the page's own total indicator (e.g. "1-50 of 92")
            // is satisfied by the unique rows collected, stop before spending another
            // advance + "is there a next page?" Claude call on a page we don't need.
            if (expectedTotal != null && expectedTotal > 0
                    && deduplicateRows(allRows).size() >= expectedTotal) {
                System.out.printf(
                        "  Reached expected total (%d rows) after page %d — stopping.%n",
                        expectedTotal, currentPage);
                break;
            }

            boolean advanced = advanceToNextPage(pagination, currentPage);
            if (!advanced) {
                System.out.printf("  No next page found after page %d. Stopping.%n", currentPage);
                break;
            }
        }

        if (pagesScraped >= maxPages) {
            System.out.printf("WARNING: Max pages (%d) reached.%n", maxPages);
        }

        // Safety net: deduplicate rows by composite key in case any duplicates survived the
        // page-fingerprint guard (e.g. partial page overlap across pagination strategies).
        allRows = deduplicateRows(allRows);

        // Drop columns that are empty in every scraped row (e.g. checkbox / avatar column).
        detectAndDropEmptyColumns(table.headers(), allRows);

        return allRows;
    }

    // -------------------------------------------------------------------------
    // Infinite-scroll strategy
    // -------------------------------------------------------------------------

    /**
     * Handles infinite-scroll pagination using an <strong>anchor-scroll</strong> strategy to
     * eliminate the boundary-skip losses seen with fixed-pixel scrolling on virtualized tables.
     *
     * <h3>Anchor-scroll strategy</h3>
     * <ol>
     *   <li>Scrape all rows currently rendered in the DOM; deduplicate against already-seen rows.</li>
     *   <li>Take the last collected row as the <em>anchor</em> and call {@link #scrollToAnchorRow}:
     *       this scrolls the anchor row to the <em>top</em> of the scroll container so the next
     *       render window starts exactly where we left off.</li>
     *   <li>The anchor row is re-scraped on the next step and silently deduped — guaranteeing
     *       zero boundary gaps regardless of scroll step size or renderer buffer width.</li>
     *   <li>When the anchor has already been evicted from the DOM (rare), falls back to the
     *       pixel-scroll advance.</li>
     * </ol>
     *
     * <h3>Stall handling (tail of the list)</h3>
     * <p>When a scrape yields no new rows, the loop waits with escalating backoff, giving the
     * server time to deliver the next batch. It uses a stricter patience threshold when
     * {@code expectedTotal} is known and we are still short of it ("patient mode").
     *
     * @param table         detection result for the accounts table
     * @param maxScrolls    fallback hard cap on scroll attempts when expectedTotal is unknown
     * @param expectedTotal total rows reported by the page UI, or {@code null} when unavailable
     */
    private List<Map<String, String>> infiniteScrollLoop(
            TableDetectionResult table, int maxScrolls, Integer expectedTotal)
            throws InterruptedException {

        final int SETTLE_MS          = 700;   // settle after each anchor/pixel scroll (ms)
        final int STABLE_POLL_MS     = 200;   // interval between render-stability polls (ms)
        final int STABLE_MAX_MS      = 3_000; // cap on the render-stability wait per step (ms)
        final int STALL_BASE_WAIT_MS = 1_500; // extra wait on first bottom-stall (ms)
        final int STALL_BACKOFF_MS   = 1_200; // incremental extra wait per subsequent stall (ms)
        // Consecutive bottom-stalls (at bottom, no scrollHeight growth) required to end a sweep.
        // Patient mode (still below expectedTotal) waits longer before conceding the sweep.
        final int BOTTOM_STALLS_NORMAL  = 3;
        final int BOTTOM_STALLS_PATIENT = 6;
        // Anchor N rows UP from the bottom of the rendered window so consecutive windows overlap.
        // Costs a few deduped re-scrapes per step; buys immunity to short / partial render windows.
        final int OVERLAP_MARGIN     = 5;
        // Completeness gate: repeat full top→bottom sweeps until we reach expectedTotal or a sweep
        // adds fewer than SWEEP_MIN_NEW rows (converged). The union grows monotonically via dedup.
        final int MAX_SWEEPS         = 6;
        final int SWEEP_MIN_NEW      = 3;
        // "At bottom" tolerance. The virtualized tail flaps by ~1 row (~16px) between rendering
        // 16 vs 17 rows; a tight threshold mis-classifies the true bottom on every other step and
        // caused an infinite advance⇄stall oscillation. A one-row margin absorbs the flap.
        final int BOTTOM_MARGIN_PX   = 80;
        // Tail load-trigger: number of small incremental down-scrolls used to coax the lazy loader
        // into fetching the next server page. A single abrupt jump often fails to re-fire the load
        // sentinel; human-like stepping is far more reliable at the end of the list.
        final int TAIL_NUDGE_STEPS   = 6;

        System.out.println("  [InfiniteScroll] Starting anchor-scroll loop "
                + "(stability-wait + overlap + multi-sweep)...");

        // Detect scroll container and its visible height for pixel-scroll fallback sizing
        ScrollTarget scrollTarget = detectScrollContainer(table);
        // Fallback step = 80% of clientHeight — used only when the anchor is evicted / advance stalls.
        int fallbackScrollPx = Math.max(200, (int) (scrollTarget.visibleHeightPx() * 0.8));
        // Small step (~¼ viewport) for the tail nudge sequence — fires scroll events gradually.
        int nudgeStepPx = Math.max(120, scrollTarget.visibleHeightPx() / 4);
        boolean isContainer = !scrollTarget.js().contains("window.scrollBy");
        System.out.printf("  [InfiniteScroll] Scroll target: %s  clientHeight=%dpx  fallbackStep=%dpx%n",
                isContainer ? "inner container" : "window",
                scrollTarget.visibleHeightPx(), fallbackScrollPx);

        // Jump-to-bottom JS for stall recovery — ensures the lazy-loader fires at the list end.
        String jumpToBottomJs = isContainer
                ? """
                  () => {
                    const c = document.querySelector('[data-scroll-container="true"]');
                    if (c) c.scrollTop = c.scrollHeight;
                  }
                  """
                : "() => window.scrollTo(0, document.body.scrollHeight)";

        // Wiggle-up JS for stall recovery — scrolls UP one viewport so the subsequent
        // scroll-to-bottom produces a real scroll event and re-enters the lazy-load
        // sentinel into view. A plain jump-to-bottom while already AT the bottom is a
        // no-op (scrollTop unchanged → no scroll event → IntersectionObserver never
        // re-fires → next batch never loads). The up→down wiggle forces that trigger.
        String wiggleUpJs = isContainer
                ? """
                  () => {
                    const c = document.querySelector('[data-scroll-container="true"]');
                    if (c) c.scrollTop = Math.max(0, c.scrollTop - c.clientHeight);
                  }
                  """
                : "() => window.scrollBy(0, -window.innerHeight)";

        // Scroll-to-top JS — resets the container to begin a fresh completeness sweep.
        String scrollToTopJs = isContainer
                ? """
                  () => {
                    const c = document.querySelector('[data-scroll-container="true"]');
                    if (c) c.scrollTop = 0;
                  }
                  """
                : "() => window.scrollTo(0, 0)";

        // Dynamic scroll cap derived from expectedTotal so large lists are never truncated
        // by the maxPages value, which was designed for button-pagination not scroll steps.
        // This is a PER-SWEEP cap; the bottom-stall guard is the real per-sweep exit condition.
        int dynamicCap = maxScrolls;
        if (expectedTotal != null && expectedTotal > 0) {
            dynamicCap = (int) Math.ceil(expectedTotal / 10.0) + 50;
            System.out.printf("  [InfiniteScroll] Per-sweep scroll cap: %d (from expectedTotal=%d)%n",
                    dynamicCap, expectedTotal);
        }

        Set<String> seenRowKeys = new LinkedHashSet<>();
        List<Map<String, String>> allRows = new ArrayList<>();
        int anchorHits = 0;        // successful anchor-scrolls (diagnostic)
        int pixelFallbacks = 0;    // pixel-scroll fallbacks (diagnostic)
        int totalScrolls = 0;      // scroll advances across all sweeps (diagnostic)
        long prevSweepMaxHeight = 0; // max scrollHeight from the previous sweep (data-cap signal)
        boolean reachedExpected = false;

        // ── Outer loop: completeness sweeps (top → bottom) ─────────────────────
        for (int sweep = 1; sweep <= MAX_SWEEPS && !reachedExpected; sweep++) {
            int unionBefore = allRows.size();

            // Sweeps after the first restart from the top so any rows missed in the tail on a
            // previous pass get another chance to render; dedup makes the re-traversal free.
            if (sweep > 1) {
                browser.page().evaluate(scrollToTopJs);
                Thread.sleep(SETTLE_MS);
            }
            System.out.printf("  [InfiniteScroll] ===== Sweep %d/%d  (union so far=%d) =====%n",
                    sweep, MAX_SWEEPS, unionBefore);

            int tailStalls       = 0;   // consecutive at-bottom rounds with NO content growth
            int scrollsThisSweep = 0;
            long maxHeight       = 0;   // max scrollHeight seen this sweep (content-growth signal)
            String anchorValue   = null;

            // ── Inner loop: scroll-position-driven descent ────────────────────
            // Advance is driven by scroll POSITION (not new-row count) so re-sweeps of
            // already-seen rows still progress downward to reach the tail.
            while (scrollsThisSweep < dynamicCap) {
                // 1. Wait for the render window to settle, then scrape & dedup into the union.
                waitForRenderStable(table, STABLE_POLL_MS, STABLE_MAX_MS);
                List<Map<String, String>> currentRows = scrapeCurrentPage(table, totalScrolls + 1);
                int newRowsAdded = 0;
                for (Map<String, String> row : currentRows) {
                    String rowKey = buildRowKey(row);
                    if (!rowKey.isEmpty() && seenRowKeys.add(rowKey)) {
                        allRows.add(row);
                        newRowsAdded++;
                    }
                }
                int uniqueCount = allRows.size();

                // 2. Choose the anchor from the rendered window, OVERLAP_MARGIN rows up from the
                //    bottom, so the next window re-renders with deliberate overlap (no boundary gap).
                if (!currentRows.isEmpty()) {
                    int idx = Math.max(0, currentRows.size() - 1 - OVERLAP_MARGIN);
                    anchorValue = getAnchorValue(currentRows.get(idx));
                }

                // 3. Read scroll geometry. "At bottom" uses a one-row tolerance so the tail render
                //    flap (~16px) is not misread as "more content below".
                ScrollMetrics m = readScrollMetrics();
                if (m.height() > maxHeight) maxHeight = m.height();
                boolean atBottom = m.top() + m.client() >= m.height() - BOTTOM_MARGIN_PX;
                System.out.printf(
                        "  [InfiniteScroll] sweep=%d step=%d  unique=%d  new=%d  DOM=%d  "
                                + "scroll=%d/%d  atBottom=%b  tailStalls=%d  anchor=%s%n",
                        sweep, scrollsThisSweep + 1, uniqueCount, newRowsAdded, currentRows.size(),
                        m.top(), m.height(), atBottom, tailStalls,
                        anchorValue != null && !anchorValue.isBlank() ? "set" : "none");

                // 4. Global completeness exit.
                if (expectedTotal != null && uniqueCount >= expectedTotal) {
                    System.out.printf("  [InfiniteScroll] Reached expected total (%d) — done.%n",
                            expectedTotal);
                    reachedExpected = true;
                    break;
                }

                if (atBottom) {
                    // At the end of currently-loaded content. Coax the lazy loader into fetching
                    // the next server page with a human-like nudge: re-arm the sentinel (scroll up
                    // one viewport), step DOWN gradually so each step fires a scroll event, then
                    // settle at the absolute bottom. A single abrupt jump often fails to re-fire the
                    // IntersectionObserver — incremental stepping is what reliably triggers a fetch.
                    long heightBefore = m.height();
                    long dwellMs      = STALL_BASE_WAIT_MS + (long) tailStalls * STALL_BACKOFF_MS;
                    System.out.printf(
                            "  [InfiniteScroll] At bottom — tail-load round %d, nudge + dwell %dms%n",
                            tailStalls + 1, dwellMs);

                    browser.page().evaluate(wiggleUpJs);
                    Thread.sleep(300);
                    for (int i = 0; i < TAIL_NUDGE_STEPS; i++) {
                        browser.page().evaluate(scrollTarget.js(), nudgeStepPx);
                        Thread.sleep(250);
                    }
                    browser.page().evaluate(jumpToBottomJs);
                    Thread.sleep(dwellMs);

                    ScrollMetrics after = readScrollMetrics();
                    if (after.height() > heightBefore + 20) {
                        // New content arrived (scrollHeight grew) — reset the streak, keep descending.
                        maxHeight = Math.max(maxHeight, after.height());
                        tailStalls = 0;
                        continue;
                    }

                    // No growth this round. tailStalls is NEVER reset by the descent branch below,
                    // so it accumulates cleanly here regardless of the tail render flap.
                    tailStalls++;
                    boolean belowExpected = expectedTotal != null && uniqueCount < expectedTotal;
                    int requiredStalls = belowExpected ? BOTTOM_STALLS_PATIENT : BOTTOM_STALLS_NORMAL;
                    if (tailStalls >= requiredStalls) {
                        System.out.printf(
                                "  [InfiniteScroll] Sweep %d exhausted at %d rows after %d tail-load "
                                        + "rounds (scrollHeight capped at %dpx)%s.%n",
                                sweep, uniqueCount, tailStalls, maxHeight,
                                belowExpected ? " — below expected=" + expectedTotal : "");
                        break;
                    }
                    // Stay pinned at the bottom and retry with a longer dwell next round.
                    continue;
                }

                // Not at the bottom — advance downward via anchor-scroll (zero boundary gap).
                // NOTE: tailStalls is intentionally NOT reset here. The previous version reset it in
                // this branch which — combined with the wiggle-up flipping atBottom off — produced
                // an infinite advance⇄stall oscillation at the tail that never met the exit guard.
                long topBefore = m.top();
                boolean anchored = anchorValue != null && scrollToAnchorRow(table, anchorValue);
                if (anchored) {
                    anchorHits++;
                } else {
                    browser.page().evaluate(scrollTarget.js(), fallbackScrollPx);
                    pixelFallbacks++;
                }
                Thread.sleep(SETTLE_MS);

                // Guard against a stuck advance (anchor mis-resolved to an earlier row, or the
                // container refused to move): if scrollTop did not progress, nudge by pixels.
                ScrollMetrics moved = readScrollMetrics();
                if (moved.top() <= topBefore + 1) {
                    browser.page().evaluate(scrollTarget.js(), fallbackScrollPx);
                    pixelFallbacks++;
                    Thread.sleep(SETTLE_MS);
                }
                scrollsThisSweep++;
                totalScrolls++;
            }

            int gained = allRows.size() - unionBefore;
            System.out.printf("  [InfiniteScroll] Sweep %d complete — gained=%d  union=%d  maxHeight=%dpx%n",
                    sweep, gained, allRows.size(), maxHeight);

            if (reachedExpected) break;

            // Did this sweep coax the grid into loading any new content? (scrollHeight growth)
            boolean grewThisSweep = maxHeight > prevSweepMaxHeight + 20;
            prevSweepMaxHeight = Math.max(prevSweepMaxHeight, maxHeight);

            // Converged — the last sweep added essentially nothing new.
            if (sweep > 1 && gained < SWEEP_MIN_NEW) {
                System.out.printf(
                        "  [InfiniteScroll] Converged — sweep %d added only %d new rows. Stopping.%n",
                        sweep, gained);
                break;
            }

            // Cap-aware early stop: the grid's data source is exhausted (no scrollHeight growth
            // vs. the previous sweep), so further top→bottom re-sweeps cannot exceed this count.
            // Sweep 1 always "grows" (from 0), so at least one verification sweep runs first to
            // recover any rows missed mid-descent; we stop as soon as a sweep yields no new data.
            if (!grewThisSweep) {
                System.out.printf(
                        "  [InfiniteScroll] Data source exhausted — scrollHeight stayed at %dpx this "
                                + "sweep (grid stopped fetching). Stopping after sweep %d.%n",
                        maxHeight, sweep);
                break;
            }
        }

        System.out.printf(
                "  [InfiniteScroll] Done — unique=%d  totalScrolls=%d  anchorHits=%d  pixelFallbacks=%d%s%n",
                allRows.size(), totalScrolls, anchorHits, pixelFallbacks,
                expectedTotal != null ? "  (expected=" + expectedTotal + ")" : "");
        pagesScraped = 1;
        return allRows;
    }

    /** Result of {@link #detectScrollContainer}: the scroll JS snippet and the visible height (px). */
    private record ScrollTarget(String js, int visibleHeightPx) {}

    /**
     * Live scroll geometry of the active scroll target (inner container or window).
     *
     * @param top    current scroll offset from the top (px)
     * @param height total scrollable content height (px)
     * @param client visible height of the scroll target (px)
     */
    private record ScrollMetrics(long top, long height, long client) {}

    /**
     * Reads the current scroll geometry of the active scroll target. Uses the marked inner
     * container when present (see {@link #detectScrollContainer}), otherwise the document
     * scrolling element (window-level scroll).
     *
     * @return current {@link ScrollMetrics}; a zeroed instance on any evaluation error
     */
    private ScrollMetrics readScrollMetrics() {
        String js = """
            () => {
              const c = document.querySelector('[data-scroll-container="true"]');
              if (c) return { top: c.scrollTop, height: c.scrollHeight, client: c.clientHeight };
              const de = document.scrollingElement || document.documentElement;
              return { top: de.scrollTop, height: de.scrollHeight, client: de.clientHeight };
            }
            """;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) browser.page().evaluate(js);
            long top    = ((Number) r.getOrDefault("top", 0)).longValue();
            long height = ((Number) r.getOrDefault("height", 0)).longValue();
            long client = ((Number) r.getOrDefault("client", 0)).longValue();
            return new ScrollMetrics(top, height, client);
        } catch (Exception e) {
            return new ScrollMetrics(0, 0, 0);
        }
    }

    /**
     * Counts the data rows currently rendered in the virtualized table's DOM (excluding header
     * rows). Cheap enough to poll for render-stability without a full scrape.
     *
     * @param table detection result containing the table selector
     * @return number of rendered data rows, or 0 on error
     */
    private int countRenderedRows(TableDetectionResult table) {
        String js = """
            (args) => {
              const sel = args.selector;
              const type = args.tableType || 'table';
              if (type === 'table') {
                const t = document.querySelector(sel);
                if (!t) return 0;
                const tb = t.querySelector('tbody');
                return tb ? tb.querySelectorAll('tr').length
                          : Math.max(0, t.querySelectorAll('tr').length - 1);
              }
              const root = document.querySelector(sel);
              if (!root) return 0;
              return Array.from(root.querySelectorAll('[role="row"]'))
                .filter(r => r.querySelector('[role="columnheader"]') === null).length;
            }
            """;
        Map<String, Object> args = new HashMap<>();
        args.put("selector",  table.selector());
        args.put("tableType", detectedTableType);
        try {
            Object r = browser.page().evaluate(js, args);
            return r instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Blocks until the rendered row count is stable (unchanged across two consecutive polls) or
     * {@code maxWaitMs} elapses. This eliminates the scrape/render race that caused partial
     * windows (e.g. DOM=16 instead of 31) to be scraped in the slower-loading tail of the list —
     * the primary source of tail row-loss.
     *
     * @param table     detection result containing the table selector
     * @param pollMs     interval between polls (ms)
     * @param maxWaitMs  hard cap on total wait (ms)
     */
    private void waitForRenderStable(TableDetectionResult table, int pollMs, int maxWaitMs)
            throws InterruptedException {
        int prev = -1;
        int stableStreak = 0;
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            int count = countRenderedRows(table);
            if (count > 0 && count == prev) {
                if (++stableStreak >= 1) return; // unchanged across two consecutive reads
            } else {
                stableStreak = 0;
            }
            prev = count;
            Thread.sleep(pollMs);
        }
    }

    /**
     * Detects whether the table scrolls within an inner container or at the window level.
     * Returns a {@link ScrollTarget} with:
     * <ul>
     *   <li>the JS snippet (accepts a pixel delta) to scroll the correct target</li>
     *   <li>the visible height of that scroll target, used to compute adaptive step sizes</li>
     * </ul>
     *
     * @param table detection result containing the table selector
     * @return scroll target descriptor; falls back to window scroll on any error
     */
    private ScrollTarget detectScrollContainer(TableDetectionResult table) {
        String detectionJs = """
            (sel) => {
              let el = document.querySelector(sel);
              if (!el) return { type: 'window', clientHeight: window.innerHeight };

              let current = el.parentElement;
              while (current && current !== document.body && current !== document.documentElement) {
                const style = getComputedStyle(current);
                const overflowY = style.overflowY;
                const overflow  = style.overflow;

                if ((overflowY === 'auto' || overflowY === 'scroll' ||
                     overflow  === 'auto' || overflow  === 'scroll')
                    && current.scrollHeight > current.clientHeight) {
                  current.setAttribute('data-scroll-container', 'true');
                  return { type: 'container', clientHeight: current.clientHeight };
                }
                current = current.parentElement;
              }
              return { type: 'window', clientHeight: window.innerHeight };
            }
            """;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) browser.page().evaluate(detectionJs, table.selector());
            String type         = result != null ? (String) result.getOrDefault("type", "window") : "window";
            int clientHeight    = result != null
                    ? ((Number) result.getOrDefault("clientHeight", 600)).intValue()
                    : 600;

            if ("container".equals(type)) {
                String scrollJs = """
                    (dy) => {
                      const container = document.querySelector('[data-scroll-container="true"]');
                      if (container) container.scrollTop += dy;
                    }
                    """;
                return new ScrollTarget(scrollJs, clientHeight);
            } else {
                return new ScrollTarget("(dy) => window.scrollBy(0, dy)", clientHeight);
            }
        } catch (Exception e) {
            System.err.println("  [InfiniteScroll] Container detection failed, using window scroll: "
                    + firstLine(e.getMessage()));
            return new ScrollTarget("(dy) => window.scrollBy(0, dy)", 600);
        }
    }

    /**
     * Builds a unique key for a row using all cell values joined together.
     * This is used for deduplication in the incremental infinite-scroll scraping.
     *
     * @param row the row map (column name → cell value)
     * @return composite key string, or empty if row is empty
     */
    private String buildRowKey(Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return String.join("\0", row.values());
    }

    /**
     * Returns the most unique cell value from {@code row} to serve as a scroll anchor.
     *
     * <p>Prefers email-shaped values (contain {@code @}) because they are globally unique in
     * a user directory. Falls back to the first non-blank value that is at least 4 characters
     * long (filtering out short status codes / flags). Returns an empty string when no
     * suitable value is found, signalling that anchor-scroll should not be attempted.
     *
     * @param row scraped row map
     * @return best unique cell value, or empty string
     */
    private String getAnchorValue(Map<String, String> row) {
        if (row == null) return "";
        for (String val : row.values()) {
            if (val != null && val.contains("@") && val.length() > 3) return val;
        }
        for (String val : row.values()) {
            if (val != null && val.length() >= 4) return val;
        }
        return "";
    }

    /**
     * Finds {@code anchorValue} in the currently-rendered rows of the table and scrolls it
     * to the top of the scroll container (or the window).
     *
     * <p>This is the core of the anchor-scroll strategy for virtualized tables:
     * <ol>
     *   <li>After every scrape, the last collected row becomes the anchor.</li>
     *   <li>We scroll that exact row to the top of the viewport.</li>
     *   <li>The next scrape re-captures the anchor row (handled by dedup) plus all new rows
     *       below it — guaranteeing zero boundary gaps regardless of scroll step size.</li>
     * </ol>
     *
     * @param table       detection result containing the table selector
     * @param anchorValue unique cell value to search for in the live DOM rows
     * @return {@code true} when the anchor was found and scrolled; {@code false} when the
     *         row has already been evicted (caller should fall back to pixel-scroll)
     */
    private boolean scrollToAnchorRow(TableDetectionResult table, String anchorValue) {
        if (anchorValue == null || anchorValue.isBlank()) return false;

        String js = """
            (args) => {
              const sel        = args.selector;
              const anchorVal  = (args.anchorValue || '').trim();
              const type       = args.tableType || 'table';

              if (!anchorVal) return false;

              // Collect all data rows from the current DOM snapshot
              let rows;
              if (type === 'table') {
                const t = document.querySelector(sel);
                if (!t) return false;
                const tbody = t.querySelector('tbody');
                rows = tbody
                  ? Array.from(tbody.querySelectorAll('tr'))
                  : Array.from(t.querySelectorAll('tr')).slice(1);
              } else {
                const root = document.querySelector(sel);
                if (!root) return false;
                rows = Array.from(root.querySelectorAll('[role="row"]'))
                  .filter(r => r.querySelector('[role="columnheader"]') === null);
              }

              if (rows.length === 0) return false;

              // Find the row whose text contains the anchor value
              const anchor = rows.find(row =>
                row.textContent.replace(/\\s+/g, ' ').trim().includes(anchorVal)
              );
              if (!anchor) return false;

              // Scroll the anchor to the TOP of its container so the next render window
              // starts exactly where we left off (guaranteed continuous coverage)
              const container = document.querySelector('[data-scroll-container="true"]');
              if (container) {
                const rowRect       = anchor.getBoundingClientRect();
                const containerRect = container.getBoundingClientRect();
                container.scrollTop += (rowRect.top - containerRect.top);
              } else {
                anchor.scrollIntoView({ block: 'start', behavior: 'instant' });
              }
              return true;
            }
            """;

        Map<String, Object> args = new HashMap<>();
        args.put("selector",    table.selector());
        args.put("anchorValue", anchorValue);
        args.put("tableType",   detectedTableType);

        try {
            Object result = browser.page().evaluate(js, args);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            System.err.println("  [InfiniteScroll] scrollToAnchorRow error: " + firstLine(e.getMessage()));
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fix 1 — Empty Column Detection + Drop.
     *
     * <p>After all pages have been scraped, any column whose value is blank in <em>every</em>
     * row is silently dropped.  This corrects the common ARIA-grid pattern where a leading
     * checkbox or avatar cell has no header, causing a one-position right-shift in the data.
     *
     * <p>The method mutates {@code headers} in-place (via {@link List#remove}) and removes the
     * corresponding key from each row map.
     *
     * @param headers the detected column headers (mutable)
     * @param allRows all scraped rows (each row is a mutable {@code LinkedHashMap})
     */
    private static void detectAndDropEmptyColumns(List<String> headers, List<Map<String, String>> allRows) {
        if (allRows.isEmpty() || headers.isEmpty()) return;

        List<String> toDrop = new ArrayList<>();
        for (String header : headers) {
            boolean alwaysEmpty = allRows.stream()
                    .allMatch(row -> row.getOrDefault(header, "").isBlank());
            if (alwaysEmpty) {
                toDrop.add(header);
            }
        }

        for (String col : toDrop) {
            System.out.printf("  [CleanCSV] Dropped empty column: '%s'%n", col);
            headers.remove(col);
            for (Map<String, String> row : allRows) {
                row.remove(col);
            }
        }

        if (!toDrop.isEmpty()) {
            System.out.printf("  [CleanCSV] Remaining columns: %s%n", headers);
        }
    }

    private List<Map<String, String>> scrapeCurrentPage(TableDetectionResult table, int pageNum) {
        Map<String, Object> args = new HashMap<>();
        args.put("selector",   table.selector());
        args.put("tableType",  detectedTableType);
        args.put("headers",    new ArrayList<>(table.headers()));
        args.put("allHeaders", new ArrayList<>(table.headers()));
        List<Integer> cm = table.columnIndexMap();
        args.put("columnMap",  cm.isEmpty() ? null : new ArrayList<>(cm));

        List<Map<String, String>> rows = new ArrayList<>();
        try {
            String json = (String) browser.page().evaluate(JS_SCRAPE_TABLE, args);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (String key : obj.keySet()) {
                    row.put(key, obj.optString(key, ""));
                }
                rows.add(row);
            }
        } catch (Exception e) {
            System.err.println("  [Scrape] Page " + pageNum + " error: " + e.getMessage());
        }
        System.out.printf("Scraped page %d: %d rows%n", pageNum, rows.size());
        return rows;
    }

    private boolean advanceToNextPage(PaginationPattern pagination, int currentPage)
            throws InterruptedException {

        // ── Pre-check: is any next-page control available on this page? ───────
        // This runs a broad pattern scan (text/aria-label/class) and tells us whether:
        //   - a next-page element exists and is ENABLED  → proceed
        //   - a next-page element exists but is DISABLED → last page, stop now
        //   - no next-page element at all                → stop (unless fingerprint known)
        // Doing this first avoids a Claude call when the last page is already signalled
        // by a disabled or absent Next button.
        try {
            String availJson = (String) browser.page().evaluate(JS_NEXT_PAGE_AVAILABLE);
            JSONObject avail = new JSONObject(availJson);
            if (avail.optBoolean("found", false) && !avail.optBoolean("enabled", true)) {
                // Button exists but is disabled — we are on the last page.
                System.out.printf("  [Next] Pagination control is present but disabled (\"%s\") — last page reached.%n",
                        avail.optString("label", "?"));
                return false;
            }
            if (!avail.optBoolean("found", false) && learnedFingerprintString == null) {
                // No next-page element found at all and we have no prior fingerprint knowledge.
                // Likely a single-page result or the last page with an invisible control.
                // Fall through to CSS / Claude rather than stopping hard — Claude will confirm.
                System.out.println("  [Next] No pagination control detected on page — will verify with CSS/Claude.");
            }
        } catch (Exception e) {
            System.err.println("  [Next] Pre-check error: " + e.getMessage() + " — continuing with CSS path.");
        }

        // ── Step 4.2: CSS selectors ───────────────────────────────────────────
        // Priority: learned CSS selector → video selectorHint → hardcoded heuristics.
        // learnedSelector is populated only when the element carries an aria-label or similar
        // attribute qualifier; most plain-text links fall through to the fingerprint path below.
        List<String> selectors = new ArrayList<>();
        if (learnedSelector != null) {
            selectors.add(learnedSelector);
        }
        String hint = pagination.selectorHint();
        if (hint != null && !hint.isBlank()) {
            selectors.add(hint);
        }
        selectors.addAll(Arrays.asList(NEXT_PAGE_SELECTORS));

        try {
            String json = (String) browser.page().evaluate(JS_NEXT_PAGE_CHECK, selectors);
            JSONObject result = new JSONObject(json);
            if (result.optBoolean("found", false)) {
                String selector = result.optString("selector");
                System.out.printf("  [Next] CSS match: %s%n", selector);
                System.out.printf("Navigating to page %d...%n", currentPage + 1);
                if (clickByCssSelector(selector)) {
                    settleAfterPageChange();
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("  [Next] CSS check error: " + e.getMessage());
        }

        // ── Step 4.2b: Fingerprint match (no LLM) ────────────────────────────
        // When CSS fails but we already know the element's fingerprintString from a previous
        // Claude-assisted click, re-scrape the DOM and look the element up by fingerprint.
        // This handles plain-text links (e.g. <a>Next</a>) that have no CSS-addressable
        // attributes.  listInteractables() is a local JS call — no LLM cost.
        if (learnedFingerprintString != null) {
            System.out.println("  [Next] CSS miss — trying learned fingerprint...");
            JSONArray freshElements = browser.listInteractables(false);
            String matchedId = findIdByFingerprintString(freshElements, learnedFingerprintString);
            if (matchedId != null) {
                System.out.printf("  [Next] Fingerprint match — navigating to page %d (no LLM)%n", currentPage + 1);
                // Check the click result: clickByStableId already verifies elementState (disabled/aria-disabled)
                // before clicking. If it fails the element is gone or disabled — pagination is done.
                JSONObject clickResult = browser.clickByStableId(matchedId);
                if (!clickResult.optBoolean("ok", false)) {
                    System.out.printf("  [Next] Fingerprint element not clickable (%s) — end of pagination.%n",
                            clickResult.optString("error", "?"));
                    return false;
                }
                settleAfterPageChange();
                return true;
            }
            // Fingerprint disappeared — likely the last page.  Ask Claude once to confirm
            // (option B) before stopping, in case the DOM shifted and there is still a next page.
            System.out.println("  [Next] Learned fingerprint not found — asking Claude to confirm end of pagination...");
            return askClaudeForNextPage(currentPage, freshElements);
        }

        // ── Step 4.3: No cached knowledge yet — ask Claude ───────────────────
        // After a successful click the element's fingerprintString (and CSS selector when
        // available) are cached so all subsequent pages skip this call.
        System.out.println("  [Next] No CSS match — asking Claude...");
        JSONArray elements = browser.listInteractables(false);
        return askClaudeForNextPage(currentPage, elements);
    }

    /**
     * Calls Claude vision to identify and click the next-page element.
     *
     * <p>On success, caches the element's {@code fingerprintString} (always) and a derived
     * CSS selector (when the element carries an {@code aria-label} or other attribute qualifier)
     * so that future pages can skip this call.
     *
     * @param currentPage 1-based index of the page just scraped
     * @param elements    fresh interactables list (already scraped by the caller)
     */
    private boolean askClaudeForNextPage(int currentPage, JSONArray elements)
            throws InterruptedException {

        java.util.List<byte[]> screenshots = browser.viewportScrollScreenshotsJpeg(4, 70);
        String elementSummary = buildElementSummary(elements);

        String systemPrompt =
                "You are a pagination detector. You are given one or more screenshots that are "
                + "vertical tiles from top to bottom of the full page, plus a list of ALL "
                + "interactive elements on the page (including those below the visible fold). "
                + "Determine if there is a NEXT PAGE button or link for the ACCOUNTS TABLE "
                + "(not login form buttons). "
                + "Reply ONLY with valid JSON: "
                + "{ \"hasNext\": <bool>, \"element_id\": <string or null>, \"reason\": \"<string>\" }"
                + " — use the exact element_id value shown in the interactable elements list.";
        String userPrompt =
                "Is there a next page button for the accounts/users table (not login)?\n\n"
                + "Interactable elements (full page, including below-fold):\n" + elementSummary
                + "\nReturn JSON only.";

        InvokeResult invokeResult = bedrock.invokeWithMultipleImages(systemPrompt, userPrompt, screenshots);
        accumulatedUsage = accumulatedUsage.add(invokeResult.usage());

        try {
            JSONObject parsed = BedrockAnthropicClient.parseModelJson(invokeResult.text());
            boolean hasNext = parsed.optBoolean("hasNext", false);
            String  reason  = parsed.optString("reason", "");
            System.out.printf("  [Next] Claude: hasNext=%b — %s%n", hasNext, reason);

            if (!hasNext) return false;

            Object elementIdObj = parsed.opt("element_id");
            if (elementIdObj == null || elementIdObj == JSONObject.NULL) {
                System.out.println("  [Next] Claude reported hasNext but no element_id — stopping.");
                return false;
            }

            String elementId;
            if (elementIdObj instanceof Number n) {
                // Fallback: some models still return a numeric index — accept it
                elementId = String.valueOf(n.intValue());
            } else {
                elementId = elementIdObj.toString().trim();
            }
            if (elementId.isBlank()) {
                System.out.println("  [Next] Claude returned invalid element_id — stopping.");
                return false;
            }

            // Cache the element's identity so future pages skip this LLM call.
            cacheLearnedElement(elements, elementId);

            System.out.printf("Navigating to page %d via element_id=%s...%n", currentPage + 1, elementId);
            browser.clickByStableId(elementId);
            settleAfterPageChange();
            return true;

        } catch (Exception e) {
            System.err.println("  [Next] Claude vision fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Caches the next-page element's identity after a successful Claude-assisted click.
     *
     * <ul>
     *   <li>{@code learnedFingerprintString} — always cached when available; works for any
     *       element type including plain-text links with no CSS-addressable attributes.</li>
     *   <li>{@code learnedSelector} — cached only when a stable, attribute-qualified CSS
     *       selector can be derived (e.g. {@code button[aria-label='Next']}); provides a
     *       lighter-weight check path that avoids even the DOM scrape cost.</li>
     * </ul>
     */
    private void cacheLearnedElement(JSONArray elements, String elementId) {
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null || !elementId.equals(el.optString("id"))) continue;

            // Fingerprint — universal fallback, works even when no CSS selector is derivable
            String fp = el.optString("fingerprintString", "").trim();
            if (!fp.isEmpty()) {
                if (learnedFingerprintString == null) {
                    System.out.printf("  [Next] Caching learned fingerprint for future pages%n");
                } else if (!fp.equals(learnedFingerprintString)) {
                    System.out.printf("  [Next] Updating learned fingerprint%n");
                }
                learnedFingerprintString = fp;
            }

            // CSS selector — only when the element has a stable attribute qualifier
            String derived = deriveSelectorFromElement(el);
            if (derived != null) {
                if (!derived.equals(learnedSelector)) {
                    System.out.printf("  [Next] %s learned CSS selector: %s%n",
                            learnedSelector == null ? "Caching" : "Updating", derived);
                    learnedSelector = derived;
                }
            }
            return;
        }
    }

    /**
     * Attempts to derive a stable, attribute-qualified CSS selector from a single element's
     * interactables metadata.  Returns {@code null} when no useful selector can be built
     * (e.g. a plain {@code <a>Next</a>} with no {@code aria-label}, {@code id}, or {@code name}).
     */
    private String deriveSelectorFromElement(JSONObject el) {
        // aria-label is the most stable attribute for pagination controls
        String ariaLabel = el.optString("ariaLabel", "").trim();
        if (!ariaLabel.isEmpty()) {
            String tag = el.optString("tag", "button");
            return tag + "[aria-label='" + ariaLabel.replace("'", "\\'") + "']";
        }

        // Use the element's pre-built fallbackSelectors, skipping bare/overly-generic ones
        JSONArray fallbacks = el.optJSONArray("fallbackSelectors");
        if (fallbacks != null) {
            for (int j = 0; j < fallbacks.length(); j++) {
                String sel = fallbacks.optString(j, "").trim();
                if (!sel.isEmpty() && sel.contains("[")) {
                    return sel;
                }
            }
        }
        return null;
    }

    /**
     * Finds an element in the interactables list whose {@code fingerprintString} matches
     * and returns its stable id, or {@code null} if not found.
     */
    private String findIdByFingerprintString(JSONArray elements, String fingerprintString) {
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            if (fingerprintString.equals(el.optString("fingerprintString", ""))) {
                String id = el.optString("id", "").trim();
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }

    private boolean clickByCssSelector(String selector) {
        try {
            browser.page().locator(selector).first().click();
            return true;
        } catch (Exception primary) {
            System.out.println("  [Next] Playwright click failed — trying JS click...");
            try {
                browser.page().evaluate(
                        "(sel) => { const el = document.querySelector(sel); if (el) el.click(); }",
                        selector);
                return true;
            } catch (Exception jsEx) {
                System.err.println("  [Next] JS click also failed: " + firstLine(jsEx.getMessage()));
                return false;
            }
        }
    }

    /**
     * Best-effort settle used when a JS probe hits a mid-navigation "context destroyed" race.
     * Sleeps briefly first so a just-fired (but not yet committed) navigation can start, then
     * waits for load states. Swallows all timeouts — callers retry the probe afterwards.
     */
    private void waitForPageSettle() {
        try {
            Thread.sleep(1_000);
            browser.page().waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(8_000));
            try {
                browser.page().waitForLoadState(LoadState.NETWORKIDLE,
                        new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(4_000));
            } catch (Exception ignored) {
                // networkidle is best-effort.
            }
            Thread.sleep(400);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Any Playwright timeout is fine — the caller retries the probe.
        }
    }

    private void settleAfterPageChange() throws InterruptedException {
        Thread.sleep(800);
        try {
            browser.page().waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(5_000));
        } catch (Exception ignored) {}
        Thread.sleep(400);
    }

    /**
     * Builds a short fingerprint from the first up to 3 rows of a page.
     * Size-independent — works even when the final real page and the wrap-around page have
     * different row counts (the original same-size-only guard missed this case).
     */
    private static String buildPageFingerprint(List<Map<String, String>> rows) {
        if (rows.isEmpty()) return "";
        int sampleSize = Math.min(3, rows.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sampleSize; i++) {
            sb.append(String.join("\0", rows.get(i).values())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Removes duplicate rows from {@code rows} using a composite key of all cell values,
     * preserving insertion order.  Logs the count of rows removed.
     *
     * <p>This is the last-resort safety net after the per-page fingerprint guard.  It handles
     * partial-page overlaps (e.g. last real page shares some rows with the wrap-around page)
     * that the page-level check would not catch.
     */
    private static List<Map<String, String>> deduplicateRows(List<Map<String, String>> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> deduped = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String key = String.join("\0", row.values());
            if (seen.add(key)) {
                deduped.add(row);
            }
        }
        int removed = rows.size() - deduped.size();
        if (removed > 0) {
            System.out.printf("  [CleanCSV] Removed %d duplicate row(s) (pagination wrap-around).%n", removed);
        }
        return deduped;
    }

    /**
     * Builds an ordered column-index map by matching each Claude-reported header against
     * the physical header cell texts captured from the DOM.
     *
     * <p>For each Claude header the algorithm:
     * <ol>
     *   <li>Tries an exact case-insensitive match against unclaimed physical cells.</li>
     *   <li>Falls back to {@link FingerprintMatcher#jaccardSimilarity} token overlap,
     *       accepting the best score above 0.2.</li>
     *   <li>Records {@code -1} for any header that remains unmatched (the JS scraper will
     *       emit an empty string for that cell, which {@code CleanCSV} will subsequently drop).</li>
     * </ol>
     *
     * <p>Returns an empty list when physical header texts are unavailable, signalling the
     * JS scraper to fall back to positional alignment with structural-cell filtering.
     *
     * @param claudeHeaders    ordered Claude-reported column names
     * @param physicalTexts    ordered physical header cell texts from the DOM (blank for structural cells)
     * @return ordered list of physical column indices, parallel to {@code claudeHeaders}
     */
    private List<Integer> buildColumnIndexMap(List<String> claudeHeaders, List<String> physicalTexts) {
        if (claudeHeaders.isEmpty() || physicalTexts.isEmpty()) return Collections.emptyList();

        boolean[] claimed = new boolean[physicalTexts.size()];
        List<Integer> map = new ArrayList<>(claudeHeaders.size());

        for (String header : claudeHeaders) {
            String normHeader = header.toLowerCase().replaceAll("\\s+", " ").trim();
            int bestIdx   = -1;
            double bestScore = 0.2; // minimum Jaccard threshold

            for (int i = 0; i < physicalTexts.size(); i++) {
                if (claimed[i]) continue;
                String phys = physicalTexts.get(i);
                if (phys.isBlank()) continue;

                if (normHeader.equals(phys.toLowerCase().replaceAll("\\s+", " ").trim())) {
                    bestIdx   = i;
                    bestScore = 1.0;
                    break;
                }
                double score = FingerprintMatcher.jaccardSimilarity(header, phys);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx   = i;
                }
            }

            if (bestIdx >= 0) {
                claimed[bestIdx] = true;
            } else {
                System.out.printf("  [Phase 3] WARNING: No physical column match for Claude header '%s'%n", header);
            }
            map.add(bestIdx);
        }

        return map;
    }

    private String buildElementSummary(JSONArray elements) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            String text = el.optString("text", "");
            if (text.length() > 80) text = text.substring(0, 80) + "…";
            sb.append('[').append(el.optString("id")).append("] ")
              .append(el.optString("tag")).append(" — ").append(text).append('\n');
        }
        return sb.toString();
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /**
     * Like {@link #toStringList} but preserves blank entries as empty strings, keeping each
     * element's list index equal to its original array position.  Required for
     * {@code physicalHeaderTexts}, where a blank entry at index 0 means "structural column at
     * position 0" — dropping it would shift every subsequent index by one and break the map.
     */
    private static List<String> toStringListPreserveBlanks(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.optString(i, "").trim());
        }
        return list;
    }

    private static String firstLine(String s) {
        if (s == null) return "(no message)";
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
    }
}
