package com.finsights.portfolio.service;

import java.util.List;

/** A composed email plus the alert keys it covers (empty for the digest, which is never deduplicated). */
public record NotificationEmail(String subject, String body, List<String> alertKeys) { }
