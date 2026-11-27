package com.finsights.portfolio.service;

import java.time.LocalDate;

/** One dated cash flow from the investor's point of view: money in is negative, money out positive. */
public record CashFlow(LocalDate date, double amount) { }
