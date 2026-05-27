package com.sailpoint.poc.uiagent.replay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fuzzy fingerprint matching during replay (REQ-RR-4.3 level 4).
 */
public final class FingerprintMatcher {

    private static final double FUZZY_THRESHOLD = 0.75;

    private FingerprintMatcher() {}

    public static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() && tb.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    /**
     * Finds element in scraped list with best fingerprint similarity.
     *
     * @return stable id or null
     */
    public static MatchResult findByFuzzy(JSONArray elements, String targetFingerprint) {
        double best = 0.0;
        String bestId = null;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            String fp = el.optString("fingerprintString", "");
            double sim = jaccardSimilarity(targetFingerprint, fp);
            if (sim > best) {
                best = sim;
                bestId = el.optString("id", null);
            }
        }
        if (best >= FUZZY_THRESHOLD && bestId != null) {
            return new MatchResult(bestId, best, elements);
        }
        return null;
    }

    public static JSONObject findElementByStableId(JSONArray elements, String stableId) {
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            if (stableId.equals(el.optString("id"))) {
                return el;
            }
        }
        return null;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String part : s.toLowerCase(Locale.ROOT).split("[:\\s>\\[\\]=]+")) {
            if (part.length() > 1) {
                out.add(part);
            }
        }
        return out;
    }

    public record MatchResult(String stableId, double similarity, JSONArray elements) {
        public JSONObject element() {
            return findElementByStableId(elements, stableId);
        }
    }
}
