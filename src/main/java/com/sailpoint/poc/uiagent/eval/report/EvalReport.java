package com.sailpoint.poc.uiagent.eval.report;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalCase;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalResult;
import com.sailpoint.poc.uiagent.eval.shared.LlmJudge;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Generates console summary and JSON report file for benchmark eval runs.
 */
public final class EvalReport {

    private static final String SEP_WIDE = "════════════════════════════════════════════════════════════════";
    private static final String SEP_MED  = "────────────────────────────────────────────────────────────────";

    private EvalReport() {}

    /**
     * Prints a formatted summary to stdout and writes a JSON report file.
     *
     * @param results   list of per-case eval results
     * @param modelId   the model id used during the run
     * @param outputDir directory to write the JSON report file
     * @throws IOException if the report file cannot be written
     */
    public static void generate(List<EvalResult> results, String modelId, String outputDir)
            throws IOException {
        generate(results, modelId, outputDir, System.out::println);
    }

    /**
     * Generates report with a custom log consumer (used when called from the web UI).
     */
    public static void generate(List<EvalResult> results, String modelId, String outputDir,
                                Consumer<String> log) throws IOException {
        printConsoleReport(results, modelId, log);
        writeJsonReport(results, modelId, outputDir, log);
    }

    // ── Console output ────────────────────────────────────────────────────────

    private static void printConsoleReport(List<EvalResult> results, String modelId) {
        printConsoleReport(results, modelId, System.out::println);
    }

    private static void printConsoleReport(List<EvalResult> results, String modelId, Consumer<String> log) {
        String runAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long passed  = results.stream().filter(EvalResult::passed).count();
        long failed  = results.size() - passed;
        double passRate = results.isEmpty() ? 0.0 : (double) passed / results.size() * 100.0;

        log.accept("");
        log.accept(SEP_WIDE);
        log.accept("VIDEO ANALYSIS EVAL REPORT");
        log.accept(SEP_WIDE);
        log.accept(String.format("Run at         : %s", runAt));
        log.accept(String.format("Model          : %s", modelId));
        log.accept(String.format("Cases run      : %d", results.size()));
        log.accept(String.format("Passed         : %d", passed));
        log.accept(String.format("Failed         : %d", failed));
        log.accept(String.format("Pass rate      : %.1f%%", passRate));

        // Happy and unhappy cases are scored on different questions, so a single blended
        // pass rate hides the one that matters. A run can look strong purely because it is
        // mostly happy cases, while every refusal test is failing.
        List<EvalResult> happy   = results.stream().filter(EvalResult::isHappy).toList();
        List<EvalResult> unhappy = results.stream().filter(EvalResult::isUnhappy).toList();
        if (!happy.isEmpty() && !unhappy.isEmpty()) {
            log.accept("");
            log.accept("Happy vs unhappy:");
            log.accept(String.format("  Happy path   : %s", passFraction(happy)));
            log.accept(String.format("  Unhappy path : %s   (behaved as expected)", passFraction(unhappy)));
        }

        // The headline safety number: an unusable video that still produced a confident plan
        // is the failure this whole exercise exists to catch.
        long overConfident = results.stream().filter(EvalResult::overConfident).count();
        long wronglyRejected = results.stream().filter(EvalResult::wronglyRejected).count();
        if (!unhappy.isEmpty() || wronglyRejected > 0) {
            log.accept("");
            log.accept("Safety:");
            log.accept(String.format("  Over-confident plans     : %d   (unusable video → confident plan)",
                    overConfident));
            log.accept(String.format("  Usable videos rejected   : %d   (triage turned away a good recording)",
                    wronglyRejected));
        }

        // The judge is the only quality gate, so the thing to report is how many cases it
        // actually reached a verdict on. A case it never scored is neither a pass nor a
        // quality failure, and collapsing the two would overstate how much this run proved.
        List<EvalResult> scored = results.stream()
                .filter(r -> r.expectation().hasGroundTruthSteps()).toList();
        if (!scored.isEmpty()) {
            long judged    = scored.stream().filter(r -> r.judgeApplicable() && !r.judgeFailed()).count();
            long unscored  = scored.size() - judged;
            long atBar     = scored.stream().filter(EvalResult::graderPassed).count();

            log.accept("");
            log.accept("AI judge — the only pass condition for the " + scored.size()
                    + " case(s) with ground truth:");
            log.accept(judged == 0
                    ? "  At bar         : judge did not run — no case can pass"
                    : String.format("  At bar         : %d/%d   (overall>=%.2f and correctness>=%.2f)",
                            atBar, judged, LlmJudge.MIN_OVERALL, LlmJudge.MIN_CORRECTNESS));
            if (unscored > 0) {
                log.accept(String.format(
                        "  ⚠ Unscored     : %d case(s) — judge unavailable or skipped, not a quality result",
                        unscored));
            }

            // The judge grades itself here: these are places its own response contradicted
            // itself. A run with many of them is a run whose scores should not be trusted.
            long withIssues = scored.stream().filter(r -> !r.judgeIssues().isEmpty()).count();
            if (withIssues > 0) {
                log.accept(String.format(
                        "  ⚠ Self-checks  : %d case(s) where the judge contradicted itself — see judgeIssues",
                        withIssues));
            }
        }

        // ── Breakdown by expectation family ────────────────────────────────────
        if (!unhappy.isEmpty()) {
            log.accept("");
            log.accept("By expectation family:");
            log.accept(SEP_MED);
            log.accept(String.format("%-14s %-7s %-12s %-15s %s",
                    "Family", "Cases", "Passed", "Over-confident", "Wrongly rejected"));
            log.accept(SEP_MED);
            for (EvalCase.Expectation e : EvalCase.Expectation.values()) {
                List<EvalResult> group = results.stream()
                        .filter(r -> r.expectation() == e).toList();
                if (group.isEmpty()) continue;
                log.accept(String.format("%-14s %-7d %-12s %-15d %d",
                        e.name(), group.size(), passFraction(group),
                        group.stream().filter(EvalResult::overConfident).count(),
                        group.stream().filter(EvalResult::wronglyRejected).count()));
            }
        }

        log.accept("");
        log.accept("Per-Case Results:");
        log.accept(SEP_MED);
        log.accept(String.format("%-22s %-22s %-4s %-4s %-10s %-6s %-6s %-6s %s",
                "ID", "Description", "T", "M", "Expect", "Corr", "Judge", "Result", "Why"));
        log.accept(SEP_MED);

        for (EvalResult r : results) {
            String desc = r.description().length() > 21
                    ? r.description().substring(0, 18) + "..."
                    : r.description();
            String tAbbr = "AGGREGATION".equalsIgnoreCase(r.taskType()) ? "AGG" : "PRO";
            String mAbbr = "PLACEHOLDER".equalsIgnoreCase(r.mode()) ? "PH" : "LI";
            String result = r.passed() ? "PASS" : "FAIL";
            // Correctness is shown next to the composite because it gates independently:
            // a case can clear the composite and still fail on correctness alone.
            boolean haveScores = r.judgeApplicable() && !r.judgeFailed();
            log.accept(String.format("%-22s %-22s %-4s %-4s %-10s %-6s %-6s %-6s %s",
                    truncate(r.caseId(), 22), desc, tAbbr, mAbbr,
                    abbreviate(r.expectation()),
                    haveScores ? String.format("%.2f", r.judgeCorrectnessScore()) : "N/A",
                    haveScores ? String.format("%.2f", r.judgeOverallScore()) : "N/A",
                    result,
                    r.failureReason()));
        }

        log.accept("");
        log.accept("T = taskType (AGG=AGGREGATION  PRO=PROVISIONING)");
        log.accept("M = mode     (PH=PLACEHOLDER   LI=LITERAL)");
        log.accept("Expect = expectation family. INVALID/UNWORKABLE have no ground truth,");
        log.accept("         so they pass by refusing or flagging rather than by matching.");
        log.accept(String.format(
                "Corr / Judge = judge correctness and composite, 0.00-1.00. Both gate: a case "
                + "passes only at correctness>=%.2f and overall>=%.2f.",
                LlmJudge.MIN_CORRECTNESS, LlmJudge.MIN_OVERALL));

        // ── Judge averages ─────────────────────────────────────────────────────
        // Averaged over cases the judge actually scored. Refusal cases are never judged, and
        // a failed judge call is an absent score rather than a zero, so both are excluded —
        // folding either in would report a low number that says nothing about quality.
        if (!scored.isEmpty()) {
            java.util.function.Predicate<EvalResult> haveScores =
                    r -> r.judgeApplicable() && !r.judgeFailed();
            long judged = scored.stream().filter(haveScores).count();
            log.accept("");
            log.accept("Judge averages (" + judged + " of " + scored.size() + " case(s) scored):");
            log.accept(String.format("  Overall       : %s   ← the headline quality score",
                    fmtScore(avgApplicable(scored, haveScores, EvalResult::judgeOverallScore))));
            log.accept(String.format("  Correctness   : %s   (weight 0.40, also gates on its own)",
                    fmtScore(avgApplicable(scored, haveScores, EvalResult::judgeCorrectnessScore))));
            log.accept(String.format("  Order         : %s   (weight 0.30)",
                    fmtScore(avgApplicable(scored, haveScores, EvalResult::judgeOrderScore))));
            log.accept(String.format("  Hallucination : %s   (weight 0.30, 1.00 = nothing invented)",
                    fmtScore(avgApplicable(scored, haveScores, EvalResult::judgeHallucinationScore))));
        }

        // ── Results by UI variety ──────────────────────────────────────────────
        Map<String, List<EvalResult>> byVariety = groupByVariety(results);
        if (!byVariety.isEmpty()) {
            log.accept("");
            log.accept("Results by UI variety:");
            log.accept(SEP_MED);
            log.accept(String.format("%-28s %-7s %-8s %-8s %s",
                    "UI variety", "Cases", "Pass", "Corr", "Judge"));
            log.accept(SEP_MED);
            java.util.function.Predicate<EvalResult> haveScores =
                    r -> r.judgeApplicable() && !r.judgeFailed();
            for (Map.Entry<String, List<EvalResult>> e : byVariety.entrySet()) {
                List<EvalResult> group = e.getValue();
                long groupPassed = group.stream().filter(EvalResult::passed).count();
                log.accept(String.format("%-28s %-7d %-8s %-8s %s",
                        truncate(e.getKey(), 28),
                        group.size(),
                        groupPassed + "/" + group.size(),
                        fmtScore(avgApplicable(group, haveScores, EvalResult::judgeCorrectnessScore)),
                        fmtScore(avgApplicable(group, haveScores, EvalResult::judgeOverallScore))));
            }
        }

        // ── Failure type summary ───────────────────────────────────────────────
        Map<String, Long> failureTypes = countFailureTypes(results);
        if (!failureTypes.isEmpty()) {
            log.accept("");
            log.accept("Most Common Failure Types:");
            int rank = 1;
            for (Map.Entry<String, Long> entry : failureTypes.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .toList()) {
                log.accept(String.format("  %d. %-30s: %d case%s",
                        rank++, entry.getKey(), entry.getValue(),
                        entry.getValue() == 1 ? "" : "s"));
            }
        }

        // ── Token usage ────────────────────────────────────────────────────────
        TokenUsage totalUsage = results.stream()
                .map(EvalResult::tokenUsage)
                .reduce(TokenUsage.ZERO, TokenUsage::add);
        log.accept("");
        log.accept("Token Usage:");
        log.accept(String.format("  Input tokens  : %d  ($%.4f)",
                totalUsage.inputTokens(), totalUsage.inputCostUsd()));
        System.out.printf("  Output tokens : %d  ($%.4f)%n",
                totalUsage.outputTokens(), totalUsage.outputCostUsd());
        System.out.printf("  Total cost    : $%.4f%n", totalUsage.totalCostUsd());
        log.accept(SEP_WIDE);
        log.accept("");
    }

    // ── JSON report ───────────────────────────────────────────────────────────

    private static void writeJsonReport(List<EvalResult> results, String modelId, String outputDir)
            throws IOException {
        writeJsonReport(results, modelId, outputDir, System.out::println);
    }

    private static void writeJsonReport(List<EvalResult> results, String modelId, String outputDir,
                                        Consumer<String> log) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        Path reportFile = dir.resolve("eval-report_" + timestamp + ".json");

        long passed   = results.stream().filter(EvalResult::passed).count();
        long failed   = results.size() - passed;
        double passRate = results.isEmpty() ? 0.0 : (double) passed / results.size();

        JSONObject root = new JSONObject();
        root.put("runAt",      LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("modelId",    modelId);
        root.put("totalCases", results.size());
        root.put("passed",     passed);
        root.put("failed",     failed);
        root.put("passRate",   passRate);

        List<EvalResult> scored = results.stream()
                .filter(r -> r.expectation().hasGroundTruthSteps()).toList();
        List<EvalResult> happyOnly   = results.stream().filter(EvalResult::isHappy).toList();
        List<EvalResult> unhappyOnly = results.stream().filter(EvalResult::isUnhappy).toList();

        // Reported apart so a strong happy-path score cannot mask weak refusal behaviour.
        JSONObject split = new JSONObject();
        split.put("happy",   splitSummary(happyOnly));
        split.put("unhappy", splitSummary(unhappyOnly));
        root.put("happyVsUnhappy", split);

        JSONObject safety = new JSONObject();
        safety.put("overConfidentPlans", results.stream().filter(EvalResult::overConfident).count());
        safety.put("usableVideosRejected", results.stream().filter(EvalResult::wronglyRejected).count());
        root.put("safety", safety);

        JSONObject families = new JSONObject();
        for (EvalCase.Expectation e : EvalCase.Expectation.values()) {
            List<EvalResult> group = results.stream().filter(r -> r.expectation() == e).toList();
            if (group.isEmpty()) continue;
            JSONObject f = splitSummary(group);
            f.put("overConfident",   group.stream().filter(EvalResult::overConfident).count());
            f.put("wronglyRejected", group.stream().filter(EvalResult::wronglyRejected).count());
            families.put(e.name(), f);
        }
        root.put("byExpectation", families);

        // Scoped to the scored cases: refusal cases are never judged.
        java.util.function.Predicate<EvalResult> haveScores =
                r -> r.judgeApplicable() && !r.judgeFailed();
        long judgedCount = scored.stream().filter(haveScores).count();

        JSONObject conditions = new JSONObject();
        conditions.put("scoredCases",  scored.size());
        conditions.put("casesJudged",  judgedCount);
        conditions.put("graderPassed", scored.stream().filter(EvalResult::graderPassed).count());
        // A case the judge never reached is not a quality failure. Kept separate so a run
        // degraded by Bedrock outages cannot be read as the agent getting worse.
        conditions.put("unscored",     scored.size() - judgedCount);
        conditions.put("judgeFailed",  scored.stream().filter(EvalResult::judgeFailed).count());
        conditions.put("casesWithJudgeSelfContradictions",
                scored.stream().filter(r -> !r.judgeIssues().isEmpty()).count());
        root.put("passConditions", conditions);

        JSONObject thresholds = new JSONObject();
        thresholds.put("minJudgeOverall",     LlmJudge.MIN_OVERALL);
        thresholds.put("minJudgeCorrectness", LlmJudge.MIN_CORRECTNESS);
        root.put("thresholds", thresholds);

        // Averaged over the cases the judge actually scored, null when it scored none.
        JSONObject avgScores = new JSONObject();
        avgScores.put("basedOnCases",  judgedCount);
        avgScores.put("correctness",   jsonOrNull(avgApplicable(scored, haveScores, EvalResult::judgeCorrectnessScore)));
        avgScores.put("order",         jsonOrNull(avgApplicable(scored, haveScores, EvalResult::judgeOrderScore)));
        avgScores.put("hallucination", jsonOrNull(avgApplicable(scored, haveScores, EvalResult::judgeHallucinationScore)));
        avgScores.put("judgeOverall",  jsonOrNull(avgApplicable(scored, haveScores, EvalResult::judgeOverallScore)));
        root.put("averageScores", avgScores);

        Map<String, Long> failureTypes = countFailureTypes(results);
        JSONObject failureJson = new JSONObject();
        failureTypes.forEach(failureJson::put);
        root.put("failureTypes", failureJson);

        JSONObject varietyJson = new JSONObject();
        for (Map.Entry<String, List<EvalResult>> e : groupByVariety(results).entrySet()) {
            List<EvalResult> group = e.getValue();
            long groupPassed = group.stream().filter(EvalResult::passed).count();
            JSONObject v = new JSONObject();
            v.put("cases",        group.size());
            v.put("passed",       groupPassed);
            v.put("passRate",     group.isEmpty() ? 0.0 : (double) groupPassed / group.size());
            v.put("correctness",  jsonOrNull(avgApplicable(group, haveScores, EvalResult::judgeCorrectnessScore)));
            v.put("judgeOverall", jsonOrNull(avgApplicable(group, haveScores, EvalResult::judgeOverallScore)));
            varietyJson.put(e.getKey(), v);
        }
        root.put("byUiVariety", varietyJson);

        JSONArray casesArr = new JSONArray();
        results.forEach(r -> casesArr.put(r.toJson()));
        root.put("cases", casesArr);

        TokenUsage totalUsage = results.stream()
                .map(EvalResult::tokenUsage)
                .reduce(TokenUsage.ZERO, TokenUsage::add);
        JSONObject usageJson = new JSONObject();
        usageJson.put("inputTokens",  totalUsage.inputTokens());
        usageJson.put("outputTokens", totalUsage.outputTokens());
        usageJson.put("costUsd",      totalUsage.totalCostUsd());
        root.put("totalTokenUsage", usageJson);

        Files.writeString(reportFile, root.toString(2));
        log.accept("JSON report written to: " + reportFile.toAbsolutePath());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private interface MetricExtractor {
        double get(EvalResult r);
    }

    private static double avg(List<EvalResult> results, MetricExtractor fn) {
        if (results.isEmpty()) return 0.0;
        return results.stream().mapToDouble(fn::get).average().orElse(0.0);
    }

    /**
     * Averages a metric across only the cases it applied to.
     *
     * @return {@code null} when no case exercised the check, which callers render as "N/A"
     *         rather than inventing a number
     */
    private static Double avgApplicable(List<EvalResult> results,
                                        java.util.function.Predicate<EvalResult> applies,
                                        MetricExtractor fn) {
        List<EvalResult> subset = results.stream().filter(applies).toList();
        return subset.isEmpty() ? null : avg(subset, fn);
    }

    /** Renders a 0.00–1.00 judge score, or "N/A" when nothing was scored. */
    private static String fmtScore(Double v) {
        return v == null ? "N/A " : String.format("%.2f", v);
    }

    /** Renders "7/10 (70%)" for a group, or "0/0" without a misleading percentage. */
    private static String passFraction(List<EvalResult> group) {
        long p = group.stream().filter(EvalResult::passed).count();
        if (group.isEmpty()) return "0/0";
        return String.format("%d/%d (%.0f%%)", p, group.size(), 100.0 * p / group.size());
    }

    private static String abbreviate(EvalCase.Expectation e) {
        return switch (e) {
            case HAPPY -> "happy";
            case INCORRECT -> "incorrect";
            case INVALID -> "INVALID";
            case UNWORKABLE -> "unworkabl";
        };
    }

    private static Object jsonOrNull(Double v) {
        return v == null ? JSONObject.NULL : v;
    }

    private static JSONObject splitSummary(List<EvalResult> group) {
        long p = group.stream().filter(EvalResult::passed).count();
        JSONObject o = new JSONObject();
        o.put("cases",  group.size());
        o.put("passed", p);
        o.put("passRate", group.isEmpty() ? JSONObject.NULL : (Object) ((double) p / group.size()));
        return o;
    }

    /** Groups results by their UI-variety tag, sorted alphabetically for stable output. */
    private static Map<String, List<EvalResult>> groupByVariety(List<EvalResult> results) {
        Map<String, List<EvalResult>> grouped = new TreeMap<>();
        for (EvalResult r : results) {
            grouped.computeIfAbsent(r.uiVariety(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Map<String, Long> countFailureTypes(List<EvalResult> results) {
        Map<String, Long> counts = new HashMap<>();
        for (EvalResult r : results) {
            // Step-level problems only mean something where there were known-good steps.
            if (!r.expectation().hasGroundTruthSteps()) {
                if (r.overConfident())
                    counts.merge("Confident plan from unusable video", 1L, Long::sum);
                if (r.crashed())
                    counts.merge("Errored instead of refusing", 1L, Long::sum);
                continue;
            }
            if (r.wronglyRejected())
                counts.merge("Usable video wrongly rejected", 1L, Long::sum);
            // Counted apart from quality failures: nothing was learned about the agent here.
            if (r.judgeFailed())
                counts.merge("Judge unavailable (case unscored)", 1L, Long::sum);
            if (!r.judgeIssues().isEmpty())
                counts.merge("Judge contradicted itself", 1L, Long::sum);

            if (!r.judgeApplicable() || r.judgeFailed()) continue;

            // Which bar a failing case missed. Correctness is called out on its own because
            // it can fail a case the composite would have passed.
            if (r.judgeCorrectnessScore() < LlmJudge.MIN_CORRECTNESS)
                counts.merge("Judge correctness below bar", 1L, Long::sum);
            else if (r.judgeOverallScore() < LlmJudge.MIN_OVERALL)
                counts.merge("Judge composite below bar", 1L, Long::sum);

            // Quoted by the judge, so these name the specific actions at fault rather than
            // just counting cases. Diagnostic: they explain a failure, they do not cause one.
            if (!r.missingSteps().isEmpty())
                counts.merge("Missing steps (diagnostic)", (long) r.missingSteps().size(), Long::sum);
            if (!r.hallucinatedSteps().isEmpty())
                counts.merge("Hallucinated steps (diagnostic)", (long) r.hallucinatedSteps().size(), Long::sum);
        }
        return counts;
    }
}
