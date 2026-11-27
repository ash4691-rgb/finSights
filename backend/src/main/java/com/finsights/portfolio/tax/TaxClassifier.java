package com.finsights.portfolio.tax;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.domain.ValuationMethod;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Best guess at a holding's {@link TaxClass} from what FinSights already knows about it. The user
 * can override it per holding; the guess only applies while {@code Holding.taxClass} is null.
 * First matching rule wins; anything unrecognised falls back to OTHER_CAPITAL (the stricter
 * 24-month rule), so a wrong guess never makes a gain look long-term too early.
 */
public final class TaxClassifier {
    private static final Pattern CRYPTO = Pattern.compile("\\b(crypto|bitcoin|btc|ethereum|eth|usdt|coin)\\b");
    private static final Pattern CRYPTO_PAIR = Pattern.compile("-(inr|usd|usdt)$");
    private static final Pattern OTHER = Pattern.compile("\\b(gold|silver|sgb|international|foreign|global|us|nasdaq|unlisted|property|land)\\b");
    private static final Pattern DEBT = Pattern.compile("\\b(debt|bond|bonds|liquid|gilt|fd|deposit|nps)\\b");
    private static final Pattern EQUITY = Pattern.compile("\\b(stock|stocks|equity|share|shares|mutual|fund|funds|etf|nifty|sensex|elss)\\b");
    private static final Pattern INDIAN_LISTING = Pattern.compile("\\.(ns|bo)$");

    private TaxClassifier() { }

    public static TaxClass effective(Holding holding) {
        if (holding.getTaxClass() != null) return holding.getTaxClass();
        return infer(holding);
    }

    public static TaxClass infer(Holding holding) {
        Category category = holding.getCategory();
        if (holding.getManualType() == ManualType.PROVIDENT_FUND) return TaxClass.EXEMPT;
        if (holding.getManualType() == ManualType.REAL_ESTATE) return TaxClass.OTHER_CAPITAL;
        if (category != null && category.isRealEstate()) return TaxClass.OTHER_CAPITAL;
        if (holding.getValuationMethod() == ValuationMethod.FIXED_RATE) return TaxClass.DEBT_SLAB;

        String ticker = lower(holding.getTickerSymbol());
        String text = lower(category == null ? "" : category.getName()) + " " + lower(holding.getName());
        if (CRYPTO.matcher(text).find() || CRYPTO_PAIR.matcher(ticker).find()) return TaxClass.VIRTUAL_DIGITAL_ASSET;
        if (OTHER.matcher(text).find()) return TaxClass.OTHER_CAPITAL;
        if (!"inr".equals(lower(holding.getCurrency()))) return TaxClass.OTHER_CAPITAL;
        if (DEBT.matcher(text).find()) return TaxClass.DEBT_SLAB;
        if (INDIAN_LISTING.matcher(ticker).find() || EQUITY.matcher(text).find()) return TaxClass.LISTED_EQUITY;
        return TaxClass.OTHER_CAPITAL;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
