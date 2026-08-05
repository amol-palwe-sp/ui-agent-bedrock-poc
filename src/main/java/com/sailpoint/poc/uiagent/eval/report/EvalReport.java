package com.sailpoint.poc.uiagent.eval.report;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalMetrics;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalResult;
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

        // A case passes only by clearing all three conditions, so report each one's
        // tally separately — "12 failed" is not actionable, "12 failed the safety gate" is.
        log.accept("");
        log.accept("Pass conditions (a case must clear all three):");
        log.accept(String.format("  1. Credentials tokenized : %d/%d",
                results.stream().filter(EvalResult::safetyGatePassed).count(), results.size()));
        log.accept(String.format("  2. Counted facts at bar  : %d/%d   (recall>=%.2f precision>=%.2f order>=%.2f)",
                results.stream().filter(EvalResult::countedFactsPassed).count(), results.size(),
                EvalMetrics.MIN_STEP_RECALL, EvalMetrics.MIN_STEP_PRECISION, EvalMetrics.MIN_STEP_ORDER));
        long judged = results.stream().filter(EvalResult::judgeApplicable).count();
        log.accept(judged == 0
                ? "  3. AI judge              : not run"
                : String.format("  3. AI judge at bar       : %d/%d   (overall>=%.1f/10)",
                        results.stream().filter(r -> r.judgeApplicable() && r.graderPassed()).count(),
                        judged, EvalMetrics.MIN_JUDGE_OVERALL));

        // How many cases actually had credentials to tokenize. Without this, a 100% safety-gate
        // figure looks reassuring even when no case ever exercised the gate.
        long gateExercised = results.stream().filter(EvalResult::placeholderApplicable).count();
        log.accept(String.format("     Cases that exercised the credential gate: %d/%d",
                gateExercised, results.size()));

        log.accept("");
        log.accept("Per-Case Results:");
        log.accept(SEP_MED);
        log.accept(String.format("%-10s %-24s %-4s %-4s %-6s %-6s %-6s %s",
                "ID", "Description", "T", "M", "Trend", "Judge", "Result", "Why"));
        log.accept(SEP_MED);

        for (EvalResult r : results) {
            String desc = r.description().length() > 23
                    ? r.description().substring(0, 20) + "..."
                    : r.description();
            String tAbbr = "AGGREGATION".equalsIgnoreCase(r.taskType()) ? "AGG" : "PRO";
            String mAbbr = "PLACEHOLDER".equalsIgnoreCase(r.mode()) ? "PH" : "LI";
            String result = r.passed() ? "PASS" : "FAIL";
            log.accept(String.format("%-10s %-24s %-4s %-4s %-6s %-6s %-6s %s",
                    r.caseId(), desc, tAbbr, mAbbr,
                    String.format("%.3f", r.overallScore()),
                    r.judgeApplicable() ? String.format("%.1f", r.judgeOverallScore()) : "N/A",
                    result,
                    r.failureReason()));
        }

        log.accept("");
        log.accept("T = taskType (AGG=AGGREGATION  PRO=PROVISIONING)");
        log.accept("M = mode     (PH=PLACEHOLDER   LI=LITERAL)");
        log.accept("Trend = composite movement indicator, not the pass rule.");

        // ── Metric averages ────────────────────────────────────────────────────
        if (!results.isEmpty()) {
            log.accept("");
            log.accept("Metric Averages:");
            log.accept(String.format("  Step Recall       : %.3f", avg(results, r -> r.stepRecall())));
            log.accept(String.format("  Step Precision    : %.3f", avg(results, r -> r.stepPrecision())));
            log.accept(String.format("  Step Order        : %.3f", avg(results, r -> r.stepOrderScore())));
            // Averaged over applicable cases only; folding in cases the check skipped would
            // report a low number that says nothing about accuracy.
            log.accept(String.format("  Placeholder       : %s  (%d applicable)",
                    fmt(avgApplicable(results, EvalResult::placeholderApplicable, EvalResult::placeholderScore)),
                    results.stream().filter(EvalResult::placeholderApplicable).count()));
            log.accept(String.format("  Pagination        : %s  (%d applicable)",
                    fmt(avgApplicable(results, EvalResult::paginationApplicable, EvalResult::paginationScore)),
                    results.stream().filter(EvalResult::paginationApplicable).count()));
            log.accept(String.format("  Trend composite   : %.3f", avg(results, r -> r.overallScore())));
            log.accept(String.format("  AI judge overall  : %s  (%d judged)",
                    fmtJudge(avgApplicable(results, EvalResult::judgeApplicable, EvalResult::judgeOverallScore)),
                    results.stream().filter(EvalResult::judgeApplicable).count()));

            log.accept("");
            log.accept("Diagnostics (not scored, for investigation only):");
            log.accept(String.format("  Label Accuracy    : %.3f", avg(results, r -> r.labelAccuracyScore())));
        }

        // ── Results by UI variety ──────────────────────────────────────────────
        Map<String, List<EvalResult>> byVariety = groupByVariety(results);
        if (!byVariety.isEmpty()) {
            log.accept("");
            log.accept("Results by UI variety:");
            log.accept(SEP_MED);
            log.accept(String.format("%-28s %-7s %-8s %-8s %s",
                    "UI variety", "Cases", "Pass", "Trend", "Judge"));
            log.accept(SEP_MED);
            for (Map.Entry<String, List<EvalResult>> e : byVariety.entrySet()) {
                List<EvalResult> group = e.getValue();
                long groupPassed = group.stream().filter(EvalResult::passed).count();
                log.accept(String.format("%-28s %-7d %-8s %-8s %s",
                        truncate(e.getKey(), 28),
                        group.size(),
                        groupPassed + "/" + group.size(),
                        String.format("%.3f", avg(group, EvalResult::overallScore)),
                        fmtJudge(avgApplicable(group, EvalResult::judgeApplicable,
                                EvalResult::judgeOverallScore))));
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

        JSONObject conditions = new JSONObject();
        conditions.put("safetyGatePassed",
                results.stream().filter(EvalResult::safetyGatePassed).count());
        conditions.put("countedFactsPassed",
                results.stream().filter(EvalResult::countedFactsPassed).count());
        conditions.put("graderPassed",
                results.stream().filter(r -> r.judgeApplicable() && r.graderPassed()).count());
        conditions.put("casesJudged",
                results.stream().filter(EvalResult::judgeApplicable).count());
        conditions.put("credentialGateExercised",
                results.stream().filter(EvalResult::placeholderApplicable).count());
        root.put("passConditions", conditions);

        JSONObject thresholds = new JSONObject();
        thresholds.put("minStepRecall",    EvalMetrics.MIN_STEP_RECALL);
        thresholds.put("minStepPrecision", EvalMetrics.MIN_STEP_PRECISION);
        thresholds.put("minStepOrder",     EvalMetrics.MIN_STEP_ORDER);
        thresholds.put("minJudgeOverall",  EvalMetrics.MIN_JUDGE_OVERALL);
        root.put("thresholds", thresholds);

        JSONObject avgScores = new JSONObject();
        avgScores.put("stepRecall",     avg(results, r -> r.stepRecall()));
        avgScores.put("stepPrecision",  avg(results, r -> r.stepPrecision()));
        avgScores.put("stepOrderScore", avg(results, r -> r.stepOrderScore()));
        // Averaged over applicable cases only, null when nothing exercised the check.
        avgScores.put("placeholderScore", jsonOrNull(
                avgApplicable(results, EvalResult::placeholderApplicable, EvalResult::placeholderScore)));
        avgScores.put("paginationScore", jsonOrNull(
                avgApplicable(results, EvalResult::paginationApplicable, EvalResult::paginationScore)));
        avgScores.put("judgeOverall", jsonOrNull(
                avgApplicable(results, EvalResult::judgeApplicable, EvalResult::judgeOverallScore)));
        avgScores.put("overallScore",   avg(results, r -> r.overallScore()));
        root.put("averageScores", avgScores);

        JSONObject avgDiagnostics = new JSONObject();
        avgDiagnostics.put("labelAccuracy", avg(results, r -> r.labelAccuracyScore()));
        root.put("averageDiagnostics", avgDiagnostics);

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
            v.put("overallScore", avg(group, EvalResult::overallScore));
            v.put("judgeOverall", jsonOrNull(
                    avgApplicable(group, EvalResult::judgeApplicable, EvalResult::judgeOverallScore)));
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

    private static String fmt(Double v) {
        return v == null ? "N/A  " : String.format("%.3f", v);
    }

    private static String fmtJudge(Double v) {
        return v == null ? "N/A" : String.format("%.1f", v);
    }

    private static Object jsonOrNull(Double v) {
        return v == null ? JSONObject.NULL : v;
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
            if (!r.missingSteps().isEmpty())
                counts.merge("Missing steps", (long) r.missingSteps().size(), Long::sum);
            if (!r.hallucinatedSteps().isEmpty())
                counts.merge("Hallucinated steps", (long) r.hallucinatedSteps().size(), Long::sum);
            if (r.labelAccuracyScore() < 0.7)
                counts.merge("Low label similarity (diagnostic)", 1L, Long::sum);
            if (!r.missingPlaceholders().isEmpty())
                counts.merge("Missing placeholders", 1L, Long::sum);
            if (r.paginationApplicable() && r.paginationScore() < 0.5)
                counts.merge("Wrong pagination type", 1L, Long::sum);
        }
        return counts;
    }
}
