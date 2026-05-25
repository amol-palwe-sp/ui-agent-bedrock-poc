package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of agent-loop configuration from {@code application.properties}.
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#agent()}.
 */
public record AgentConfig(
        int    maxSteps,
        String logFile,
        int    noProgressLimit,
        int    multiViewportMaxFrames) {}
