package com.finsights.portfolio.domain;

public enum TransactionType {
    BUY, SELL, SPLIT, INTEREST, ADJUSTMENT,
    /** Liability only: a loan repayment. Interest for the period is settled first, the rest cuts principal. */
    REPAY
}
