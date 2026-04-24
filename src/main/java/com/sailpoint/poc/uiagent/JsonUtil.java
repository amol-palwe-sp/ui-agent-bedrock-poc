package com.sailpoint.poc.uiagent;

/**
 * JSON extraction utilities for cleaning up model output that may contain markdown fences or leading prose.
 */
public final class JsonUtil {

    private JsonUtil() {}

    public static String stripMarkdownFence(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```json")) {
            int start = t.indexOf("```json") + 7;
            int end = t.lastIndexOf("```");
            if (end > start) return t.substring(start, end).trim();
        }
        if (t.startsWith("```")) {
            int start = t.indexOf("```") + 3;
            int end = t.lastIndexOf("```");
            if (end > start) return t.substring(start, end).trim();
        }
        return t;
    }

    /**
     * When the model adds prose before/after the plan, take the first top-level JSON object by brace matching
     * (string-aware for {@code "} and {@code \\}).
     */
    public static String extractFirstJsonObject(String text) {
        if (text == null || text.isEmpty()) return "";
        int start = text.indexOf('{');
        if (start < 0) return text.trim();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return text.substring(start).trim();
    }
}
