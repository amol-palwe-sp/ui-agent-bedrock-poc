package com.sailpoint.poc.uiagent.video.relevance;

/**
 * Outcome of the pre-flight relevance triage on an uploaded video.
 */
public enum RelevanceVerdict {

    /** The video shows a usable UI workflow — continue to step generation. */
    ACCEPT,

    /** The video cannot yield meaningful steps — stop before the main model call. */
    REJECT,

    /**
     * The classifier could not decide, or decided below the configured confidence floor.
     * Treated as an accept carrying a warning: a false accept is recoverable downstream,
     * a false reject is a dead end for the user.
     */
    UNCERTAIN
}
