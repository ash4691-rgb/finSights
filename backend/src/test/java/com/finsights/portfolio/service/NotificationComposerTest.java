package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.service.NotificationComposer.AlertLine;
import com.finsights.portfolio.service.NotificationComposer.DigestData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationComposerTest {
    private static final String URL = "https://app.example";

    @Test
    void digestShowsTotalsWeekChangeXirrAndEverySection() {
        NotificationEmail email = NotificationComposer.digest(new DigestData("Asha", "INR", new BigDecimal("1234567"),
                new BigDecimal("1300000"), new BigDecimal("65433"), new BigDecimal("-2500"), new BigDecimal("12.345"),
                List.of(new AlertLine("k1", "EMI due — Home loan", "₹25,000 on 5 Dec")),
                List.of(new AlertLine("k2", "Interest payout — FD", "")),
                List.of(new AlertLine("k3", "Reliance", "DAILY +6.20% (threshold 5%)")), URL));

        assertThat(email.subject()).isEqualTo("Your FinSights week: net worth ₹12,34,567 (−₹2,500)");
        assertThat(email.body())
                .startsWith("Hi Asha,")
                .contains("Net worth: ₹12,34,567 (−₹2,500 this week)")
                .contains("Liabilities: ₹65,433")
                .contains("Portfolio XIRR: +12.35% a year")
                .contains("Needs attention (1):\n• EMI due — Home loan\n  ₹25,000 on 5 Dec")
                .contains("Coming up (1):\n• Interest payout — FD\n\n")
                .contains("Moved past your thresholds (1)")
                .contains("Open FinSights: " + URL)
                .contains("doesn't give investment advice");
        assertThat(email.alertKeys()).isEmpty();
    }

    @Test
    void digestOmitsOptionalPartsWhenMissing() {
        NotificationEmail email = NotificationComposer.digest(new DigestData("user@example.com", "XYZ",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.of(), null, List.of(), URL));

        assertThat(email.subject()).isEqualTo("Your FinSights week: net worth ₹0");
        assertThat(email.body()).startsWith("Hi there,").doesNotContain("this week)").doesNotContain("XIRR")
                .doesNotContain("Needs attention").doesNotContain("Coming up");
    }

    @Test
    void alertCountsItemsAndCarriesTheirKeys() {
        NotificationEmail one = NotificationComposer.alert(null, List.of(new AlertLine("a", "Matured — FD", null)), URL);
        assertThat(one.subject()).isEqualTo("FinSights: 1 item needs your attention");
        assertThat(one.body()).startsWith("Hi there,").contains("One item needs your attention:\n• Matured — FD\n\n");
        assertThat(one.alertKeys()).containsExactly("a");

        NotificationEmail two = NotificationComposer.alert(" Ravi ", List.of(
                new AlertLine("a", "A", "x"), new AlertLine("b", "B", "y")), URL);
        assertThat(two.subject()).isEqualTo("FinSights: 2 items need your attention");
        assertThat(two.body()).startsWith("Hi Ravi,").contains("2 items need your attention:");
        assertThat(two.alertKeys()).containsExactly("a", "b");
    }

    @Test
    void formatsMoneyAndPercentWithSigns() {
        assertThat(NotificationComposer.money(new BigDecimal("1500.6"), "USD")).isEqualTo("$1,501");
        assertThat(NotificationComposer.money(BigDecimal.TEN, null)).isEqualTo("₹10");
        assertThat(NotificationComposer.signedMoney(new BigDecimal("100"), "INR")).isEqualTo("+₹100");
        assertThat(NotificationComposer.signedPercent(new BigDecimal("-3.456"))).isEqualTo("−3.46%");
        assertThat(NotificationComposer.signedPercent(BigDecimal.ZERO)).isEqualTo("+0.00%");
        assertThat(NotificationComposer.money(new BigDecimal("-1234567"), "INR")).isEqualTo("-₹12,34,567");
        assertThat(NotificationComposer.indianGrouping("123")).isEqualTo("123");
        assertThat(NotificationComposer.indianGrouping("1234")).isEqualTo("1,234");
        assertThat(NotificationComposer.indianGrouping("123456789")).isEqualTo("12,34,56,789");
    }
}
