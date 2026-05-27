package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.eval.benchmark.VideoAnalysisEvaluator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * POST /api/eval/run
 *
 * <p>Starts a benchmark eval run in a background thread.
 * Progress is pushed to {@link EvalServerState#logQueue} for the SSE stream.
 *
 * <p>Request body (JSON):
 * <pre>
 * {
 *   "caseId":    "eval_001",   // optional — omit to run all cases
 *   "skipJudge": false          // optional — skip LLM judge step
 * }
 * </pre>
 *
 * <p>POST /api/eval/stop — handled by the inner {@link StopHandler}.
 */
public final class EvalRunHandler implements HttpHandler {

    private final EvalServerState state;

    public EvalRunHandler(EvalServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        if (state.isRunning.get()) {
            sendJson(ex, 409, "{\"error\":\"Eval already running\"}");
            return;
        }

        // Parse JSON body
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        String caseId    = null;
        boolean skipJudge = false;
        if (!body.isEmpty()) {
            try {
                JSONObject req = new JSONObject(body);
                String c = req.optString("caseId", "").trim();
                if (!c.isEmpty()) caseId = c;
                skipJudge = req.optBoolean("skipJudge", false);
            } catch (Exception ignored) {}
        }

        final String finalCaseId = caseId;
        final boolean finalSkipJudge = skipJudge;

        // Clear old log messages
        state.logQueue.clear();
        state.isRunning.set(true);
        state.logQueue.offer("STATUS:running");

        Thread thread = new Thread(() -> {
            try {
                PocConfig config = new PocConfig();
                VideoAnalysisEvaluator.run(
                        config.evalBenchmarksPath(),
                        config.evalOutputDir(),
                        finalSkipJudge,
                        finalCaseId,
                        config,
                        state.logQueue::offer);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                state.logQueue.offer("LOG:ERROR:Eval interrupted");
                state.logQueue.offer("DONE:1");
            } catch (Exception e) {
                state.logQueue.offer("LOG:ERROR:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                state.logQueue.offer("DONE:1");
            } finally {
                state.isRunning.set(false);
                state.logQueue.offer("STATUS:ready");
            }
        }, "eval-run-thread");

        thread.setDaemon(true);
        state.evalThread.set(thread);
        thread.start();

        sendJson(ex, 200, "{\"started\":true}");
    }

    // ── Stop handler ─────────────────────────────────────────────────────────

    public static final class StopHandler implements HttpHandler {
        private final EvalServerState state;

        public StopHandler(EvalServerState state) { this.state = state; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            Thread t = state.evalThread.get();
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
            state.isRunning.set(false);
            state.logQueue.offer("LOG:ERROR:Eval stopped by user");
            state.logQueue.offer("STATUS:ready");
            state.logQueue.offer("DONE:1");
            sendJson(ex, 200, "{\"stopped\":true}");
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }
}
