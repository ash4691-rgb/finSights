package com.finsights.portfolio.domain;

/** Why a synced {@link com.finsights.portfolio.dto.BrokerSnapshot} couldn't be auto-applied onto a
 *  {@link Holding} and needed a human decision instead — see {@link PendingBrokerHolding}. */
public enum PendingBrokerHoldingReason {
    /** No existing holding is linked to this instrument yet — needs a category to be filed under. */
    NEW,
    /** An existing holding at the same broker already has this exact name (most likely entered
     *  manually before the broker was connected) — needs the user to confirm it's the same
     *  position before linking them, rather than silently creating a duplicate. */
    NAME_CONFLICT
}
