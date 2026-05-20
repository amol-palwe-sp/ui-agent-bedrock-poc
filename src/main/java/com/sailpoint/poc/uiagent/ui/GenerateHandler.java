package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.video.GoalExtractor;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sailpoint.poc.uiagent.video.VideoToGoalPrompt;
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
import java.util.List;
import java.util.UUID;

/**
 * POST /api/generate
 *
 * <p>Accepts a multipart/form-data request containing an MP4 video upload plus
 * optional overrides (url, maxFrames). Runs the full video → Claude → goal pipeline
 * and returns a JSON result.
 *
 * <p>Progress is pushed to {@link AgentUIServer.ServerState#logQueue} so the SSE
 * stream can relay it to the browser in real time.
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

            // Commons FileUpload needs a javax.servlet.http.HttpServletRequest-like
            // context; we bridge it with a minimal RequestContext adapter.
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

            // ── Save to temp file (OpenCV needs a file path) ───────────────────
            tempFile = Files.createTempFile("uiagent-upload-" + UUID.randomUUID(), ".mp4");
            Files.write(tempFile, videoBytes);

            // ── Pipeline ───────────────────────────────────────────────────────
            push("STATUS:generating");
            push("LOG:INFO:Extracting frames from video...");

            PocConfig config = new PocConfig();
            int effectiveMaxFrames = maxFrames != null ? maxFrames
                    : Integer.parseInt(config.optional("video.max.frames", "80"));
            double changeThreshold = Double.parseDouble(config.optional("video.change.threshold", "0.02"));
            double minGapSeconds   = Double.parseDouble(config.optional("video.min.gap.seconds", "0.5"));

            VideoFrameExtractor extractor = new VideoFrameExtractor(
                    effectiveMaxFrames, changeThreshold, minGapSeconds, null);
            List<byte[]> frames = extractor.extractFrames(tempFile.toString());

            if (frames.isEmpty()) {
                push("LOG:ERROR:No frames extracted — video may be empty or corrupted");
                push("STATUS:ready");
                push("DONE:1");
                sendJson(ex, 500, "{\"error\":\"No frames extracted from video\"}");
                return;
            }

            push("LOG:INFO:Extracted " + frames.size() + " frames");
            push("PROGRESS:0:" + frames.size() + ":Sending to Claude...");
            push("LOG:INFO:Invoking Claude (model: " + config.bedrockModelId() + ")...");

            String userPrompt = (overrideUrl != null && !overrideUrl.isBlank())
                    ? VideoToGoalPrompt.userPromptWithUrl(overrideUrl)
                    : VideoToGoalPrompt.USER_PROMPT;

            InvokeResult result;
            try (BedrockAnthropicClient client = new BedrockAnthropicClient(
                    config.awsRegion(),
                    config.awsProfile(),
                    config.bedrockModelId(),
                    config.maxTokens(),
                    config.temperature())) {
                result = client.invokeWithMultipleImages(
                        VideoToGoalPrompt.SYSTEM_PROMPT, userPrompt, frames);
            }

            push("LOG:SUCCESS:Claude responded — extracting goal...");

            GoalExtractor.ExtractionResult extraction = GoalExtractor.extract(result.text());
            state.lastGoalLine.set(extraction.goalLine() != null ? extraction.goalLine() : "");

            push("STATUS:ready");
            push("DONE:" + (extraction.isValid() ? "0" : "1"));

            // ── Build JSON response ────────────────────────────────────────────
            String json = buildResultJson(extraction, result, frames.size());
            sendJson(ex, 200, json);

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

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] body = msg.getBytes();
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static String buildResultJson(
            GoalExtractor.ExtractionResult extraction,
            InvokeResult result,
            int frameCount) {

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"goalLine\":").append(quoted(extraction.goalLine()));
        sb.append(",\"url\":").append(quoted(extraction.url()));
        sb.append(",\"steps\":[");
        List<String> steps = extraction.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoted(steps.get(i)));
        }
        sb.append("]");
        sb.append(",\"isValid\":").append(extraction.isValid());
        sb.append(",\"issues\":[");
        List<String> issues = extraction.issues();
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

    private static String quoted(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    // ── Commons FileUpload adapter ────────────────────────────────────────────

    /**
     * Minimal bridge between {@link HttpExchange} and the interface expected by
     * {@link ServletFileUpload#parseRequest}.
     */
    private static final class HttpServletRequestAdapter
            implements org.apache.commons.fileupload.RequestContext {

        private final HttpExchange ex;
        private final String       contentType;

        HttpServletRequestAdapter(HttpExchange ex, String contentType) {
            this.ex          = ex;
            this.contentType = contentType;
        }

        @Override public String   getCharacterEncoding() { return "UTF-8"; }
        @Override public String   getContentType()        { return contentType; }
        @Override public int      getContentLength()      { return -1; }
        @Override public InputStream getInputStream()     { return ex.getRequestBody(); }
    }
}
