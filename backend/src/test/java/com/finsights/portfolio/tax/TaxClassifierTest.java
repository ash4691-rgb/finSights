package com.finsights.portfolio.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.domain.ValuationMethod;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class TaxClassifierTest {
    private static Holding holding(String category, String name, String ticker, String currency) {
        Category c = new Category();
        c.setName(category);
        Holding h = new Holding();
        h.setCategory(c);
        h.setName(name);
        h.setTickerSymbol(ticker);
        h.setCurrency(currency);
        h.setValuationMethod(ValuationMethod.MARKET_PRICE);
        return h;
    }

    @ParameterizedTest(name = "{0} / {1} / {2} / {3} -> {4}")
    @CsvSource(nullValues = "null", value = {
            "Domestic Stocks, Infosys, INFY.NS, INR, LISTED_EQUITY",
            "Long term, Reliance, RELIANCE.BO, INR, LISTED_EQUITY",
            "Mutual Funds, Parag Parikh Flexi Cap, null, INR, LISTED_EQUITY",
            "Debt, HDFC Liquid Fund, null, INR, DEBT_SLAB",
            "Savings, SBI FD, null, INR, DEBT_SLAB",
            "Crypto, Bitcoin, BTC-INR, INR, VIRTUAL_DIGITAL_ASSET",
            "Alt, Something, ETH-USD, INR, VIRTUAL_DIGITAL_ASSET",
            "Commodities, SGB 2028, null, INR, OTHER_CAPITAL",
            "International Stocks, Apple, AAPL, USD, OTHER_CAPITAL",
            "Misc, Apple, AAPL, USD, OTHER_CAPITAL",
            "Misc, Painting, null, INR, OTHER_CAPITAL",
            "null, null, null, null, OTHER_CAPITAL",
    })
    void infersFromCategoryNameTickerAndCurrency(String category, String name, String ticker, String currency, TaxClass expected) {
        Holding h = holding(category, name, ticker, currency);
        if (category == null) h.setCategory(null);
        assertThat(TaxClassifier.infer(h)).isEqualTo(expected);
    }

    @Test
    void structuralFactsWinOverNames() {
        Holding pf = holding("Equity", "EPF", null, "INR");
        pf.setManualType(ManualType.PROVIDENT_FUND);
        assertThat(TaxClassifier.infer(pf)).isEqualTo(TaxClass.EXEMPT);

        Holding flat = holding("Equity", "Flat", null, "INR");
        flat.setManualType(ManualType.REAL_ESTATE);
        assertThat(TaxClassifier.infer(flat)).isEqualTo(TaxClass.OTHER_CAPITAL);

        Holding plot = holding("Land", "Plot", null, "INR");
        plot.getCategory().setRealEstate(true);
        assertThat(TaxClassifier.infer(plot)).isEqualTo(TaxClass.OTHER_CAPITAL);

        Holding fd = holding("Equity", "Bank deposit", null, "INR");
        fd.setValuationMethod(ValuationMethod.FIXED_RATE);
        assertThat(TaxClassifier.infer(fd)).isEqualTo(TaxClass.DEBT_SLAB);
    }

    @Test
    void userOverrideWins() {
        Holding h = holding("Domestic Stocks", "Infosys", "INFY.NS", "INR");
        assertThat(TaxClassifier.effective(h)).isEqualTo(TaxClass.LISTED_EQUITY);
        h.setTaxClass(TaxClass.OTHER_CAPITAL);
        assertThat(TaxClassifier.effective(h)).isEqualTo(TaxClass.OTHER_CAPITAL);
    }
}
