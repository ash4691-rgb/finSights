package com.finsights.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** Turns notification data into plain-text email. Pure: no I/O, no Spring. */
public final class NotificationComposer {
    private static final String INR = "INR";
    private static final String FOOTER = "\n\n—\nYou get this because email notifications are on. "
            + "Turn them off in FinSights → Settings → Notification preferences.\n"
            + "FinSights tracks your wealth. It doesn't give investment advice.";

    /** One line item: {@code key} is the stable id used to avoid re-sending it. */
    public record AlertLine(String key, String title, String detail) { }

    /** Everything the weekly digest shows. {@code weekChange} and {@code xirrPercent} may be null. */
    public record DigestData(String displayName, String currency, BigDecimal netWorth, BigDecimal assets,
                             BigDecimal liabilities, BigDecimal weekChange, BigDecimal xirrPercent,
                             List<AlertLine> needsAttention, List<AlertLine> comingUp, List<AlertLine> movers,
                             String appUrl) { }

    private NotificationComposer() { }

    public static NotificationEmail digest(DigestData data) {
        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(nameOrThere(data.displayName())).append(",\n\nHere's your week in FinSights.\n\n");
        body.append("Net worth: ").append(money(data.netWorth(), data.currency()));
        if (data.weekChange() != null) body.append(" (").append(signedMoney(data.weekChange(), data.currency())).append(" this week)");
        body.append("\nAssets: ").append(money(data.assets(), data.currency()));
        body.append("\nLiabilities: ").append(money(data.liabilities(), data.currency()));
        if (data.xirrPercent() != null) body.append("\nPortfolio XIRR: ").append(signedPercent(data.xirrPercent())).append(" a year");
        section(body, "Needs attention", data.needsAttention());
        section(body, "Coming up", data.comingUp());
        section(body, "Moved past your thresholds", data.movers());
        body.append("\n\nOpen FinSights: ").append(data.appUrl()).append(FOOTER);

        String subject = "Your FinSights week: net worth " + money(data.netWorth(), data.currency());
        if (data.weekChange() != null) subject += " (" + signedMoney(data.weekChange(), data.currency()) + ")";
        return new NotificationEmail(subject, body.toString(), List.of());
    }

    public static NotificationEmail alert(String displayName, List<AlertLine> lines, String appUrl) {
        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(nameOrThere(displayName)).append(",\n\n");
        body.append(lines.size() == 1 ? "One item needs your attention:" : lines.size() + " items need your attention:");
        lines.forEach(line -> appendLine(body, line));
        body.append("\n\nOpen FinSights: ").append(appUrl).append(FOOTER);
        String noun = lines.size() == 1 ? "item needs" : "items need";
        String subject = "FinSights: " + lines.size() + " " + noun + " your attention";
        return new NotificationEmail(subject, body.toString(), lines.stream().map(AlertLine::key).toList());
    }

    private static void section(StringBuilder body, String heading, List<AlertLine> lines) {
        if (lines == null || lines.isEmpty()) return;
        body.append("\n\n").append(heading).append(" (").append(lines.size()).append("):");
        lines.forEach(line -> appendLine(body, line));
    }

    private static void appendLine(StringBuilder body, AlertLine line) {
        body.append("\n• ").append(line.title());
        if (line.detail() != null && !line.detail().isBlank()) body.append("\n  ").append(line.detail());
    }

    private static String nameOrThere(String name) {
        if (name == null || name.isBlank() || name.contains("@")) return "there";
        return name.trim();
    }

    /** Whole units with the currency symbol; INR uses Indian lakh/crore grouping (₹12,34,567). */
    static String money(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.setScale(0, RoundingMode.HALF_UP);
        Currency resolved = currencyOrInr(currency);
        if (!INR.equals(resolved.getCurrencyCode())) {
            NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
            format.setCurrency(resolved);
            format.setMaximumFractionDigits(0);
            format.setMinimumFractionDigits(0);
            return format.format(value);
        }
        String sign = value.signum() < 0 ? "-" : "";
        return sign + "₹" + indianGrouping(value.abs().toPlainString());
    }

    static String indianGrouping(String digits) {
        if (digits.length() <= 3) return digits;
        String lastThree = digits.substring(digits.length() - 3);
        StringBuilder head = new StringBuilder(digits.substring(0, digits.length() - 3));
        for (int i = head.length() - 2; i > 0; i -= 2) head.insert(i, ',');
        return head + "," + lastThree;
    }

    static String signedMoney(BigDecimal amount, String currency) {
        if (amount.signum() < 0) return "−" + money(amount.abs(), currency);
        return "+" + money(amount, currency);
    }

    static String signedPercent(BigDecimal percent) {
        String value = percent.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        if (percent.signum() < 0) return "−" + value;
        return "+" + value;
    }

    private static Currency currencyOrInr(String code) {
        try {
            return Currency.getInstance(code == null ? INR : code);
        } catch (IllegalArgumentException ex) {
            return Currency.getInstance(INR);
        }
    }
}
