package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.aggregation.AggregationUIAnalysis;
import com.sailpoint.poc.uiagent.aggregation.AggregationVideoAnalysisPrompt;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * POST /api/aggregation/generate
 *
 * <p>Accepts a multipart/form-data MP4 upload, extracts frames, invokes Claude once using
 * {@link AggregationVideoAnalysisPrompt} to produce a navigation goal with {@code {Token}}
 * placeholders AND a pagination pattern, then returns the parsed result as JSON.
 *
 * <p>Progress messages are pushed to {@link AggregationServerState#logQueue} so the
 * aggregation SSE stream can relay them to the browser in real time.
 */
public final class AggregationGenerateHandler implements HttpHandler {

    private static final long MAX_UPLOAD_BYTES = 500L * 1024 * 1024; // 500 MB

    private final AggregationServerState state;

    public AggregationGenerateHandler(AggregationServerState state) {
        this.state = state;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Path tempFile = null;
        try {
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

            byte[]  videoBytes  = null;
            String  overrideUrl = null;
            Integer maxFrames   = null;

            for (FileItem item : items) {
                if (!item.isFormField() && "video".equals(item.getFieldName())) {
                    String name = item.getName();
                    if (name == null || !name.toLowerCase().endsWith(".mp4")) {
                        sendJson(ex, 400, "{\"error\":\"Only MP4 supported\"}");
                        return;
                    }
                    if (item.getSize() > MAX_UPLOAD_BYTES) {
                        sendJson(ex, 400, "{\"error\":\"File exceeds 500MB\"}");
                        return;
                    }
                    videoBytes = item.get();
                } else if (item.isFormField()) {
                    switch (item.getFieldName()) {
                        case "url"       -> overrideUrl = item.getString().trim();
                        case "maxFrames" -> {
                            try { maxFrames = Integer.parseInt(item.getString().trim()); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            if (videoBytes == null || videoBytes.length == 0) {
                sendJson(ex, 400, "{\"error\":\"No video file received\"}");
                return;
            }

            tempFile = Files.createTempFile("uiagent-aggr-" + UUID.randomUUID(), ".mp4");
            Files.write(tempFile, videoBytes);

            push("STATUS:generating");
            push("LOG:INFO:Extracting frames from video...");

            PocConfig config = new PocConfig();
            int effectiveMaxFrames  = maxFrames != null ? maxFrames
                    : Integer.parseInt(config.optional("video.max.frames", "80"));
            double changeThreshold  = Double.parseDouble(config.optional("video.change.threshold", "0.02"));
            double minGapSeconds    = Double.parseDouble(config.optional("video.min.gap.seconds", "0.5"));
            double maxForcedGapSecs = Double.parseDouble(config.optional("video.max.forced.gap.seconds", "3.0"));
            int    frameMaxWidth    = Integer.parseInt(config.optional("video.frame.max.width", "1280"));
            int    jpegQuality      = Integer.parseInt(config.optional("video.jpeg.quality", "75"));

            VideoFrameExtractor extractor = new VideoFrameExtractor(
                    effectiveMaxFrames, changeThreshold, minGapSeconds, maxForcedGapSecs,
                    frameMaxWidth, jpegQuality, null);
            List<byte[]> frames = extractor.extractFrames(tempFile.toString());

            if (frames.isEmpty()) {
                push("LOG:ERROR:No frames extracted — video may be empty or corrupted");
                push("STATUS:ready");
                sendJson(ex, 500, "{\"error\":\"No frames extracted from video\"}");
                return;
            }

            push("LOG:INFO:Extracted " + frames.size() + " frames");
            push("PROGRESS:0:" + frames.size() + ":Sending to Claude...");
            push("LOG:INFO:Invoking Claude (model: " + config.bedrockModelId() + ")...");

            String userPrompt = (overrideUrl != null && !overrideUrl.isBlank())
                    ? AggregationVideoAnalysisPrompt.userPromptWithUrl(overrideUrl)
                    : AggregationVideoAnalysisPrompt.USER_PROMPT;

            InvokeResult result;
            try (BedrockAnthropicClient client = new BedrockAnthropicClient(
                    config.awsRegion(),
                    config.awsProfile(),
                    config.bedrockModelId(),
                    config.maxTokens(),
                    config.temperature())) {
                result = client.invokeWithMultipleImages(
                        AggregationVideoAnalysisPrompt.SYSTEM_PROMPT, userPrompt, frames);
            }

            push("LOG:SUCCESS:Claude responded — parsing navigation goal and pagination pattern...");

            AggregationUIAnalysis analysis;
            List<String> issues;
            boolean isValid;
            try {
                analysis = AggregationVideoAnalysisPrompt.parse(result.text());
                issues   = List.of();
                isValid  = true;
            } catch (IllegalArgumentException e) {
                push("LOG:ERROR:Parse failed: " + e.getMessage());
                push("STATUS:ready");
                String escaped = e.getMessage() == null ? "Parse error"
                        : e.getMessage().replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
                sendJson(ex, 500, "{\"error\":\"" + escaped + "\"}");
                return;
            }

            List<String> steps = parseSteps(analysis.navigationGoal());
            push("LOG:INFO:Extracted " + steps.size() + " navigation step(s)");
            push("STATUS:ready");

            String json = buildResultJson(analysis, steps, result, frames.size(), isValid, issues);
            sendJson(ex, 200, json);

        } catch (Exception e) {
            push("LOG:ERROR:" + e.getMessage());
            push("STATUS:ready");
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

    /** Splits a navigation goal on {@code ", then "} to produce individual step strings. */
    private static List<String> parseSteps(String navigationGoal) {
        if (navigationGoal == null || navigationGoal.isBlank()) return List.of();
        return Arrays.stream(navigationGoal.split(",\\s*then\\s+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String buildResultJson(
            AggregationUIAnalysis analysis,
            List<String> steps,
            InvokeResult result,
            int frameCount,
            boolean isValid,
            List<String> issues) {

        PaginationPattern pp = analysis.paginationPattern();

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"url\":").append(quoted(analysis.targetUrl()));
        sb.append(",\"navigationGoal\":").append(quoted(analysis.navigationGoal()));
        sb.append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoted(steps.get(i)));
        }
        sb.append("]");
        sb.append(",\"paginationPattern\":{");
        sb.append("\"type\":").append(quoted(pp.type()));
        sb.append(",\"description\":").append(quoted(pp.description()));
        sb.append(",\"selectorHint\":").append(quoted(pp.selectorHint()));
        sb.append("}");
        sb.append(",\"isValid\":").append(isValid);
        sb.append(",\"issues\":[");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoted(issues.get(i)));
        }
        sb.append("]");
        sb.append(",\"inputTokens\":").append(result.usage().inputTokens());
        sb.append(",\"outputTokens\":").append(result.usage().outputTokens());
        sb.append(",\"costUsd\":").append(result.usage().totalCostUsd());
        sb.append(",\"frameCount\":").append(frameCount);
        sb.append("}");
        return sb.toString();
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String quoted(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
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
