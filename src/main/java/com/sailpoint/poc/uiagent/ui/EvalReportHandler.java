package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GET /api/eval/reports
 *
 * <p>Lists all {@code eval-report_*.json} files from the configured eval output directory
 * and returns their summary metadata plus the full case results as JSON.
 * Used by the Eval UI page to display benchmark history.
 */
public final class EvalReportHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try {
            PocConfig config = new PocConfig();
            String outputDir = config.evalOutputDir();
            Path dir = Paths.get(outputDir);

            JSONArray reports = new JSONArray();

            if (Files.isDirectory(dir)) {
                List<Path> reportFiles = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("eval-report_") && name.endsWith(".json");
                    }).forEach(reportFiles::add);
                }

                // Sort newest first (by file name which has timestamp)
                reportFiles.sort(Comparator.comparing(
                        p -> p.getFileName().toString(), Comparator.reverseOrder()));

                for (Path reportFile : reportFiles) {
                    try {
                        String raw = Files.readString(reportFile);
                        JSONObject report = new JSONObject(raw);
                        // Add the file name so the UI can display it
                        report.put("fileName", reportFile.getFileName().toString());
                        reports.put(report);
                    } catch (Exception ignored) {
                        // Skip malformed report files
                    }
                }
            }

            JSONObject response = new JSONObject();
            response.put("reports", reports);
            response.put("outputDir", dir.toAbsolutePath().toString());
            response.put("benchmarksPath", config.evalBenchmarksPath());

            sendJson(ex, 200, response.toString());

        } catch (Exception e) {
            String escaped = e.getMessage() == null ? "Internal error"
                    : e.getMessage().replace("\"", "\\\"").replace("\n", " ");
            sendJson(ex, 500, "{\"error\":\"" + escaped + "\"}");
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
