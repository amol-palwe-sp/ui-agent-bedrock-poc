package com.sailpoint.poc.uiagent.pipeline;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.AccountAggregator;
import com.sailpoint.poc.uiagent.aggregation.AggregationMode;
import com.sailpoint.poc.uiagent.aggregation.NetworkAggregator;
import com.sailpoint.poc.uiagent.aggregation.TableDetectionResult;
import com.microsoft.playwright.options.LoadState;
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

                // ── NETWORK mode: start sniffing BEFORE navigation (REQ-NA-41) ─
                NetworkAggregator networkAggregator = null;
                if (config.isAggregation() && config.isNetworkMode()) {
                    networkAggregator = new NetworkAggregator(config.networkAggConfig());
                    networkAggregator.startSniffing(browser.page());
                    pl.onLog("INFO", "Aggregation mode: NETWORK — network sniffer active");
                }

                // ── Navigate to start URL ─────────────────────────────────────
                pl.onStatusChange(PipelineStatus.NAVIGATING);
                pl.onLog("INFO", "Navigating to " + config.startUrl() + "...");
                browser.navigate(config.startUrl());

                // ── Run AgentLoop (single loop for both modes — REQ-NA-44) ────
                pl.onStatusChange(PipelineStatus.AGENT_RUNNING);
                // SECURITY (secret isolation): feed the *tokenized* goal to the LLM. Real secret
                // values are resolved only at Playwright type-time inside AgentLoop.withTokens and
                // never enter the prompt, history, or logs. resolvedGoal() is intentionally NOT used
                // for the prompt anymore.
                String promptGoal = config.goal();
                TokenValues tokens = TokenValues.fromMap(config.tokenValues());
                pl.onLog("INFO", "Starting agent loop (goal: "
                        + promptGoal.substring(0, Math.min(80, promptGoal.length()))
                        + (promptGoal.length() > 80 ? "…" : "") + ")");

                ScriptRecorder recorder = null;
                if (config.isRecord()) {
                    recorder = new ScriptRecorder(
                            config.startUrl(), config.goal(),
                            config.taskType().name(), config.scriptName(), tokens);
                }

                AgentLoop loop = new AgentLoop(
                        bedrock, browser, actionLogger,
                        ac.maxSteps(), promptGoal, ac.noProgressLimit())
                        .setMultiViewportMaxFrames(ac.multiViewportMaxFrames())
                        .withTokens(tokens);
                if (recorder != null) {
                    loop.enableRecording(recorder);
                }

                TokenUsage loopUsage = loop.run();
                totalUsage = totalUsage.add(loopUsage);
                int agentSteps = loop.stepsExecuted();
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
                    return PipelineResult.provisioningSuccess(totalUsage, finalUrl)
                            .agentSteps(agentSteps).build();
                }

                // AGGREGATION tail — shared by the live path (here) and REPLAY (runReplay).
                return finishAggregation(config, pl, browser, bedrock,
                        totalUsage, agentSteps, networkAggregator, finalUrl);
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

    /**
     * Runs the aggregation tail after navigation has reached the account-list page: NETWORK-mode
     * settle + direct extraction, otherwise LLM_DOM table detection → pagination → CSV. Shared by
     * the live path and by {@link #runReplay} so a deterministic replay finishes exactly like a
     * live run (Phase 1 of the determinism plan).
     *
     * @param agentSteps        navigation steps taken (AgentLoop steps live; replay steps on REPLAY)
     * @param networkAggregator an already-started sniffer when NETWORK mode is active, else {@code null}
     */
    private static PipelineResult finishAggregation(
            PipelineConfig config,
            ProgressListener pl,
            BrowserSession browser,
            BedrockAnthropicClient bedrock,
            TokenUsage totalUsage,
            int agentSteps,
            NetworkAggregator networkAggregator,
            String finalUrl) throws Exception {

                // ── NETWORK mode: settle wait, stop sniffing, attempt aggregation ──
                if (networkAggregator != null) {
                    // Allow SPA async XHR calls to fire before stopping the sniffer.
                    // React/Angular/Vue apps load user data asynchronously AFTER the
                    // page shell renders — the agent issues DONE once it sees the page
                    // chrome, but the data XHR may still be in-flight.
                    int settleMs = config.networkAggConfig().settleAfterDoneMs();
                    if (settleMs > 0) {
                        pl.onLog("INFO", "Waiting " + settleMs + " ms for SPA data calls to complete...");
                        try { Thread.sleep(settleMs); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    networkAggregator.stopSniffing();
                    pl.onLog("INFO", "Sniffing complete — attempting NETWORK aggregation...");

                    NetworkAggregator.AggregationResult netResult =
                            networkAggregator.aggregate(browser, bedrock);
                    totalUsage = totalUsage.add(networkAggregator.accumulatedUsage());
                    pl.onTokenUsage(totalUsage);

                    // Only treat as success when at least one row was extracted.
                    // A qualifying payload with 0 extracted rows means the format was
                    // unrecognised (e.g. Google batchexecute )]}'  prefix) — fall back.
                    if (netResult.hasData() && !netResult.rows().isEmpty()) {
                        pl.onLog("INFO", "NETWORK aggregation succeeded: "
                                + netResult.rows().size() + " rows, "
                                + netResult.pagesCollected() + " page(s)");
                        pl.onStatusChange(PipelineStatus.WRITING_CSV);
                        String csvPath = "";
                        List<String> headers = netResult.headers();
                        try {
                            csvPath = writeCsv(netResult.rows(), headers,
                                    config.aggregationConfig().outputDir());
                            pl.onLog("SUCCESS", "CSV written to " + csvPath);
                        } catch (IOException e) {
                            pl.onLog("ERROR", "CSV write failed: " + e.getMessage());
                        }
                        List<Map<String,String>> preview = netResult.rows().size() > 10
                                ? new ArrayList<>(netResult.rows().subList(0, 10))
                                : new ArrayList<>(netResult.rows());
                        pl.onStatusChange(PipelineStatus.DONE);
                        return PipelineResult.aggregationSuccess(
                                totalUsage, finalUrl,
                                netResult.rows().size(), netResult.pagesCollected(),
                                csvPath, headers, preview,
                                AggregationMode.NETWORK)
                                .agentSteps(agentSteps).build();
                    }

                    // No qualifying payload — check fallback setting (REQ-NA-14)
                    if (!config.networkAggConfig().fallbackToLlmDom()) {
                        pl.onLog("WARNING", "No network payload found — fallback disabled");
                        pl.onStatusChange(PipelineStatus.DONE);
                        return PipelineResult.aggregationSuccess(
                                totalUsage, finalUrl, 0, 0, "", List.of(), List.of(),
                                AggregationMode.NETWORK)
                                .agentSteps(agentSteps).build();
                    }

                    pl.onLog("WARNING", "No network payload found — falling back to LLM_DOM");
                    // fall through to LLM_DOM aggregation below
                }

                // ── LLM_DOM AGGREGATION: detect table ─────────────────────────
                pl.onStatusChange(PipelineStatus.DETECTING_TABLE);
                if (networkAggregator == null) {
                    pl.onLog("INFO", "Aggregation mode: LLM_DOM — detecting accounts table...");
                } else {
                    pl.onLog("INFO", "Detecting accounts table (LLM_DOM fallback)...");
                }
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

                // ── LLM_DOM AGGREGATION: expected-total oracle ─────────────────
                // accumulatedUsage() is batched — all aggregator costs (detectTable +
                // oracle + pagination) are captured together at the single add() below.
                Integer expectedTotal = aggregator.detectExpectedTotal(tableResult);
                pl.onLog("INFO", expectedTotal != null
                        ? "Detected expected total from page: " + expectedTotal
                        : "No total indicator found on page — completeness check unavailable");

                // ── LLM_DOM AGGREGATION: pagination loop ──────────────────────
                pl.onStatusChange(PipelineStatus.AGGREGATING);
                int maxPages = config.aggregationConfig().maxPages();
                List<Map<String,String>> allRows = aggregator.paginationLoop(
                        tableResult, config.paginationPattern(), maxPages, expectedTotal);
                int pagesScraped = aggregator.pagesScraped();
                totalUsage = totalUsage.add(aggregator.accumulatedUsage());
                pl.onTokenUsage(totalUsage);

                pl.onLog("INFO", "Scraped " + pagesScraped + " page(s), "
                        + allRows.size() + " total rows");

                // ── LLM_DOM AGGREGATION: write CSV ────────────────────────────
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

                // Strategy used: LLM_DOM directly, or LLM_DOM as fallback from NETWORK
                AggregationMode strategyUsed = networkAggregator != null
                        ? AggregationMode.LLM_DOM   // was NETWORK, fell back
                        : AggregationMode.LLM_DOM;  // was LLM_DOM from the start

                pl.onStatusChange(PipelineStatus.DONE);
                return PipelineResult.aggregationSuccess(
                        totalUsage, finalUrl,
                        allRows.size(), pagesScraped,
                        csvPath, headers, preview,
                        strategyUsed)
                        .agentSteps(agentSteps)
                        .expectedTotalAccounts(expectedTotal)
                        .build();
    }

    private static PipelineResult runReplay(
            PipelineConfig config,
            ProgressListener pl,
            BrowserSession browser,
            BedrockAnthropicClient bedrock,
            TokenUsage totalUsage) throws Exception {
        Script script = Script.load(config.scriptPath());
        ReplayConfig replayConfig = new PocConfig().replay();

        // NETWORK aggregation replays: the sniffer must be attached BEFORE navigation, but
        // replay navigates internally inside ScriptExecutor.replay(). So we start sniffing here
        // first — mirroring the live-path ordering (see run(): sniff before browser.navigate()).
        NetworkAggregator networkAggregator = null;
        if (config.isAggregation() && config.isNetworkMode()) {
            networkAggregator = new NetworkAggregator(config.networkAggConfig());
            networkAggregator.startSniffing(browser.page());
            pl.onLog("INFO", "Aggregation mode: NETWORK — network sniffer active (replay)");
        }

        ScriptExecutor executor = new ScriptExecutor(browser, bedrock, true, replayConfig);
        ReplayResult replay = executor.replay(script, TokenValues.fromMap(config.tokenValues()));
        // Propagate replay token cost + step count so the UI reports real numbers (not zero).
        totalUsage = totalUsage.add(replay.totalCost());
        pl.onTokenUsage(totalUsage);

        if (!replay.success()) {
            pl.onStatusChange(PipelineStatus.FAILED);
            String err = replay.failedSteps().isEmpty() ? "replay failed"
                    : replay.failedSteps().get(0).reason();
            return PipelineResult.error(config.taskType(), totalUsage, err);
        }

        // PROVISIONING replay is done once navigation succeeds.
        if (!config.isAggregation()) {
            pl.onStatusChange(PipelineStatus.DONE);
            return PipelineResult.provisioningSuccess(totalUsage, browser.currentUrl())
                    .agentSteps(replay.stepsTotal()).build();
        }

        // Let the final replayed navigation settle before detecting the table. The live path
        // gets this for free (AgentLoop observes the settled list page before DONE); replay
        // returns the instant the last click fires, so without this wait detectTable's JS can
        // run mid-navigation and fail with "Execution context was destroyed".
        settleAfterReplay(browser, pl);

        // AGGREGATION replay reached the list page — finish exactly like a live run, but with
        // zero live LLM navigation calls (Phase 1). replay.stepsTotal() stands in for agentSteps.
        return finishAggregation(config, pl, browser, bedrock,
                totalUsage, replay.stepsTotal(), networkAggregator, browser.currentUrl());
    }

    /**
     * Waits for the page to quiesce after a replay's final navigation so downstream table
     * detection does not evaluate JS against a context that is being torn down. Best-effort:
     * any timeout/exception is swallowed since aggregation has its own retry/fallback paths.
     */
    private static void settleAfterReplay(BrowserSession browser, ProgressListener pl) {
        try {
            // The replay returns the instant the final click fires; a full-page navigation it
            // triggered may not have committed yet. Sleep briefly so waitForLoadState observes
            // the *new* page rather than resolving instantly against the old (already-loaded) one.
            Thread.sleep(1_000);
            browser.page().waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(8_000));
            try {
                browser.page().waitForLoadState(LoadState.NETWORKIDLE,
                        new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(3_000));
            } catch (Exception ignored) {
                // networkidle is best-effort; DOMCONTENTLOADED is the hard requirement.
            }
            pl.onLog("INFO", "Replay navigation settled — starting aggregation.");
        } catch (Exception e) {
            pl.onLog("WARNING", "Replay settle wait timed out — proceeding to aggregation anyway.");
        }
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
