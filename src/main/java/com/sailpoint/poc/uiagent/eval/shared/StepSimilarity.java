package com.sailpoint.poc.uiagent.eval.shared;

import com.sailpoint.poc.uiagent.replay.FingerprintMatcher;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fuzzy string similarity for step matching.
 *
 * <p>Delegates to {@link FingerprintMatcher#jaccardSimilarity} for token-based
 * Jaccard comparison. Shared by {@link EvalMetrics} and {@link LlmJudge}.
 *
 * <p>Comparison is <em>structural</em>: an entered value (e.g. {@code "jdoe"}) is masked to a
 * single sentinel token before scoring, so a step matches on its action + target regardless of
 * the specific value entered. This prevents a long or legitimately-different entered value from
 * diluting the token overlap and sinking an otherwise-correct match. Value correctness, where it
 * matters, is judged separately (LLM judge / placeholder gate) rather than through this fuzzy match.
 *
 * <p>Quotes carry two different meanings in a step, and they must be handled differently:
 * after a value verb ({@code enter "Ada" in the Name field}) the quoted run is a value and is
 * masked; after any other verb ({@code click "Enterprise" option}) the quoted run is the target's
 * label and is the step's identity — masking it would make that step match every other option
 * click. Quote characters are not tokenizer delimiters, so they are stripped rather than left in
 * place: otherwise {@code "Enterprise"} and {@code Enterprise} tokenize differently and a step
 * fails to match itself purely because one side quoted the label and the other did not.
 */
public final class StepSimilarity {

    private static final Pattern QUOTED_VALUE = Pattern.compile("\"[^\"]*\"");
    /** Sentinel that survives the Jaccard tokenizer (length > 1, no delimiter chars). */
    private static final String VALUE_SENTINEL = " valuetoken ";

    /**
     * Verbs whose quoted argument is a value the user supplied, not the label of a target.
     * Navigation verbs are deliberately absent: a URL is the step's identity, and the tokenizer
     * does not split on {@code /}, so masking a quoted URL leaves nothing comparable against an
     * unquoted one.
     */
    private static final Pattern VALUE_VERB = Pattern.compile(
            "^[\\s\\p{Punct}]*(enter|type|input|fill|set|select|choose|search\\s+for)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTE_CHARS = Pattern.compile("[\"'`\u2018\u2019\u201c\u201d]");

    private StepSimilarity() {}

    /**
     * Returns a Jaccard similarity score between two step strings, normalised so the comparison
     * reflects structure (action + target label) rather than the specific value entered.
     *
     * @param a first step string
     * @param b second step string
     * @return score 0.0 (no overlap) to 1.0 (identical tokens)
     */
    public static double similarity(String a, String b) {
        return FingerprintMatcher.jaccardSimilarity(normalizeForCompare(a), normalizeForCompare(b));
    }

    /**
     * Masks a quoted value when the step's verb takes one, and otherwise preserves the quoted run
     * as label text. Quote characters are removed either way so that quoting style alone cannot
     * change the token set.
     */
    static String normalizeForCompare(String s) {
        if (s == null) {
            return "";
        }
        return stripQuotes(VALUE_VERB.matcher(s).find() ? maskValues(s) : s);
    }

    private static String stripQuotes(String s) {
        return QUOTE_CHARS.matcher(s).replaceAll("");
    }

    /**
     * Replaces every quoted run with a single sentinel token, unconditionally. Callers that have
     * already stripped the leading verb (and so cannot tell a value from a label) use this to
     * reduce a step to its bare target; {@link #similarity} does not, since it can still see the
     * verb and must keep label text intact.
     */
    public static String maskValues(String s) {
        if (s == null) {
            return "";
        }
        return QUOTED_VALUE.matcher(s).replaceAll(VALUE_SENTINEL);
    }

    /**
     * Returns the best similarity score between {@code target} and any candidate.
     *
     * @param target     the step to match
     * @param candidates list of candidate steps
     * @return highest similarity score found, or 0.0 if candidates is empty
     */
    public static double bestMatch(String target, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return 0.0;
        double best = 0.0;
        for (String candidate : candidates) {
            double score = similarity(target, candidate);
            if (score > best) best = score;
        }
        return best;
    }
}
