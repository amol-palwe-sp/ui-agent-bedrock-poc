package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.TokenUsage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores all scores and metadata for one benchmark eval case run.
 */
public final class EvalResult {

    // ── Identity ──────────────────────────────────────────────────────────────
    private final String caseId;
    private final String description;
    private final String taskType;
    private final String mode;

    // ── Automated metrics (0.0–1.0) ──────────────────────────────────────────
    private final double stepRecall;
    private final double stepPrecision;
    private final double stepOrderScore;
    private final double labelAccuracyScore;
    private final double placeholderScore;
    private final double paginationScore;
    private final double overallScore;

    // ── LLM judge scores (0.0–10.0) ──────────────────────────────────────────
    private final double judgeCorrectnessScore;
    private final double judgeOrderScore;
    private final double judgeHallucinationScore;
    private final double judgeLabelScore;
    private final double judgePlaceholderScore;
    private final double judgeOverallScore;
    private final String judgeReasoning;

    // ── Generated output ──────────────────────────────────────────────────────
    private final List<String> generatedSteps;
    private final String       generatedGoal;
    private final String       generatedPaginationType;

    // ── Error tracking ────────────────────────────────────────────────────────
    private final List<String> hallucinatedSteps;
    private final List<String> missingSteps;
    private final boolean      misordered;
    private final List<String> credentialLeaks;
    private final List<String> issues;

    // ── Meta ──────────────────────────────────────────────────────────────────
    private final long       durationMs;
    private final TokenUsage tokenUsage;
    private final boolean    passed;

    private EvalResult(Builder b) {
        this.caseId               = b.caseId;
        this.description          = b.description;
        this.taskType             = b.taskType;
        this.mode                 = b.mode;
        this.stepRecall           = b.stepRecall;
        this.stepPrecision        = b.stepPrecision;
        this.stepOrderScore       = b.stepOrderScore;
        this.labelAccuracyScore   = b.labelAccuracyScore;
        this.placeholderScore     = b.placeholderScore;
        this.paginationScore      = b.paginationScore;
        this.overallScore         = b.overallScore;
        this.judgeCorrectnessScore    = b.judgeCorrectnessScore;
        this.judgeOrderScore          = b.judgeOrderScore;
        this.judgeHallucinationScore  = b.judgeHallucinationScore;
        this.judgeLabelScore          = b.judgeLabelScore;
        this.judgePlaceholderScore    = b.judgePlaceholderScore;
        this.judgeOverallScore        = b.judgeOverallScore;
        this.judgeReasoning           = b.judgeReasoning;
        this.generatedSteps           = Collections.unmodifiableList(new ArrayList<>(b.generatedSteps));
        this.generatedGoal            = b.generatedGoal;
        this.generatedPaginationType  = b.generatedPaginationType;
        this.hallucinatedSteps        = Collections.unmodifiableList(new ArrayList<>(b.hallucinatedSteps));
        this.missingSteps             = Collections.unmodifiableList(new ArrayList<>(b.missingSteps));
        this.misordered               = b.misordered;
        this.credentialLeaks          = Collections.unmodifiableList(new ArrayList<>(b.credentialLeaks));
        this.issues                   = Collections.unmodifiableList(new ArrayList<>(b.issues));
        this.durationMs               = b.durationMs;
        this.tokenUsage               = b.tokenUsage != null ? b.tokenUsage : TokenUsage.ZERO;
        this.passed                   = overallScore >= 0.70;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     caseId()               { return caseId; }
    public String     description()          { return description; }
    public String     taskType()             { return taskType; }
    public String     mode()                 { return mode; }
    public double     stepRecall()           { return stepRecall; }
    public double     stepPrecision()        { return stepPrecision; }
    public double     stepOrderScore()       { return stepOrderScore; }
    public double     labelAccuracyScore()   { return labelAccuracyScore; }
    public double     placeholderScore()     { return placeholderScore; }
    public double     paginationScore()      { return paginationScore; }
    public double     overallScore()         { return overallScore; }
    public double     judgeCorrectnessScore()   { return judgeCorrectnessScore; }
    public double     judgeOrderScore()         { return judgeOrderScore; }
    public double     judgeHallucinationScore() { return judgeHallucinationScore; }
    public double     judgeLabelScore()         { return judgeLabelScore; }
    public double     judgePlaceholderScore()   { return judgePlaceholderScore; }
    public double     judgeOverallScore()       { return judgeOverallScore; }
    public String     judgeReasoning()          { return judgeReasoning; }
    public List<String> generatedSteps()        { return generatedSteps; }
    public String     generatedGoal()           { return generatedGoal; }
    public String     generatedPaginationType() { return generatedPaginationType; }
    public List<String> hallucinatedSteps()     { return hallucinatedSteps; }
    public List<String> missingSteps()          { return missingSteps; }
    public boolean    misordered()              { return misordered; }
    public List<String> credentialLeaks()       { return credentialLeaks; }
    public List<String> issues()               { return issues; }
    public long       durationMs()             { return durationMs; }
    public TokenUsage tokenUsage()             { return tokenUsage; }
    public boolean    passed()                 { return passed; }

    // ── Serialization ─────────────────────────────────────────────────────────

    /** Serializes this result to a JSON object for report writing. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("caseId",      caseId);
        o.put("description", description);
        o.put("taskType",    taskType);
        o.put("mode",        mode);
        o.put("passed",      passed);
        o.put("durationMs",  durationMs);

        JSONObject metrics = new JSONObject();
        metrics.put("stepRecall",         round(stepRecall));
        metrics.put("stepPrecision",      round(stepPrecision));
        metrics.put("stepOrderScore",     round(stepOrderScore));
        metrics.put("labelAccuracyScore", round(labelAccuracyScore));
        metrics.put("placeholderScore",   round(placeholderScore));
        metrics.put("paginationScore",    round(paginationScore));
        metrics.put("overallScore",       round(overallScore));
        o.put("metrics", metrics);

        JSONObject judge = new JSONObject();
        judge.put("correctness",   round(judgeCorrectnessScore));
        judge.put("order",         round(judgeOrderScore));
        judge.put("hallucination", round(judgeHallucinationScore));
        judge.put("labelQuality",  round(judgeLabelScore));
        judge.put("placeholder",   round(judgePlaceholderScore));
        judge.put("overall",       round(judgeOverallScore));
        judge.put("reasoning",     judgeReasoning != null ? judgeReasoning : "");
        o.put("judgeScores", judge);

        o.put("generatedGoal",           generatedGoal != null ? generatedGoal : "");
        o.put("generatedPaginationType", generatedPaginationType != null ? generatedPaginationType : "");
        o.put("generatedSteps",          toJsonArray(generatedSteps));
        o.put("hallucinatedSteps",       toJsonArray(hallucinatedSteps));
        o.put("missingSteps",            toJsonArray(missingSteps));
        o.put("misordered",              misordered);
        o.put("credentialLeaks",         toJsonArray(credentialLeaks));
        o.put("issues",                  toJsonArray(issues));

        JSONObject usage = new JSONObject();
        usage.put("inputTokens",  tokenUsage.inputTokens());
        usage.put("outputTokens", tokenUsage.outputTokens());
        usage.put("costUsd",      tokenUsage.totalCostUsd());
        o.put("tokenUsage", usage);

        return o;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static JSONArray toJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) list.forEach(arr::put);
        return arr;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String caseId = "";
        private String description = "";
        private String taskType = "";
        private String mode = "";
        private double stepRecall = 0.0;
        private double stepPrecision = 0.0;
        private double stepOrderScore = 0.0;
        private double labelAccuracyScore = 0.0;
        private double placeholderScore = 1.0;
        private double paginationScore = 0.0;
        private double overallScore = 0.0;
        private double judgeCorrectnessScore = 0.0;
        private double judgeOrderScore = 0.0;
        private double judgeHallucinationScore = 0.0;
        private double judgeLabelScore = 0.0;
        private double judgePlaceholderScore = 0.0;
        private double judgeOverallScore = 0.0;
        private String judgeReasoning = "";
        private List<String> generatedSteps = new ArrayList<>();
        private String generatedGoal = "";
        private String generatedPaginationType = "";
        private List<String> hallucinatedSteps = new ArrayList<>();
        private List<String> missingSteps = new ArrayList<>();
        private boolean misordered = false;
        private List<String> credentialLeaks = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private long durationMs = 0L;
        private TokenUsage tokenUsage = TokenUsage.ZERO;

        public Builder caseId(String v)               { this.caseId = v; return this; }
        public Builder description(String v)          { this.description = v; return this; }
        public Builder taskType(String v)             { this.taskType = v; return this; }
        public Builder mode(String v)                 { this.mode = v; return this; }
        public Builder stepRecall(double v)           { this.stepRecall = v; return this; }
        public Builder stepPrecision(double v)        { this.stepPrecision = v; return this; }
        public Builder stepOrderScore(double v)       { this.stepOrderScore = v; return this; }
        public Builder labelAccuracyScore(double v)   { this.labelAccuracyScore = v; return this; }
        public Builder placeholderScore(double v)     { this.placeholderScore = v; return this; }
        public Builder paginationScore(double v)      { this.paginationScore = v; return this; }
        public Builder overallScore(double v)         { this.overallScore = v; return this; }
        public Builder judgeCorrectnessScore(double v)   { this.judgeCorrectnessScore = v; return this; }
        public Builder judgeOrderScore(double v)         { this.judgeOrderScore = v; return this; }
        public Builder judgeHallucinationScore(double v) { this.judgeHallucinationScore = v; return this; }
        public Builder judgeLabelScore(double v)         { this.judgeLabelScore = v; return this; }
        public Builder judgePlaceholderScore(double v)   { this.judgePlaceholderScore = v; return this; }
        public Builder judgeOverallScore(double v)       { this.judgeOverallScore = v; return this; }
        public Builder judgeReasoning(String v)          { this.judgeReasoning = v; return this; }
        public Builder generatedSteps(List<String> v)    { this.generatedSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder generatedGoal(String v)           { this.generatedGoal = v; return this; }
        public Builder generatedPaginationType(String v) { this.generatedPaginationType = v; return this; }
        public Builder hallucinatedSteps(List<String> v) { this.hallucinatedSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder missingSteps(List<String> v)      { this.missingSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder misordered(boolean v)             { this.misordered = v; return this; }
        public Builder credentialLeaks(List<String> v)   { this.credentialLeaks = v != null ? v : new ArrayList<>(); return this; }
        public Builder issues(List<String> v)            { this.issues = v != null ? v : new ArrayList<>(); return this; }
        public Builder durationMs(long v)                { this.durationMs = v; return this; }
        public Builder tokenUsage(TokenUsage v)          { this.tokenUsage = v; return this; }

        public EvalResult build() { return new EvalResult(this); }
    }
}
