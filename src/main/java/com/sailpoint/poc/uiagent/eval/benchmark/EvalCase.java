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

    /**
     * What we expect the agent to do with this video.
     *
     * <p>Only {@link #HAPPY} and {@link #INCORRECT} have a known-good answer to compare against.
     * For the other two the right result is a refusal or a flagged, low-confidence plan, so
     * there are no steps to score and the case is judged on its verdict instead.
     */
    public enum Expectation {
        /** A clean recording of the intended task. Score against the ground-truth steps. */
        HAPPY,
        /**
         * A real recording of a flawed run — detours, corrections, skipped steps. A plan is
         * still expected, matching the *intended* task, so this is scored against ground truth
         * exactly like a happy case. What makes it a test is that the ground truth deliberately
         * excludes the mistakes, and that rejecting the video outright is a failure.
         */
        INCORRECT,
        /** Not a usable input at all. The only correct outcome is a refusal with a reason. */
        INVALID,
        /** Right task, unreadable capture. Correct outcome is a refusal or a flagged plan. */
        UNWORKABLE;

        static Expectation from(String raw) {
            if (raw == null || raw.isBlank()) return HAPPY;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return HAPPY;
            }
        }

        /** True when this case has ground-truth steps worth comparing against. */
        public boolean hasGroundTruthSteps() {
            return this == HAPPY || this == INCORRECT;
        }
    }

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
    private final String      uiVariety;  // UI variation tag, e.g. "paging:infinite-scroll", "field:masked-input"
    private final String      description;
    private final String      videoPath;
    private final String      targetUrl;
    private final String      mode;       // PLACEHOLDER | LITERAL
    private final String      taskType;   // AGGREGATION | PROVISIONING
    private final Expectation expectation;
    /** Category we expect the triage gate to return. Diagnostic only — see {@link #expectedRejection()}. */
    private final String      expectedRejection;
    private final GroundTruth groundTruth;

    private EvalCase(String id, String uiVariety, String description, String videoPath, String targetUrl,
                     String mode, String taskType, Expectation expectation, String expectedRejection,
                     GroundTruth groundTruth) {
        this.id                = id;
        this.uiVariety         = uiVariety;
        this.description       = description;
        this.videoPath         = videoPath;
        this.targetUrl         = targetUrl;
        this.mode              = mode;
        this.taskType          = taskType;
        this.expectation       = expectation;
        this.expectedRejection = expectedRejection;
        this.groundTruth       = groundTruth;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String      id()          { return id; }
    public String      uiVariety()   { return uiVariety == null || uiVariety.isBlank() ? "(untagged)" : uiVariety; }
    public String      description() { return description; }
    public String      videoPath()   { return videoPath; }
    public String      targetUrl()   { return targetUrl; }
    public String      mode()        { return mode; }
    public String      taskType()    { return taskType; }
    public Expectation expectation() { return expectation; }
    public GroundTruth groundTruth() { return groundTruth; }

    /**
     * The rejection category we expect the triage gate to return, or {@code ""} if we only
     * care that it rejected at all. Reported as a diagnostic and never used to fail a case:
     * refusing an unusable video is the requirement, naming the exact reason is a bonus.
     */
    public String expectedRejection() { return expectedRejection == null ? "" : expectedRejection; }

    public boolean isAggregation()   { return "AGGREGATION".equalsIgnoreCase(taskType); }
    public boolean isProvisioning()  { return "PROVISIONING".equalsIgnoreCase(taskType); }
    public boolean isPlaceholderMode() { return "PLACEHOLDER".equalsIgnoreCase(mode); }
    public boolean isHappy()         { return expectation == Expectation.HAPPY; }
    public boolean isUnhappy()       { return expectation != Expectation.HAPPY; }

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
        String uiVariety   = o.optString("uiVariety",    "");
        String description = o.optString("description", "");
        String videoPath   = o.optString("videoPath",   "");
        String targetUrl   = o.optString("targetUrl",   "");
        String mode        = o.optString("mode",        "LITERAL");
        String taskType    = o.optString("taskType",    "PROVISIONING");
        // Absent means happy, so every case authored before unhappy paths existed keeps working.
        Expectation expectation = Expectation.from(o.optString("expectation", ""));
        String expectedRejection = o.optString("expectedRejection", "");

        JSONObject gt = o.optJSONObject("groundTruth");
        GroundTruth groundTruth = parseGroundTruth(gt != null ? gt : new JSONObject());

        return new EvalCase(id, uiVariety, description, videoPath, targetUrl, mode, taskType,
                expectation, expectedRejection, groundTruth);
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
