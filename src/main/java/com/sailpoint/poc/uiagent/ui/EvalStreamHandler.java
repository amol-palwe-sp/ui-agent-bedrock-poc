package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/eval/stream
 *
 * <p>Server-Sent Events stream for the eval run log.
 * Reads from {@link EvalServerState#logQueue} and pushes events to the browser.
 *
 * <p>Message protocol (same format as the main {@code StreamHandler}):
 * <ul>
 *   <li>{@code LOG:LEVEL:text}  — log line</li>
 *   <li>{@code PROGRESS:n:total:label} — progress update</li>
 *   <li>{@code STATUS:value}    — status change (running/ready)</li>
 *   <li>{@code DONE:exitCode}   — eval finished</li>
 * </ul>
 */
public final class EvalStreamHandler implements HttpHandler {

    private final EvalServerState state;

    public EvalStreamHandler(EvalServerState state) {
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
            while (true) {
                String msg;
                try {
                    msg = state.logQueue.poll(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (msg == null) {
                    // Keep-alive ping
                    write(out, "data: {\"type\":\"ping\"}\n\n");
                    continue;
                }

                String json = formatEvent(msg);
                write(out, "data: " + json + "\n\n");

                if (msg.startsWith("DONE:")) break;
            }
        } catch (IOException ignored) {
            // Client disconnected
        }
    }

    private static String formatEvent(String msg) {
        if (msg.startsWith("LOG:")) {
            String rest  = msg.substring(4);
            int colon    = rest.indexOf(':');
            String level = colon > 0 ? rest.substring(0, colon).toLowerCase() : "info";
            String text  = colon > 0 ? rest.substring(colon + 1) : rest;
            return "{\"type\":\"log\",\"level\":\"" + level + "\",\"text\":" + jsonStr(text) + "}";
        }
        if (msg.startsWith("PROGRESS:")) {
            String[] parts = msg.split(":", 4);
            int current = parts.length > 1 ? parseInt(parts[1]) : 0;
            int total   = parts.length > 2 ? parseInt(parts[2]) : 0;
            String label = parts.length > 3 ? parts[3] : "";
            return "{\"type\":\"progress\",\"current\":" + current
                    + ",\"total\":" + total
                    + ",\"label\":" + jsonStr(label) + "}";
        }
        if (msg.startsWith("STATUS:")) {
            return "{\"type\":\"status\",\"value\":" + jsonStr(msg.substring(7)) + "}";
        }
        if (msg.startsWith("DONE:")) {
            int exitCode = parseInt(msg.substring(5));
            return "{\"type\":\"done\",\"exitCode\":" + exitCode + "}";
        }
        return "{\"type\":\"log\",\"level\":\"info\",\"text\":" + jsonStr(msg) + "}";
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
