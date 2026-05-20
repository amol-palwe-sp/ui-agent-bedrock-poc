package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET /api/aggregation/download
 *
 * <p>Streams the last successfully written CSV file to the browser as a native
 * file download. The browser receives the original filename via
 * {@code Content-Disposition: attachment} and triggers an OS save dialog.
 *
 * <h3>Error responses</h3>
 * <ul>
 *   <li>405 — wrong HTTP method</li>
 *   <li>404 — no aggregation has run yet, or file was deleted before download</li>
 *   <li>500 — file exists but could not be read</li>
 * </ul>
 */
public final class AggregationDownloadHandler implements HttpHandler {

    private final AggregationServerState state;

    public AggregationDownloadHandler(AggregationServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method Not Allowed");
            return;
        }

        String csvPath = state.lastCsvPath.get();
        if (csvPath == null || csvPath.isBlank()) {
            sendError(ex, 404, "No CSV available yet");
            return;
        }

        Path filePath = Path.of(csvPath);
        if (!Files.exists(filePath)) {
            sendError(ex, 404, "CSV file not found on disk");
            return;
        }

        String filename = filePath.getFileName().toString();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(filePath);
        } catch (IOException e) {
            sendError(ex, 500, "Could not read CSV file");
            return;
        }

        ex.getResponseHeaders().set("Content-Type",        "text/csv; charset=UTF-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.getResponseHeaders().set("Content-Length",      String.valueOf(bytes.length));
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        String json = "{\"error\":\"" + msg + "\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
