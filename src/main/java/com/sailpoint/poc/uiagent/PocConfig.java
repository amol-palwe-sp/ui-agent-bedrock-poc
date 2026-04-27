package com.sailpoint.poc.uiagent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads {@code application.properties} from the classpath.
 */
public final class PocConfig {

    private final Properties properties;

    public PocConfig() throws IOException {
        Properties p = new Properties();
        try (InputStream in = PocConfig.class.getResourceAsStream("/application.properties")) {
            if (in == null) {
                throw new IOException(
                        "Missing classpath application.properties. "
                                + "Copy application.properties.example to src/main/resources/application.properties");
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

    public String awsRegion() { return require("aws.region"); }
    public String awsProfile() { return optional("aws.profile", ""); }

    public String bedrockModelId() {
        String env = System.getenv("BEDROCK_MODEL_ID");
        return (env != null && !env.isBlank()) ? env.trim() : require("bedrock.model.id");
    }

    public String bedrockModelIdEnvRaw() { return System.getenv("BEDROCK_MODEL_ID"); }

    public int maxTokens() { return Integer.parseInt(optional("anthropic.max_tokens", "4096")); }
    public double temperature() { return Double.parseDouble(optional("anthropic.temperature", "0")); }
    public int agentMaxSteps() { return Integer.parseInt(optional("agent.max_steps", "15")); }
    public boolean browserHeadless() { return Boolean.parseBoolean(optional("browser.headless", "false")); }
    public int browserSlowMoMs() { return Integer.parseInt(optional("browser.slow_mo_ms", "0")); }

    /** {@code jpeg} or {@code png}. Defaults to {@code jpeg} for lower token cost. */
    public String screenshotFormat() { return optional("screenshot.format", "jpeg"); }

    /** JPEG quality 0–100. Ignored when format is {@code png}. */
    public int screenshotJpegQuality() { return Integer.parseInt(optional("screenshot.jpeg.quality", "70")); }

    public int browserViewportWidth() { return Integer.parseInt(optional("browser.viewport.width", "1024")); }
    public int browserViewportHeight() { return Integer.parseInt(optional("browser.viewport.height", "640")); }
}
