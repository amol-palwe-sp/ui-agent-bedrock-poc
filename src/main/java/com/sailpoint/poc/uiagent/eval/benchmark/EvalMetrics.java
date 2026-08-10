package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.eval.shared.StepSimilarity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure static metric calculations for eval scoring.
 *
 * <p>No I/O or LLM calls — fully unit-testable.
 * All fuzzy comparisons use a Jaccard threshold of 0.6.
 */
public final class EvalMetrics {

    private static final double FUZZY_THRESHOLD = 0.6;

    /**
     * Weights for the composite trend score.
     *
     * <p>Label accuracy is deliberately absent. Word-overlap on labels penalises harmless
     * wording differences — "click the Save button" vs "click Save button" scores 0.75 purely
     * for the dropped article — so it is reported as a diagnostic only and feeds no score.
     * Judging whether a label is <em>meaningfully</em> wrong is the LLM judge's job.
     *
     * <p>Weights are renormalised over whichever metrics apply to the case, so they always
     * sum to 1.0 regardless of which checks were skipped.
     */
    private static final double W_STEP_RECALL     = 0.35;
    private static final double W_STEP_PRECISION  = 0.25;
    private static final double W_STEP_ORDER      = 0.20;
    private static final double W_PLACEHOLDER     = 0.10;
    private static final double W_PAGINATION      = 0.10;

    /**
     * Reference lines for the word-overlap diagnostics. <strong>These do not gate anything.</strong>
     *
     * <p>They used to be pass thresholds. They aren't any more, because token overlap cannot
     * tell two different actions apart: {@code "click the cancel button"} and
     * {@code "click the accept button"} share three of four tokens and score 0.75, comfortably
     * above any threshold loose enough to tolerate harmless rewording. A metric that matches
     * the wrong button is not a safety check. Semantic comparison is the LLM judge's job, and
     * the judge is now the only thing that decides a case — see
     * {@link com.sailpoint.poc.uiagent.eval.shared.LlmJudge#MIN_OVERALL}.
     *
     * <p>These values survive only as the line the report draws when flagging a case worth
     * eyeballing, and as a way to compare one run against the next.
     */
    public static final double DIAG_STEP_RECALL_FLOOR    = 0.70;
    public static final double DIAG_STEP_PRECISION_FLOOR = 0.70;
    public static final double DIAG_STEP_ORDER_FLOOR     = 0.80;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}");

    /** Leading action verb of a step, stripped when isolating a step's target for comparison. */
    private static final Pattern LEADING_VERB =
            Pattern.compile("^\\s*(click|enter|type|select|fill|choose|press|tap|check|toggle)\\b",
                    Pattern.CASE_INSENSITIVE);
    /** Connective / affordance words that carry no target identity. */
    private static final Pattern TARGET_NOISE =
            Pattern.compile("(?i)\\b(in|into|on|to|from|the|a|an|field|button|input|box|area|value|dropdown|menu|option)\\b");
    /** A focus-click collapses into the following entry step when their targets are at least this similar. */
    private static final double FOCUS_CLICK_TARGET_THRESHOLD = 0.3;

    /**
     * Matches values directly entered or selected by the agent:
     *   enter "VALUE" ...   or   select "VALUE" ...
     * Anchoring to action verbs avoids false positives from inter-token text.
     */
    private static final Pattern ENTERED_VALUE_PATTERN =
            Pattern.compile("(?:enter|select|type|fill)\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private EvalMetrics() {}

    // ── Step normalization ────────────────────────────────────────────────────

    /**
     * Collapses a focus-click that immediately precedes typing into the same field. Browser
     * recordings routinely capture a click to focus a field followed by the actual text entry;
     * ground-truth plans treat that pair as a single "enter" step. Left unnormalized, the
     * focus-click is counted as a hallucinated step and tanks precision.
     *
     * <p>A leading {@code click} step is dropped only when the very next step is an
     * enter/type/select/fill action whose <em>target</em> is similar to the click's target
     * (see {@link #FOCUS_CLICK_TARGET_THRESHOLD}). Genuine navigation clicks — e.g.
     * {@code "click Add"} then {@code "enter ... in Name"} — have dissimilar targets and are
     * preserved.
     *
     * @return a new list with focus-clicks removed (never mutates the input)
     */
    public static List<String> collapseFocusClicks(List<String> steps) {
        List<String> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (int i = 0; i < steps.size(); i++) {
            String cur = steps.get(i);
            boolean focusClick = i + 1 < steps.size()
                    && isClick(cur)
                    && isEntry(steps.get(i + 1))
                    && StepSimilarity.similarity(targetOf(cur), targetOf(steps.get(i + 1)))
                            >= FOCUS_CLICK_TARGET_THRESHOLD;
            if (!focusClick) {
                out.add(cur);
            }
        }
        return out;
    }

    private static boolean isClick(String s) {
        return s != null && s.trim().toLowerCase().startsWith("click");
    }

    private static boolean isEntry(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return t.startsWith("enter") || t.startsWith("type") || t.startsWith("select") || t.startsWith("fill");
    }

    /** Isolates a step's target by stripping the leading verb, quoted values, and connective words. */
    private static String targetOf(String step) {
        String s = LEADING_VERB.matcher(nullToEmpty(step)).replaceFirst(" ");
        s = StepSimilarity.maskValues(s);
        s = TARGET_NOISE.matcher(s).replaceAll(" ");
        return s;
    }

    // ── Core metric computations ──────────────────────────────────────────────

    /**
     * Computes the fraction of GT steps found in the generated output.
     * A GT step is "found" if any generated step scores {@code >= 0.6} Jaccard.
     */
    public static double computeStepRecall(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;
        long matched = gtSteps.stream()
                .filter(gt -> StepSimilarity.bestMatch(gt, generatedSteps) >= FUZZY_THRESHOLD)
                .count();
        return (double) matched / gtSteps.size();
    }

    /**
     * Computes the fraction of generated steps matching any GT step.
     * Low precision indicates hallucinated steps.
     */
    public static double computeStepPrecision(List<String> gtSteps, List<String> generatedSteps) {
        if (generatedSteps == null || generatedSteps.isEmpty()) return 1.0;
        if (gtSteps == null || gtSteps.isEmpty()) return 0.0;
        long matched = generatedSteps.stream()
                .filter(gen -> StepSimilarity.bestMatch(gen, gtSteps) >= FUZZY_THRESHOLD)
                .count();
        return (double) matched / generatedSteps.size();
    }

    /**
     * Computes a normalized Kendall Tau order score for matched step pairs.
     *
     * @return score 0.0–1.0; 1.0 = perfect order
     */
    public static double computeStepOrderScore(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;

        // Build list of GT indices in the order they appear in generatedSteps
        List<Integer> gtIndexSequence = new ArrayList<>();
        for (String gen : generatedSteps) {
            int bestGtIdx = -1;
            double bestScore = FUZZY_THRESHOLD - 0.001;
            for (int i = 0; i < gtSteps.size(); i++) {
                double score = StepSimilarity.similarity(gen, gtSteps.get(i));
                if (score > bestScore) {
                    bestScore = score;
                    bestGtIdx = i;
                }
            }
            if (bestGtIdx >= 0) {
                gtIndexSequence.add(bestGtIdx);
            }
        }

        if (gtIndexSequence.size() < 2) return 1.0;

        // Count concordant vs discordant pairs (Kendall Tau)
        int concordant = 0;
        int discordant = 0;
        for (int i = 0; i < gtIndexSequence.size() - 1; i++) {
            for (int j = i + 1; j < gtIndexSequence.size(); j++) {
                int diff = gtIndexSequence.get(i) - gtIndexSequence.get(j);
                if (diff < 0) concordant++;
                else if (diff > 0) discordant++;
            }
        }

        int total = concordant + discordant;
        if (total == 0) return 1.0;
        double tau = (double) (concordant - discordant) / total;
        return (tau + 1.0) / 2.0;
    }

    /**
     * Computes average similarity score between matched GT/generated step pairs.
     */
    public static double computeLabelAccuracy(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;
        double total = 0.0;
        int matched = 0;
        for (String gt : gtSteps) {
            double best = StepSimilarity.bestMatch(gt, generatedSteps);
            if (best > 0.0) {
                total += best;
                matched++;
            }
        }
        return matched == 0 ? 0.0 : total / matched;
    }

    /**
     * Checks that every declared variable appears as a placeholder in the generated output.
     *
     * <p>This is a <em>placeholder-presence</em> check, not a secret-value scan: we do not
     * inspect what any literal value is. We only verify that each variable defined in the
     * ground truth ({@code tokens}) was emitted as a {@code {Name}} placeholder somewhere in
     * the generated goal or steps — rather than being dropped or written out literally.
     *
     * <p>Only meaningful for PLACEHOLDER mode with declared variables; returns {@code 1.0}
     * (nothing to check) for LITERAL mode or when there are no tokens.
     *
     * @param navigationGoal      the generated navigation goal string (raw model output)
     * @param generatedSteps      the generated step list
     * @param mode                "PLACEHOLDER" or "LITERAL"
     * @param tokens              ground-truth variable definitions
     * @param missingPlaceholders mutable list populated with variables NOT found as placeholders (may be null)
     * @return fraction of variables present as placeholders (1.0 if none to check)
     */
    public static double computePlaceholderScore(
            String navigationGoal, List<String> generatedSteps, String mode,
            List<EvalCase.TokenDef> tokens, List<String> missingPlaceholders) {
        if (!"PLACEHOLDER".equalsIgnoreCase(mode)) return 1.0;
        if (tokens == null || tokens.isEmpty()) return 1.0;

        StringBuilder sb = new StringBuilder(nullToEmpty(navigationGoal));
        if (generatedSteps != null) {
            for (String s : generatedSteps) sb.append(' ').append(nullToEmpty(s));
        }
        String haystack = sb.toString().toLowerCase();

        int total = 0;
        int present = 0;
        for (EvalCase.TokenDef t : tokens) {
            String name = t.name() == null ? "" : t.name().trim();
            if (name.isEmpty()) continue;
            total++;
            if (haystack.contains(("{" + name + "}").toLowerCase())) {
                present++;
            } else if (missingPlaceholders != null) {
                missingPlaceholders.add("{" + name + "}");
            }
        }
        return total == 0 ? 1.0 : (double) present / total;
    }

    /**
     * Scores pagination pattern accuracy for AGGREGATION tasks.
     * Returns 0.0 for PROVISIONING tasks.
     */
    public static double computePaginationScore(
            EvalCase.PaginationGT gt, PaginationPattern generated, String taskType) {
        if ("PROVISIONING".equalsIgnoreCase(taskType)) return 0.0;
        if (gt == null || generated == null) return 0.0;

        double typeScore = gt.type() != null && gt.type().equalsIgnoreCase(generated.type()) ? 1.0 : 0.0;
        double selectorScore = StepSimilarity.similarity(
                nullToEmpty(gt.selectorHint()), nullToEmpty(generated.selectorHint()));
        double descScore = StepSimilarity.similarity(
                nullToEmpty(gt.description()), nullToEmpty(generated.description()));

        return (typeScore * 0.5) + (selectorScore * 0.3) + (descScore * 0.2);
    }

    /**
     * Computes the composite word-overlap score across whichever metrics apply.
     *
     * <p>A movement indicator for comparing one run against the next, never a verdict. The
     * Stage 1 benchmark no longer computes it at all — the LLM judge decides those cases on
     * its own — so this survives for the Stage 2 aggregation evaluator, which reports a
     * script-generation score alongside a live execution result.
     *
     * <p>Checks that do not apply are passed as {@code null}; the remaining weights are
     * renormalised so a skipped check neither helps nor hurts. Scoring a non-applicable
     * check as 0.0 was the old behaviour and it silently depressed every provisioning case.
     */
    public static double computeOverallScore(double stepRecall, double stepPrecision,
                                             double stepOrderScore,
                                             Double placeholderScore, Double paginationScore) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        weightedSum += stepRecall     * W_STEP_RECALL;
        totalWeight += W_STEP_RECALL;
        weightedSum += stepPrecision  * W_STEP_PRECISION;
        totalWeight += W_STEP_PRECISION;
        weightedSum += stepOrderScore * W_STEP_ORDER;
        totalWeight += W_STEP_ORDER;

        if (placeholderScore != null) {
            weightedSum += placeholderScore * W_PLACEHOLDER;
            totalWeight += W_PLACEHOLDER;
        }
        if (paginationScore != null) {
            weightedSum += paginationScore * W_PAGINATION;
            totalWeight += W_PAGINATION;
        }

        return totalWeight == 0.0 ? 0.0 : weightedSum / totalWeight;
    }

    // ── Detection helpers ─────────────────────────────────────────────────────

    // The step-level detectors that used to live here are gone. Deciding whether a generated
    // step is missing or invented is a judgement about meaning, and token overlap cannot make
    // it: it matched "click cancel" to "click accept". The judge now names both lists itself,
    // quoting the actions it compared, which is evidence a reviewer can check.

    /**
     * Detects credential leaks in a navigation goal string produced by Claude in
     * AGGREGATION + PLACEHOLDER mode (the only context where this check is meaningful).
     *
     * <p>Only inspects values that are directly entered or selected by the agent,
     * e.g. {@code enter "value" in ...} or {@code select "value" from ...}.
     * This avoids false positives from matching the navigation text that sits
     * <em>between</em> quoted values.
     *
     * <p>A value is considered a leak when it:
     * <ul>
     *   <li>matches an email pattern (contains {@code @}), or</li>
     *   <li>is not wrapped in {@code {braces}} (i.e. not a proper placeholder token)</li>
     * </ul>
     */
    public static List<String> detectCredentialLeaks(String navigationGoal) {
        List<String> leaks = new ArrayList<>();
        if (navigationGoal == null || navigationGoal.isBlank()) return leaks;

        Matcher actionMatcher = ENTERED_VALUE_PATTERN.matcher(navigationGoal);
        while (actionMatcher.find()) {
            String val = actionMatcher.group(1).trim();
            if (val.isEmpty()) continue;

            boolean isProperToken = val.startsWith("{") && val.endsWith("}");
            if (isProperToken) continue;

            // Any non-token entered/selected value is a potential credential leak
            if (!leaks.contains(val)) {
                leaks.add(val);
            }
        }

        return leaks;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
