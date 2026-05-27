package com.sailpoint.poc.uiagent.eval.report;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        log.accept(String.format("Passed (>=0.70): %d", passed));
        log.accept(String.format("Failed (< 0.70): %d", failed));
        log.accept(String.format("Pass rate      : %.1f%%", passRate));

        log.accept("");
        log.accept("Per-Case Results:");
        log.accept(SEP_MED);
        log.accept(String.format("%-10s %-24s %-4s %-4s %-6s %-6s %s",
                "ID", "Description", "T", "M", "Score", "Judge", "Result"));
        log.accept(SEP_MED);

        for (EvalResult r : results) {
            String desc = r.description().length() > 23
                    ? r.description().substring(0, 20) + "..."
                    : r.description();
            String tAbbr = "AGGREGATION".equalsIgnoreCase(r.taskType()) ? "AGG" : "PRO";
            String mAbbr = "PLACEHOLDER".equalsIgnoreCase(r.mode()) ? "PH" : "LI";
            String result = r.passed() ? "PASS" : "FAIL";
            log.accept(String.format("%-10s %-24s %-4s %-4s %-6s %-6s %s",
                    r.caseId(), desc, tAbbr, mAbbr,
                    String.format("%.3f", r.overallScore()),
                    String.format("%.1f", r.judgeOverallScore()),
                    result));
        }

        log.accept("");
        log.accept("T = taskType (AGG=AGGREGATION  PRO=PROVISIONING)");
        log.accept("M = mode     (PH=PLACEHOLDER   LI=LITERAL)");

        // ── Metric averages ────────────────────────────────────────────────────
        if (!results.isEmpty()) {
            log.accept("");
            log.accept("Metric Averages:");
            log.accept(String.format("  Step Recall       : %.3f", avg(results, r -> r.stepRecall())));
            log.accept(String.format("  Step Precision    : %.3f", avg(results, r -> r.stepPrecision())));
            log.accept(String.format("  Step Order        : %.3f", avg(results, r -> r.stepOrderScore())));
            log.accept(String.format("  Label Accuracy    : %.3f", avg(results, r -> r.labelAccuracyScore())));
            log.accept(String.format("  Placeholder       : %.3f", avg(results, r -> r.placeholderScore())));
            log.accept(String.format("  Pagination        : %.3f", avg(results, r -> r.paginationScore())));
            log.accept(String.format("  Overall           : %.3f", avg(results, r -> r.overallScore())));
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

        JSONObject avgScores = new JSONObject();
        avgScores.put("stepRecall",       avg(results, r -> r.stepRecall()));
        avgScores.put("stepPrecision",    avg(results, r -> r.stepPrecision()));
        avgScores.put("stepOrderScore",   avg(results, r -> r.stepOrderScore()));
        avgScores.put("labelAccuracy",    avg(results, r -> r.labelAccuracyScore()));
        avgScores.put("placeholderScore", avg(results, r -> r.placeholderScore()));
        avgScores.put("paginationScore",  avg(results, r -> r.paginationScore()));
        avgScores.put("overallScore",     avg(results, r -> r.overallScore()));
        root.put("averageScores", avgScores);

        Map<String, Long> failureTypes = countFailureTypes(results);
        JSONObject failureJson = new JSONObject();
        failureTypes.forEach(failureJson::put);
        root.put("failureTypes", failureJson);

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

    private static Map<String, Long> countFailureTypes(List<EvalResult> results) {
        Map<String, Long> counts = new HashMap<>();
        for (EvalResult r : results) {
            if (!r.missingSteps().isEmpty())
                counts.merge("Missing steps", (long) r.missingSteps().size(), Long::sum);
            if (!r.hallucinatedSteps().isEmpty())
                counts.merge("Hallucinated steps", (long) r.hallucinatedSteps().size(), Long::sum);
            if (r.labelAccuracyScore() < 0.7)
                counts.merge("Wrong element labels", 1L, Long::sum);
            if (!r.credentialLeaks().isEmpty())
                counts.merge("Credential leaks", 1L, Long::sum);
            if (r.paginationScore() < 0.5 && "AGGREGATION".equalsIgnoreCase(r.taskType()))
                counts.merge("Wrong pagination type", 1L, Long::sum);
        }
        return counts;
    }
}
