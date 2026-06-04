package com.sailpoint.poc.uiagent.aggregation;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.config.NetworkAggregationConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Network-layer aggregation: intercepts raw JSON API responses from the Playwright browser,
 * scores them to identify user data, maps fields via a single Claude call, and paginates
 * using direct authenticated HTTP requests backed by the browser session's cookies.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Call {@link #startSniffing(Page)} BEFORE the browser navigates to the start URL.</li>
 *   <li>Let {@code AgentLoop} run normally — payloads are captured passively.</li>
 *   <li>Call {@link #stopSniffing()} AFTER {@code AgentLoop.run()} returns.</li>
 *   <li>Call {@link #aggregate(BrowserSession, BedrockAnthropicClient)} to identify, map, and
 *       paginate the best payload. Returns an {@link AggregationResult}.</li>
 * </ol>
 *
 * <p>{@link NetworkAggregator} has no dependency on {@link AccountAggregator}; both classes
 * are independently instantiable and testable (REQ-NA-NFR-6).
 */
public final class NetworkAggregator {

    // ── Wrapped-response key search order (REQ-NA-22) ─────────────────────────
    private static final List<String> WRAPPED_KEYS = List.of(
            "data", "users", "accounts", "members", "items",
            "results", "records", "list", "content");

    // ── URL patterns that indicate non-user-data endpoints (REQ-NA-11) ────────
    private static final List<String> NOISE_PATTERNS = List.of(
            "analytics", "tracking", ".css", ".js", ".png", ".svg",
            "telemetry", "metrics", "/log", "favicon", "health");

    // ── Canonical identity field names for Claude mapping (REQ-NA-17) ─────────
    private static final String CANONICAL_FIELDS =
            "name, email, username, status, department, manager, " +
            "firstName, lastName, userId, createdAt, lastLogin, roles";

    // ── Cursor-pagination response keys (REQ-NA-27) ───────────────────────────
    private static final List<String> CURSOR_KEYS = List.of(
            "nextPageToken", "nextCursor", "next", "cursor",
            "next_cursor", "continuation", "pageToken");

    // ── Internal result ───────────────────────────────────────────────────────

    /** Result returned by {@link #aggregate}. */
    public record AggregationResult(
            boolean hasData,
            List<Map<String, String>> rows,
            List<String> headers,
            int pagesCollected) {

        static AggregationResult noData() {
            return new AggregationResult(false, List.of(), List.of(), 0);
        }
    }

    // ── Internal types ────────────────────────────────────────────────────────

    private record ScoredPayload(String url, String body, int score) {}

    private enum PaginationStrategyType { CURSOR, PAGE_PARAM, SINGLE_PAGE }

    private record PaginationStrategy(
            PaginationStrategyType type,
            String paramName,
            String firstParamValue) {}

    // ── State ─────────────────────────────────────────────────────────────────

    /** URL → raw JSON body (first-capture wins, thread-safe). */
    private final Map<String, String> capturedPayloads = new ConcurrentHashMap<>();

    /**
     * URL → Authorization header value from the intercepted request.
     * Populated alongside {@link #capturedPayloads} so that cross-domain APIs
     * (e.g. ISC's api.cloud.sailpoint.com) can be paginated using the same
     * Bearer token the browser used — cookies alone won't work across domains.
     * Values are NEVER logged (REQ-NA-NFR-8).
     */
    private final Map<String, String> capturedAuthHeaders = new ConcurrentHashMap<>();

    /** Ordered capture list — used to prefer first-captured on score ties. */
    private final Queue<String> captureOrder = new ConcurrentLinkedQueue<>();

    /**
     * Query-parameter names that represent a record offset (SQL-style) rather than
     * a page number. These must be incremented by the {@code limit} value, not by 1.
     */
    private static final java.util.Set<String> OFFSET_PARAMS =
            java.util.Set.of("offset", "startindex", "start");

    private final AtomicBoolean sniffing = new AtomicBoolean(false);
    private final NetworkAggregationConfig config;

    private TokenUsage accumulatedUsage = TokenUsage.ZERO;
    private int pagesCollected = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public NetworkAggregator(NetworkAggregationConfig config) {
        this.config = config;
    }

    // ── Public accessors ──────────────────────────────────────────────────────

    /** Returns total Claude token usage accumulated during this aggregation run. */
    public TokenUsage accumulatedUsage() { return accumulatedUsage; }

    /** Returns the number of pages fetched (page 1 = captured payload, pages 2+ = HTTP). */
    public int pagesCollected() { return pagesCollected; }

    // ── Sniffing lifecycle ────────────────────────────────────────────────────

    /**
     * Registers a Playwright {@code onResponse} listener on {@code page}.
     * Must be called BEFORE the browser navigates to the start URL (REQ-NA-5).
     */
    public void startSniffing(Page page) {
        sniffing.set(true);
        System.out.println("[NetworkAggregator] Sniffing started");

        page.onResponse(response -> {
            if (!sniffing.get()) return;
            try {
                int status = response.status();
                if (status < 200 || status >= 300) return;

                String url = response.url();
                if (capturedPayloads.containsKey(url)) return; // dedup (REQ-NA-9)

                String contentType = response.headers().getOrDefault("content-type", "");
                if (!contentType.contains("application/json")) return;

                String body = response.text();
                if (body == null || body.length() < config.minBodyLength()) return;

                if (capturedPayloads.putIfAbsent(url, body) == null) {
                    captureOrder.add(url);

                    // Capture the Authorization header from the originating request so
                    // that cross-domain APIs (Bearer-token auth) can be paginated without
                    // relying solely on browser cookies (REQ-NA-NFR-8: value not logged).
                    try {
                        String auth = response.request().headers()
                                .getOrDefault("authorization", "");
                        if (!auth.isBlank()) {
                            capturedAuthHeaders.put(url, auth);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                // REQ-NA-8: never let listener exceptions propagate
            }
        });
    }

    /**
     * Stops capturing and logs how many JSON responses were captured (REQ-NA-10).
     * Must be called AFTER {@code AgentLoop.run()} and BEFORE {@link #aggregate}.
     */
    public void stopSniffing() {
        sniffing.set(false);
        System.out.println("[NetworkAggregator] Sniffing stopped — "
                + capturedPayloads.size() + " JSON responses captured");
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Identifies the best user-data payload, maps fields via Claude (one call), and
     * paginates via direct HTTP using the browser session's cookies.
     *
     * @return {@link AggregationResult#noData()} when no qualifying payload is found
     */
    public AggregationResult aggregate(BrowserSession browser, BedrockAnthropicClient bedrock) {

        ScoredPayload best = identifyUserDataPayload();
        if (best == null) {
            System.out.println("[NetworkAggregator] No qualifying payload found");
            return AggregationResult.noData();
        }

        System.out.println("[NetworkAggregator] Best payload: score=" + best.score()
                + "  url=" + truncate(best.url(), 120));

        // Claude field mapping — one call (REQ-NA-17, REQ-NA-20)
        Map<String, String> fieldMapping = mapFields(best.body(), bedrock);

        // Page 1: use already-captured body (REQ-NA-31)
        List<Map<String, String>> allRows = new ArrayList<>(extractRecords(best.body(), fieldMapping));
        pagesCollected = 1;

        // Detect pagination strategy (REQ-NA-25)
        PaginationStrategy strategy = detectPaginationStrategy(best.url(), best.body());
        System.out.println("[NetworkAggregator] Pagination strategy: " + strategy.type()
                + (strategy.paramName() != null ? "  param=" + strategy.paramName() : ""));

        // Resolve auth header for the best payload's URL (may be empty for cookie-auth apps)
        String authHeader = capturedAuthHeaders.getOrDefault(best.url(), "");

        // Paginate pages 2+ via direct HTTP (REQ-NA-26, REQ-NA-27)
        if (strategy.type() != PaginationStrategyType.SINGLE_PAGE) {
            paginateAndCollect(best.url(), best.body(), strategy, fieldMapping, allRows, browser, authHeader);
        }

        System.out.println("[NetworkAggregator] Collected " + allRows.size()
                + " rows across " + pagesCollected + " page(s)");

        List<String> headers = deriveHeaders(fieldMapping, allRows);
        return new AggregationResult(true, allRows, headers, pagesCollected);
    }

    // ── Payload identification ────────────────────────────────────────────────

    /**
     * Scores every captured payload and returns the highest-scoring one.
     * First-captured wins on ties (REQ-NA-13).
     * Returns {@code null} when no payload meets the qualifying threshold (REQ-NA-12).
     */
    private ScoredPayload identifyUserDataPayload() {
        List<String> urlKeywords = Arrays.asList(config.urlKeywords().split("\\|"));
        List<String> idFields    = Arrays.asList(config.identityFields().split("\\|"));

        ScoredPayload best = null;

        for (String url : captureOrder) {
            String body = capturedPayloads.get(url);
            if (body == null) continue;

            int score = scorePayload(url, body, urlKeywords, idFields);
            System.out.println("[NetworkAggregator] score=" + score + "  " + truncate(url, 100));

            if (score < config.qualifyingScoreThreshold()) continue;

            if (best == null || score > best.score()) {
                best = new ScoredPayload(url, body, score);
            }
        }
        return best;
    }

    /** Implements the scoring algorithm from REQ-NA-11. */
    private int scorePayload(String url, String body, List<String> urlKeywords, List<String> idFields) {
        String urlLower = url.toLowerCase();
        int score = 0;

        // Noise penalty first — early exit
        for (String noise : NOISE_PATTERNS) {
            if (urlLower.contains(noise)) return -100;
        }

        // Also penalise Google/OAuth batchexecute and sign-in RPCs — they are auth
        // infrastructure, not user-data endpoints, and their domain names can match
        // keyword lists ("accounts.google.com" → "accounts").
        if (urlLower.contains("batchexecute") || urlLower.contains("/signin/")
                || urlLower.contains("/sessions/") || urlLower.contains("authn_")) {
            return -100;
        }

        // URL keyword bonus (+30): match against PATH ONLY, not the full URL.
        // This prevents the domain "accounts.google.com" from scoring +30 because
        // "accounts" appears in the hostname rather than in a meaningful path segment.
        String urlPath = extractUrlPath(url).toLowerCase();
        for (String kw : urlKeywords) {
            if (urlPath.contains(kw)) { score += 30; break; }
        }

        // Body analysis
        try {
            String bodyTrimmed = body.trim();
            JSONArray array = null;

            if (bodyTrimmed.startsWith("[")) {
                array = new JSONArray(bodyTrimmed);
                score += 20; // root array bonus
            } else if (bodyTrimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(bodyTrimmed);
                array = findLargestArray(obj);
            }

            if (array != null) {
                // Identity field hint bonus (+10 per hit, max +40)
                int fieldBonus = 0;
                String firstElemStr = array.length() > 0 ? array.optJSONObject(0).toString().toLowerCase() : "";
                for (String f : idFields) {
                    if (firstElemStr.contains("\"" + f.toLowerCase() + "\"")) {
                        fieldBonus += 10;
                        if (fieldBonus >= 40) break;
                    }
                }
                score += fieldBonus;

                // Length bonus (+10)
                if (array.length() >= config.minRecords()) score += 10;
            }

        } catch (Exception e) {
            // Malformed JSON — leave score as-is
        }

        return score;
    }

    // ── Field mapping (Claude) ────────────────────────────────────────────────

    /**
     * Extracts a representative sample from the payload and asks Claude to produce
     * a canonical field mapping (REQ-NA-17). Returns an empty map on any failure.
     */
    private Map<String, String> mapFields(String body, BedrockAnthropicClient bedrock) {
        JSONObject sample = extractSampleObject(body);
        if (sample == null) {
            System.out.println("[NetworkAggregator] No sample object for field mapping — using raw keys");
            return Map.of();
        }

        String systemPrompt =
                "You are a field-mapping assistant. You will be given a sample JSON object from "
                + "a user/account directory API response. Map the raw JSON keys to canonical "
                + "identity fields. Return ONLY a flat JSON object with this exact shape: "
                + "{\"canonicalField\": \"rawJsonKey\", ...}. "
                + "Use ONLY these canonical field names: " + CANONICAL_FIELDS + ". "
                + "Omit any canonical field that has no matching raw key. "
                + "Do not include any explanation, markdown, or extra text — only the JSON object.";

        String userMessage = "Map the fields in this sample record:\n\n" + sample;

        try {
            BedrockAnthropicClient.InvokeResult result =
                    bedrock.invokeWithVision(systemPrompt, userMessage, null);
            accumulatedUsage = accumulatedUsage.add(result.usage());

            String text = result.text().trim();

            // Strip markdown fences if present
            if (text.startsWith("```")) {
                int firstNl = text.indexOf('\n');
                int lastFence = text.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) {
                    text = text.substring(firstNl + 1, lastFence).trim();
                }
            }

            JSONObject mapping = new JSONObject(text);
            Map<String, String> result2 = new LinkedHashMap<>();
            for (String key : mapping.keySet()) {
                result2.put(key, mapping.getString(key));
            }
            System.out.println("[NetworkAggregator] Field mapping from Claude: " + result2);
            return result2;

        } catch (Exception e) {
            System.out.println("[NetworkAggregator] Field mapping failed (" + e.getMessage()
                    + ") — using raw keys (REQ-NA-19)");
            return Map.of();
        }
    }

    // ── Record extraction ─────────────────────────────────────────────────────

    /**
     * Extracts records from a JSON response body, applying the canonical field mapping.
     * Handles root arrays and wrapped responses (REQ-NA-21, REQ-NA-22).
     */
    List<Map<String, String>> extractRecords(String body, Map<String, String> fieldMapping) {
        if (body == null || body.isBlank()) return List.of();

        JSONArray array = null;
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                // Check known wrapper keys in priority order
                for (String key : WRAPPED_KEYS) {
                    if (obj.has(key) && obj.get(key) instanceof JSONArray) {
                        array = obj.getJSONArray(key);
                        break;
                    }
                }
                // Fall back to largest array in object
                if (array == null) {
                    array = findLargestArray(obj);
                }
            }
        } catch (Exception e) {
            return List.of();
        }

        if (array == null || array.length() == 0) return List.of();

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject elem = array.optJSONObject(i);
            if (elem == null) continue;
            Map<String, String> row = buildRow(elem, fieldMapping);
            if (!isEmptyRow(row)) rows.add(row);
        }
        return rows;
    }

    /** Builds a single record map from a JSON object, applying field mapping (REQ-NA-23). */
    private Map<String, String> buildRow(JSONObject obj, Map<String, String> fieldMapping) {
        Map<String, String> row = new LinkedHashMap<>();

        if (fieldMapping.isEmpty()) {
            // No mapping: use raw keys
            for (String key : obj.keySet()) {
                Object val = obj.opt(key);
                row.put(key, val != null && !(val instanceof JSONObject) && !(val instanceof JSONArray)
                        ? String.valueOf(val) : "");
            }
        } else {
            // Apply canonical → raw mapping
            for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
                String canonical = entry.getKey();
                String raw = entry.getValue();
                Object val = obj.opt(raw);
                String strVal = (val != null && !(val instanceof JSONObject) && !(val instanceof JSONArray))
                        ? String.valueOf(val) : "";
                row.put(canonical, strVal);
            }
        }
        return row;
    }

    /** Returns true when all values in a row are blank (REQ-NA-24). */
    private boolean isEmptyRow(Map<String, String> row) {
        return row.values().stream().allMatch(v -> v == null || v.isBlank() || v.equals("null"));
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    /** Detects the pagination strategy from the captured URL and response body (REQ-NA-25). */
    private PaginationStrategy detectPaginationStrategy(String url, String body) {
        // Priority 1: cursor/token in response body (REQ-NA-25 row 1)
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                for (String cursorKey : CURSOR_KEYS) {
                    if (obj.has(cursorKey) && !obj.isNull(cursorKey)) {
                        String cursorVal = obj.optString(cursorKey, "").trim();
                        if (!cursorVal.isBlank()) {
                            return new PaginationStrategy(PaginationStrategyType.CURSOR, cursorKey, cursorVal);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Priority 2: known page/offset param in URL (REQ-NA-25 row 2)
        String[] paramNames = config.paginationParams().split("\\|");
        for (String param : paramNames) {
            String val = extractQueryParam(url, param);
            if (val != null) {
                return new PaginationStrategy(PaginationStrategyType.PAGE_PARAM, param, val);
            }
        }

        return new PaginationStrategy(PaginationStrategyType.SINGLE_PAGE, null, null);
    }

    /**
     * Fetches pages 2, 3, … via direct HTTP, appending all records to {@code allRows}.
     * Uses the captured {@code authHeader} (Bearer token) when present; falls back to
     * browser session cookies for cookie-authenticated applications (REQ-NA-26, REQ-NA-27).
     */
    private void paginateAndCollect(
            String baseUrl,
            String page1Body,
            PaginationStrategy strategy,
            Map<String, String> fieldMapping,
            List<Map<String, String>> allRows,
            BrowserSession browser,
            String authHeader) {

        String cookieHeader = extractCookieHeader(browser);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.httpConnectTimeoutMs()))
                .build();

        String firstRecordKey = allRows.isEmpty() ? null : rowFingerprint(allRows.get(0));
        String currentCursor = strategy.firstParamValue();
        String currentUrl    = baseUrl;

        while (pagesCollected < config.maxPages()) {
            // Build next-page URL (REQ-NA-26, REQ-NA-27)
            String nextUrl;
            if (strategy.type() == PaginationStrategyType.CURSOR) {
                nextUrl = replaceOrAppendParam(currentUrl, strategy.paramName(), currentCursor);
            } else {
                // PAGE_PARAM: increment.
                // For offset-style params (offset, startIndex, start) the step equals the
                // limit value from the URL — e.g. limit=50,offset=0 → offset=50 → offset=100.
                // For page-number params (page, pageToken) the step is always 1.
                String paramLower = strategy.paramName().toLowerCase();
                String currentVal = extractQueryParam(currentUrl, strategy.paramName());
                long currentNum   = tryParsePageNum(currentVal);
                long step;
                if (OFFSET_PARAMS.contains(paramLower)) {
                    String limitStr = extractQueryParam(currentUrl, "limit");
                    step = limitStr != null ? tryParsePageNum(limitStr) : 1;
                    if (step <= 0) step = 1;
                } else {
                    step = 1;
                }
                long nextVal = currentNum + step;
                nextUrl = replaceOrAppendParam(currentUrl, strategy.paramName(), String.valueOf(nextVal));
            }

            String responseBody = fetchPage(nextUrl, cookieHeader, authHeader, http);
            if (responseBody == null) {
                System.out.println("[NetworkAggregator] HTTP fetch returned null — stopping pagination");
                break;
            }

            List<Map<String, String>> pageRows = extractRecords(responseBody, fieldMapping);

            // Empty page guard (REQ-NA-30)
            if (pageRows.isEmpty()) {
                System.out.println("[NetworkAggregator] Empty page — stopping pagination");
                break;
            }

            // Duplicate page guard (REQ-NA-29)
            String newFirstKey = rowFingerprint(pageRows.get(0));
            if (firstRecordKey != null && firstRecordKey.equals(newFirstKey)) {
                System.out.println("[NetworkAggregator] Duplicate first record — stopping pagination");
                break;
            }

            allRows.addAll(pageRows);
            pagesCollected++;

            if (strategy.type() == PaginationStrategyType.CURSOR) {
                // Advance cursor or stop
                try {
                    String trimmed = responseBody.trim();
                    if (!trimmed.startsWith("{")) break;
                    JSONObject obj = new JSONObject(trimmed);
                    String next = obj.optString(strategy.paramName(), "").trim();
                    if (next.isBlank() || next.equals("null")) {
                        System.out.println("[NetworkAggregator] Cursor exhausted — stopping pagination");
                        break;
                    }
                    currentCursor = next;
                    currentUrl = nextUrl;
                } catch (Exception e) {
                    break;
                }
            } else {
                currentUrl = nextUrl;
            }
        }

        if (pagesCollected >= config.maxPages()) {
            System.out.println("[NetworkAggregator] Max pages (" + config.maxPages() + ") reached");
        }
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    /**
     * Fetches a single page via direct HTTP.
     * Prefers {@code authHeader} (Bearer token) when present; also sends cookies
     * as a fallback for cookie-authenticated apps. Never logs credential values.
     * Returns {@code null} on non-2xx status or any exception (REQ-NA-35, REQ-NA-36).
     */
    private String fetchPage(String url, String cookieHeader, String authHeader, HttpClient http) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(config.httpRequestTimeoutMs()))
                    .GET()
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest");

            // Bearer token auth (cross-domain APIs like ISC api.cloud.sailpoint.com)
            if (authHeader != null && !authHeader.isBlank()) {
                req.header("Authorization", authHeader);
            }

            // Cookie auth fallback (same-domain session-cookie apps)
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                req.header("Cookie", cookieHeader);
            }

            HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status < 200 || status >= 300) {
                System.out.println("[NetworkAggregator] Non-2xx HTTP " + status + " for " + truncate(url, 100));
                return null;
            }
            return response.body();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[NetworkAggregator] HTTP request interrupted for " + truncate(url, 100));
            return null;
        } catch (Exception e) {
            System.out.println("[NetworkAggregator] HTTP request failed: " + e.getMessage()
                    + "  url=" + truncate(url, 100));
            return null;
        }
    }

    /**
     * Extracts all browser session cookies and assembles a {@code Cookie:} header value.
     * Cookie values are NEVER logged (REQ-NA-NFR-8).
     */
    private String extractCookieHeader(BrowserSession browser) {
        try {
            List<Cookie> cookies = browser.page().context().cookies();
            return cookies.stream()
                    .map(c -> URLEncoder.encode(c.name, StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(c.value, StandardCharsets.UTF_8))
                    .collect(Collectors.joining("; "));
        } catch (Exception e) {
            System.out.println("[NetworkAggregator] Cookie extraction failed: " + e.getClass().getSimpleName());
            return "";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Finds the largest JSONArray among the values of {@code obj}. */
    private JSONArray findLargestArray(JSONObject obj) {
        JSONArray best = null;
        for (String key : obj.keySet()) {
            Object val = obj.opt(key);
            if (val instanceof JSONArray arr) {
                if (best == null || arr.length() > best.length()) {
                    best = arr;
                }
            }
        }
        return best;
    }

    /**
     * Extracts a representative sample object for field mapping.
     * Prefers the first element of a root array or the first element of the largest nested array.
     */
    private JSONObject extractSampleObject(String body) {
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                return arr.length() > 0 ? arr.optJSONObject(0) : null;
            }
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                // Check known wrapper keys first
                for (String key : WRAPPED_KEYS) {
                    if (obj.has(key) && obj.get(key) instanceof JSONArray arr && arr.length() > 0) {
                        return arr.optJSONObject(0);
                    }
                }
                JSONArray largest = findLargestArray(obj);
                if (largest != null && largest.length() > 0) {
                    return largest.optJSONObject(0);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Extracts a query parameter value from a URL. Returns {@code null} if not found. */
    private String extractQueryParam(String url, String paramName) {
        if (url == null || paramName == null) return null;
        String query = "";
        try {
            int q = url.indexOf('?');
            if (q < 0) return null;
            query = url.substring(q + 1);
        } catch (Exception e) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length >= 1 && kv[0].equalsIgnoreCase(paramName)) {
                return kv.length == 2 ? kv[1] : "";
            }
        }
        return null;
    }

    /** Replaces an existing query param in {@code url} or appends it if absent. */
    private String replaceOrAppendParam(String url, String paramName, String paramValue) {
        int q = url.indexOf('?');
        if (q < 0) {
            return url + "?" + paramName + "=" + encode(paramValue);
        }
        String base  = url.substring(0, q);
        String query = url.substring(q + 1);
        boolean replaced = false;
        StringBuilder sb = new StringBuilder();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (sb.length() > 0) sb.append('&');
            if (kv[0].equalsIgnoreCase(paramName)) {
                sb.append(paramName).append('=').append(encode(paramValue));
                replaced = true;
            } else {
                sb.append(pair);
            }
        }
        if (!replaced) {
            if (sb.length() > 0) sb.append('&');
            sb.append(paramName).append('=').append(encode(paramValue));
        }
        return base + "?" + sb;
    }

    private long tryParsePageNum(String val) {
        if (val == null || val.isBlank()) return 0;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0; }
    }

    private String rowFingerprint(Map<String, String> row) {
        return row.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .limit(3)
                .collect(Collectors.joining("|"));
    }

    private List<String> deriveHeaders(Map<String, String> fieldMapping, List<Map<String, String>> rows) {
        if (!fieldMapping.isEmpty()) {
            return new ArrayList<>(fieldMapping.keySet());
        }
        if (!rows.isEmpty()) {
            return new ArrayList<>(rows.get(0).keySet());
        }
        return List.of();
    }

    private String encode(String val) {
        if (val == null) return "";
        try { return URLEncoder.encode(val, StandardCharsets.UTF_8); }
        catch (Exception e) { return val; }
    }

    /**
     * Extracts just the path component from a URL so that keyword scoring is not
     * accidentally triggered by domain-name substrings
     * (e.g. "accounts.google.com" contains "accounts" but is not a user-data endpoint).
     */
    private static String extractUrlPath(String url) {
        if (url == null) return "";
        try {
            // Strip scheme + authority: find the third slash after "://"
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;
            int pathStart = url.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) return "/";
            int queryStart = url.indexOf('?', pathStart);
            return queryStart < 0 ? url.substring(pathStart) : url.substring(pathStart, queryStart);
        } catch (Exception e) {
            return url;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
