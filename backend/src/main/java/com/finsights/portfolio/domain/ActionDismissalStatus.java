package com.finsights.portfolio.domain;

/** What the user did with an Action Centre row. */
public enum ActionDismissalStatus {
    /** Handled — hide it for good. */
    DONE,
    /** Snooze — hide until {@code deferredUntil}, then let it come back. */
    DEFERRED,
    /** Not relevant — hide it for good. */
    DELETED
}
