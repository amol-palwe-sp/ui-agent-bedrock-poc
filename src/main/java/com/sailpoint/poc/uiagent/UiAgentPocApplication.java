package com.sailpoint.poc.uiagent;

import com.sailpoint.poc.uiagent.pipeline.AgentPipeline;
import com.sailpoint.poc.uiagent.pipeline.PipelineConfig;
import com.sailpoint.poc.uiagent.pipeline.PipelineMode;
import com.sailpoint.poc.uiagent.pipeline.PipelineResult;
import com.sailpoint.poc.uiagent.pipeline.ProgressListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for the UI Agent POC.
 *
 * <pre>
 *   ./gradlew run --args='--url=https://example.com --goal=Follow the first obvious link you see'
 *   ./gradlew run --args='--url=https://example.com --goal=enter "a@b.com" in email then submit'
 *   (Words after --goal= are merged until the next token starting with --, so quoted args split by Gradle still work.)
 * </pre>
 */
public final class UiAgentPocApplication {

    public static void main(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args);
        if (parsed == null) {
            printUsage();
            System.exit(1);
            return;
        }
        if (parsed.mode != PipelineMode.LIST
                && !parsed.startUrl.startsWith("http://") && !parsed.startUrl.startsWith("https://")) {
            System.err.println("ERROR: --url must start with http:// or https:// (got: " + parsed.startUrl + ")");
            System.exit(1);
            return;
        }

        if (parsed.mode != PipelineMode.LIST && parsed.mode != PipelineMode.REPLAY) {
            System.out.println("Goal (merged): " + parsed.goal);
        }
        System.out.println("Mode: " + parsed.mode);

        PocConfig config  = new PocConfig();
        String    modelId = config.bedrockModelId();
        String    envModel = config.bedrockModelIdEnvRaw();
        String    source  = envModel != null && !envModel.isBlank()
                ? "env BEDROCK_MODEL_ID" : "application.properties";
        System.out.println("Bedrock model id: " + modelId + " (from " + source + ")");

        if (BedrockModelHints.likelyRequiresInferenceProfileArn(modelId)) {
            System.err.println(
                    """
                    ERROR: This model id is not usable as a bare on-demand InvokeModel target. Bedrock expects an inference profile ARN (or profile id) for many Claude 4.x models.

                    Fix one of:
                      1) Set bedrock.model.id (or env BEDROCK_MODEL_ID) to an inference profile ARN from AWS console → Bedrock → Inference profiles.
                      2) Or use an on-demand model your account still allows, e.g. anthropic.claude-3-5-sonnet-20241022-v2:0

                    If BEDROCK_MODEL_ID is set in your shell, unset it:  unset BEDROCK_MODEL_ID
                    """);
            System.exit(2);
            return;
        }

        // Build pipeline config — single source of truth for all resource setup (REQ-3)
        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .mode(parsed.mode)
                .taskType(PipelineConfig.TaskType.PROVISIONING)
                .startUrl(parsed.startUrl)
                .goal(parsed.goal)
                .scriptPath(parsed.scriptPath)
                .scriptName(parsed.scriptName)
                .saveScriptTo(config.scriptOutputDir())
                .tokenValues(parsed.tokenValues)
                .bedrockConfig(config.bedrock())
                .browserConfig(config.browser())
                .agentConfig(config.agent())
                .build();

        // AgentPipeline owns BedrockClient + BrowserSession + ActionLogger lifecycle (REQ-3.4)
        PipelineResult result = AgentPipeline.run(pipelineConfig, ProgressListener.SILENT);

        if (!result.success()) {
            System.err.println("Pipeline ended: " + result.exitReason()
                    + (result.errorMessage().isBlank() ? "" : " — " + result.errorMessage()));
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println(
                """
                UI Agent POC (Bedrock + Playwright)

                Required (GENERATE / RECORD):
                  --url=<https://...>     Starting page (or: --url https://...)
                  --goal=...              Goal text; tokens after --goal= merge until next --flag

                Modes (--mode=):
                  GENERATE  Default — AgentLoop only (same as before)
                  RECORD    AgentLoop + save script JSON on completion
                  REPLAY    --script=<path.json> [--token=Name:value ...]
                  LIST      List scripts in script.output.dir

                Examples:
                  --mode=RECORD --url=... --goal=...
                  --mode=REPLAY --script=./output/scripts/foo.json --token=Email:user@corp.com
                  --mode=LIST

                Configuration: src/main/resources/application.properties
                  (see application.properties.example)
                  Env BEDROCK_MODEL_ID overrides bedrock.model.id if set.

                AWS credentials: default profile or aws.profile in properties.
                If using SSO: run 'aws sso login' first.

                First run: Playwright downloads Chromium automatically.
                """);
    }

    private record ParsedArgs(
            PipelineMode mode,
            String startUrl,
            String goal,
            String scriptPath,
            String scriptName,
            Map<String, String> tokenValues) {

        static ParsedArgs parse(String[] args) {
            String url  = "https://example.com";
            String goal = "";
            PipelineMode mode = PipelineMode.GENERATE;
            String scriptPath = "";
            String scriptName = "";
            Map<String, String> tokens = new HashMap<>();

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--mode=")) {
                    mode = PipelineMode.valueOf(a.substring("--mode=".length()).trim().toUpperCase());
                } else if ("--mode".equals(a) && i + 1 < args.length) {
                    mode = PipelineMode.valueOf(args[++i].trim().toUpperCase());
                } else if (a.startsWith("--script=")) {
                    scriptPath = a.substring("--script=".length()).trim();
                } else if ("--script".equals(a) && i + 1 < args.length) {
                    scriptPath = args[++i].trim();
                } else if (a.startsWith("--script-name=")) {
                    scriptName = a.substring("--script-name=".length()).trim();
                } else if (a.startsWith("--token=")) {
                    String pair = a.substring("--token=".length());
                    int colon = pair.indexOf(':');
                    if (colon > 0) {
                        tokens.put(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
                    }
                }
                if (a.startsWith("--url=")) {
                    url = a.substring("--url=".length()).trim();
                } else if ("--url".equals(a) && i + 1 < args.length && !isOptionPrefix(args[i + 1])) {
                    i++;
                    url = args[i].trim();
                } else if (a.startsWith("--goal=")) {
                    StringBuilder g = new StringBuilder(a.substring("--goal=".length()).trim());
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                } else if ("--goal".equals(a)) {
                    if (i + 1 >= args.length || isOptionPrefix(args[i + 1])) continue;
                    StringBuilder g = new StringBuilder();
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                }
            }
            if (url == null || url.isBlank() || goal == null || goal.isBlank()) return null;
            return new ParsedArgs(mode, url, goal, scriptPath, scriptName, tokens);
        }

        private static int appendMergedWords(String[] args, int goalArgIndex, StringBuilder out) {
            int j = goalArgIndex + 1;
            while (j < args.length && !isOptionPrefix(args[j])) {
                if (!out.isEmpty()) out.append(' ');
                out.append(args[j]);
                j++;
            }
            return j - 1;
        }

        private static boolean isOptionPrefix(String token) {
            return token != null && token.startsWith("--");
        }
    }
}
