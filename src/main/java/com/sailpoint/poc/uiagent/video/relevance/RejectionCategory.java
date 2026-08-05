package com.sailpoint.poc.uiagent.video.relevance;

/**
 * Why a video was rejected. The category drives the guidance shown to the user —
 * "you uploaded the wrong thing" and "re-record this properly" need different messages.
 */
public enum RejectionCategory {

    /** Camera footage, a movie, an animation — not a capture of a computer screen. */
    NOT_A_SCREEN_RECORDING(
            "This is not a screen recording.",
            "Upload a screen recording of the browser workflow you want automated."),

    /** A screen recording of something other than a web browser: IDE, terminal, game, slides. */
    NOT_A_WEB_UI(
            "This is a screen recording, but not of a web application.",
            "Record the workflow in a browser — the agent replays steps against web pages."),

    /** A real web UI, but not the kind of administrative workflow this agent automates. */
    OUT_OF_DOMAIN(
            "This web application is outside the scope of this agent.",
            "Record a provisioning or account-aggregation workflow in an admin console."),

    /** A web UI where nothing meaningful happens — no clicks, no typing, no navigation. */
    NO_INTERACTIONS(
            "No user interactions were visible in this recording.",
            "Re-record while actually performing the workflow: click, type, and submit."),

    /** Too blurry, too small, or too heavily compressed to read field and button labels. */
    UNREADABLE(
            "The recording is too unclear to read UI labels.",
            "Re-record at a higher resolution, full screen, without heavy compression."),

    /** Not rejected. */
    NONE("", "");

    private final String reason;
    private final String suggestion;

    RejectionCategory(String reason, String suggestion) {
        this.reason = reason;
        this.suggestion = suggestion;
    }

    /** Default human-readable reason, used when the classifier does not supply its own. */
    public String defaultReason() {
        return reason;
    }

    /** Actionable next step shown to the user alongside the reason. */
    public String suggestion() {
        return suggestion;
    }

    /** Maps a classifier-supplied category name to an enum constant, defaulting to {@link #NONE}. */
    public static RejectionCategory from(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
