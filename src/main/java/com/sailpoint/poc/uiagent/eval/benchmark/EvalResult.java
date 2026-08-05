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
    private final String uiVariety;
    private final String description;
    private final String taskType;
    private final String mode;
    private final EvalCase.Expectation expectation;

    // ── Triage gate outcome ───────────────────────────────────────────────────
    private final String  gateVerdict;   // ACCEPT | REJECT | UNCERTAIN | SKIPPED
    private final String  gateCategory;
    private final int     gateConfidence;
    private final String  gateReason;
    private final String  expectedRejection;

    /** Set when the case died on an error rather than reaching a verdict. */
    private final boolean crashed;
    /** TRUST | REVIEW | CAUTION, or "" when the confidence check didn't run. */
    private final String  confidenceRecommendation;

    // ── Automated metrics (0.0–1.0) ──────────────────────────────────────────
    private final double stepRecall;
    private final double stepPrecision;
    private final double stepOrderScore;
    /** Diagnostic only — feeds no score. See {@link EvalMetrics} for why. */
    private final double labelAccuracyScore;
    private final double placeholderScore;
    private final double paginationScore;
    private final double overallScore;

    // ── Which checks actually applied to this case ───────────────────────────
    private final boolean placeholderApplicable;
    private final boolean paginationApplicable;
    private final boolean judgeApplicable;

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
    private final List<String> missingPlaceholders;
    private final List<String> issues;

    // ── Meta ──────────────────────────────────────────────────────────────────
    private final long       durationMs;
    private final TokenUsage tokenUsage;

    // ── Verdict: three independent conditions, all of which must hold ─────────
    private final boolean safetyGatePassed;
    private final boolean countedFactsPassed;
    private final boolean graderPassed;
    private final boolean passed;

    // ── Unhappy-path verdict ──────────────────────────────────────────────────
    private final boolean behavedAsExpected;
    private final boolean overConfident;
    private final boolean wronglyRejected;

    private EvalResult(Builder b) {
        this.caseId               = b.caseId;
        this.uiVariety            = b.uiVariety;
        this.description          = b.description;
        this.taskType             = b.taskType;
        this.mode                 = b.mode;
        this.expectation          = b.expectation != null ? b.expectation : EvalCase.Expectation.HAPPY;
        this.gateVerdict          = b.gateVerdict != null ? b.gateVerdict : "SKIPPED";
        this.gateCategory         = b.gateCategory != null ? b.gateCategory : "NONE";
        this.gateConfidence       = b.gateConfidence;
        this.gateReason           = b.gateReason != null ? b.gateReason : "";
        this.expectedRejection    = b.expectedRejection != null ? b.expectedRejection : "";
        this.crashed              = b.crashed;
        this.confidenceRecommendation = b.confidenceRecommendation != null ? b.confidenceRecommendation : "";
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
        this.missingPlaceholders      = Collections.unmodifiableList(new ArrayList<>(b.missingPlaceholders));
        this.issues                   = Collections.unmodifiableList(new ArrayList<>(b.issues));
        this.durationMs               = b.durationMs;
        this.tokenUsage               = b.tokenUsage != null ? b.tokenUsage : TokenUsage.ZERO;

        // Applicability defaults to what the task type and mode imply, but callers that know
        // more (e.g. whether the case declared any credentials at all) can state it outright.
        this.placeholderApplicable = b.placeholderApplicable != null
                ? b.placeholderApplicable
                : "PLACEHOLDER".equalsIgnoreCase(this.mode);
        this.paginationApplicable = b.paginationApplicable != null
                ? b.paginationApplicable
                : "AGGREGATION".equalsIgnoreCase(this.taskType);
        this.judgeApplicable = b.judgeApplicable;

        // Hard gate: every declared variable must appear as a placeholder in the generated steps.
        this.safetyGatePassed = this.missingPlaceholders.isEmpty();
        // Each counted fact clears its own bar — a blended average would let a failing
        // dimension hide behind strong ones.
        this.countedFactsPassed = stepRecall    >= EvalMetrics.MIN_STEP_RECALL
                               && stepPrecision >= EvalMetrics.MIN_STEP_PRECISION
                               && stepOrderScore >= EvalMetrics.MIN_STEP_ORDER;
        // A case is not penalised for a judge that was skipped or errored out; that is an
        // infrastructure gap, recorded in issues(), not a quality signal about the output.
        this.graderPassed = !this.judgeApplicable
                || judgeOverallScore >= EvalMetrics.MIN_JUDGE_OVERALL;

        boolean gateRejected  = "REJECT".equals(this.gateVerdict);
        boolean gateUncertain = "UNCERTAIN".equals(this.gateVerdict);
        boolean producedPlan  = !this.generatedSteps.isEmpty();

        // Turning away a video we could actually have used is a failure in its own right,
        // and one that only shows up on cases we expected to succeed.
        this.wronglyRejected = gateRejected && this.expectation.hasGroundTruthSteps();

        switch (this.expectation) {
            case HAPPY, INCORRECT -> {
                this.behavedAsExpected = !gateRejected
                        && this.safetyGatePassed && this.countedFactsPassed && this.graderPassed;
                this.overConfident = false;
            }
            case INVALID -> {
                // Zero tolerance: any plan at all is a failure. A crash also yields no steps,
                // so silence only counts as a refusal when nothing errored.
                this.behavedAsExpected = gateRejected || (!producedPlan && !this.crashed);
                this.overConfident = producedPlan;
            }
            case UNWORKABLE -> {
                boolean flagged = gateUncertain
                        || "CAUTION".equals(this.confidenceRecommendation)
                        || "REVIEW".equals(this.confidenceRecommendation)
                        || !producedPlan;
                this.behavedAsExpected = gateRejected || flagged;
                // The specific danger here is a confident plan built on guesswork.
                this.overConfident = producedPlan && !gateRejected && !flagged;
            }
            default -> {
                this.behavedAsExpected = false;
                this.overConfident = false;
            }
        }

        // Unhappy cases have no known-good steps to score, so their verdict *is* the pass.
        this.passed = this.behavedAsExpected;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     caseId()               { return caseId; }
    public String     uiVariety()            { return uiVariety == null || uiVariety.isBlank() ? "(untagged)" : uiVariety; }
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
    public List<String> missingPlaceholders()   { return missingPlaceholders; }
    public List<String> issues()               { return issues; }
    public long       durationMs()             { return durationMs; }
    public TokenUsage tokenUsage()             { return tokenUsage; }
    public boolean    placeholderApplicable()  { return placeholderApplicable; }
    public boolean    paginationApplicable()   { return paginationApplicable; }
    public boolean    judgeApplicable()        { return judgeApplicable; }
    public boolean    safetyGatePassed()       { return safetyGatePassed; }
    public boolean    countedFactsPassed()     { return countedFactsPassed; }
    public boolean    graderPassed()           { return graderPassed; }
    public boolean    passed()                 { return passed; }
    public EvalCase.Expectation expectation()  { return expectation; }
    public String     gateVerdict()            { return gateVerdict; }
    public String     gateCategory()           { return gateCategory; }
    public int        gateConfidence()         { return gateConfidence; }
    public String     gateReason()             { return gateReason; }
    public String     expectedRejection()      { return expectedRejection; }
    public boolean    crashed()                { return crashed; }
    public String     confidenceRecommendation() { return confidenceRecommendation; }
    public boolean    behavedAsExpected()      { return behavedAsExpected; }
    public boolean    overConfident()          { return overConfident; }
    public boolean    wronglyRejected()        { return wronglyRejected; }
    public boolean    isHappy()                { return expectation == EvalCase.Expectation.HAPPY; }
    public boolean    isUnhappy()              { return expectation != EvalCase.Expectation.HAPPY; }

    /**
     * True when the gate named the exact category the case expected. Reported as a
     * diagnostic: refusing is the requirement, naming the right reason is a bonus.
     */
    public boolean rejectionCategoryMatched() {
        return !expectedRejection.isBlank() && expectedRejection.equalsIgnoreCase(gateCategory);
    }

    /** Short human-readable reason a case failed, or {@code ""} when it passed. */
    public String failureReason() {
        if (passed) return "";
        List<String> reasons = new ArrayList<>();
        if (expectation.hasGroundTruthSteps()) {
            if (wronglyRejected)     reasons.add("usable video wrongly rejected");
            if (!safetyGatePassed)   reasons.add("credentials not tokenized");
            if (!countedFactsPassed) reasons.add("counted facts below bar");
            if (!graderPassed)       reasons.add("judge below bar");
        } else {
            if (overConfident)   reasons.add("produced a confident plan from an unusable video");
            else if (crashed)    reasons.add("errored rather than refusing");
            else                 reasons.add("did not refuse or flag the input");
        }
        return String.join("; ", reasons);
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    /** Serializes this result to a JSON object for report writing. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("caseId",      caseId);
        o.put("uiVariety",   uiVariety());
        o.put("description", description);
        o.put("taskType",    taskType);
        o.put("mode",        mode);
        o.put("passed",      passed);
        o.put("durationMs",  durationMs);

        // Each condition is reported separately so a reader can see *which* one failed
        // without having to reverse-engineer it from a blended number.
        JSONObject verdict = new JSONObject();
        verdict.put("safetyGatePassed",   safetyGatePassed);
        verdict.put("countedFactsPassed", countedFactsPassed);
        verdict.put("graderPassed",       judgeApplicable ? (Object) graderPassed : JSONObject.NULL);
        verdict.put("failureReason",      failureReason());
        o.put("verdict", verdict);

        // Checks that did not apply are serialized as null, never as a number. A 0.0 here
        // reads as "scored zero" and a 1.0 as "passed", when the truth is "never ran".
        JSONObject metrics = new JSONObject();
        metrics.put("stepRecall",       round(stepRecall));
        metrics.put("stepPrecision",    round(stepPrecision));
        metrics.put("stepOrderScore",   round(stepOrderScore));
        metrics.put("placeholderScore", placeholderApplicable ? (Object) round(placeholderScore) : JSONObject.NULL);
        metrics.put("paginationScore",  paginationApplicable  ? (Object) round(paginationScore)  : JSONObject.NULL);
        metrics.put("overallScore",     round(overallScore));
        o.put("metrics", metrics);

        JSONObject applicable = new JSONObject();
        applicable.put("placeholder", placeholderApplicable);
        applicable.put("pagination",  paginationApplicable);
        applicable.put("judge",       judgeApplicable);
        o.put("checksApplicable", applicable);

        // Kept away from `metrics` so it is not mistaken for something that affects the verdict.
        JSONObject diagnostics = new JSONObject();
        diagnostics.put("labelAccuracyScore", round(labelAccuracyScore));
        o.put("diagnostics", diagnostics);

        JSONObject judge = new JSONObject();
        if (judgeApplicable) {
            judge.put("correctness",   round(judgeCorrectnessScore));
            judge.put("order",         round(judgeOrderScore));
            judge.put("hallucination", round(judgeHallucinationScore));
            judge.put("labelQuality",  round(judgeLabelScore));
            // The judge is told to answer 10 in LITERAL mode, so the number carries no signal.
            judge.put("placeholder",   placeholderApplicable ? (Object) round(judgePlaceholderScore) : JSONObject.NULL);
            judge.put("overall",       round(judgeOverallScore));
            judge.put("reasoning",     judgeReasoning != null ? judgeReasoning : "");
        } else {
            for (String k : new String[]{"correctness", "order", "hallucination",
                                         "labelQuality", "placeholder", "overall"}) {
                judge.put(k, JSONObject.NULL);
            }
            judge.put("reasoning", "");
        }
        o.put("judgeScores", judge);

        o.put("generatedGoal",           generatedGoal != null ? generatedGoal : "");
        o.put("generatedPaginationType", generatedPaginationType != null ? generatedPaginationType : "");
        o.put("generatedSteps",          toJsonArray(generatedSteps));
        o.put("hallucinatedSteps",       toJsonArray(hallucinatedSteps));
        o.put("missingSteps",            toJsonArray(missingSteps));
        o.put("misordered",              misordered);
        o.put("missingPlaceholders",     toJsonArray(missingPlaceholders));
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
        private String uiVariety = "";
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
        private List<String> missingPlaceholders = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private long durationMs = 0L;
        private TokenUsage tokenUsage = TokenUsage.ZERO;
        // Null means "infer from taskType/mode".
        private Boolean placeholderApplicable = null;
        private Boolean paginationApplicable = null;
        private boolean judgeApplicable = false;

        public Builder caseId(String v)               { this.caseId = v; return this; }
        public Builder uiVariety(String v)            { this.uiVariety = v; return this; }
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
        public Builder missingPlaceholders(List<String> v) { this.missingPlaceholders = v != null ? v : new ArrayList<>(); return this; }
        public Builder issues(List<String> v)            { this.issues = v != null ? v : new ArrayList<>(); return this; }
        public Builder durationMs(long v)                { this.durationMs = v; return this; }
        public Builder tokenUsage(TokenUsage v)          { this.tokenUsage = v; return this; }
        public Builder placeholderApplicable(boolean v)  { this.placeholderApplicable = v; return this; }
        public Builder paginationApplicable(boolean v)   { this.paginationApplicable = v; return this; }
        public Builder judgeApplicable(boolean v)        { this.judgeApplicable = v; return this; }

        public EvalResult build() { return new EvalResult(this); }
    }
}
