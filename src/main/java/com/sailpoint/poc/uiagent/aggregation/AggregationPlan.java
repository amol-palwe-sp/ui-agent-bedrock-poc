package com.sailpoint.poc.uiagent.aggregation;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serialisable plan produced by Step 1 ({@code runAggregationPlan}).
 *
 * <p>Saved to {@code ./output/aggregation-plan_<timestamp>.json} so Step 2
 * ({@code runAggregation --plan=<file>}) can read the pagination metadata without
 * re-running video analysis or paying for another Claude call.
 *
 * <p>The {@code navigationGoal} field is stored for reference only; Step 2 always
 * requires an explicit {@code --goal} on the CLI so that real credentials can be
 * supplied at runtime rather than extracted from the video.
 */
public record AggregationPlan(
        String targetUrl,
        String navigationGoal,
        PaginationPattern paginationPattern,
        String createdAt) {

    // -------------------------------------------------------------------------
    // Serialise → JSON file
    // -------------------------------------------------------------------------

    public void save(String outputDir) throws IOException {
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path filePath = dir.resolve("aggregation-plan_" + timestamp + ".json");

        JSONObject json = toJson();
        Files.writeString(filePath, json.toString(2), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("Plan saved to: " + filePath.toAbsolutePath());
    }

    public JSONObject toJson() {
        JSONObject pagination = new JSONObject()
                .put("type",          paginationPattern.type())
                .put("description",   paginationPattern.description())
                .put("selector_hint", paginationPattern.selectorHint());

        return new JSONObject()
                .put("targetUrl",         targetUrl)
                .put("navigationGoal",    navigationGoal)
                .put("paginationPattern", pagination)
                .put("createdAt",         createdAt);
    }

    // -------------------------------------------------------------------------
    // Deserialise ← JSON file
    // -------------------------------------------------------------------------

    public static AggregationPlan load(String filePath) throws IOException {
        String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(content);

        String url  = json.optString("targetUrl",      "");
        String goal = json.optString("navigationGoal", "");
        String at   = json.optString("createdAt",      "");

        JSONObject pp = json.optJSONObject("paginationPattern");
        PaginationPattern pattern;
        if (pp != null) {
            pattern = new PaginationPattern(
                    pp.optString("type",          "unknown"),
                    pp.optString("description",   ""),
                    pp.optString("selector_hint", ""));
        } else {
            pattern = new PaginationPattern("unknown", "", "");
        }

        return new AggregationPlan(url, goal, pattern, at);
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static AggregationPlan from(
            String targetUrl,
            AggregationVideoAnalysis analysis) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return new AggregationPlan(
                targetUrl,
                analysis.navigationGoal(),
                analysis.paginationPattern(),
                now);
    }
}
