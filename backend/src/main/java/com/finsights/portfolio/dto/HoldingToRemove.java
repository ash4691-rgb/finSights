package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * An existing holding at the statement's broker that a holdings statement no longer lists — the
 * user is offered logging a SELL for its full {@code quantity} (the likely story), or deleting it,
 * or keeping it. Only holdings at the same broker as the statement are ever considered, and never
 * for a transactions-only statement, which doesn't claim to be a full snapshot of what's held.
 */
public record HoldingToRemove(String holdingId, String name, String broker, BigDecimal quantity) { }
