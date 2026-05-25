package com.sailpoint.poc.uiagent;

import com.sailpoint.poc.uiagent.config.AgentConfig;
import com.sailpoint.poc.uiagent.config.AggregationConfig;
import com.sailpoint.poc.uiagent.config.BedrockConfig;
import com.sailpoint.poc.uiagent.config.BrowserConfig;
import com.sailpoint.poc.uiagent.config.VideoConfig;

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

    /**
     * Maximum viewport-sized screenshot tiles captured top→bottom on page-shape-changing
     * observations (INIT / GOTO / RELOAD_PAGE) so the LLM can see a tall page (long forms)
     * in one observation. Set to {@code 1} or {@code 0} to disable and use single-viewport
     * screenshots only.  Default {@code 6} balances visibility with token cost.
     */
    public int agentMultiViewportMaxFrames() {
        return Integer.parseInt(optional("agent.multi.viewport.max.frames", "6"));
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

    // --- Video frame extraction ---

    public int videoMaxFrames() {
        return Integer.parseInt(optional("video.max.frames", "80"));
    }

    public double videoChangeThreshold() {
        return Double.parseDouble(optional("video.change.threshold", "0.005"));
    }

    public double videoMinGapSeconds() {
        return Double.parseDouble(optional("video.min.gap.seconds", "0.5"));
    }

    /**
     * Maximum seconds allowed between consecutive kept frames before a frame is forced through
     * regardless of pixel-change magnitude. Catches subtle UI changes (checkbox toggles, etc.)
     * that fall below {@code videoChangeThreshold()}. Defaults to 3.0 seconds.
     */
    public double videoMaxForcedGapSeconds() {
        return Double.parseDouble(optional("video.max.forced.gap.seconds", "3.0"));
    }

    /**
     * Maximum frame width in pixels. Frames wider than this are downscaled proportionally before
     * JPEG encoding. Reduces HTTP payload size and Claude token cost (tokens scale with pixel count).
     * Set to {@code 0} to disable resizing. Defaults to 1280.
     */
    public int videoFrameMaxWidth() {
        return Integer.parseInt(optional("video.frame.max.width", "1280"));
    }

    /**
     * JPEG quality for video frame encoding (1–100). 75 is a good balance between
     * payload size and UI text legibility. Defaults to 75.
     */
    public int videoJpegQuality() {
        return Integer.parseInt(optional("video.jpeg.quality", "75"));
    }

    /** Returns the debug-frames directory path, or an empty string when disabled. */
    public String videoDebugFramesDir() {
        return optional("video.debug.frames.dir", "");
    }

    public int videoGridSize() {
        return clampGrid(Integer.parseInt(optional("video.grid.size", "12")));
    }

    public double videoGridCellThreshold() {
        return Double.parseDouble(optional("video.grid.cell.threshold", "0.08"));
    }

    public double videoScoreWeightBreadth() {
        return Double.parseDouble(optional("video.score.weight.breadth", "0.4"));
    }

    public double videoScoreWeightIntensity() {
        return Double.parseDouble(optional("video.score.weight.intensity", "0.4"));
    }

    public double videoScoreWeightPresence() {
        return Double.parseDouble(optional("video.score.weight.presence", "0.2"));
    }

    public double videoZoneWeightUrlBar() {
        return Double.parseDouble(optional("video.zone.weight.url_bar", "3.0"));
    }

    public double videoZoneWeightMainContent() {
        return Double.parseDouble(optional("video.zone.weight.main_content", "2.0"));
    }

    public double videoZoneWeightLeftNav() {
        return Double.parseDouble(optional("video.zone.weight.left_nav", "0.5"));
    }

    public double videoZoneWeightRightSidebar() {
        return Double.parseDouble(optional("video.zone.weight.right_sidebar", "0.5"));
    }

    public double videoZoneWeightBottomBar() {
        return Double.parseDouble(optional("video.zone.weight.bottom_bar", "0.5"));
    }

    public double videoPatternNavSpikeThreshold() {
        return Double.parseDouble(optional("video.pattern.nav.spike.threshold", "0.5"));
    }

    public double videoPatternNavStableBonus() {
        return Double.parseDouble(optional("video.pattern.nav.stable.bonus", "2.5"));
    }

    public double videoPatternNavSpikePenalty() {
        return Double.parseDouble(optional("video.pattern.nav.spike.penalty", "0.3"));
    }

    public int videoPatternTypingMaxCells() {
        return Integer.parseInt(optional("video.pattern.typing.max.cells", "3"));
    }

    public double videoPatternTypingMaxCellChange() {
        return Double.parseDouble(optional("video.pattern.typing.max.cell.change", "0.05"));
    }

    public int videoPatternTypingMinFrames() {
        return Integer.parseInt(optional("video.pattern.typing.min.frames", "3"));
    }

    public double videoPatternTypingEndBonus() {
        return Double.parseDouble(optional("video.pattern.typing.end.bonus", "2.0"));
    }

    public double videoPatternTypingMidPenalty() {
        return Double.parseDouble(optional("video.pattern.typing.mid.penalty", "0.2"));
    }

    public int videoPatternAnimationWindow() {
        return Integer.parseInt(optional("video.pattern.animation.window", "5"));
    }

    public double videoPatternAnimationVarianceThreshold() {
        return Double.parseDouble(optional("video.pattern.animation.variance.threshold", "0.20"));
    }

    public int videoPatternAnimationMinFrames() {
        return Integer.parseInt(optional("video.pattern.animation.min.frames", "4"));
    }

    public double videoPatternAnimationPenalty() {
        return Double.parseDouble(optional("video.pattern.animation.penalty", "0.1"));
    }

    public int videoPatternElementMinClusterSize() {
        return Integer.parseInt(optional("video.pattern.element.min.cluster.size", "4"));
    }

    public int videoPatternElementPersistFrames() {
        return Integer.parseInt(optional("video.pattern.element.persist.frames", "2"));
    }

    public double videoPatternElementBonus() {
        return Double.parseDouble(optional("video.pattern.element.bonus", "2.0"));
    }

    public double videoPatternScrollThreshold() {
        return Double.parseDouble(optional("video.pattern.scroll.threshold", "0.3"));
    }

    public double videoPatternScrollEndBonus() {
        return Double.parseDouble(optional("video.pattern.scroll.end.bonus", "1.5"));
    }

    public double videoPatternScrollMidPenalty() {
        return Double.parseDouble(optional("video.pattern.scroll.mid.penalty", "0.3"));
    }

    public double videoSelectionMaxGapSeconds() {
        return Double.parseDouble(optional("video.selection.max.gap.seconds", "8.0"));
    }

    public double videoSelectionSimilarityThreshold() {
        return Double.parseDouble(optional("video.selection.similarity.threshold", "0.92"));
    }

    public double videoUrlBarMandatoryThreshold() {
        return Double.parseDouble(optional("video.url.bar.mandatory.threshold", "0.30"));
    }

    private static int clampGrid(int size) {
        return Math.max(4, Math.min(32, size));
    }

    // --- Account Aggregation ---

    /** Maximum number of pages the aggregation loop will scrape before stopping. */
    public int aggregationMaxPages() {
        return Integer.parseInt(optional("aggregation.max.pages", "50"));
    }

    /** Directory where aggregation CSV files are written. */
    public String aggregationOutputDir() {
        return optional("aggregation.output.dir", "./output");
    }

    // =========================================================================
    // REQ-5: Typed sub-config facade methods
    // =========================================================================

    /**
     * Returns all Bedrock/LLM settings as a single typed object.
     * Prefer this over calling individual methods in new code.
     */
    public BedrockConfig bedrock() {
        return new BedrockConfig(
                awsRegion(), awsProfile(), bedrockModelId(),
                maxTokens(), temperature());
    }

    /**
     * Returns all browser/Playwright settings as a single typed object.
     * Prefer this over calling individual methods in new code.
     */
    public BrowserConfig browser() {
        return new BrowserConfig(
                browserHeadless(), browserSlowMoMs(),
                browserViewportWidth(), browserViewportHeight(),
                browserStartMaximized(),
                browserFullscreenViewportWidth(), browserFullscreenViewportHeight(),
                actionTimeoutClickMs(), actionTimeoutTypeMs(),
                actionTimeoutNavigateMs(), interActionDelayMs());
    }

    /**
     * Returns all agent-loop settings as a single typed object.
     * Prefer this over calling individual methods in new code.
     */
    public AgentConfig agent() {
        return new AgentConfig(
                agentMaxSteps(), agentLogFile(),
                agentNoProgressLimit(), agentMultiViewportMaxFrames());
    }

    /**
     * Returns all video-frame-extraction settings as a single typed object.
     * Prefer this over calling individual methods in new code.
     */
    public VideoConfig video() {
        return new VideoConfig(
                videoMaxFrames(), videoChangeThreshold(),
                videoMinGapSeconds(), videoMaxForcedGapSeconds(),
                videoFrameMaxWidth(), videoJpegQuality(),
                videoDebugFramesDir(),
                videoGridSize(), videoGridCellThreshold(),
                videoScoreWeightBreadth(), videoScoreWeightIntensity(), videoScoreWeightPresence(),
                videoZoneWeightUrlBar(), videoZoneWeightMainContent(),
                videoZoneWeightLeftNav(), videoZoneWeightRightSidebar(), videoZoneWeightBottomBar(),
                videoPatternNavSpikeThreshold(), videoPatternNavStableBonus(), videoPatternNavSpikePenalty(),
                videoPatternTypingMaxCells(), videoPatternTypingMaxCellChange(), videoPatternTypingMinFrames(),
                videoPatternTypingEndBonus(), videoPatternTypingMidPenalty(),
                videoPatternAnimationWindow(), videoPatternAnimationVarianceThreshold(),
                videoPatternAnimationMinFrames(), videoPatternAnimationPenalty(),
                videoPatternElementMinClusterSize(), videoPatternElementPersistFrames(), videoPatternElementBonus(),
                videoPatternScrollThreshold(), videoPatternScrollEndBonus(), videoPatternScrollMidPenalty(),
                videoSelectionMaxGapSeconds(), videoSelectionSimilarityThreshold(),
                videoUrlBarMandatoryThreshold());
    }

    /**
     * Returns all account-aggregation settings as a single typed object.
     * Prefer this over calling individual methods in new code.
     */
    public AggregationConfig aggregation() {
        return new AggregationConfig(aggregationMaxPages(), aggregationOutputDir());
    }
}
