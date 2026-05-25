package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.pipeline.AgentPipeline;
import com.sailpoint.poc.uiagent.pipeline.PipelineConfig;
import com.sailpoint.poc.uiagent.pipeline.PipelineResult;
import com.sailpoint.poc.uiagent.pipeline.PipelineStatus;
import com.sailpoint.poc.uiagent.pipeline.ProgressListener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

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
 * <p>Uses {@link AgentPipeline} (AGGREGATION task type) which owns the full resource
 * lifecycle (REQ-3.4) and uses {@link ProgressListener} instead of the former
 * {@code System.setOut()} capture hack (REQ-3.5).
 */
public final class AggregationRunHandler implements HttpHandler {

    private final AggregationServerState state;

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
        String url      = req.optString("url",      "").trim();

        if (goalLine.isBlank()) { sendJson(ex, 400, "{\"error\":\"Missing goalLine\"}"); return; }
        if (url.isBlank())      { sendJson(ex, 400, "{\"error\":\"Missing url\"}");      return; }

        JSONObject ppJson = req.optJSONObject("paginationPattern");
        PaginationPattern pagination = ppJson != null
                ? new PaginationPattern(
                        ppJson.optString("type",         "unknown").trim().toLowerCase(),
                        ppJson.optString("description",  "").trim(),
                        ppJson.optString("selectorHint", "").trim())
                : new PaginationPattern("unknown", "", "");

        state.agentRunning.set(true);
        state.logQueue.offer("STATUS:aggregating");

        final String            finalUrl        = url;
        final String            finalGoal       = goalLine;
        final PaginationPattern finalPagination = pagination;

        Thread agent = new Thread(() -> {
            try {
                PocConfig config = new PocConfig();

                // Build pipeline config — single source of truth for resource setup (REQ-3.3)
                PipelineConfig pipelineConfig = PipelineConfig.builder()
                        .taskType(PipelineConfig.TaskType.AGGREGATION)
                        .startUrl(finalUrl)
                        .goal(finalGoal)
                        .paginationPattern(finalPagination)
                        .bedrockConfig(config.bedrock())
                        .browserConfig(config.browser())
                        .agentConfig(config.agent())
                        .aggregationConfig(config.aggregation())
                        .build();

                // ProgressListener forwards pipeline output to SSE queue (REQ-3.5)
                ProgressListener listener = new QueueProgressListener(state.logQueue);

                // AgentPipeline owns all resource lifecycle (REQ-3.4)
                PipelineResult result = AgentPipeline.run(pipelineConfig, listener);

                if (result.success()) {
                    storeResults(result);
                    state.logQueue.offer("AGGREGATION_DONE:"
                            + result.rowsScraped() + ":" + result.csvPath());
                } else {
                    state.logQueue.offer("LOG:ERROR:Aggregation failed: "
                            + result.exitReason()
                            + (result.errorMessage().isBlank()
                                    ? "" : " — " + result.errorMessage()));
                }

                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:" + (result.success() ? "0" : "1"));

            } catch (Exception e) {
                state.logQueue.offer("LOG:ERROR:" + e.getMessage());
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } finally {
                state.agentRunning.set(false);
            }
        }, "aggregation-loop");

        agent.setDaemon(true);
        state.agentThread.set(agent);
        agent.start();

        sendJson(ex, 200, "{\"started\":true}");
    }

    // ── Store preview + stats in shared state ────────────────────────────────

    private void storeResults(PipelineResult result) {
        List<Map<String,String>> preview = result.previewRows();
        List<String>             headers = result.headers();

        // Build previewRows JSON
        StringBuilder previewSb = new StringBuilder("[");
        for (int i = 0; i < preview.size(); i++) {
            if (i > 0) previewSb.append(",");
            previewSb.append("{");
            Map<String,String> row = preview.get(i);
            boolean first = true;
            for (Map.Entry<String,String> entry : row.entrySet()) {
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
        statsSb.append("\"totalRows\":").append(result.rowsScraped());
        statsSb.append(",\"pagesScraped\":").append(result.pagesScraped());
        statsSb.append(",\"columns\":[");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) statsSb.append(",");
            statsSb.append(quoted(headers.get(i)));
        }
        statsSb.append("]");
        statsSb.append(",\"inputTokens\":").append(result.totalUsage().inputTokens());
        statsSb.append(",\"outputTokens\":").append(result.totalUsage().outputTokens());
        statsSb.append(",\"costUsd\":").append(result.totalUsage().totalCostUsd());
        statsSb.append("}");
        state.lastStatsJson.set(statsSb.toString());

        if (!result.csvPath().isBlank()) state.lastCsvPath.set(result.csvPath());
    }

    // ── ProgressListener → SSE queue adapter ──────────────────────────────────

    private static final class QueueProgressListener implements ProgressListener {

        private final BlockingQueue<String> queue;

        QueueProgressListener(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void onLog(String level, String message) {
            if (message != null) queue.offer("LOG:" + level + ":" + message);
        }

        @Override
        public void onStatusChange(PipelineStatus status) {
            switch (status) {
                case NAVIGATING        -> queue.offer("LOG:INFO:Navigating to list page...");
                case AGENT_RUNNING     -> queue.offer("LOG:INFO:Running agent loop...");
                case DETECTING_TABLE   -> queue.offer("LOG:INFO:Detecting accounts table...");
                case AGGREGATING       -> queue.offer("LOG:INFO:Scraping pages...");
                case WRITING_CSV       -> queue.offer("LOG:INFO:Writing CSV...");
                default -> {} // STARTING, DONE, FAILED, INTERRUPTED — handled elsewhere
            }
        }

        @Override
        public void onTokenUsage(TokenUsage usage) {
            queue.offer("TOKEN_USAGE:" + usage.inputTokens()
                    + ":" + usage.outputTokens() + ":" + usage.totalCostUsd());
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
