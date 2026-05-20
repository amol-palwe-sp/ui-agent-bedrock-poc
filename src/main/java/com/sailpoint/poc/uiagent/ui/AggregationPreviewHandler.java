package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * GET /api/aggregation/preview
 *
 * <p>Returns the preview data from the last completed aggregation run.
 * Responds with 404 if no aggregation has completed yet.
 *
 * <h3>Success response shape</h3>
 * <pre>
 * {
 *   "csvPath":     "./output/accounts_20260501_143022.csv",
 *   "totalRows":   247,
 *   "pagesScraped": 10,
 *   "columns":     ["Name", "Email", "Status"],
 *   "previewRows": [ {"Name":"John Doe", "Email":"john@example.com"}, ... ],
 *   "stats": {
 *     "inputTokens":  45678,
 *     "outputTokens": 1234,
 *     "costUsd":      0.1423
 *   }
 * }
 * </pre>
 */
public final class AggregationPreviewHandler implements HttpHandler {

    private final AggregationServerState state;

    public AggregationPreviewHandler(AggregationServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String previewJson = state.lastPreviewJson.get();
        String statsJson   = state.lastStatsJson.get();
        String csvPath     = state.lastCsvPath.get();

        if (previewJson == null || statsJson == null) {
            sendJson(ex, 404, "{\"error\":\"No aggregation run has completed yet\"}");
            return;
        }

        // Assemble the full response from the stored JSON fragments
        String csv = csvPath != null ? csvPath : "";
        String json = "{\"csvPath\":" + quoted(csv)
                + ",\"previewRows\":" + previewJson
                + ",\"stats\":" + statsJson
                + "}";

        sendJson(ex, 200, json);
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
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
