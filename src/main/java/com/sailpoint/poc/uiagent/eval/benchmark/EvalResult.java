package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.TokenUsage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores all scores and metadata for one benchmark eval case run.
 *
 * <h2>What decides a case</h2>
 * For cases with ground truth ({@code HAPPY}, {@code INCORRECT}) the LLM judge is the only
 * quality gate. Word-overlap metrics and per-case assertions used to gate alongside it and
 * no longer exist.
 *
 * <p>Two non-quality conditions still fail a case, because both describe a run where
 * <em>no judge score exists</em>: a usable video turned away by the triage gate, and a run
 * that errored before producing a plan. Without them a rejected or crashed case would pass
 * on the strength of a verdict it never received.
 *
 * <p>Cases without ground truth ({@code INVALID}, {@code UNWORKABLE}) are never judged —
 * there is nothing to compare against — so they are decided on their verdict as before.
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

    // ── LLM judge (0.0–1.0) — the only quality signal ────────────────────────
    private final boolean judgeApplicable;
    private final double  judgeCorrectnessScore;
    private final double  judgeOrderScore;
    private final double  judgeHallucinationScore;
    private final double  judgeOverallScore;
    private final boolean judgeTestPassed;
    private final String  judgeReasoning;
    /** True when the judge call or parse failed — infrastructure, not plan quality. */
    private final boolean judgeFailed;
    /** Self-contradictions found in the judge's own response. */
    private final List<String> judgeIssues;

    // ── Generated output ──────────────────────────────────────────────────────
    private final List<String> generatedSteps;
    private final String       generatedGoal;
    private final String       generatedPaginationType;

    // ── Evidence, as quoted by the judge ─────────────────────────────────────
    private final List<String> hallucinatedSteps;
    private final List<String> missingSteps;
    private final List<String> issues;

    // ── Meta ──────────────────────────────────────────────────────────────────
    private final long       durationMs;
    private final TokenUsage tokenUsage;

    // ── Verdict ───────────────────────────────────────────────────────────────
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
        this.judgeApplicable          = b.judgeApplicable;
        this.judgeCorrectnessScore    = b.judgeCorrectnessScore;
        this.judgeOrderScore          = b.judgeOrderScore;
        this.judgeHallucinationScore  = b.judgeHallucinationScore;
        this.judgeOverallScore        = b.judgeOverallScore;
        this.judgeTestPassed          = b.judgeTestPassed;
        this.judgeReasoning           = b.judgeReasoning;
        this.judgeFailed              = b.judgeFailed;
        this.judgeIssues              = Collections.unmodifiableList(new ArrayList<>(b.judgeIssues));
        this.generatedSteps           = Collections.unmodifiableList(new ArrayList<>(b.generatedSteps));
        this.generatedGoal            = b.generatedGoal;
        this.generatedPaginationType  = b.generatedPaginationType;
        this.hallucinatedSteps        = Collections.unmodifiableList(new ArrayList<>(b.hallucinatedSteps));
        this.missingSteps             = Collections.unmodifiableList(new ArrayList<>(b.missingSteps));
        this.issues                   = Collections.unmodifiableList(new ArrayList<>(b.issues));
        this.durationMs               = b.durationMs;
        this.tokenUsage               = b.tokenUsage != null ? b.tokenUsage : TokenUsage.ZERO;

        // A judge that never ran cannot pass a case. This used to default to true, which was
        // safe only because two other conditions gated alongside it; as the sole gate it would
        // turn every skipped or failed judge call into a free pass.
        this.graderPassed = this.judgeApplicable && !this.judgeFailed && this.judgeTestPassed;

        boolean gateRejected  = "REJECT".equals(this.gateVerdict);
        boolean gateUncertain = "UNCERTAIN".equals(this.gateVerdict);
        boolean producedPlan  = !this.generatedSteps.isEmpty();

        // Turning away a video we could actually have used is a failure in its own right,
        // and one that only shows up on cases we expected to succeed.
        this.wronglyRejected = gateRejected && this.expectation.hasGroundTruthSteps();

        switch (this.expectation) {
            case HAPPY, INCORRECT -> {
                this.behavedAsExpected = !this.wronglyRejected && !this.crashed && this.graderPassed;
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

        this.passed = this.behavedAsExpected;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     caseId()               { return caseId; }
    public String     uiVariety()            { return uiVariety == null || uiVariety.isBlank() ? "(untagged)" : uiVariety; }
    public String     description()          { return description; }
    public String     taskType()             { return taskType; }
    public String     mode()                 { return mode; }
    public double     judgeCorrectnessScore()   { return judgeCorrectnessScore; }
    public double     judgeOrderScore()         { return judgeOrderScore; }
    public double     judgeHallucinationScore() { return judgeHallucinationScore; }
    public double     judgeOverallScore()       { return judgeOverallScore; }
    public boolean    judgeTestPassed()         { return judgeTestPassed; }
    public String     judgeReasoning()          { return judgeReasoning; }
    public boolean    judgeFailed()             { return judgeFailed; }
    public List<String> judgeIssues()           { return judgeIssues; }
    public List<String> generatedSteps()        { return generatedSteps; }
    public String     generatedGoal()           { return generatedGoal; }
    public String     generatedPaginationType() { return generatedPaginationType; }
    public List<String> hallucinatedSteps()     { return hallucinatedSteps; }
    public List<String> missingSteps()          { return missingSteps; }
    public List<String> issues()               { return issues; }
    public long       durationMs()             { return durationMs; }
    public TokenUsage tokenUsage()             { return tokenUsage; }
    public boolean    judgeApplicable()        { return judgeApplicable; }
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

    /** True when this case was scored by the judge, i.e. it has ground truth to compare against. */
    public boolean scoredAgainstGroundTruth() { return expectation.hasGroundTruthSteps(); }

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
            if (wronglyRejected)      reasons.add("usable video wrongly rejected");
            else if (crashed)         reasons.add("errored before producing a plan");
            else if (judgeFailed)     reasons.add("judge unavailable — case unscored, not failed on quality");
            else if (!judgeApplicable) reasons.add("judge did not run — case unscored");
            else                      reasons.add(judgeSummary());
        } else {
            if (overConfident)   reasons.add("produced a confident plan from an unusable video");
            else if (crashed)    reasons.add("errored rather than refusing");
            else                 reasons.add("did not refuse or flag the input");
        }
        return String.join("; ", reasons);
    }

    /**
     * Names which bar the judge missed and quotes its reasoning, which is now the only
     * diagnostic a reviewer gets on a failing case.
     */
    private String judgeSummary() {
        List<String> parts = new ArrayList<>();
        if (judgeCorrectnessScore < com.sailpoint.poc.uiagent.eval.shared.LlmJudge.MIN_CORRECTNESS) {
            parts.add(String.format("correctness %.2f", judgeCorrectnessScore));
        }
        if (judgeOverallScore < com.sailpoint.poc.uiagent.eval.shared.LlmJudge.MIN_OVERALL) {
            parts.add(String.format("overall %.2f", judgeOverallScore));
        }
        String bars = parts.isEmpty() ? "judge below bar" : "judge below bar (" + String.join(", ", parts) + ")";
        return judgeReasoning == null || judgeReasoning.isBlank() ? bars : bars + " — " + judgeReasoning;
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
        o.put("expectation", expectation.name());
        o.put("durationMs",  durationMs);

        boolean scored = expectation.hasGroundTruthSteps();
        JSONObject verdict = new JSONObject();
        verdict.put("behavedAsExpected", behavedAsExpected);
        verdict.put("graderPassed",
                (scored && judgeApplicable) ? (Object) graderPassed : JSONObject.NULL);
        verdict.put("overConfident",     scored ? JSONObject.NULL : (Object) overConfident);
        verdict.put("wronglyRejected",   wronglyRejected);
        verdict.put("crashed",           crashed);
        verdict.put("judgeFailed",       judgeFailed);
        verdict.put("failureReason",     failureReason());
        o.put("verdict", verdict);

        JSONObject gate = new JSONObject();
        gate.put("verdict",    gateVerdict);
        gate.put("category",   gateCategory);
        gate.put("confidence", gateConfidence);
        gate.put("reason",     gateReason);
        gate.put("expectedRejection", expectedRejection.isBlank() ? JSONObject.NULL : expectedRejection);
        gate.put("categoryMatched",
                expectedRejection.isBlank() ? JSONObject.NULL : (Object) rejectionCategoryMatched());
        o.put("triageGate", gate);

        o.put("confidenceRecommendation",
                confidenceRecommendation.isBlank() ? JSONObject.NULL : confidenceRecommendation);

        // Scores are null, never 0.0, when the judge did not run. A 0.0 reads as "scored
        // zero" when the truth is "never scored", and those mean opposite things.
        JSONObject judge = new JSONObject();
        if (judgeApplicable && !judgeFailed) {
            judge.put("correctness",   round(judgeCorrectnessScore));
            judge.put("order",         round(judgeOrderScore));
            judge.put("hallucination", round(judgeHallucinationScore));
            judge.put("overall",       round(judgeOverallScore));
            judge.put("testPassed",    judgeTestPassed);
            judge.put("reasoning",     judgeReasoning != null ? judgeReasoning : "");
        } else {
            for (String k : new String[]{"correctness", "order", "hallucination", "overall"}) {
                judge.put(k, JSONObject.NULL);
            }
            judge.put("testPassed", JSONObject.NULL);
            judge.put("reasoning",  judgeReasoning != null ? judgeReasoning : "");
        }
        judge.put("failed", judgeFailed);
        judge.put("issues", toJsonArray(judgeIssues));
        o.put("judgeScores", judge);

        o.put("generatedGoal",           generatedGoal != null ? generatedGoal : "");
        o.put("generatedPaginationType", generatedPaginationType != null ? generatedPaginationType : "");
        o.put("generatedSteps",          toJsonArray(generatedSteps));
        // Quoted by the judge from its own comparison, not computed by word overlap.
        o.put("hallucinatedSteps",       toJsonArray(hallucinatedSteps));
        o.put("missingSteps",            toJsonArray(missingSteps));
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
        private double judgeCorrectnessScore = 0.0;
        private double judgeOrderScore = 0.0;
        private double judgeHallucinationScore = 0.0;
        private double judgeOverallScore = 0.0;
        private boolean judgeTestPassed = false;
        private String judgeReasoning = "";
        private boolean judgeFailed = false;
        private List<String> judgeIssues = new ArrayList<>();
        private List<String> generatedSteps = new ArrayList<>();
        private String generatedGoal = "";
        private String generatedPaginationType = "";
        private List<String> hallucinatedSteps = new ArrayList<>();
        private List<String> missingSteps = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private long durationMs = 0L;
        private TokenUsage tokenUsage = TokenUsage.ZERO;
        private boolean judgeApplicable = false;
        private EvalCase.Expectation expectation = EvalCase.Expectation.HAPPY;
        private String gateVerdict = "SKIPPED";
        private String gateCategory = "NONE";
        private int gateConfidence = 0;
        private String gateReason = "";
        private String expectedRejection = "";
        private boolean crashed = false;
        private String confidenceRecommendation = "";

        public Builder caseId(String v)               { this.caseId = v; return this; }
        public Builder uiVariety(String v)            { this.uiVariety = v; return this; }
        public Builder description(String v)          { this.description = v; return this; }
        public Builder taskType(String v)             { this.taskType = v; return this; }
        public Builder mode(String v)                 { this.mode = v; return this; }
        public Builder judgeCorrectnessScore(double v)   { this.judgeCorrectnessScore = v; return this; }
        public Builder judgeOrderScore(double v)         { this.judgeOrderScore = v; return this; }
        public Builder judgeHallucinationScore(double v) { this.judgeHallucinationScore = v; return this; }
        public Builder judgeOverallScore(double v)       { this.judgeOverallScore = v; return this; }
        public Builder judgeTestPassed(boolean v)        { this.judgeTestPassed = v; return this; }
        public Builder judgeReasoning(String v)          { this.judgeReasoning = v; return this; }
        public Builder judgeFailed(boolean v)            { this.judgeFailed = v; return this; }
        public Builder judgeIssues(List<String> v)       { this.judgeIssues = v != null ? v : new ArrayList<>(); return this; }
        public Builder generatedSteps(List<String> v)    { this.generatedSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder generatedGoal(String v)           { this.generatedGoal = v; return this; }
        public Builder generatedPaginationType(String v) { this.generatedPaginationType = v; return this; }
        public Builder hallucinatedSteps(List<String> v) { this.hallucinatedSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder missingSteps(List<String> v)      { this.missingSteps = v != null ? v : new ArrayList<>(); return this; }
        public Builder issues(List<String> v)            { this.issues = v != null ? v : new ArrayList<>(); return this; }
        public Builder durationMs(long v)                { this.durationMs = v; return this; }
        public Builder tokenUsage(TokenUsage v)          { this.tokenUsage = v; return this; }
        public Builder judgeApplicable(boolean v)        { this.judgeApplicable = v; return this; }
        public Builder expectation(EvalCase.Expectation v) { this.expectation = v; return this; }
        public Builder gateVerdict(String v)             { this.gateVerdict = v; return this; }
        public Builder gateCategory(String v)            { this.gateCategory = v; return this; }
        public Builder gateConfidence(int v)             { this.gateConfidence = v; return this; }
        public Builder gateReason(String v)              { this.gateReason = v; return this; }
        public Builder expectedRejection(String v)       { this.expectedRejection = v; return this; }
        public Builder crashed(boolean v)                { this.crashed = v; return this; }
        public Builder confidenceRecommendation(String v) { this.confidenceRecommendation = v; return this; }

        public EvalResult build() { return new EvalResult(this); }
    }
}
