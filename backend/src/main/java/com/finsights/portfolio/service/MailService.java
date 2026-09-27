package com.finsights.portfolio.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Sends via Resend's HTTP API (not SMTP) — Render's free tier blocks all outbound SMTP ports
 *  (25/465/587) as of September 2025, so a real mail server on that plan is unreachable
 *  regardless of credentials. Resend's API runs over plain HTTPS (443), which isn't affected. */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final RestClient restClient = RestClient.create();
    @Value("${app.resend.api-key:}") private String apiKey;
    @Value("${app.resend.from}") private String fromAddress;
    @Value("${app.frontend-url}") private String frontendUrl;

    /** A signup should never fail because mail delivery did — log and move on either way. Without
     *  RESEND_API_KEY configured (local dev), just logs the link so the flow is still fully
     *  testable without a real mailbox or third-party account. */
    public void sendVerificationEmail(String toEmail, String token) {
        String link = frontendUrl + "/?verify=" + token;
        if (apiKey == null || apiKey.isBlank()) {
            log.info("Email sending isn't configured (RESEND_API_KEY) — verification link for {}: {}", toEmail, link);
            return;
        }
        try {
            restClient.post()
                    .uri(RESEND_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", fromAddress,
                            "to", List.of(toEmail),
                            "subject", "Verify your FinSights account",
                            "text", "Welcome to FinSights! Verify your email address to activate your account:\n\n" + link
                                    + "\n\nThis link expires in 24 hours. If you didn't create this account, you can ignore this email."
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to send verification email to {}", toEmail, e);
        }
    }
}
