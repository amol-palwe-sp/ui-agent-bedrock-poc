package com.sailpoint.poc.uiagent.replay;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token substitution for replay TYPE steps (REQ-RR-2.3 / REQ-RR-4.5).
 */
public final class TokenValues {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private final Map<String, String> values;

    public TokenValues(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static TokenValues fromMap(Map<String, String> map) {
        return new TokenValues(map != null ? map : Map.of());
    }

    public String get(String tokenName) {
        return values.get(tokenName);
    }

    public Map<String, String> asMap() {
        return values;
    }

    public void validateRequired(List<String> required) throws MissingTokenException {
        for (String name : required) {
            String v = values.get(name);
            if (v == null || v.isBlank()) {
                throw new MissingTokenException(name);
            }
        }
    }

    public String substitute(String text) throws MissingTokenException {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher m = TOKEN_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new MissingTokenException(name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static List<String> tokensIn(String text) {
        if (text == null) {
            return List.of();
        }
        return TOKEN_PATTERN.matcher(text).results()
                .map(r -> r.group(1))
                .distinct()
                .toList();
    }

    public static class MissingTokenException extends Exception {
        private final String tokenName;

        public MissingTokenException(String tokenName) {
            super("Missing token value for {" + tokenName + "}");
            this.tokenName = tokenName;
        }

        public String tokenName() {
            return tokenName;
        }
    }
}
