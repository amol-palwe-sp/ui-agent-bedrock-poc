package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POST /api/run  — starts the agent in a background thread.
 * POST /api/stop — interrupts the running thread (handled by {@link StopHandler}).
 *
 * <p>While the agent runs, {@link LogCapturingPrintStream} wraps {@code System.out}
 * and forwards every printed line to the SSE log queue so the browser sees live output.
 */
public final class RunHandler implements HttpHandler {

    private static final Pattern URL_PATTERN  = Pattern.compile("--url=([^\\s']+)");
    private static final Pattern GOAL_PATTERN = Pattern.compile("--goal=(.+?)'?\\s*$");

    private final AgentUIServer.ServerState state;
    private volatile PrintStream originalOut;

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

        // ── Read body ─────────────────────────────────────────────────────────
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        String goalLine = extractJsonString(body, "goalLine");
        if (goalLine == null || goalLine.isBlank()) {
            sendJson(ex, 400, "{\"error\":\"Missing goalLine\"}");
            return;
        }

        // ── Parse url + goal from the command line string ─────────────────────
        Matcher urlM  = URL_PATTERN.matcher(goalLine);
        Matcher goalM = GOAL_PATTERN.matcher(goalLine);
        if (!urlM.find() || !goalM.find()) {
            sendJson(ex, 400, "{\"error\":\"goalLine must contain --url=... and --goal=...\"}");
            return;
        }
        String startUrl  = urlM.group(1).trim();
        String goalText  = goalM.group(1).trim();
        // strip trailing quote if present
        if (goalText.endsWith("'")) goalText = goalText.substring(0, goalText.length() - 1).trim();

        state.agentRunning.set(true);
        state.logQueue.offer("STATUS:running");

        // ── Capture System.out ────────────────────────────────────────────────
        originalOut = System.out;
        LogCapturingPrintStream capturer =
                new LogCapturingPrintStream(originalOut, state.logQueue);
        System.setOut(capturer);

        final String finalUrl  = startUrl;
        final String finalGoal = goalText;

        Thread agent = new Thread(() -> {
            try {
                PocConfig config = new PocConfig();
                try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                             config.awsRegion(), config.awsProfile(), config.bedrockModelId(),
                             config.maxTokens(), config.temperature());
                     BrowserSession browser = new BrowserSession(
                             config.browserHeadless(),
                             config.browserSlowMoMs(),
                             config.browserViewportWidth(),
                             config.browserViewportHeight(),
                             config.browserStartMaximized(),
                             config.browserFullscreenViewportWidth(),
                             config.browserFullscreenViewportHeight(),
                             config.actionTimeoutClickMs(),
                             config.actionTimeoutTypeMs(),
                             config.actionTimeoutNavigateMs(),
                             config.interActionDelayMs());
                     ActionLogger actionLogger = new ActionLogger(config.agentLogFile())) {

                    browser.navigate(finalUrl);
                    AgentLoop loop = new AgentLoop(
                            bedrock, browser, actionLogger,
                            config.agentMaxSteps(), finalGoal,
                            config.agentNoProgressLimit());
                    loop.run();
                }
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:0");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.logQueue.offer("LOG:WARNING:Agent interrupted by user");
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } catch (Exception e) {
                state.logQueue.offer("LOG:ERROR:" + e.getMessage());
                state.logQueue.offer("STATUS:ready");
                state.logQueue.offer("DONE:1");
            } finally {
                restoreOut();
                state.agentRunning.set(false);
            }
        }, "agent-loop");

        agent.setDaemon(true);
        state.agentThread.set(agent);
        agent.start();

        sendJson(ex, 200, "{\"started\":true}");
    }

    private void restoreOut() {
        if (originalOut != null) {
            System.setOut(originalOut);
            originalOut = null;
        }
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

    // ── LogCapturingPrintStream ───────────────────────────────────────────────

    /**
     * Wraps the real {@code System.out} and forwards every printed line to the
     * log queue with an appropriate level prefix.
     */
    static final class LogCapturingPrintStream extends PrintStream {

        private final BlockingQueue<String> queue;

        LogCapturingPrintStream(PrintStream delegate, BlockingQueue<String> queue) {
            super(delegate, true);
            this.queue = queue;
        }

        @Override
        public void println(String x) {
            super.println(x);
            if (x != null) queue.offer(classify(x));
        }

        @Override
        public void println(Object x) {
            String s = String.valueOf(x);
            super.println(s);
            queue.offer(classify(s));
        }

        private static String classify(String line) {
            if (line.contains("✓") || line.contains("✅") ||
                    line.toLowerCase().contains("success")) {
                return "LOG:SUCCESS:" + line;
            }
            if (line.contains("✗") || line.toUpperCase().contains("ERROR") ||
                    line.toLowerCase().contains("failed")) {
                return "LOG:ERROR:" + line;
            }
            if (line.contains("⚠") || line.toUpperCase().contains("WARNING") ||
                    line.toLowerCase().contains("stuck")) {
                return "LOG:WARNING:" + line;
            }
            if (line.contains("--- Step") || line.matches("^Step \\d+.*")) {
                return "LOG:STEP:" + line;
            }
            return "LOG:INFO:" + line;
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
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return null;
        int quote2 = quote1 + 1;
        // find closing quote, honoring escapes
        while (quote2 < json.length()) {
            char c = json.charAt(quote2);
            if (c == '\\') { quote2 += 2; continue; }
            if (c == '"')  break;
            quote2++;
        }
        if (quote2 >= json.length()) return null;
        return json.substring(quote1 + 1, quote2)
                   .replace("\\\"", "\"")
                   .replace("\\/", "/")
                   .replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\\\", "\\");
    }
}
