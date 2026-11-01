package com.finsights.portfolio.dto;

/** A write Goku has proposed and is waiting on the user to confirm — rendered as a Confirm card in the chat. */
public record GokuPendingAction(String id, String tool, String summary, boolean destructive) {}
