package com.finsights.portfolio.dto;

/** {@code loggedIn}: true when email verification is off (see LocalAuthService) and the session
 *  was established right here — the frontend can skip straight into the app instead of showing
 *  a "check your email" screen. */
public record RegisterResponse(String email, String message, boolean loggedIn) { }
