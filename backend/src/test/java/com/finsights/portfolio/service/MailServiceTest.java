package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MailServiceTest {
    @Test
    void withoutAnApiKeyNothingIsSentAndItSaysSo() {
        MailService mail = new MailService();
        ReflectionTestUtils.setField(mail, "apiKey", "");
        assertThat(mail.send("a@example.com", "Subject", "Body")).isFalse();
        ReflectionTestUtils.setField(mail, "apiKey", null);
        assertThat(mail.send("a@example.com", "Subject", "Body")).isFalse();
    }

    @Test
    void deliveryFailureReturnsFalseInsteadOfThrowing() {
        MailService mail = new MailService();
        ReflectionTestUtils.setField(mail, "apiKey", "re_test");
        ReflectionTestUtils.setField(mail, "fromAddress", "FinSights <x@example.com>");
        ReflectionTestUtils.setField(mail, "restClient", org.springframework.web.client.RestClient.builder()
                .requestInterceptor((request, body, execution) -> { throw new java.io.IOException("offline"); }).build());
        assertThat(mail.send("a@example.com", "Subject", "Body")).isFalse();
    }
}
