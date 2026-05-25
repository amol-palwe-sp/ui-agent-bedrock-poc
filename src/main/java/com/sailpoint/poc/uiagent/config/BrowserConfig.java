package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of browser / Playwright configuration from {@code application.properties}.
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#browser()}.
 */
public record BrowserConfig(
        boolean headless,
        int     slowMoMs,
        int     viewportWidth,
        int     viewportHeight,
        boolean startMaximized,
        int     fullscreenWidth,
        int     fullscreenHeight,
        int     clickTimeoutMs,
        int     typeTimeoutMs,
        int     navigateTimeoutMs,
        int     interActionDelayMs) {}
