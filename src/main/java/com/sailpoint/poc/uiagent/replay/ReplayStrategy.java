package com.sailpoint.poc.uiagent.replay;

/** Element location strategy used during replay (REQ-RR-4.3, REQ-SIV-2). */
public enum ReplayStrategy {
    FINGERPRINT,
    RETAG,
    /** Scroll element into viewport via CSS selector, retag full DOM (REQ-SIV-2). */
    SCROLL_INTO_VIEW,
    /** Chunk-scroll from top, retag full DOM after each chunk (REQ-SIV-3). */
    PROGRESSIVE_SCROLL,
    FUZZY,
    CLAUDE,
    /** @deprecated Replaced by {@link #SCROLL_INTO_VIEW}; kept for older script JSON. */
    @Deprecated
    CSS_FALLBACK
}
