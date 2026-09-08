package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.CountryResponse;
import com.finsights.portfolio.service.CountryCurrencyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/countries")
public class CountryController {
    private final CountryCurrencyService countries;

    public CountryController(CountryCurrencyService countries) { this.countries = countries; }

    @GetMapping
    List<CountryResponse> list() { return countries.all(); }
}
