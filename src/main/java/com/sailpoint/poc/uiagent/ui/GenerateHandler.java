package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.eval.realtime.ConfidenceEvaluator;
import com.sailpoint.poc.uiagent.eval.realtime.ConfidenceResult;
import com.sailpoint.poc.uiagent.video.GoalExtractor;
import com.sailpoint.poc.uiagent.video.VideoAnalysisRequest;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sailpoint.poc.uiagent.video.VideoToGoalPrompt;
import com.sailpoint.poc.uiagent.video.relevance.RelevanceResponse;
import com.sailpoint.poc.uiagent.video.relevance.RelevanceResult;
import com.sailpoint.poc.uiagent.video.relevance.VideoRelevanceGate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.commons.fileupload.FileItem;
import org.json.JSONObject;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * POST /api/generate
 *
 * <p>Accepts a multipart/form-data request containing an MP4 video upload plus
 * optional overrides (url, maxFrames). Runs the video → {@link VideoToGoalPrompt}
 * → Claude → {@link GoalExtractor} pipeline and returns a JSON result.
 *
 * <p>Progress is pushed to {@link AgentUIServer.ServerState#logQueue} so the SSE
 * stream can relay it to the browser in real time.
 *
 * <p>The response includes both the legacy {@code goalLine} field (for backward
 * compatibility with the existing UI form) and the new {@code navigationGoal} /
 * {@code targetUrl} fields from the unified schema.
 */
public final class GenerateHandler implements HttpHandler {

    private static final long MAX_UPLOAD_BYTES = 500L * 1024 * 1024; // 500 MB

    private final AgentUIServer.ServerState state;

    public GenerateHandler(AgentUIServer.ServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method Not Allowed");
            return;
        }

        Path tempFile = null;
        try {
            // ── Parse multipart ────────────────────────────────────────────────
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                sendJson(ex, 400, "{\"error\":\"Expected multipart/form-data\"}");
                return;
            }

            FileItemFactory factory = new DiskFileItemFactory();
            ServletFileUpload upload = new ServletFileUpload(factory);
            upload.setSizeMax(MAX_UPLOAD_BYTES);

            HttpServletRequestAdapter adapter = new HttpServletRequestAdapter(ex, contentType);
            List<FileItem> items = upload.parseRequest(adapter);

            byte[]  videoBytes   = null;
            String  overrideUrl  = null;
            Integer maxFrames    = null;
            boolean evalEnabled  = true;
            boolean force        = false;

            for (FileItem item : items) {
                if (!item.isFormField() && "video".equals(item.getFieldName())) {
                    String name = item.getName();
                    if (name == null || !name.toLowerCase().endsWith(".mp4")) {
                        sendJson(ex, 400, "{\"error\":\"Only MP4 supported\"}"); return;
                    }
                    if (item.getSize() > MAX_UPLOAD_BYTES) {
                        sendJson(ex, 400, "{\"error\":\"File exceeds 500MB\"}"); return;
                    }
                    videoBytes = item.get();
                } else if (item.isFormField()) {
                    switch (item.getFieldName()) {
                        case "url"         -> overrideUrl = item.getString().trim();
                        case "maxFrames"   -> {
                            try { maxFrames = Integer.parseInt(item.getString().trim()); }
                            catch (NumberFormatException ignored) {}
                        }
                        case "evalEnabled" -> evalEnabled = !"false".equalsIgnoreCase(item.getString().trim());
                        case "force"       -> force = "true".equalsIgnoreCase(item.getString().trim());
                    }
                }
            }

            if (videoBytes == null || videoBytes.length == 0) {
                sendJson(ex, 400, "{\"error\":\"No video file received\"}"); return;
            }

            tempFile = Files.createTempFile("uiagent-upload-" + UUID.randomUUID(), ".mp4");
            Files.write(tempFile, videoBytes);

            // ── Pipeline ───────────────────────────────────────────────────────
            push("STATUS:generating");
            push("LOG:INFO:Extracting frames from video...");

            PocConfig config = new PocConfig();
            int    effectiveMaxFrames = maxFrames != null ? maxFrames : config.video().maxFrames();

            VideoFrameExtractor extractor = new VideoFrameExtractor(
                    config.video().withMaxFrames(effectiveMaxFrames));

            List<byte[]> frames = extractor.extractFrames(tempFile.toString());

            if (frames.isEmpty()) {
                push("LOG:ERROR:No frames extracted — video may be empty or corrupted");
                push("STATUS:ready");
                push("DONE:1");
                sendJson(ex, 500, "{\"error\":\"No frames extracted from video\"}");
                return;
            }

            push("LOG:INFO:Extracted " + frames.size() + " frames");

            // ── Relevance triage ───────────────────────────────────────────────
            // Runs before the full-frame call so an unusable video costs a fraction of a
            // generation. Skipped entirely when the user has chosen to analyse anyway.
            RelevanceResult relevance = RelevanceResult.skipped();
            if (force) {
                push("LOG:WARN:Relevance check overridden by user — analysing anyway");
            } else {
                push("LOG:INFO:Checking whether the video shows a UI workflow...");
                relevance = new VideoRelevanceGate(config.relevance(), config.bedrock())
                        .evaluate(frames);

                if (relevance.isRejected()) {
                    push("LOG:ERROR:Video rejected — " + RelevanceResponse.logLine(relevance));
                    push("STATUS:ready");
                    sendJson(ex, 200, RelevanceResponse.rejectionJson(relevance, frames.size()));
                    return;
                }
                if (relevance.isUncertain()) {
                    push("LOG:WARN:" + relevance.reason());
                } else {
                    push("LOG:SUCCESS:Video looks like a UI workflow — continuing");
                }
            }

            push("PROGRESS:0:" + frames.size() + ":Sending to Claude...");
            push("LOG:INFO:Invoking Claude (model: " + config.bedrockModelId() + ")...");

            // Original provisioning prompt (```goal block + full gradle line) — same as runVideo CLI
            String userPrompt = overrideUrl != null && !overrideUrl.isBlank()
                    ? VideoToGoalPrompt.userPromptWithUrl(overrideUrl)
                    : VideoToGoalPrompt.USER_PROMPT;

            try (BedrockAnthropicClient client = new BedrockAnthropicClient(
                    config.bedrock().region(), config.bedrock().profile(),
                    config.bedrock().modelId(), config.bedrock().maxTokens(),
                    config.bedrock().temperature())) {
                InvokeResult result = client.invokeWithMultipleImages(
                        VideoToGoalPrompt.SYSTEM_PROMPT, userPrompt, frames);

                push("LOG:SUCCESS:Claude responded — extracting goal...");

                GoalExtractor.ExtractionResult extraction = GoalExtractor.extract(result.text());
                String goalLine = extraction.goalLine();
                if (goalLine != null) {
                    state.lastGoalLine.set(goalLine);
                }

                if (!extraction.isValid()) {
                    push("LOG:WARN:Goal format validation failed (" + extraction.issues().size()
                            + " issue(s)) — showing results and confidence eval anyway");
                }

                String navigationGoal = !extraction.steps().isEmpty()
                        ? String.join(", then ", extraction.steps())
                        : GoalExtractor.bestEffortNavigationGoal(result.text(), extraction);
                String targetUrl = extraction.url() != null && !extraction.url().isBlank()
                        ? extraction.url()
                        : (overrideUrl != null ? overrideUrl : "");

                // Real-time confidence (same as aggregation: runs whenever eval toggle is on)
                ConfidenceResult confidence = null;
                if (evalEnabled) {
                    try {
                        VideoAnalysisResult syntheticResult = syntheticProvisioningResult(
                                navigationGoal, targetUrl);
                        push("LOG:INFO:Running real-time confidence evaluation...");
                        confidence = ConfidenceEvaluator.evaluate(
                                syntheticResult, "PROVISIONING", "LITERAL", client);
                        confidence = mergeExtractionIssues(confidence, extraction);
                        push("LOG:INFO:Confidence: " + confidence.confidenceScore()
                                + " — " + confidence.recommendation());
                    } catch (Exception e) {
                        push("LOG:WARN:Confidence evaluation failed: " + e.getMessage());
                    }
                } else {
                    push("LOG:INFO:Confidence eval skipped (disabled by user)");
                }

                push("STATUS:ready");
                // Do not emit DONE here — generate uses fetch; DONE is for /api/run only.

                String json = buildResultJson(
                        extraction, result, frames.size(), confidence, navigationGoal, targetUrl,
                        relevance);
                sendJson(ex, 200, json);
            }

        } catch (Exception e) {
            push("LOG:ERROR:" + e.getMessage());
            push("STATUS:ready");
            push("DONE:1");
            String escaped = e.getMessage() == null ? "Internal error"
                    : e.getMessage().replace("\"", "\\\"").replace("\n", " ");
            sendJson(ex, 500, "{\"error\":\"" + escaped + "\"}");
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void push(String msg) {
        state.logQueue.offer(msg);
    }

    private static VideoAnalysisResult syntheticProvisioningResult(
            String navigationGoal, String targetUrl) {
        JSONObject json = new JSONObject();
        json.put("navigationGoal", navigationGoal != null ? navigationGoal : "");
        json.put("targetUrl", targetUrl != null ? targetUrl : "");
        return VideoAnalysisResult.parse(json.toString(), VideoAnalysisRequest.provisioning());
    }

    private static ConfidenceResult mergeExtractionIssues(
            ConfidenceResult confidence, GoalExtractor.ExtractionResult extraction) {
        if (extraction.isValid() || extraction.issues().isEmpty()) {
            return confidence;
        }
        List<String> merged = new ArrayList<>();
        for (String issue : extraction.issues()) {
            merged.add("Goal format: " + issue);
        }
        for (String w : confidence.warnings()) {
            if (!merged.contains(w)) {
                merged.add(w);
            }
        }
        String recommendation = ConfidenceResult.deriveRecommendation(
                confidence.confidenceScore(), merged, confidence.suspectedHallucinations());
        return new ConfidenceResult(
                confidence.confidenceScore(),
                recommendation,
                List.copyOf(merged),
                confidence.suspectedHallucinations(),
                confidence.placeholderCompliant(),
                confidence.stepCount(),
                confidence.reasoning(),
                confidence.tokenUsage());
    }

    private static String buildResultJson(
            GoalExtractor.ExtractionResult extraction,
            InvokeResult result,
            int frameCount,
            ConfidenceResult confidence,
            String navigationGoal,
            String targetUrl,
            RelevanceResult relevance) {

        List<String> steps  = extraction.steps();
        List<String> issues = extraction.issues();
        String goalLine     = extraction.goalLine();
        String url          = targetUrl != null && !targetUrl.isBlank() ? targetUrl : extraction.url();

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"goalLine\":").append(quoted(goalLine));
        sb.append(",\"url\":").append(quoted(url));
        sb.append(",\"targetUrl\":").append(quoted(url));
        sb.append(",\"navigationGoal\":").append(quoted(navigationGoal));
        sb.append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoted(steps.get(i)));
        }
        sb.append("]");
        sb.append(",\"isValid\":").append(extraction.isValid());
        sb.append(",\"issues\":[");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoted(issues.get(i)));
        }
        sb.append("]");
        // Totals include the triage call so the reported cost matches what was actually spent.
        TokenUsage total = result.usage().add(relevance.tokenUsage());
        sb.append(",\"inputTokens\":").append(total.inputTokens());
        sb.append(",\"outputTokens\":").append(total.outputTokens());
        sb.append(",\"costUsd\":").append(total.totalCostUsd());
        sb.append(",\"frameCount\":").append(frameCount);
        sb.append(",\"status\":\"ACCEPTED\"");
        if (relevance.isUncertain()) {
            sb.append(",\"relevanceWarning\":").append(quoted(relevance.reason()));
        }
        // Confidence evaluation (best-effort — null when eval was skipped or failed)
        if (confidence != null) {
            sb.append(",\"confidenceScore\":").append(confidence.confidenceScore());
            sb.append(",\"recommendation\":").append(quoted(confidence.recommendation()));
            sb.append(",\"confidenceReasoning\":").append(quoted(confidence.reasoning()));
            sb.append(",\"confidenceWarnings\":[");
            List<String> warnings = confidence.warnings();
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(quoted(warnings.get(i)));
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] body = msg.getBytes();
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static String quoted(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ── Commons FileUpload adapter ────────────────────────────────────────────

    private static final class HttpServletRequestAdapter
            implements org.apache.commons.fileupload.RequestContext {

        private final HttpExchange ex;
        private final String       contentType;

        HttpServletRequestAdapter(HttpExchange ex, String contentType) {
            this.ex          = ex;
            this.contentType = contentType;
        }

        @Override public String      getCharacterEncoding() { return "UTF-8"; }
        @Override public String      getContentType()        { return contentType; }
        @Override public int         getContentLength()      { return -1; }
        @Override public InputStream getInputStream()        { return ex.getRequestBody(); }
    }
}
