package com.sailpoint.poc.uiagent.replay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recorded automation script (REQ-RR-2.1).
 */
public final class Script {

    public static final String VERSION = "1.0";

    private String version;
    private String scriptName;
    private String createdAt;
    private String lastRunAt;
    private int runCount;
    private String startUrl;
    private String goal;
    private String taskType;
    private List<ScriptStep> steps;
    private double successRate;
    private int browserWidth;
    private int browserHeight;
    private Path sourcePath;

    public Script() {
        this.version = VERSION;
        this.steps = new ArrayList<>();
        this.createdAt = Instant.now().toString();
        this.runCount = 0;
        this.successRate = 1.0;
    }

    public static Script load(String path) throws IOException {
        String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Script script = fromJson(new JSONObject(json));
        script.sourcePath = Path.of(path);
        return script;
    }

    public static Script fromJson(JSONObject root) {
        Script s = new Script();
        s.version = root.optString("version", VERSION);
        s.scriptName = root.optString("scriptName", "");
        s.createdAt = root.optString("createdAt", "");
        s.lastRunAt = root.optString("lastRunAt", "");
        s.runCount = root.optInt("runCount", 0);
        s.startUrl = root.optString("startUrl", "");
        s.goal = root.optString("goal", "");
        s.taskType = root.optString("taskType", "PROVISIONING");
        JSONArray arr = root.optJSONArray("steps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                s.steps.add(ScriptStep.fromJson(arr.getJSONObject(i)));
            }
        }
        JSONObject meta = root.optJSONObject("metadata");
        if (meta != null) {
            s.successRate = meta.optDouble("successRate", 1.0);
            s.browserWidth = meta.optInt("browserWidth", 0);
            s.browserHeight = meta.optInt("browserHeight", 0);
        }
        return s;
    }

    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        root.put("version", version);
        root.put("scriptName", scriptName);
        root.put("createdAt", createdAt);
        root.put("lastRunAt", lastRunAt);
        root.put("runCount", runCount);
        root.put("startUrl", startUrl);
        root.put("goal", goal);
        root.put("taskType", taskType);
        JSONArray stepsJson = new JSONArray();
        for (ScriptStep step : steps) {
            stepsJson.put(step.toJson());
        }
        root.put("steps", stepsJson);
        JSONObject meta = new JSONObject();
        meta.put("recordedBy", "AgentLoop v1.0");
        meta.put("browserWidth", browserWidth);
        meta.put("browserHeight", browserHeight);
        meta.put("successRate", successRate);
        root.put("metadata", meta);
        return root;
    }

    public Path save(String outputDir) throws IOException {
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);
        String slug = goalSlug(goal);
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now());
        String filename = slug + "_" + ts + ".json";
        Path file = dir.resolve(filename);
        Files.writeString(file, toJson().toString(2), StandardCharsets.UTF_8);
        sourcePath = file;
        return file;
    }

    public void saveInPlace() throws IOException {
        if (sourcePath == null) {
            throw new IllegalStateException("No source path — use save(dir) first");
        }
        Files.writeString(sourcePath, toJson().toString(2), StandardCharsets.UTF_8);
    }

    public static String goalSlug(String goal) {
        if (goal == null || goal.isBlank()) {
            return "script";
        }
        String s = goal.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s.isBlank() ? "script" : s;
    }

    public double healthScore() {
        if (steps.isEmpty()) {
            return 0.0;
        }
        long zeroMiss = steps.stream().filter(st -> st.missCount() == 0).count();
        return (double) zeroMiss / steps.size();
    }

    public String version() { return version; }
    public String scriptName() { return scriptName; }
    public void setScriptName(String n) { this.scriptName = n; }
    public String startUrl() { return startUrl; }
    public void setStartUrl(String u) { this.startUrl = u; }
    public String goal() { return goal; }
    public void setGoal(String g) { this.goal = g; }
    public String taskType() { return taskType; }
    public void setTaskType(String t) { this.taskType = t; }
    public List<ScriptStep> steps() { return steps; }

    public void setStep(int index, ScriptStep step) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepIndex() == index) {
                steps.set(i, step);
                return;
            }
        }
    }

    public void addStep(ScriptStep step) { steps.add(step); }
    public Path sourcePath() { return sourcePath; }
    public void setSourcePath(Path p) { this.sourcePath = p; }
    public double successRate() { return successRate; }
    public void setSuccessRate(double r) { this.successRate = r; }
    public int browserWidth() { return browserWidth; }
    public void setBrowserWidth(int w) { this.browserWidth = w; }
    public int browserHeight() { return browserHeight; }
    public void setBrowserHeight(int h) { this.browserHeight = h; }
    public int runCount() { return runCount; }
    public String lastRunAt() { return lastRunAt; }
    public void incrementRunCount() { runCount++; lastRunAt = Instant.now().toString(); }
}
