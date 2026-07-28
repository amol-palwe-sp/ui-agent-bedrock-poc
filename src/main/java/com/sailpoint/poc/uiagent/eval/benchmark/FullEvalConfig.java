package com.sailpoint.poc.uiagent.eval.benchmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level model for {@code aggregation-full-eval.json}.
 *
 * <p>The file has two sections:
 * <ul>
 *   <li>{@code defaults.runsPerSystem} — shared run count inherited by every system</li>
 *   <li>{@code systems[]} — the actual eval cases, each optionally overriding the run count</li>
 * </ul>
 *
 * <pre>{@code
 * {
 *   "defaults": { "runsPerSystem": 5 },
 *   "systems": [ { "id": "...", "runsPerSystem": 10, ... } ]
 * }
 * }</pre>
 */
public record FullEvalConfig(int defaultRunsPerSystem, List<FullEvalCase> systems) {

    /**
     * Loads and parses a full-eval config from the given file path.
     *
     * @param path path to {@code aggregation-full-eval.json}
     * @return parsed config
     * @throws IOException if the file cannot be read or parsed
     */
    public static FullEvalConfig loadAll(String path) throws IOException {
        String raw = Files.readString(Paths.get(path));
        JSONObject root = new JSONObject(raw);

        JSONObject defaults = root.optJSONObject("defaults");
        int defaultRuns = defaults != null ? defaults.optInt("runsPerSystem", 5) : 5;

        JSONArray systemsArr = root.optJSONArray("systems");
        List<FullEvalCase> cases = new ArrayList<>();
        if (systemsArr != null) {
            for (int i = 0; i < systemsArr.length(); i++) {
                cases.add(FullEvalCase.fromJson(systemsArr.getJSONObject(i)));
            }
        }

        return new FullEvalConfig(defaultRuns, Collections.unmodifiableList(cases));
    }
}
