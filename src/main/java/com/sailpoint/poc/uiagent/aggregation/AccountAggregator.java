package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.microsoft.playwright.options.LoadState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                const headerRow = best.querySelector('thead tr') || best.querySelector('tr');
                if (headerRow) {
                  headerRow.querySelectorAll('th, td').forEach(cell => {
                    const h = cell.textContent.replace(/\\s+/g, ' ').trim();
                    if (h) headers.push(h);
                  });
                }
                return JSON.stringify({
                  found: true, type: 'table', selector: 'table',
                  headers, rowCount: bestCount
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
     * {@code args}: {@code { selector, tableType, headers[], allHeaders[] }}
     *
     * <p>Cell cleaning pipeline (applied in order):
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
                  cells.forEach((cell, idx) => {
                    const key = (headers && headers[idx]) ? headers[idx] : ('col' + idx);
                    rowData[key] = cleanCell(cell.textContent);
                  });
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
                  cells.forEach((cell, idx) => {
                    const key = (headers && headers[idx]) ? headers[idx] : ('col' + idx);
                    rowData[key] = cleanCell(cell.textContent);
                  });
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

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final BrowserSession browser;
    private final BedrockAnthropicClient bedrock;
    private TokenUsage accumulatedUsage = TokenUsage.ZERO;
    private int pagesScraped = 0;

    /** Tracks whether the detected container is a standard table or ARIA grid. */
    private String detectedTableType = "table";

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
        boolean jsFound = false;

        try {
            String json = (String) browser.page().evaluate(JS_DETECT_TABLE);
            JSONObject result = new JSONObject(json);
            if (result.optBoolean("found", false)) {
                jsSelector  = result.optString("selector", "table");
                jsTableType = result.optString("type",     "table");
                jsHeaders   = toStringList(result.optJSONArray("headers"));
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
            System.out.printf("  [Phase 3] Final: selector='%s', type='%s', headers=%s%n",
                    jsSelector, jsTableType, finalHeaders);
            return new TableDetectionResult(jsSelector, finalHeaders, true, rawHeaderResponse);
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
            return new TableDetectionResult(selector, finalHeaders, false, rawStructureResponse);
        } catch (Exception e) {
            System.err.println("  [Phase 3.2b] Could not parse Claude structure response: " + e.getMessage());
            System.err.println("  [Phase 3.2b] Raw: " + rawStructureResponse);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4 — Pagination loop
    // -------------------------------------------------------------------------

    public List<Map<String, String>> paginationLoop(
            TableDetectionResult table,
            PaginationPattern pagination,
            int maxPages) throws InterruptedException {

        List<Map<String, String>> allRows = new ArrayList<>();
        List<Map<String, String>> previousPageRows = null;
        pagesScraped = 0;

        while (pagesScraped < maxPages) {
            int currentPage = pagesScraped + 1;

            List<Map<String, String>> pageRows = scrapeCurrentPage(table, currentPage);
            pagesScraped++;

            // Duplicate page guard
            if (previousPageRows != null
                    && !pageRows.isEmpty()
                    && pageRows.size() == previousPageRows.size()
                    && firstRowMatches(pageRows, previousPageRows)) {
                System.out.println("WARNING: Duplicate page detected. Stopping.");
                break;
            }

            allRows.addAll(pageRows);
            previousPageRows = pageRows;

            boolean advanced = advanceToNextPage(pagination, currentPage);
            if (!advanced) {
                System.out.printf("  No next page found after page %d. Stopping.%n", currentPage);
                break;
            }
        }

        if (pagesScraped >= maxPages) {
            System.out.printf("WARNING: Max pages (%d) reached.%n", maxPages);
        }

        // Fix 1: drop columns that are empty in every scraped row (e.g. checkbox / avatar column)
        detectAndDropEmptyColumns(table.headers(), allRows);

        return allRows;
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
        args.put("allHeaders", new ArrayList<>(table.headers()));  // Fix 3: passed for prefix-stripping

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

        List<String> selectors = new ArrayList<>(Arrays.asList(NEXT_PAGE_SELECTORS));
        String hint = pagination.selectorHint();
        if (hint != null && !hint.isBlank()) {
            // Put the hint first — it came directly from observing the video
            selectors.add(0, hint);
        }

        // Step 4.2 — CSS selectors
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

        // Step 4.3 — Claude vision fallback
        System.out.println("  [Next] No CSS match — asking Claude...");
        byte[] screenshot = browser.viewportScreenshotJpeg(70);

        JSONArray elements = browser.listInteractables();
        String elementSummary = buildElementSummary(elements);

        String systemPrompt =
                "You are a pagination detector. Analyse the screenshot and interactive elements. "
                + "Determine if there is a NEXT PAGE button or link for the ACCOUNTS TABLE "
                + "(not login form buttons). "
                + "Reply ONLY with valid JSON: "
                + "{ \"hasNext\": <bool>, \"element_id\": <string or null>, \"reason\": \"<string>\" }"
                + " — use the exact element_id value shown in the interactable elements list.";
        String userPrompt =
                "Is there a next page button for the accounts/users table (not login)?\n\n"
                + "Interactable elements:\n" + elementSummary
                + "\nReturn JSON only.";

        InvokeResult invokeResult = bedrock.invokeWithVision(systemPrompt, userPrompt, screenshot);
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

            System.out.printf("Navigating to page %d via element_id=%s...%n", currentPage + 1, elementId);
            browser.clickByStableId(elementId);
            settleAfterPageChange();
            return true;

        } catch (Exception e) {
            System.err.println("  [Next] Claude vision fallback failed: " + e.getMessage());
            return false;
        }
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

    private void settleAfterPageChange() throws InterruptedException {
        Thread.sleep(800);
        try {
            browser.page().waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(5_000));
        } catch (Exception ignored) {}
        Thread.sleep(400);
    }

    private boolean firstRowMatches(List<Map<String, String>> a, List<Map<String, String>> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.get(0).equals(b.get(0));
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

    private static String firstLine(String s) {
        if (s == null) return "(no message)";
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
    }
}
