package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One holding's current state as reported by a broker's API as of the daily sync — the handoff
 * shape from Core's {@code BrokerSyncScheduler} to {@code BrokerHoldingSyncConsumer}, which
 * applies it onto {@code Holding} rows (per issue #90), routing anything it can't auto-match
 * through the Action Centre for review instead.
 *
 * <p>{@code brokerSyncRef} follows the same namespace-prefix convention {@code MarketDataService}/
 * {@code LivePrice} already established for {@code tickerSymbol} (e.g. {@code "MF:120503"}) — for
 * Kite this is {@code "kite:" + instrument_token}, Kite's own stable per-instrument identifier
 * (stable across symbol renames, unlike {@code tradingsymbol}).
 *
 * <p>{@code displayName} is the broker's own human-readable symbol/name (e.g. {@code "INFY"},
 * {@code "BTC"}) — never shown as-is for an already-matched holding (that keeps its own name),
 * only used to pre-fill the suggested name when a snapshot needs a new holding created.
 */
public record BrokerSnapshot(String brokerSyncRef, String displayName, BigDecimal quantity, BigDecimal currentValue, Instant asOf) { }
