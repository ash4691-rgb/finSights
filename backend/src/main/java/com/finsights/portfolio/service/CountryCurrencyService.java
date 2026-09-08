package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.CountryResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps a user's country of residence to their default (base) currency. Deliberately limited
 * to countries whose currency {@link FxRateService} can actually convert — add a currency
 * there first if you want to add more countries here.
 */
@Service
public class CountryCurrencyService {

    private static final List<CountryResponse> COUNTRIES = List.of(
            new CountryResponse("IN", "India", "INR"),
            new CountryResponse("US", "United States", "USD"),
            new CountryResponse("GB", "United Kingdom", "GBP"),
            new CountryResponse("SG", "Singapore", "SGD"),
            new CountryResponse("AE", "United Arab Emirates", "AED"),
            new CountryResponse("DE", "Germany", "EUR"),
            new CountryResponse("FR", "France", "EUR"),
            new CountryResponse("ES", "Spain", "EUR"),
            new CountryResponse("IT", "Italy", "EUR"),
            new CountryResponse("NL", "Netherlands", "EUR"),
            new CountryResponse("IE", "Ireland", "EUR"),
            new CountryResponse("PT", "Portugal", "EUR"),
            new CountryResponse("BE", "Belgium", "EUR"),
            new CountryResponse("AT", "Austria", "EUR"),
            new CountryResponse("FI", "Finland", "EUR"));

    private static final Map<String, CountryResponse> BY_CODE = new LinkedHashMap<>();
    static {
        COUNTRIES.forEach(c -> BY_CODE.put(c.code(), c));
    }

    public List<CountryResponse> all() {
        return COUNTRIES;
    }

    public CountryResponse require(String code) {
        CountryResponse country = BY_CODE.get(normalize(code));
        if (country == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported country: " + code
                    + ". Supported: " + String.join(", ", BY_CODE.keySet()));
        }
        return country;
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }
}
