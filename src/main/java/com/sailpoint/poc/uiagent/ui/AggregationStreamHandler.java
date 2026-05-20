package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/aggregation/stream — Server-Sent Events endpoint for the Aggregation tab.
 *
 * <p>Drains {@link AggregationServerState#logQueue} and forwards every entry as a typed
 * JSON SSE message.  Sends keepalive comments when idle.
 *
 * <h3>Message prefix → JSON shape (same as {@link StreamHandler}, plus one extra type)</h3>
 * <pre>
 *   "LOG:INFO:text"              → { type:"log",               level:"info",   text:"..." }
 *   "LOG:SUCCESS:text"           → { type:"log",               level:"success",text:"..." }
 *   "LOG:ERROR:text"             → { type:"log",               level:"error",  text:"..." }
 *   "LOG:WARNING:text"           → { type:"log",               level:"warning",text:"..." }
 *   "LOG:STEP:text"              → { type:"log",               level:"step",   text:"..." }
 *   "STATUS:value"               → { type:"status",            value:"..." }
 *   "PROGRESS:N:M:label"         → { type:"progress",          current:N, total:M, label:"..." }
 *   "DONE:0|1"                   → { type:"done",              exitCode:0|1 }
 *   "ERROR:message"              → { type:"error",             message:"..." }
 *   "AGGREGATION_DONE:N:path"    → { type:"aggregation_done",  totalRows:N, csvPath:"..." }
 * </pre>
 */
public final class AggregationStreamHandler implements HttpHandler {

    private final AggregationServerState state;

    public AggregationStreamHandler(AggregationServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);

        try (OutputStream out = ex.getResponseBody()) {
            while (!Thread.currentThread().isInterrupted()) {
                String msg;
                try {
                    msg = state.logQueue.poll(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (msg == null) {
                    writeLine(out, ": keepalive\n\n");
                } else {
                    String json = toJson(msg);
                    writeLine(out, "data: " + json + "\n\n");
                }
            }
        } catch (IOException ignored) {
            // client disconnected — normal
        }
    }

    private static void writeLine(OutputStream out, String text) throws IOException {
        out.write(text.getBytes());
        out.flush();
    }

    // ── Message prefix → JSON ─────────────────────────────────────────────────

    private static String toJson(String msg) {
        if (msg.startsWith("LOG:")) {
            int second = msg.indexOf(':', 4);
            if (second < 0) return logJson("info", msg.substring(4));
            String level = msg.substring(4, second).toLowerCase();
            String text  = msg.substring(second + 1);
            return logJson(level, text);
        }
        if (msg.startsWith("STATUS:")) {
            return "{\"type\":\"status\",\"value\":" + quoted(msg.substring(7)) + "}";
        }
        if (msg.startsWith("PROGRESS:")) {
            String[] parts = msg.substring(9).split(":", 3);
            int current = safeInt(parts, 0);
            int total   = safeInt(parts, 1);
            String label = parts.length > 2 ? parts[2] : "";
            return "{\"type\":\"progress\",\"current\":" + current + ",\"total\":" + total
                    + ",\"label\":" + quoted(label) + "}";
        }
        if (msg.startsWith("DONE:")) {
            int exitCode = safeInt(new String[]{msg.substring(5)}, 0);
            return "{\"type\":\"done\",\"exitCode\":" + exitCode + "}";
        }
        if (msg.startsWith("ERROR:")) {
            return "{\"type\":\"error\",\"message\":" + quoted(msg.substring(6)) + "}";
        }
        if (msg.startsWith("TOKEN_USAGE:")) {
            String[] parts = msg.substring(12).split(":", 3);
            int inputTokens  = safeInt(parts, 0);
            int outputTokens = safeInt(parts, 1);
            double costUsd   = safeDouble(parts, 2);
            return String.format("{\"type\":\"token_usage\",\"inputTokens\":%d,\"outputTokens\":%d,\"costUsd\":%.6f}",
                    inputTokens, outputTokens, costUsd);
        }
        if (msg.startsWith("AGGREGATION_DONE:")) {
            // AGGREGATION_DONE:<totalRows>:<csvPath>
            String payload = msg.substring("AGGREGATION_DONE:".length());
            int sep = payload.indexOf(':');
            int totalRows = 0;
            String csvPath = "";
            if (sep >= 0) {
                totalRows = safeInt(new String[]{payload.substring(0, sep)}, 0);
                csvPath   = payload.substring(sep + 1);
            } else {
                totalRows = safeInt(new String[]{payload}, 0);
            }
            return "{\"type\":\"aggregation_done\",\"totalRows\":" + totalRows
                    + ",\"csvPath\":" + quoted(csvPath) + "}";
        }
        return logJson("info", msg);
    }

    private static String logJson(String level, String text) {
        return "{\"type\":\"log\",\"level\":" + quoted(level) + ",\"text\":" + quoted(text) + "}";
    }

    private static String quoted(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    private static int safeInt(String[] parts, int idx) {
        try { return idx < parts.length ? Integer.parseInt(parts[idx].trim()) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static double safeDouble(String[] parts, int idx) {
        try { return idx < parts.length ? Double.parseDouble(parts[idx].trim()) : 0.0; }
        catch (NumberFormatException e) { return 0.0; }
    }
}
