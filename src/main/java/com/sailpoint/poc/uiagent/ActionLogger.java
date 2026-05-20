package com.sailpoint.poc.uiagent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import org.json.JSONObject;

/**
 * Writes one JSON line per action outcome to a configurable log file (JSONL format).
 *
 * <p>Each line records:
 * <ul>
 *   <li>{@code ts} — ISO-8601 timestamp</li>
 *   <li>{@code step}, {@code action_index} — position in the agent loop</li>
 *   <li>{@code type} — action type (CLICK, TYPE, …)</li>
 *   <li>{@code element_id} — target element id (if applicable)</li>
 *   <li>{@code ok} — whether the action succeeded</li>
 *   <li>{@code strategy} — Playwright strategy used (on success)</li>
 *   <li>{@code err} — error message (on failure)</li>
 *   <li>{@code url} — page URL at time of action</li>
 *   <li>{@code elapsed_ms} — wall-clock duration of the action</li>
 * </ul>
 *
 * <p>The log file path is set via {@code agent.log.file} in {@code application.properties}.
 * When the path is empty or {@code none}, logging is disabled (no-op).
 */
public final class ActionLogger implements AutoCloseable {

    private final BufferedWriter writer;
    private final boolean enabled;

    public ActionLogger(String logFilePath) {
        boolean ok = false;
        BufferedWriter w = null;
        if (logFilePath != null && !logFilePath.isBlank() && !logFilePath.equalsIgnoreCase("none")) {
            try {
                Path path = Paths.get(logFilePath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                w = new BufferedWriter(new FileWriter(path.toFile(), true));
                ok = true;
                System.out.println("[ActionLogger] Writing to: " + path.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[ActionLogger] Cannot open log file '" + logFilePath
                        + "': " + e.getMessage() + " — logging disabled.");
            }
        }
        this.writer = w;
        this.enabled = ok;
    }

    /**
     * Logs a single action outcome.
     *
     * @param step         step index (0-based)
     * @param actionIndex  index within the batch (0-based)
     * @param type         action type string (e.g. "CLICK")
     * @param elementId    element id or -1 if not applicable
     * @param result       JSONObject returned by BrowserSession (contains ok, strategy/err)
     * @param url          current page URL
     * @param elapsedMs    wall-clock duration of the action
     */
    public void log(
            int step,
            int actionIndex,
            String type,
            int elementId,
            JSONObject result,
            String url,
            long elapsedMs) {
        if (!enabled || writer == null) return;
        try {
            JSONObject entry = new JSONObject();
            entry.put("ts", Instant.now().toString());
            entry.put("step", step);
            entry.put("action_index", actionIndex);
            entry.put("type", type);
            if (elementId >= 0) entry.put("element_id", elementId);
            entry.put("ok", result.optBoolean("ok", false));
            if (result.has("strategy")) entry.put("strategy", result.optString("strategy"));
            if (result.has("err"))      entry.put("err",      result.optString("err"));
            entry.put("url", url);
            entry.put("elapsed_ms", elapsedMs);
            writer.write(entry.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("[ActionLogger] Write error: " + e.getMessage());
        }
    }

    /** Convenience overload for actions without a target element (GOTO, WAIT, …). */
    public void log(int step, int actionIndex, String type,
                    JSONObject result, String url, long elapsedMs) {
        log(step, actionIndex, type, -1, result, url, elapsedMs);
    }

    @Override
    public void close() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
        }
    }
}
