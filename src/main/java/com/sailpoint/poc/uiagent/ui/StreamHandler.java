package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/stream — Server-Sent Events endpoint.
 *
 * <p>Drains {@link AgentUIServer.ServerState#logQueue} and forwards every entry
 * as a typed JSON SSE message. Sends keepalive comments when idle so the
 * browser connection stays alive.
 *
 * <h3>Message prefix → JSON shape</h3>
 * <pre>
 *   "LOG:INFO:text"      → { type:"log",      level:"info",    text:"..." }
 *   "LOG:SUCCESS:text"   → { type:"log",      level:"success", text:"..." }
 *   "LOG:ERROR:text"     → { type:"log",      level:"error",   text:"..." }
 *   "LOG:WARNING:text"   → { type:"log",      level:"warning", text:"..." }
 *   "LOG:STEP:text"      → { type:"log",      level:"step",    text:"..." }
 *   "STATUS:value"       → { type:"status",   value:"..." }
 *   "PROGRESS:N:M:label" → { type:"progress", current:N, total:M, label:"..." }
 *   "DONE:0|1"           → { type:"done",     exitCode:0|1 }
 *   "ERROR:message"      → { type:"error",    message:"..." }
 * </pre>
 */
public final class StreamHandler implements HttpHandler {

    private final AgentUIServer.ServerState state;

    public StreamHandler(AgentUIServer.ServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);   // chunked / indefinite

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
            // LOG:<LEVEL>:<text>
            int second = msg.indexOf(':', 4);
            if (second < 0) return logJson("info", msg.substring(4));
            String level = msg.substring(4, second).toLowerCase();
            String text  = msg.substring(second + 1);
            return logJson(level, text);
        }
        if (msg.startsWith("STATUS:")) {
            String value = msg.substring(7);
            return "{\"type\":\"status\",\"value\":" + quoted(value) + "}";
        }
        if (msg.startsWith("PROGRESS:")) {
            // PROGRESS:N:M:label
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
            String message = msg.substring(6);
            return "{\"type\":\"error\",\"message\":" + quoted(message) + "}";
        }
        // Fallback — treat as info log
        return logJson("info", msg);
    }

    private static String logJson(String level, String text) {
        return "{\"type\":\"log\",\"level\":" + quoted(level) + ",\"text\":" + quoted(text) + "}";
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    private static int safeInt(String[] parts, int idx) {
        try { return idx < parts.length ? Integer.parseInt(parts[idx].trim()) : 0; }
        catch (NumberFormatException e) { return 0; }
    }
}
