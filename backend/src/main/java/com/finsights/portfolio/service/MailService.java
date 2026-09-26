package com.finsights.portfolio.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private final JavaMailSender mailSender;
    @Value("${spring.mail.username:}") private String fromAddress;
    @Value("${app.frontend-url}") private String frontendUrl;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** A signup should never fail because mail delivery did — log and move on either way. Without
     *  GMAIL_USERNAME/GMAIL_APP_PASSWORD configured (local dev), just logs the link so the flow
     *  is still fully testable without a real mailbox. */
    public void sendVerificationEmail(String toEmail, String token) {
        String link = frontendUrl + "/?verify=" + token;
        if (fromAddress == null || fromAddress.isBlank()) {
            log.info("Email sending isn't configured (GMAIL_USERNAME/GMAIL_APP_PASSWORD) — verification link for {}: {}", toEmail, link);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Verify your FinSights account");
            message.setText("Welcome to FinSights! Verify your email address to activate your account:\n\n" + link
                    + "\n\nThis link expires in 24 hours. If you didn't create this account, you can ignore this email.");
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Failed to send verification email to {}", toEmail, e);
        }
    }
}
