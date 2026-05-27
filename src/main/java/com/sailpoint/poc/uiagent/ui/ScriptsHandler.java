package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.replay.Script;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * GET /api/scripts — lists saved replay scripts (REQ-RR-9.3).
 */
public final class ScriptsHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            RunHandler.sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try {
            PocConfig config = new PocConfig();
            String dir = config.scriptOutputDir();
            JSONArray scripts = new JSONArray();

            Path scriptsDir = Path.of(dir);
            if (Files.isDirectory(scriptsDir)) {
                try (Stream<Path> paths = Files.list(scriptsDir)) {
                    paths.filter(p -> p.toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::getFileName).reversed())
                            .forEach(p -> scripts.put(toEntry(p, dir)));
                }
            }

            JSONObject out = new JSONObject();
            out.put("dir", dir);
            out.put("scripts", scripts);
            RunHandler.sendJson(ex, 200, out.toString());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to list scripts";
            RunHandler.sendJson(ex, 500, "{\"error\":" + JSONObject.quote(msg) + "}");
        }
    }

    private static JSONObject toEntry(Path file, String baseDir) {
        JSONObject o = new JSONObject();
        String path = file.toAbsolutePath().toString();
        o.put("path", path);
        o.put("name", file.getFileName().toString());
        try {
            Script script = Script.load(path);
            o.put("scriptName", script.scriptName());
            o.put("goal", script.goal());
            o.put("startUrl", script.startUrl());
            o.put("steps", script.steps().size());
            o.put("health", script.healthScore());
            o.put("lastRunAt", script.lastRunAt());
            o.put("taskType", script.taskType());
        } catch (Exception e) {
            o.put("goal", "");
            o.put("steps", 0);
            o.put("health", 0);
            o.put("parseError", e.getMessage());
        }
        return o;
    }
}
