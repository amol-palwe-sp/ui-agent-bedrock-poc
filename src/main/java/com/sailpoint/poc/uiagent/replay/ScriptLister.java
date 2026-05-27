package com.sailpoint.poc.uiagent.replay;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Lists saved scripts in the output directory (REQ-RR-9.3). */
public final class ScriptLister {

    private ScriptLister() {}

    public static void list(String outputDir) throws IOException {
        Path dir = Path.of(outputDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("No scripts directory: " + dir.toAbsolutePath());
            return;
        }
        System.out.println("Available scripts in " + dir.toAbsolutePath() + ":");
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> scripts = paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            if (scripts.isEmpty()) {
                System.out.println("  (none)");
                return;
            }
            int i = 1;
            for (Path p : scripts) {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(json);
                int steps = root.optJSONArray("steps") != null
                        ? root.optJSONArray("steps").length() : 0;
                double health = 0.0;
                JSONObject meta = root.optJSONObject("metadata");
                if (meta != null) {
                    health = meta.optDouble("successRate", 0.0) * 100;
                }
                System.out.printf(
                        "  %d. %s%n     Goal: %s%n     Steps: %d | Health: %.0f%% | Last run: %s%n",
                        i++,
                        p.getFileName(),
                        root.optString("goal", ""),
                        steps,
                        health,
                        root.optString("lastRunAt", "never"));
            }
        }
    }
}
