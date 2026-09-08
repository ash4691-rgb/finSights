package com.finsights.portfolio.dto;

/** Public, pre-login: tells the sign-in screen which options to offer. */
public record AuthConfigResponse(boolean googleEnabled, boolean demoEnabled) { }
