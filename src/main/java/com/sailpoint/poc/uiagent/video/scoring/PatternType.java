package com.sailpoint.poc.uiagent.video.scoring;

/**
 * Classification applied to a video frame after pattern detection (REQ-FS-3 / REQ-FS-4.3).
 */
public enum PatternType {
    NORMAL,
    TYPING_MID,
    TYPING_END,
    ANIMATION,
    NAVIGATION_SPIKE,
    PAGE_SETTLED,
    NEW_ELEMENT,
    SCROLL_MID,
    SCROLL_END,
    CURSOR_ONLY,
    MANDATORY
}
