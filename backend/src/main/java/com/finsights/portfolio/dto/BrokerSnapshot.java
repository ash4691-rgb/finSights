package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One holding's current state as reported by a broker's API as of the daily sync — the handoff
 * shape from Core's {@code BrokerSyncScheduler} to whichever {@link
 * com.finsights.portfolio.service.BrokerSyncConsumer} applies it onto {@code Holding} rows (per
 * issue #90: Data Onboarding's {@code HoldingService.refreshBrokerSyncHoldings()}, not yet built).
 *
 * <p>{@code brokerSyncRef} follows the same namespace-prefix convention {@code MarketDataService}/
 * {@code LivePrice} already established for {@code tickerSymbol} (e.g. {@code "MF:120503"}) — for
 * Kite this is {@code "kite:" + instrument_token}, Kite's own stable per-instrument identifier
 * (stable across symbol renames, unlike {@code tradingsymbol}).
 */
public record BrokerSnapshot(String brokerSyncRef, BigDecimal quantity, BigDecimal currentValue, Instant asOf) { }
