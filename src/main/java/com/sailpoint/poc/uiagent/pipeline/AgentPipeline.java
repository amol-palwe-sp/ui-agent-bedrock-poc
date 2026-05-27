package com.sailpoint.poc.uiagent.pipeline;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.AccountAggregator;
import com.sailpoint.poc.uiagent.aggregation.TableDetectionResult;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.replay.ReplayResult;
import com.sailpoint.poc.uiagent.replay.Script;
import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.config.ReplayConfig;
import com.sailpoint.poc.uiagent.replay.ScriptExecutor;
import com.sailpoint.poc.uiagent.replay.ScriptLister;
import com.sailpoint.poc.uiagent.replay.ScriptRecorder;
import com.sailpoint.poc.uiagent.replay.TokenValues;
import com.sailpoint.poc.uiagent.config.AgentConfig;
import com.sailpoint.poc.uiagent.config.BedrockConfig;
import com.sailpoint.poc.uiagent.config.BrowserConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified pipeline runner — the single way to set up and execute a browser-automation
 * pipeline.
 *
 * <p>Implements REQ-3 (Unified Pipeline). Replaces the duplicated
 * {@code BedrockAnthropicClient} + {@code BrowserSession} + {@code ActionLogger} +
 * {@code AgentLoop} setup that previously existed in five separate places:
 * <ul>
 *   <li>{@code UiAgentPocApplication}</li>
 *   <li>{@code AggregationRunner}</li>
 *   <li>{@code AggregationPlanRunner}</li>
 *   <li>{@code RunHandler}</li>
 *   <li>{@code AggregationRunHandler}</li>
 * </ul>
 *
 * <h2>Key guarantees</h2>
 * <ul>
 *   <li>This class owns the {@code try-with-resources} for Bedrock client, browser, and
 *       logger — callers never manage those lifecycles (REQ-3.4).</li>
 *   <li>{@link #run(PipelineConfig, ProgressListener)} never throws; errors are captured
 *       in the returned {@link PipelineResult}.</li>
 *   <li>When a {@link ProgressListener} is provided, all {@code System.out} output from
 *       {@code AgentLoop} and internal helpers is intercepted and forwarded to
 *       {@link ProgressListener#onLog} — callers no longer need to redirect
 *       {@code System.out} themselves (REQ-3.5).</li>
 * </ul>
 *
 * <h2>Task types</h2>
 * <ul>
 *   <li>{@code PROVISIONING}: navigate to {@code startUrl} → run AgentLoop until DONE.</li>
 *   <li>{@code AGGREGATION}: navigate → AgentLoop → detectTable → paginationLoop →
 *       writeCsv.</li>
 * </ul>
 */
public final class AgentPipeline {

    private AgentPipeline() {}

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Runs the full pipeline described by {@code config} and returns a
     * {@link PipelineResult}. Never throws.
     *
     * @param config   complete pipeline parameters — build via {@link PipelineConfig#builder()}
     * @param listener optional progress callback; pass {@link ProgressListener#SILENT} for CLI
     *                 callers that rely on {@code System.out} directly
     * @return result containing success/failure status, token usage, and task-specific output
     */
    public static PipelineResult run(PipelineConfig config, ProgressListener listener) {
        if (listener == null) listener = ProgressListener.SILENT;
        final ProgressListener pl = listener;

        // Intercept System.out so AgentLoop output reaches ProgressListener (REQ-3.5).
        // SILENT listener does not wrap the stream to avoid overhead on CLI callers.
        PrintStream originalOut = System.out;
        if (pl != ProgressListener.SILENT) {
            System.setOut(new ProgressForwardingPrintStream(originalOut, pl));
        }

        TokenUsage totalUsage = TokenUsage.ZERO;

        try {
            if (config.mode() == PipelineMode.LIST) {
                ScriptLister.list(config.saveScriptTo());
                pl.onStatusChange(PipelineStatus.DONE);
                return PipelineResult.provisioningSuccess(totalUsage, "").build();
            }

            pl.onStatusChange(PipelineStatus.STARTING);

            BedrockConfig bc  = config.bedrockConfig();
            BrowserConfig brc = config.browserConfig();
            AgentConfig   ac  = config.agentConfig();

            try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                         bc.region(), bc.profile(), bc.modelId(),
                         bc.maxTokens(), bc.temperature());
                 BrowserSession browser = new BrowserSession(
                         brc.headless(), brc.slowMoMs(),
                         brc.viewportWidth(), brc.viewportHeight(),
                         brc.startMaximized(),
                         brc.fullscreenWidth(), brc.fullscreenHeight(),
                         brc.clickTimeoutMs(), brc.typeTimeoutMs(),
                         brc.navigateTimeoutMs(), brc.interActionDelayMs());
                 ActionLogger actionLogger = new ActionLogger(ac.logFile())) {

                if (config.isReplay()) {
                    return runReplay(config, pl, browser, bedrock, totalUsage);
                }

                // ── Navigate to start URL ─────────────────────────────────────
                pl.onStatusChange(PipelineStatus.NAVIGATING);
                pl.onLog("INFO", "Navigating to " + config.startUrl() + "...");
                browser.navigate(config.startUrl());

                // ── Run AgentLoop ─────────────────────────────────────────────
                pl.onStatusChange(PipelineStatus.AGENT_RUNNING);
                String resolvedGoal = config.resolvedGoal();
                pl.onLog("INFO", "Starting agent loop (goal: "
                        + resolvedGoal.substring(0, Math.min(80, resolvedGoal.length()))
                        + (resolvedGoal.length() > 80 ? "…" : "") + ")");

                ScriptRecorder recorder = null;
                if (config.isRecord()) {
                    recorder = new ScriptRecorder(
                            config.startUrl(), config.goal(),
                            config.taskType().name(), config.scriptName());
                }

                AgentLoop loop = new AgentLoop(
                        bedrock, browser, actionLogger,
                        ac.maxSteps(), resolvedGoal, ac.noProgressLimit())
                        .setMultiViewportMaxFrames(ac.multiViewportMaxFrames());
                if (recorder != null) {
                    loop.enableRecording(recorder);
                }

                TokenUsage loopUsage = loop.run();
                totalUsage = totalUsage.add(loopUsage);
                pl.onTokenUsage(totalUsage);

                String finalUrl = browser.currentUrl();

                if (config.isRecord() && recorder != null && recorder.shouldSave()) {
                    try {
                        Script script = recorder.script();
                        script.setBrowserWidth(brc.viewportWidth());
                        script.setBrowserHeight(brc.viewportHeight());
                        Path saved = script.save(config.saveScriptTo());
                        pl.onLog("SUCCESS", "Script saved to " + saved);
                    } catch (IOException e) {
                        pl.onLog("WARNING", "Failed to save script: " + e.getMessage());
                    }
                }

                if (!config.isAggregation()) {
                    // PROVISIONING: pipeline is done
                    pl.onStatusChange(PipelineStatus.DONE);
                    return PipelineResult.provisioningSuccess(totalUsage, finalUrl).build();
                }

                // ── AGGREGATION: detect table ─────────────────────────────────
                pl.onStatusChange(PipelineStatus.DETECTING_TABLE);
                pl.onLog("INFO", "Detecting accounts table...");
                AccountAggregator aggregator = new AccountAggregator(browser, bedrock);
                TableDetectionResult tableResult = aggregator.detectTable();

                if (tableResult == null) {
                    pl.onLog("ERROR", "No accounts table found on the page.");
                    pl.onStatusChange(PipelineStatus.FAILED);
                    return PipelineResult.error(config.taskType(), totalUsage,
                            "No accounts table found on page after agent navigation");
                }

                pl.onLog("INFO", "Table detected — selector: " + tableResult.selector());
                pl.onLog("INFO", "Columns: " + String.join(", ", tableResult.headers()));

                // ── AGGREGATION: pagination loop ──────────────────────────────
                pl.onStatusChange(PipelineStatus.AGGREGATING);
                int maxPages = config.aggregationConfig().maxPages();
                List<Map<String,String>> allRows = aggregator.paginationLoop(
                        tableResult, config.paginationPattern(), maxPages);
                int pagesScraped = aggregator.pagesScraped();
                totalUsage = totalUsage.add(aggregator.accumulatedUsage());
                pl.onTokenUsage(totalUsage);

                pl.onLog("INFO", "Scraped " + pagesScraped + " page(s), "
                        + allRows.size() + " total rows");

                // ── AGGREGATION: write CSV ────────────────────────────────────
                pl.onStatusChange(PipelineStatus.WRITING_CSV);
                List<String> headers = resolveHeaders(tableResult.headers(), allRows);
                String csvPath = "";
                try {
                    csvPath = writeCsv(allRows, headers, config.aggregationConfig().outputDir());
                    pl.onLog("SUCCESS", "CSV written to " + csvPath);
                } catch (IOException e) {
                    pl.onLog("ERROR", "CSV write failed: " + e.getMessage());
                }

                // Build preview (up to 10 rows)
                List<Map<String,String>> preview = allRows.size() > 10
                        ? new ArrayList<>(allRows.subList(0, 10))
                        : new ArrayList<>(allRows);

                pl.onStatusChange(PipelineStatus.DONE);
                return PipelineResult.aggregationSuccess(
                        totalUsage, finalUrl,
                        allRows.size(), pagesScraped,
                        csvPath, headers, preview).build();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pl.onLog("WARNING", "Pipeline interrupted by user");
            pl.onStatusChange(PipelineStatus.INTERRUPTED);
            return PipelineResult.interrupted(config.taskType(), totalUsage);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            pl.onLog("ERROR", msg);
            pl.onStatusChange(PipelineStatus.FAILED);
            return PipelineResult.error(config.taskType(), totalUsage, msg);

        } finally {
            System.setOut(originalOut);
        }
    }

    private static PipelineResult runReplay(
            PipelineConfig config,
            ProgressListener pl,
            BrowserSession browser,
            BedrockAnthropicClient bedrock,
            TokenUsage totalUsage) throws Exception {
        Script script = Script.load(config.scriptPath());
        ReplayConfig replayConfig = new PocConfig().replay();
        ScriptExecutor executor = new ScriptExecutor(browser, bedrock, true, replayConfig);
        ReplayResult replay = executor.replay(script, TokenValues.fromMap(config.tokenValues()));
        pl.onTokenUsage(totalUsage);
        if (replay.success()) {
            pl.onStatusChange(PipelineStatus.DONE);
            return PipelineResult.provisioningSuccess(totalUsage, browser.currentUrl()).build();
        }
        pl.onStatusChange(PipelineStatus.FAILED);
        String err = replay.failedSteps().isEmpty() ? "replay failed"
                : replay.failedSteps().get(0).reason();
        return PipelineResult.error(config.taskType(), totalUsage, err);
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    /**
     * Writes {@code rows} to a timestamped CSV file in {@code outputDirStr}.
     * Moved here from {@code AggregationRunner} and {@code AggregationRunHandler}
     * (previously duplicated).
     */
    private static String writeCsv(
            List<Map<String,String>> rows,
            List<String>             headers,
            String                   outputDirStr) throws IOException {

        Path outputDir = Path.of(outputDirStr);
        Files.createDirectories(outputDir);

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path filePath = outputDir.resolve("accounts_" + timestamp + ".csv");

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            writer.write(headers.stream()
                    .map(AgentPipeline::csvEscape)
                    .collect(Collectors.joining(",")));
            writer.newLine();
            for (Map<String,String> row : rows) {
                writer.write(headers.stream()
                        .map(h -> csvEscape(row.getOrDefault(h, "")))
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
        }
        return filePath.toAbsolutePath().toString();
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static List<String> resolveHeaders(
            List<String> detected, List<Map<String,String>> rows) {
        if (detected != null && !detected.isEmpty()) return detected;
        if (!rows.isEmpty()) return new ArrayList<>(rows.get(0).keySet());
        return List.of();
    }

    // ── System.out → ProgressListener forwarding ──────────────────────────────

    /**
     * Intercepts {@code System.out.println} calls from {@code AgentLoop} and other
     * internal components, forwarding each line to {@link ProgressListener#onLog} so
     * HTTP handlers no longer need to redirect {@code System.out} (REQ-3.5).
     */
    static final class ProgressForwardingPrintStream extends PrintStream {

        private final ProgressListener listener;

        ProgressForwardingPrintStream(PrintStream delegate, ProgressListener listener) {
            super(delegate, true);
            this.listener = listener;
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null) {
                String[] lm = classifyLevel(x);
                listener.onLog(lm[0], lm[1]);
            }
        }

        @Override
        public void println(Object x) { println(String.valueOf(x)); }

        private static String[] classifyLevel(String line) {
            if (line.contains("✓") || line.contains("✅")
                    || line.toLowerCase().contains("success")) {
                return new String[]{"SUCCESS", line};
            }
            if (line.contains("✗") || line.toUpperCase().contains("ERROR")
                    || line.toLowerCase().contains("failed")) {
                return new String[]{"ERROR", line};
            }
            if (line.contains("⚠") || line.toUpperCase().contains("WARNING")
                    || line.toLowerCase().contains("stuck")) {
                return new String[]{"WARNING", line};
            }
            if (line.contains("--- Step") || line.matches("^Step \\d+.*")) {
                return new String[]{"STEP", line};
            }
            return new String[]{"INFO", line};
        }
    }
}
