package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.AccountAggregator;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.aggregation.TableDetectionResult;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * POST /api/aggregation/run  — starts the full aggregation pipeline in a background thread.
 * POST /api/aggregation/stop — interrupts it (inner {@link StopHandler}).
 *
 * <h3>Request body (application/json)</h3>
 * <pre>
 * {
 *   "goalLine":  "enter \"user@example.com\" in the Email field, then click Next...",
 *   "url":       "https://admin.google.com/ac/users",
 *   "paginationPattern": {
 *     "type":         "next_button",
 *     "description":  "...",
 *     "selectorHint": "button[aria-label='Next']"
 *   }
 * }
 * </pre>
 *
 * <h3>Pipeline executed in background thread</h3>
 * <ol>
 *   <li>browser.navigate(url)</li>
 *   <li>AgentLoop.run() — navigate to list page</li>
 *   <li>AccountAggregator.detectTable()</li>
 *   <li>AccountAggregator.paginationLoop()</li>
 *   <li>writeCsv() → {@code ./output/accounts_<ts>.csv}</li>
 *   <li>Store preview + stats in {@link AggregationServerState}</li>
 *   <li>Push {@code AGGREGATION_DONE:<rows>:<path>} to SSE</li>
 * </ol>
 */
public final class AggregationRunHandler implements HttpHandler {

    private final AggregationServerState state;
    private volatile PrintStream originalOut;

    public AggregationRunHandler(AggregationServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        if (state.agentRunning.get()) {
            sendJson(ex, 409, "{\"error\":\"Aggregation already running\"}");
            return;
        }

        // ── Parse JSON body ────────────────────────────────────────────────────
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();

        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }

        String goalLine = req.optString("goalLine", "").trim();
        String url      = req.optString("url", "").trim();

        if (goalLine.isBlank()) {
            sendJson(ex, 400, "{\"error\":\"Missing goalLine\"}");
            return;
        }
        if (url.isBlank()) {
            sendJson(ex, 400, "{\"error\":\"Missing url\"}");
            return;
        }

        JSONObject ppJson = req.optJSONObject("paginationPattern");
        PaginationPattern pagination;
        if (ppJson != null) {
            pagination = new PaginationPattern(
                    ppJson.optString("type",         "unknown").trim().toLowerCase(),
                    ppJson.optString("description",  "").trim(),
                    ppJson.optString("selectorHint", "").trim());
        } else {
            pagination = new PaginationPattern("unknown", "", "");
        }

        state.agentRunning.set(true);
        state.logQueue.offer("STATUS:aggregating");

        // ── Capture System.out → SSE log queue ────────────────────────────────
        originalOut = System.out;
        RunHandler.LogCapturingPrintStream capturer =
                new RunHandler.LogCapturingPrintStream(originalOut, state.logQueue);
        System.setOut(capturer);

        final String            finalUrl        = url;
        final String            finalGoal       = goalLine;
        final PaginationPattern finalPagination = pagination;

        Thread agent = new Thread(() -> {
            try {
                PocConfig config = new PocConfig();

                try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                             config.awsRegion(), config.awsProfile(), config.bedrockModelId(),
                             config.maxTokens(), config.temperature());
                     BrowserSession browser = new BrowserSession(
                             config.browserHeadless(),
                             config.browserSlowMoMs(),
                             config.browserViewportWidth(),
                             config.browserViewportHeight(),
                             config.browserStartMaximized(),
                             config.browserFullscreenViewportWidth(),
                             config.browserFullscreenViewportHeight(),
                             config.actionTimeoutClickMs(),
                             config.actionTimeoutTypeMs(),
                             config.actionTimeoutNavigateMs(),
                             config.interActionDelayMs());
                     ActionLogger actionLogger = new ActionLogger(config.agentLogFile())) {

                    // Phase 2 — Navigate to start URL
                    state.logQueue.offer("LOG:INFO:Navigating to " + finalUrl + "...");
                    browser.navigate(finalUrl);

                    // Phase 2 continued — Run AgentLoop to reach list page
                    state.logQueue.offer("LOG:INFO:Running AgentLoop to navigate to list page...");
                    AgentLoop loop = new AgentLoop(
                            bedrock, browser, actionLogger,
                            config.agentMaxSteps(), finalGoal,
                            config.agentNoProgressLimit())
                            .setMultiViewportMaxFrames(config.agentMultiViewportMaxFrames());
                    TokenUsage loopUsage = loop.run();

                    // Phase 3 — Detect table
                    state.logQueue.offer("LOG:INFO:Detecting accounts table...");
                    AccountAggregator aggregator = new AccountAggregator(browser, bedrock);
                    TableDetectionResult tableResult = aggregator.detectTable();

                    if (tableResult == null) {
                        state.logQueue.offer("LOG:ERROR:No table found. Cannot aggregate accounts.");
                        state.logQueue.offer("STATUS:ready");
                        state.logQueue.offer("DONE:1");
                        return;
                    }

                    state.logQueue.offer("LOG:INFO:Table detected: " + tableResult.selector());
                    state.logQueue.offer("LOG:INFO:Columns: "
                            + String.join(", ", tableResult.headers()));

                    // Phase 4 — Pagination loop
                    int maxPages = config.aggregationMaxPages();
                    List<Map<String, String>> allRows =
                            aggregator.paginationLoop(tableResult, finalPagination, maxPages);
                    int pagesScraped = aggregator.pagesScraped();
                    // Combine AgentLoop navigation tokens + AccountAggregator scraping tokens
                    TokenUsage usage = loopUsage.add(aggregator.accumulatedUsage());

                    state.logQueue.offer("LOG:INFO:Scraped " + pagesScraped
                            + " page(s), " + allRows.size() + " total rows");

                    // Phase 5 — Write CSV
                    List<String> headers = resolveHeaders(tableResult.headers(), allRows);
                    String csvPath = null;
                    try {
                        csvPath = writeCsv(allRows, headers, config.aggregationOutputDir());
                        state.logQueue.offer("LOG:SUCCESS:CSV written to " + csvPath);
                    } catch (IOException e) {
                        state.logQueue.offer("LOG:ERROR:CSV write failed: " + e.getMessage());
                    }

                    // Store preview + stats in shared state
                    storePreview(allRows, headers, pagesScraped, usage, csvPath);

                    String donePath = csvPath != null ? csvPath : "";
                    state.logQueue.offer("AGGREGATION_DONE:" + allRows.size() + ":" + donePath);
                    state.logQueue.offer("STATUS:ready");
                    state.logQueue.offer("DONE:0");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.logQueue.offer("LOG:WARNING:Aggregation interrupted by user");
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } catch (Exception e) {
                state.logQueue.offer("LOG:ERROR:" + e.getMessage());
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } finally {
                restoreOut();
                state.agentRunning.set(false);
            }
        }, "aggregation-loop");

        agent.setDaemon(true);
        state.agentThread.set(agent);
        agent.start();

        sendJson(ex, 200, "{\"started\":true}");
    }

    // ── Preview + Stats storage ───────────────────────────────────────────────

    private void storePreview(
            List<Map<String, String>> allRows,
            List<String> headers,
            int pagesScraped,
            TokenUsage usage,
            String csvPath) {

        // Build previewRows JSON (up to 10 rows)
        List<Map<String, String>> preview = allRows.size() > 10
                ? allRows.subList(0, 10) : allRows;

        StringBuilder previewSb = new StringBuilder("[");
        for (int i = 0; i < preview.size(); i++) {
            if (i > 0) previewSb.append(",");
            previewSb.append("{");
            Map<String, String> row = preview.get(i);
            boolean first = true;
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (!first) previewSb.append(",");
                previewSb.append(quoted(entry.getKey())).append(":").append(quoted(entry.getValue()));
                first = false;
            }
            previewSb.append("}");
        }
        previewSb.append("]");
        state.lastPreviewJson.set(previewSb.toString());

        // Build stats JSON
        StringBuilder statsSb = new StringBuilder("{");
        statsSb.append("\"totalRows\":").append(allRows.size());
        statsSb.append(",\"pagesScraped\":").append(pagesScraped);
        statsSb.append(",\"columns\":[");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) statsSb.append(",");
            statsSb.append(quoted(headers.get(i)));
        }
        statsSb.append("]");
        statsSb.append(",\"inputTokens\":").append(usage.inputTokens());
        statsSb.append(",\"outputTokens\":").append(usage.outputTokens());
        statsSb.append(",\"costUsd\":").append(usage.totalCostUsd());
        statsSb.append("}");
        state.lastStatsJson.set(statsSb.toString());

        if (csvPath != null) state.lastCsvPath.set(csvPath);
    }

    // ── CSV write ─────────────────────────────────────────────────────────────

    private static String writeCsv(
            List<Map<String, String>> rows,
            List<String> headers,
            String outputDirStr) throws IOException {

        Path outputDir = Path.of(outputDirStr);
        Files.createDirectories(outputDir);

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path filePath = outputDir.resolve("accounts_" + timestamp + ".csv");

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            writer.write(headers.stream()
                    .map(AggregationRunHandler::csvEscape)
                    .collect(Collectors.joining(",")));
            writer.newLine();

            for (Map<String, String> row : rows) {
                writer.write(headers.stream()
                        .map(h -> csvEscape(row.getOrDefault(h, "")))
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
        }

        return filePath.toAbsolutePath().toString();
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static List<String> resolveHeaders(
            List<String> detected, List<Map<String, String>> rows) {
        if (detected != null && !detected.isEmpty()) return detected;
        if (!rows.isEmpty()) return new ArrayList<>(rows.get(0).keySet());
        return List.of();
    }

    // ── Restore System.out ────────────────────────────────────────────────────

    private void restoreOut() {
        if (originalOut != null) {
            System.setOut(originalOut);
            originalOut = null;
        }
    }

    // ── StopHandler ───────────────────────────────────────────────────────────

    public static final class StopHandler implements HttpHandler {

        private final AggregationServerState state;

        public StopHandler(AggregationServerState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            Thread t = state.agentThread.get();
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
            state.agentRunning.set(false);
            state.logQueue.offer("LOG:WARNING:Aggregation stopped by user");
            state.logQueue.offer("STATUS:ready");
            sendJson(ex, 200, "{\"stopped\":true}");
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String quoted(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
