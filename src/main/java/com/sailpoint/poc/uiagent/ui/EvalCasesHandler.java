package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * GET /api/eval/cases
 *
 * <p>Returns the list of benchmark cases from {@code benchmarks.json} so the
 * Eval UI page can build its dropdown and case table dynamically — no hardcoded
 * case names in the frontend.
 */
public final class EvalCasesHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try {
            PocConfig config = new PocConfig();
            List<EvalCase> cases = EvalCase.loadAll(config.evalBenchmarksPath());

            JSONArray arr = new JSONArray();
            for (EvalCase c : cases) {
                JSONObject o = new JSONObject();
                o.put("id",          c.id());
                o.put("description", c.description());
                o.put("taskType",    c.taskType());
                o.put("mode",        c.mode());
                o.put("targetUrl",   c.targetUrl());
                o.put("videoPath",   c.videoPath());
                arr.put(o);
            }

            JSONObject response = new JSONObject();
            response.put("cases", arr);
            response.put("benchmarksPath", config.evalBenchmarksPath());
            sendJson(ex, 200, response.toString());

        } catch (Exception e) {
            String msg = e.getMessage() == null ? "Failed to load cases"
                    : e.getMessage().replace("\"", "\\\"").replace("\n", " ");
            sendJson(ex, 500, "{\"error\":\"" + msg + "\"}");
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }
}
