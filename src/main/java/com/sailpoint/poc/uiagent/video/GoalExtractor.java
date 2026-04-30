package com.sailpoint.poc.uiagent.video;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates the {@code ```goal} block from Claude's raw response.
 * Implements the validation checklist from PROMPT.md.
 */
public final class GoalExtractor {

    private static final Pattern GOAL_BLOCK_PATTERN = Pattern.compile(
            "```goal\\s*\\n(.+?)\\n?```",
            Pattern.DOTALL
    );

    private static final Pattern NUMBERED_STEP_PATTERN = Pattern.compile("\\b\\d+\\.\\s");
    private static final Pattern URL_PATTERN = Pattern.compile("--url=(https?://[^\\s']+)");
    private static final Pattern GOAL_PATTERN = Pattern.compile("--goal=(.+)'\\s*$");

    private GoalExtractor() {}

    /**
     * Result of parsing and validating a goal response.
     */
    public record ExtractionResult(
            String goalLine,
            String url,
            List<String> steps,
            boolean isValid,
            List<String> issues
    ) {
        public void print() {
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════");
            System.out.println("GENERATED GOAL COMMAND");
            System.out.println("════════════════════════════════════════════════════════════════");

            if (goalLine != null && !goalLine.isBlank()) {
                System.out.println(goalLine);
            } else {
                System.out.println("(no goal line extracted)");
            }

            System.out.println("════════════════════════════════════════════════════════════════");

            if (url != null) {
                System.out.println("URL: " + url);
            }

            System.out.println("Steps detected: " + steps.size());

            if (!steps.isEmpty()) {
                System.out.println();
                System.out.println("Parsed steps:");
                for (int i = 0; i < steps.size(); i++) {
                    System.out.printf("  %d. %s%n", i + 1, steps.get(i).trim());
                }
            }

            System.out.println();
            System.out.println("Validation: " + (isValid ? "PASSED" : "FAILED"));

            if (!issues.isEmpty()) {
                System.out.println();
                System.out.println("Issues:");
                for (String issue : issues) {
                    System.out.println("  - " + issue);
                }
            }

            System.out.println("════════════════════════════════════════════════════════════════");
        }
    }

    /**
     * Extracts and validates the goal command from Claude's raw response.
     *
     * @param rawResponse the raw text response from Claude
     * @return extraction result with validation status
     */
    public static ExtractionResult extract(String rawResponse) {
        List<String> issues = new ArrayList<>();
        String goalLine = null;
        String url = null;
        List<String> steps = new ArrayList<>();

        if (rawResponse == null || rawResponse.isBlank()) {
            issues.add("Response is empty");
            return new ExtractionResult(null, null, steps, false, issues);
        }

        Matcher blockMatcher = GOAL_BLOCK_PATTERN.matcher(rawResponse);
        if (!blockMatcher.find()) {
            issues.add("No ```goal block found in response");
            return new ExtractionResult(null, null, steps, false, issues);
        }

        goalLine = blockMatcher.group(1).trim();

        if (!goalLine.startsWith("./gradlew run --args='")) {
            issues.add("Line does not start with ./gradlew run --args='");
        }

        if (!goalLine.endsWith("'")) {
            issues.add("Line does not end with '");
        }

        Matcher urlMatcher = URL_PATTERN.matcher(goalLine);
        if (urlMatcher.find()) {
            url = urlMatcher.group(1);
        } else {
            issues.add("Missing --url= with http:// or https://");
        }

        if (!goalLine.contains("--goal=")) {
            issues.add("Missing --goal= parameter");
        }

        int argsStart = goalLine.indexOf("--args='");
        int argsEnd = goalLine.lastIndexOf("'");
        if (argsStart >= 0 && argsEnd > argsStart) {
            String argsContent = goalLine.substring(argsStart + 8, argsEnd);
            if (argsContent.contains("\n")) {
                issues.add("Newlines found inside the quoted --args value");
            }
        }

        if (NUMBERED_STEP_PATTERN.matcher(goalLine).find()) {
            issues.add("Numbered steps found (patterns like \"1.\", \"2.\")");
        }

        if (!goalLine.contains("do not perform any further actions")) {
            issues.add("Missing halt clause: \"do not perform any further actions\"");
        }

        if (!goalLine.contains(", then ")) {
            issues.add("Steps should be joined by \", then \" separator");
        }

        Matcher goalMatcher = GOAL_PATTERN.matcher(goalLine);
        if (goalMatcher.find()) {
            String goalContent = goalMatcher.group(1);
            String[] rawSteps = goalContent.split(", then ");
            for (String step : rawSteps) {
                String trimmed = step.trim();
                if (!trimmed.isEmpty()) {
                    steps.add(trimmed);
                }
            }
        }

        boolean isValid = issues.isEmpty();
        return new ExtractionResult(goalLine, url, steps, isValid, issues);
    }
}
