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

            byte[]  videoBytes  = null;
            String  overrideUrl = null;
            Integer maxFrames   = null;

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
                        case "url"       -> overrideUrl = item.getString().trim();
                        case "maxFrames" -> {
                            try { maxFrames = Integer.parseInt(item.getString().trim()); }
                            catch (NumberFormatException ignored) {}
                        }
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
            push("PROGRESS:0:" + frames.size() + ":Sending to Claude...");
            push("LOG:INFO:Invoking Claude (model: " + config.bedrockModelId() + ")...");

            // Original provisioning prompt (```goal block + full gradle line) — same as runVideo CLI
            String userPrompt = overrideUrl != null && !overrideUrl.isBlank()
                    ? VideoToGoalPrompt.userPromptWithUrl(overrideUrl)
                    : VideoToGoalPrompt.USER_PROMPT;

            InvokeResult result;
            try (BedrockAnthropicClient client = new BedrockAnthropicClient(
                    config.bedrock().region(), config.bedrock().profile(),
                    config.bedrock().modelId(), config.bedrock().maxTokens(),
                    config.bedrock().temperature())) {
                result = client.invokeWithMultipleImages(
                        VideoToGoalPrompt.SYSTEM_PROMPT, userPrompt, frames);
            }

            push("LOG:SUCCESS:Claude responded — extracting goal...");

            GoalExtractor.ExtractionResult extraction = GoalExtractor.extract(result.text());
            String goalLine = extraction.goalLine();
            if (goalLine != null) {
                state.lastGoalLine.set(goalLine);
            }

            push("STATUS:ready");
            push("DONE:" + (extraction.isValid() ? "0" : "1"));

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

    private static String buildResultJson(
            GoalExtractor.ExtractionResult extraction,
            InvokeResult result,
            int frameCount) {

        List<String> steps  = extraction.steps();
        List<String> issues = extraction.issues();
        String goalLine     = extraction.goalLine();
        String url          = extraction.url();
        String navigationGoal = steps.isEmpty() ? "" : String.join(", then ", steps);

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
