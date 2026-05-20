package com.sailpoint.poc.uiagent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads {@code application.properties} from the classpath and exposes typed accessors.
 */
public final class PocConfig {

    private final Properties properties;

    public PocConfig() throws IOException {
        Properties p = new Properties();
        try (InputStream in = PocConfig.class.getResourceAsStream("/application.properties")) {
            if (in == null) {
                throw new IOException(
                        "Missing classpath application.properties. "
                                + "Copy application.properties.example to "
                                + "src/main/resources/application.properties");
            }
            p.load(in);
        }
        this.properties = p;
    }

    public String require(String key) {
        String v = properties.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required property: " + key);
        return v.trim();
    }

    public String optional(String key, String defaultValue) {
        String v = properties.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    // --- AWS / Bedrock ---

    public String awsRegion()   { return require("aws.region"); }
    public String awsProfile()  { return optional("aws.profile", ""); }

    public String bedrockModelId() {
        String env = System.getenv("BEDROCK_MODEL_ID");
        return (env != null && !env.isBlank()) ? env.trim() : require("bedrock.model.id");
    }

    public String bedrockModelIdEnvRaw() { return System.getenv("BEDROCK_MODEL_ID"); }

    // --- LLM parameters ---

    public int    maxTokens()   { return Integer.parseInt(optional("anthropic.max_tokens", "4096")); }
    public double temperature() { return Double.parseDouble(optional("anthropic.temperature", "0")); }

    // --- Agent ---

    public int agentMaxSteps()  { return Integer.parseInt(optional("agent.max_steps", "15")); }

    /** Path to JSONL action log. Set to {@code none} or leave blank to disable. */
    public String agentLogFile() { return optional("agent.log.file", "none"); }

    /** Consecutive no-progress steps before the agent injects a stuck-warning into history. */
    public int agentNoProgressLimit() {
        return Integer.parseInt(optional("agent.no_progress_limit", "3"));
    }

    // --- Browser ---

    public boolean browserHeadless()  { return Boolean.parseBoolean(optional("browser.headless", "false")); }
    public int     browserSlowMoMs()  { return Integer.parseInt(optional("browser.slow_mo_ms", "0")); }
    public boolean browserStartMaximized() {
        return Boolean.parseBoolean(optional("browser.start.maximized", "true"));
    }
    public int browserFullscreenViewportWidth() {
        return Integer.parseInt(optional("browser.fullscreen.width", "1920"));
    }
    public int browserFullscreenViewportHeight() {
        return Integer.parseInt(optional("browser.fullscreen.height", "1080"));
    }

    // --- Viewport (used when start.maximized=false) ---

    public int browserViewportWidth()  { return Integer.parseInt(optional("browser.viewport.width",  "1024")); }
    public int browserViewportHeight() { return Integer.parseInt(optional("browser.viewport.height", "640")); }

    // --- Screenshot ---

    /** {@code jpeg} or {@code png}. Defaults to {@code jpeg} for lower token cost. */
    public String screenshotFormat()      { return optional("screenshot.format", "jpeg"); }

    /** JPEG quality 0–100. Ignored when format is {@code png}. */
    public int screenshotJpegQuality()    { return Integer.parseInt(optional("screenshot.jpeg.quality", "70")); }

    // --- Per-action timeouts ---

    /** Timeout for click-family actions (click, hover, checkbox). */
    public int actionTimeoutClickMs() {
        return Integer.parseInt(optional("browser.action.timeout.click.ms", "8000"));
    }

    /** Timeout for type-family actions (fill, pressSequentially, keyboard.type). */
    public int actionTimeoutTypeMs() {
        return Integer.parseInt(optional("browser.action.timeout.type.ms", "5000"));
    }

    /** Timeout for navigation actions (navigate, reload). */
    public int actionTimeoutNavigateMs() {
        return Integer.parseInt(optional("browser.action.timeout.navigate.ms", "90000"));
    }

    /**
     * Upper bound for random inter-action delay in milliseconds. 0 = no delay.
     * When non-zero a uniform random delay in [0, value] is inserted before each action to
     * introduce human-like pacing.
     */
    public int interActionDelayMs() {
        return Integer.parseInt(optional("browser.inter.action.delay.ms", "0"));
    }
}
