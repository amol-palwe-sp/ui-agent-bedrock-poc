package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.pipeline.AgentPipeline;
import com.sailpoint.poc.uiagent.pipeline.PipelineConfig;
import com.sailpoint.poc.uiagent.pipeline.PipelineResult;
import com.sailpoint.poc.uiagent.pipeline.PipelineStatus;
import com.sailpoint.poc.uiagent.pipeline.PipelineMode;
import com.sailpoint.poc.uiagent.pipeline.ProgressListener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POST /api/run  — starts the agent in a background thread via {@link AgentPipeline}.
 * POST /api/stop — interrupts the running thread (handled by {@link StopHandler}).
 *
 * <p>Uses {@link ProgressListener} to receive real-time log output from the pipeline
 * (REQ-3.5 — replaces the former {@code System.setOut()} capture hack).
 */
public final class RunHandler implements HttpHandler {

    private static final Pattern URL_PATTERN  = Pattern.compile("--url=([^\\s']+)");
    private static final Pattern GOAL_PATTERN = Pattern.compile("--goal=(.+?)'?\\s*$");

    private final AgentUIServer.ServerState state;

    public RunHandler(AgentUIServer.ServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        if (state.agentRunning.get()) {
            sendJson(ex, 409, "{\"error\":\"Agent already running\"}");
            return;
        }

        RunRequest request;
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request = RunRequest.fromJson(body);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"Invalid JSON body\"}");
            return;
        }

        String startUrl;
        String goalText;

        if (request.isReplay()) {
            if (request.scriptPath().isBlank()) {
                sendJson(ex, 400, "{\"error\":\"scriptPath is required for REPLAY mode\"}");
                return;
            }
            startUrl = "";
            goalText = "";
        } else {
            if (request.goalLine().isBlank()) {
                sendJson(ex, 400, "{\"error\":\"Missing goalLine\"}");
                return;
            }
            Matcher urlM  = URL_PATTERN.matcher(request.goalLine());
            Matcher goalM = GOAL_PATTERN.matcher(request.goalLine());
            if (!urlM.find() || !goalM.find()) {
                sendJson(ex, 400, "{\"error\":\"goalLine must contain --url=... and --goal=...\"}");
                return;
            }
            startUrl = urlM.group(1).trim();
            goalText = goalM.group(1).trim();
            if (goalText.endsWith("'")) {
                goalText = goalText.substring(0, goalText.length() - 1).trim();
            }
        }

        state.agentRunning.set(true);
        state.logQueue.offer("STATUS:running");
        state.logQueue.offer("LOG:INFO:Mode " + request.mode()
                + (request.isReplay() ? " — script " + request.scriptPath() : ""));

        final String finalUrl   = startUrl;
        final String finalGoal  = goalText;
        final RunRequest finalReq = request;

        Thread agent = new Thread(() -> {
            try {
                PocConfig config = new PocConfig();

                PipelineConfig.Builder pcb = PipelineConfig.builder()
                        .mode(finalReq.pipelineMode())
                        .taskType(PipelineConfig.TaskType.PROVISIONING)
                        .startUrl(finalUrl)
                        .goal(finalGoal)
                        .scriptPath(finalReq.scriptPath())
                        .tokenValues(finalReq.tokenValues())
                        .saveScriptTo(config.scriptOutputDir())
                        .bedrockConfig(config.bedrock())
                        .browserConfig(config.browser())
                        .agentConfig(config.agent());

                PipelineConfig pipelineConfig = pcb.build();

                // ProgressListener forwards pipeline output to SSE queue (REQ-3.5)
                ProgressListener listener = new QueueProgressListener(state.logQueue);

                // AgentPipeline owns all resource lifecycle (REQ-3.4)
                PipelineResult result = AgentPipeline.run(pipelineConfig, listener);

                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:" + (result.success() ? "0" : "1"));

            } catch (Exception e) {
                state.logQueue.offer("LOG:ERROR:" + e.getMessage());
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } finally {
                state.agentRunning.set(false);
            }
        }, "agent-loop");

        agent.setDaemon(true);
        state.agentThread.set(agent);
        agent.start();

        sendJson(ex, 200, "{\"started\":true}");
    }

    // ── StopHandler ───────────────────────────────────────────────────────────

    public static final class StopHandler implements HttpHandler {

        private final AgentUIServer.ServerState state;

        public StopHandler(AgentUIServer.ServerState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            Thread t = state.agentThread.get();
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
            state.agentRunning.set(false);
            state.logQueue.offer("LOG:WARNING:Agent stopped by user");
            state.logQueue.offer("STATUS:ready");
            sendJson(ex, 200, "{\"stopped\":true}");
        }
    }

    // ── ProgressListener → SSE queue adapter ──────────────────────────────────

    /**
     * Bridges {@link ProgressListener} callbacks to the SSE log queue.
     * Replaces the former {@code LogCapturingPrintStream} / {@code System.setOut()} approach.
     */
    static final class QueueProgressListener implements ProgressListener {

        private final BlockingQueue<String> queue;

        QueueProgressListener(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void onLog(String level, String message) {
            if (message != null) queue.offer("LOG:" + level + ":" + message);
        }

        @Override
        public void onStatusChange(PipelineStatus status) {
            String legacy = switch (status) {
                case STARTING, NAVIGATING, AGENT_RUNNING, DETECTING_TABLE, AGGREGATING, WRITING_CSV -> "running";
                case DONE -> "ready";
                case FAILED, INTERRUPTED -> "error";
                default -> null;
            };
            if (legacy != null) {
                queue.offer("STATUS:" + legacy);
            }
        }

        @Override
        public void onTokenUsage(TokenUsage usage) {
            queue.offer("TOKEN_USAGE:" + usage.inputTokens()
                    + ":" + usage.outputTokens()
                    + ":" + usage.totalCostUsd());
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Extracts a string value from a minimal JSON object (no full parser needed). */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon  = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return null;
        int quote2 = quote1 + 1;
        while (quote2 < json.length()) {
            char c = json.charAt(quote2);
            if (c == '\\') { quote2 += 2; continue; }
            if (c == '"')  break;
            quote2++;
        }
        if (quote2 >= json.length()) return null;
        return json.substring(quote1 + 1, quote2)
                   .replace("\\\"", "\"").replace("\\/", "/")
                   .replace("\\n",  "\n").replace("\\r",  "\r")
                   .replace("\\\\", "\\");
    }
}
