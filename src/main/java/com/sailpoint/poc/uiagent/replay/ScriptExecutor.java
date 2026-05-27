package com.sailpoint.poc.uiagent.replay;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.config.ReplayConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a recorded {@link Script} with multi-level element fallback (REQ-RR-4, REQ-SIV).
 */
public final class ScriptExecutor {

    private static final int MISS_WARN_THRESHOLD = 3;
    private static final double HIGH_MISS_RATE = 0.30;

    private static final String SCROLL_INTO_VIEW_JS = """
            (selector) => {
              const el = document.querySelector(selector);
              if (!el) return JSON.stringify({ ok: false, notInDom: true });
              const rectBefore = el.getBoundingClientRect();
              const foldPx = Math.max(0, rectBefore.top - window.innerHeight);
              el.scrollIntoView({ behavior: 'instant', block: 'center' });
              return JSON.stringify({
                ok: true,
                topBefore: rectBefore.top,
                foldPx: foldPx,
                viewportHeight: window.innerHeight
              });
            }
            """;

    private final BrowserSession browser;
    private final BedrockAnthropicClient bedrock;
    private final boolean selfHeal;
    private final ReplayConfig replay;

    public ScriptExecutor(
            BrowserSession browser,
            BedrockAnthropicClient bedrock,
            boolean selfHeal,
            ReplayConfig replay) {
        this.browser = browser;
        this.bedrock = bedrock;
        this.selfHeal = selfHeal;
        this.replay = replay != null ? replay : defaultReplayConfig();
    }

    private static ReplayConfig defaultReplayConfig() {
        return new ReplayConfig(300, 400, 15, 250);
    }

    public ReplayResult replay(Script script, TokenValues tokens) throws Exception {
        validatePreReplay(script, tokens);

        int total = script.steps().size();
        int succeeded = 0;
        int failed = 0;
        int claudeCalls = 0;
        TokenUsage cost = TokenUsage.ZERO;
        ReplayResult.Builder result = ReplayResult.builder().stepsTotal(total);

        System.out.println("Replaying: " + script.scriptName());
        System.out.printf("Steps: %d | Health: %.0f%% | Last run: %s%n",
                total, script.healthScore() * 100, script.lastRunAt());

        browser.navigate(script.startUrl());

        for (ScriptStep step : script.steps()) {
            int n = step.stepIndex() + 1;
            logStepStart(n, total, step);
            validateUrl(step);

            StepOutcome outcome = executeStep(step, tokens);
            if (outcome.usage != null) {
                cost = cost.add(outcome.usage);
            }
            if (outcome.claudeUsed) {
                claudeCalls++;
            }

            if (outcome.success) {
                succeeded++;
                result.addStrategy(outcome.strategy);
                logStepSuccess(step, outcome);
                if (selfHeal && outcome.updatedStep != null) {
                    updateStepInScript(script, outcome.updatedStep);
                }
            } else {
                failed++;
                result.addFailed(step.stepIndex(), outcome.error);
                logStepFailure(outcome);
                result.success(false);
                break;
            }
        }

        script.incrementRunCount();
        script.setSuccessRate((double) succeeded / Math.max(1, total));
        if (selfHeal && script.sourcePath() != null) {
            script.saveInPlace();
        }

        printSummary(script, succeeded, failed, result, claudeCalls, cost);
        checkMissWarnings(script);

        return result
                .stepsSucceeded(succeeded)
                .stepsFailed(failed)
                .claudeCalls(claudeCalls)
                .totalCost(cost)
                .build();
    }

    private StepOutcome executeStep(ScriptStep step, TokenValues tokens) throws Exception {
        String action = step.action().toUpperCase();

        return switch (action) {
            case "GOTO" -> {
                browser.navigate(step.url() != null ? step.url() : step.expectedUrlPattern());
                yield StepOutcome.ok(ReplayStrategy.FINGERPRINT);
            }
            case "CLICK" -> executeElementStep(step, tokens, (id) -> browser.clickByStableId(id));
            case "TYPE" -> {
                String text = tokens.substitute(step.text() != null ? step.text() : "");
                yield executeElementStep(step, tokens,
                        (id) -> browser.typeByStableId(id, text));
            }
            case "SELECT_OPTION" -> executeElementStep(step, tokens,
                    (id) -> browser.selectOptionByStableId(id, "", step.label()));
            case "HOVER" -> executeElementStep(step, tokens, (id) -> browser.hoverByStableId(id));
            case "CHECK" -> executeElementStep(step, tokens,
                    (id) -> browser.checkboxByStableId(id, step.checked() != null && step.checked()));
            case "KEYPRESS" -> {
                JSONObject r = browser.keypress(step.key());
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "keypress failed"));
            }
            case "SCROLL" -> {
                JSONObject r = browser.scroll(
                        step.direction() != null ? step.direction() : "down",
                        step.amount() != null ? step.amount() : 600);
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "scroll failed"));
            }
            case "RELOAD_PAGE" -> {
                JSONObject r = browser.reloadPage();
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "reload failed"));
            }
            default -> StepOutcome.fail("Unsupported action: " + action);
        };
    }

    @FunctionalInterface
    private interface ElementAction {
        JSONObject run(String stableId) throws Exception;
    }

    private StepOutcome executeElementStep(ScriptStep step, TokenValues tokens, ElementAction action)
            throws Exception {
        String stableId = step.stableId();
        if (stableId == null || stableId.isBlank()) {
            return StepOutcome.fail("missing stableId");
        }

        // Level 1 — FINGERPRINT
        if (browser.countByStableId(stableId) > 0) {
            JSONObject r = action.run(stableId);
            if (r.optBoolean("ok")) {
                return StepOutcome.ok(ReplayStrategy.FINGERPRINT);
            }
        }

        // Level 2 — RETAG (viewport only)
        browser.listInteractables(true);
        if (browser.countByStableId(stableId) > 0) {
            JSONObject r = action.run(stableId);
            if (r.optBoolean("ok")) {
                return StepOutcome.ok(ReplayStrategy.RETAG);
            }
        }

        // Level 3 — SCROLL_INTO_VIEW
        StepOutcome scroll = tryScrollIntoView(step, action);
        if (scroll != null) {
            return scroll;
        }

        // Level 4 — PROGRESSIVE_SCROLL
        StepOutcome progressive = tryProgressiveScroll(step, action);
        if (progressive != null) {
            return progressive;
        }

        // Level 5 — FUZZY (full DOM)
        JSONArray elements = browser.listInteractables(false);
        FingerprintMatcher.MatchResult fuzzy =
                FingerprintMatcher.findByFuzzy(elements, step.fingerprintString());
        if (fuzzy != null) {
            JSONObject r = action.run(fuzzy.stableId());
            if (r.optBoolean("ok")) {
                ScriptStep updated = step.withUpdatedFingerprint(
                        fuzzy.stableId(),
                        fuzzy.element().optString("fingerprintString"),
                        fuzzy.element().optInt("fingerprintLevel"),
                        jsonFallbacks(fuzzy.element()),
                        ReplayStrategy.FUZZY);
                return StepOutcome.ok(ReplayStrategy.FUZZY, updated);
            }
        }

        // Level 6 — CLAUDE
        StepOutcome claude = claudeRecover(step, action);
        if (claude.success) {
            return claude;
        }

        return StepOutcome.fail("all replay strategies failed for " + step.elementLabel());
    }

    private StepOutcome tryScrollIntoView(ScriptStep step, ElementAction action) throws Exception {
        String stableId = step.stableId();
        List<String> selectors = querySelectorSafe(step.fallbackSelectors());
        if (selectors.isEmpty()) {
            System.out.println("  → SCROLL_INTO_VIEW: no querySelector-safe fallback selectors");
            return null;
        }

        for (String selector : selectors) {
            String raw = (String) browser.page().evaluate(SCROLL_INTO_VIEW_JS, selector);
            JSONObject info = new JSONObject(raw);

            if (info.optBoolean("notInDom")) {
                System.out.println("  → SCROLL_INTO_VIEW: querySelector returned null");
                System.out.println("  → for selector: " + selector);
                continue;
            }

            int foldPx = info.optInt("foldPx", 0);
            int topBefore = (int) info.optDouble("topBefore", 0);
            sleepMs(replay.scrollSettleMs());

            browser.listInteractables(false);
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    if (foldPx > 0) {
                        System.out.printf(
                                "  → Element was %dpx below viewport fold%n", foldPx);
                    }
                    System.out.println("  → Scrolled to center, retagged, found");
                    return StepOutcome.ok(ReplayStrategy.SCROLL_INTO_VIEW);
                }
            }

            System.out.printf(
                    "  → SCROLL_INTO_VIEW: element in DOM at top=%dpx%n", topBefore);
            System.out.println("  → scrollIntoView executed, retag failed to find");
        }

        System.out.println("  → Falling through to PROGRESSIVE_SCROLL");
        return null;
    }

    private StepOutcome tryProgressiveScroll(ScriptStep step, ElementAction action) throws Exception {
        String stableId = step.stableId();

        browser.page().evaluate("() => window.scrollTo(0, 0)");
        sleepMs(200);

        for (int chunk = 0; chunk < replay.progressiveScrollMaxChunks(); chunk++) {
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    return StepOutcome.ok(ReplayStrategy.PROGRESSIVE_SCROLL);
                }
            }

            browser.listInteractables(false);
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    return StepOutcome.ok(ReplayStrategy.PROGRESSIVE_SCROLL);
                }
            }

            Object atBottom = browser.page().evaluate("""
                    () => {
                      const y = window.scrollY + window.innerHeight;
                      const h = document.documentElement.scrollHeight;
                      return y >= h - 2;
                    }
                    """);
            if (Boolean.TRUE.equals(atBottom)) {
                break;
            }

            browser.page().evaluate(
                    "(px) => window.scrollBy(0, px)", replay.progressiveScrollChunkPx());
            sleepMs(replay.progressiveScrollChunkWaitMs());
        }

        return null;
    }

    private static List<String> querySelectorSafe(List<String> selectors) {
        List<String> out = new ArrayList<>();
        if (selectors == null) {
            return out;
        }
        for (String s : selectors) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (s.contains(":has-text")
                    || s.contains(":text(")
                    || s.contains("nth=")) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private List<String> jsonFallbacks(JSONObject el) {
        List<String> list = new ArrayList<>();
        JSONArray fb = el.optJSONArray("fallbackSelectors");
        if (fb != null) {
            for (int i = 0; i < fb.length(); i++) {
                list.add(fb.optString(i));
            }
        }
        return list;
    }

    private StepOutcome claudeRecover(ScriptStep step, ElementAction action) {
        try {
            JSONArray elements = browser.listInteractables(false);
            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.getJSONObject(i);
                String label = el.optString("elementLabel", el.optString("text", ""));
                if (step.elementLabel() != null && !step.elementLabel().isBlank()
                        && label.toLowerCase().contains(
                        step.elementLabel().toLowerCase().substring(
                                0, Math.min(12, step.elementLabel().length())))) {
                    String id = el.optString("id");
                    JSONObject r = action.run(id);
                    if (r.optBoolean("ok")) {
                        ScriptStep updated = step.withUpdatedFingerprint(
                                id,
                                el.optString("fingerprintString"),
                                el.optInt("fingerprintLevel"),
                                jsonFallbacks(el),
                                ReplayStrategy.CLAUDE);
                        return new StepOutcome(true, ReplayStrategy.CLAUDE, updated, null, true, null);
                    }
                }
            }
        } catch (Exception e) {
            return StepOutcome.fail(e.getMessage());
        }
        return StepOutcome.fail("claude recovery could not locate element");
    }

    private void validatePreReplay(Script script, TokenValues tokens) throws Exception {
        if (script.steps().isEmpty()) {
            throw new IllegalArgumentException("Script has no steps");
        }
        if (!Script.VERSION.equals(script.version())) {
            System.out.println("WARNING: script version " + script.version()
                    + " may not be fully compatible with " + Script.VERSION);
        }
        List<String> required = new ArrayList<>();
        for (ScriptStep step : script.steps()) {
            if (step.text() != null) {
                required.addAll(TokenValues.tokensIn(step.text()));
            }
        }
        tokens.validateRequired(required.stream().distinct().toList());
    }

    private void validateUrl(ScriptStep step) {
        if (step.expectedUrlPattern() == null || step.expectedUrlPattern().isBlank()) {
            return;
        }
        String current = browser.currentUrl();
        boolean ok = step.expectedUrlExact()
                ? current.equals(step.expectedUrlPattern())
                : current.contains(step.expectedUrlPattern());
        if (!ok) {
            System.out.printf("  [Replay] URL mismatch at step %d (current=%s)%n",
                    step.stepIndex(), current);
        }
    }

    private void updateStepInScript(Script script, ScriptStep updated) {
        script.setStep(updated.stepIndex(), updated);
    }

    private static void logStepStart(int n, int total, ScriptStep step) {
        String label = step.elementLabel() != null ? step.elementLabel() : step.action();
        System.out.printf("[Replay] Step %d/%d: %s \"%s\"%n", n, total, step.action(), label);
    }

    private static void logStepSuccess(ScriptStep step, StepOutcome outcome) {
        System.out.printf("  → Strategy: %s ✓%n", outcome.strategy);
    }

    private static void logStepFailure(StepOutcome outcome) {
        System.out.printf("  → FAILED: %s%n", outcome.error);
    }

    private static void printSummary(
            Script script,
            int succeeded,
            int failed,
            ReplayResult.Builder result,
            int claudeCalls,
            TokenUsage cost) {
        int total = succeeded + failed;
        System.out.println("══════════════════════════════════════");
        System.out.println("REPLAY COMPLETE");
        System.out.println("══════════════════════════════════════");
        System.out.println("Script:       " + script.scriptName());
        System.out.printf("Steps:        %d/%d succeeded%n", succeeded, total);
        System.out.println("Claude calls: " + claudeCalls);
        System.out.println("Claude cost:  $" + String.format("%.4f", cost.totalCostUsd()));
        System.out.printf("Script health: %.0f%%%n", script.healthScore() * 100);
        printStrategyBreakdown(result.strategiesUsed(), succeeded);
        System.out.println("══════════════════════════════════════");
    }

    private static void printStrategyBreakdown(Map<ReplayStrategy, Integer> strategies, int succeeded) {
        if (strategies.isEmpty() || succeeded == 0) {
            return;
        }
        System.out.println("Strategies:");
        EnumMap<ReplayStrategy, Integer> ordered = new EnumMap<>(ReplayStrategy.class);
        ordered.putAll(strategies);
        for (ReplayStrategy s : List.of(
                ReplayStrategy.FINGERPRINT,
                ReplayStrategy.RETAG,
                ReplayStrategy.SCROLL_INTO_VIEW,
                ReplayStrategy.PROGRESSIVE_SCROLL,
                ReplayStrategy.FUZZY,
                ReplayStrategy.CLAUDE)) {
            int count = ordered.getOrDefault(s, 0);
            if (count == 0) {
                continue;
            }
            int pct = (int) Math.round(100.0 * count / succeeded);
            System.out.printf("  %-18s %d steps  (%d%%)%n", strategyLabel(s), count, pct);
        }
    }

    private static String strategyLabel(ReplayStrategy s) {
        return switch (s) {
            case FINGERPRINT -> "Fingerprint";
            case RETAG -> "Retag";
            case SCROLL_INTO_VIEW -> "Scroll-Into-View";
            case PROGRESSIVE_SCROLL -> "Progressive";
            case FUZZY -> "Fuzzy";
            case CLAUDE -> "Claude";
            case CSS_FALLBACK -> "CSS-Fallback";
        };
    }

    private static void checkMissWarnings(Script script) {
        for (ScriptStep step : script.steps()) {
            if (step.missCount() >= MISS_WARN_THRESHOLD) {
                System.out.printf(
                        "WARNING: Step %d missed fingerprint %d times — consider re-recording.%n",
                        step.stepIndex(), step.missCount());
            }
        }
        long misses = script.steps().stream().filter(s -> s.missCount() > 0).count();
        if (!script.steps().isEmpty() && (double) misses / script.steps().size() > HIGH_MISS_RATE) {
            System.out.println("WARNING: High miss rate (>30%). Consider re-recording with --mode=RECORD.");
        }
    }

    private static void sleepMs(int ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    private record StepOutcome(
            boolean success,
            ReplayStrategy strategy,
            ScriptStep updatedStep,
            String error,
            boolean claudeUsed,
            TokenUsage usage) {

        static StepOutcome ok(ReplayStrategy s) {
            return new StepOutcome(true, s, null, null, false, null);
        }

        static StepOutcome ok(ReplayStrategy s, ScriptStep updated) {
            return new StepOutcome(true, s, updated, null, false, null);
        }

        static StepOutcome fail(String err) {
            return new StepOutcome(false, null, null, err, false, null);
        }
    }
}
