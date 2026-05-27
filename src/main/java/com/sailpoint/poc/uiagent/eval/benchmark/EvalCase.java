package com.sailpoint.poc.uiagent.eval.benchmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Schema for one benchmark eval case.
 * Ties one video to its ground truth steps, pagination, and token definitions.
 */
public final class EvalCase {

    /** Immutable pagination ground truth nested inside {@link GroundTruth}. */
    public record PaginationGT(String type, String description, String selectorHint) {}

    /** Ground truth for one eval case. */
    public record GroundTruth(
            String navigationGoal,
            List<String> steps,
            PaginationGT paginationPattern,
            List<TokenDef> tokens) {}

    /** Token placeholder definition in the ground truth. */
    public record TokenDef(String name, String label, String type) {}

    private final String      id;
    private final String      description;
    private final String      videoPath;
    private final String      targetUrl;
    private final String      mode;       // PLACEHOLDER | LITERAL
    private final String      taskType;   // AGGREGATION | PROVISIONING
    private final GroundTruth groundTruth;

    private EvalCase(String id, String description, String videoPath, String targetUrl,
                     String mode, String taskType, GroundTruth groundTruth) {
        this.id          = id;
        this.description = description;
        this.videoPath   = videoPath;
        this.targetUrl   = targetUrl;
        this.mode        = mode;
        this.taskType    = taskType;
        this.groundTruth = groundTruth;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String      id()          { return id; }
    public String      description() { return description; }
    public String      videoPath()   { return videoPath; }
    public String      targetUrl()   { return targetUrl; }
    public String      mode()        { return mode; }
    public String      taskType()    { return taskType; }
    public GroundTruth groundTruth() { return groundTruth; }

    public boolean isAggregation()   { return "AGGREGATION".equalsIgnoreCase(taskType); }
    public boolean isProvisioning()  { return "PROVISIONING".equalsIgnoreCase(taskType); }
    public boolean isPlaceholderMode() { return "PLACEHOLDER".equalsIgnoreCase(mode); }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Loads all benchmark cases from the given JSON file path.
     *
     * @param benchmarksPath path to benchmarks.json
     * @return immutable list of eval cases
     * @throws IOException if the file cannot be read
     */
    public static List<EvalCase> loadAll(String benchmarksPath) throws IOException {
        String raw = Files.readString(Paths.get(benchmarksPath));
        JSONObject root = new JSONObject(raw);
        JSONArray arr = root.getJSONArray("benchmarks");
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            cases.add(fromJson(arr.getJSONObject(i)));
        }
        return Collections.unmodifiableList(cases);
    }

    /**
     * Deserializes one eval case from a JSON object.
     *
     * @param o the JSONObject representing one benchmark entry
     * @return populated EvalCase
     */
    public static EvalCase fromJson(JSONObject o) {
        String id          = o.optString("id",          "");
        String description = o.optString("description", "");
        String videoPath   = o.optString("videoPath",   "");
        String targetUrl   = o.optString("targetUrl",   "");
        String mode        = o.optString("mode",        "LITERAL");
        String taskType    = o.optString("taskType",    "PROVISIONING");

        JSONObject gt = o.optJSONObject("groundTruth");
        GroundTruth groundTruth = parseGroundTruth(gt != null ? gt : new JSONObject());

        return new EvalCase(id, description, videoPath, targetUrl, mode, taskType, groundTruth);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static GroundTruth parseGroundTruth(JSONObject gt) {
        String navigationGoal = gt.optString("navigationGoal", "");

        // Parse steps
        List<String> steps = new ArrayList<>();
        JSONArray stepsArr = gt.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                String s = stepsArr.optString(i, "").trim();
                if (!s.isBlank()) steps.add(s);
            }
        }

        // Parse paginationPattern
        PaginationGT paginationGT = null;
        JSONObject ppJson = gt.optJSONObject("paginationPattern");
        if (ppJson != null) {
            paginationGT = new PaginationGT(
                    ppJson.optString("type",         "unknown"),
                    ppJson.optString("description",  ""),
                    ppJson.optString("selectorHint", ""));
        }

        // Parse tokens
        List<TokenDef> tokens = new ArrayList<>();
        JSONArray tokensArr = gt.optJSONArray("tokens");
        if (tokensArr != null) {
            for (int i = 0; i < tokensArr.length(); i++) {
                JSONObject t = tokensArr.optJSONObject(i);
                if (t != null) {
                    tokens.add(new TokenDef(
                            t.optString("name",  ""),
                            t.optString("label", ""),
                            t.optString("type",  "text")));
                }
            }
        }

        return new GroundTruth(
                navigationGoal,
                Collections.unmodifiableList(steps),
                paginationGT,
                Collections.unmodifiableList(tokens));
    }
}
