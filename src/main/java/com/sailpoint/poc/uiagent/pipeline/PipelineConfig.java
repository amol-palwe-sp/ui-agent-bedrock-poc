package com.sailpoint.poc.uiagent.pipeline;

import com.sailpoint.poc.uiagent.aggregation.AggregationMode;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.config.AgentConfig;
import com.sailpoint.poc.uiagent.config.AggregationConfig;
import com.sailpoint.poc.uiagent.config.BedrockConfig;
import com.sailpoint.poc.uiagent.config.BrowserConfig;
import com.sailpoint.poc.uiagent.config.NetworkAggregationConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Complete configuration for a single {@link AgentPipeline} run.
 *
 * <p>Implements REQ-3.3 — replaces the five separate sets of constructor arguments
 * that were copy-pasted across:
 * <ul>
 *   <li>{@code UiAgentPocApplication}</li>
 *   <li>{@code AggregationRunner}</li>
 *   <li>{@code AggregationPlanRunner}</li>
 *   <li>{@code RunHandler}</li>
 *   <li>{@code AggregationRunHandler}</li>
 * </ul>
 *
 * <h2>Usage (provisioning)</h2>
 * <pre>
 * PocConfig config = new PocConfig();
 * PipelineConfig pc = PipelineConfig.builder()
 *         .taskType(PipelineConfig.TaskType.PROVISIONING)
 *         .startUrl("https://example.com")
 *         .goal("click Sign In, then ...")
 *         .bedrockConfig(config.bedrock())
 *         .browserConfig(config.browser())
 *         .agentConfig(config.agent())
 *         .build();
 * PipelineResult result = AgentPipeline.run(pc, ProgressListener.SILENT);
 * </pre>
 *
 * <h2>Usage (aggregation)</h2>
 * <pre>
 * PipelineConfig pc = PipelineConfig.builder()
 *         .taskType(PipelineConfig.TaskType.AGGREGATION)
 *         .startUrl("https://admin.google.com/ac/users")
 *         .goal("enter \"{Email}\" in Email, then click Next, then ...")
 *         .tokenValues(Map.of("Email", "user@corp.com", "Password", "s3cr3t"))
 *         .paginationPattern(plan.paginationPattern())
 *         .bedrockConfig(config.bedrock())
 *         .browserConfig(config.browser())
 *         .agentConfig(config.agent())
 *         .aggregationConfig(config.aggregation())
 *         .build();
 * </pre>
 */
public final class PipelineConfig {

    /** What the pipeline does after navigating to the start URL. */
    public enum TaskType {
        /** Navigate + AgentLoop only. No table detection or CSV output. */
        PROVISIONING,
        /** Navigate + AgentLoop + detectTable + paginationLoop + writeCsv. */
        AGGREGATION
    }

    private final TaskType                 taskType;
    private final String                   startUrl;
    private final String                   goal;
    private final Map<String,String>       tokenValues;
    private final PaginationPattern        paginationPattern;
    private final AgentConfig             agentConfig;
    private final BrowserConfig           browserConfig;
    private final BedrockConfig           bedrockConfig;
    private final AggregationConfig       aggregationConfig;
    private final PipelineMode            mode;
    private final String                  scriptPath;
    private final String                  scriptName;
    private final String                  saveScriptTo;
    /** Aggregation strategy — defaults to {@link AggregationMode#LLM_DOM} (REQ-NA-2). */
    private final AggregationMode         aggregationMode;
    /** Config for NETWORK mode; ignored when mode is LLM_DOM (REQ-NA-40). */
    private final NetworkAggregationConfig networkAggConfig;

    private PipelineConfig(Builder b) {
        this.taskType          = b.taskType;
        this.startUrl          = b.startUrl;
        this.goal              = b.goal;
        this.tokenValues       = Collections.unmodifiableMap(new HashMap<>(b.tokenValues));
        this.paginationPattern = b.paginationPattern;
        this.agentConfig       = b.agentConfig;
        this.browserConfig     = b.browserConfig;
        this.bedrockConfig     = b.bedrockConfig;
        this.aggregationConfig = b.aggregationConfig;
        this.mode              = b.mode;
        this.scriptPath        = b.scriptPath;
        this.scriptName        = b.scriptName;
        this.saveScriptTo      = b.saveScriptTo;
        this.aggregationMode   = b.aggregationMode;
        this.networkAggConfig  = b.networkAggConfig;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public TaskType                taskType()           { return taskType; }
    public String                  startUrl()           { return startUrl; }
    public String                  goal()               { return goal; }
    public Map<String,String>      tokenValues()        { return tokenValues; }
    public PaginationPattern       paginationPattern()  { return paginationPattern; }
    public AgentConfig             agentConfig()        { return agentConfig; }
    public BrowserConfig           browserConfig()      { return browserConfig; }
    public BedrockConfig           bedrockConfig()      { return bedrockConfig; }
    public AggregationConfig       aggregationConfig()  { return aggregationConfig; }
    public PipelineMode            mode()               { return mode; }
    public String                  scriptPath()         { return scriptPath; }
    public String                  scriptName()         { return scriptName; }
    public String                  saveScriptTo()       { return saveScriptTo; }
    public AggregationMode         aggregationMode()    { return aggregationMode; }
    public NetworkAggregationConfig networkAggConfig()  { return networkAggConfig; }

    public boolean isAggregation()  { return taskType == TaskType.AGGREGATION; }
    public boolean isRecord()       { return mode == PipelineMode.RECORD; }
    public boolean isReplay()       { return mode == PipelineMode.REPLAY; }
    public boolean isNetworkMode()  { return aggregationMode == AggregationMode.NETWORK; }

    /**
     * Returns {@link #goal()} with all {@code {Token}} placeholders replaced by
     * their values from {@link #tokenValues()}.
     */
    public String resolvedGoal() {
        String resolved = goal;
        for (Map.Entry<String,String> e : tokenValues.entrySet()) {
            resolved = resolved.replace("{" + e.getKey() + "}", e.getValue());
        }
        return resolved;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private TaskType                 taskType          = TaskType.PROVISIONING;
        private String                   startUrl          = "";
        private String                   goal              = "";
        private Map<String,String>       tokenValues       = Collections.emptyMap();
        private PaginationPattern        paginationPattern = null;
        private AgentConfig             agentConfig       = null;
        private BrowserConfig           browserConfig     = null;
        private BedrockConfig           bedrockConfig     = null;
        private AggregationConfig       aggregationConfig = null;
        private PipelineMode            mode              = PipelineMode.GENERATE;
        private String                  scriptPath        = "";
        private String                  scriptName        = "";
        private String                  saveScriptTo      = "./output/scripts";
        private AggregationMode         aggregationMode   = AggregationMode.LLM_DOM;
        private NetworkAggregationConfig networkAggConfig = NetworkAggregationConfig.defaults();

        public Builder taskType(TaskType t)                { this.taskType = t;              return this; }
        public Builder mode(PipelineMode m)                { this.mode = m;                   return this; }
        public Builder scriptPath(String p)                { this.scriptPath = p;            return this; }
        public Builder scriptName(String n)                { this.scriptName = n;            return this; }
        public Builder saveScriptTo(String d)              { this.saveScriptTo = d;          return this; }
        public Builder startUrl(String u)                  { this.startUrl = u;              return this; }
        public Builder goal(String g)                      { this.goal = g;                  return this; }
        public Builder tokenValues(Map<String,String> tv)  {
            this.tokenValues = tv != null ? tv : Collections.emptyMap(); return this; }
        public Builder paginationPattern(PaginationPattern pp){ this.paginationPattern = pp; return this; }
        public Builder agentConfig(AgentConfig a)          { this.agentConfig = a;           return this; }
        public Builder browserConfig(BrowserConfig b)      { this.browserConfig = b;         return this; }
        public Builder bedrockConfig(BedrockConfig b)      { this.bedrockConfig = b;         return this; }
        public Builder aggregationConfig(AggregationConfig a){ this.aggregationConfig = a;   return this; }
        public Builder aggregationMode(AggregationMode m)  { this.aggregationMode = m != null ? m : AggregationMode.LLM_DOM; return this; }
        public Builder networkAggConfig(NetworkAggregationConfig c) {
            this.networkAggConfig = c != null ? c : NetworkAggregationConfig.defaults(); return this; }

        public PipelineConfig build() {
            if (mode == PipelineMode.REPLAY) {
                if (scriptPath == null || scriptPath.isBlank()) {
                    throw new IllegalStateException("PipelineConfig: scriptPath is required for REPLAY");
                }
            } else if (mode != PipelineMode.LIST) {
                if (startUrl == null || startUrl.isBlank()) {
                    throw new IllegalStateException("PipelineConfig: startUrl is required");
                }
                if (goal == null || goal.isBlank()) {
                    throw new IllegalStateException("PipelineConfig: goal is required");
                }
            }
            if (agentConfig == null)
                throw new IllegalStateException("PipelineConfig: agentConfig is required");
            if (browserConfig == null)
                throw new IllegalStateException("PipelineConfig: browserConfig is required");
            if (bedrockConfig == null)
                throw new IllegalStateException("PipelineConfig: bedrockConfig is required");
            if (taskType == TaskType.AGGREGATION && aggregationConfig == null)
                throw new IllegalStateException(
                        "PipelineConfig: aggregationConfig is required for AGGREGATION tasks");
            return new PipelineConfig(this);
        }
    }
}
