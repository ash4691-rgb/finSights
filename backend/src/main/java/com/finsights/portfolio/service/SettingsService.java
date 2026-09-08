package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.CountryResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.SettingsRequest;
import com.finsights.portfolio.dto.SettingsResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.TagSuggestionRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import com.finsights.portfolio.repository.UserAccountRepository;
import com.finsights.portfolio.repository.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private final UserAccountRepository users;
    private final HoldingRepository holdings;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final HoldingService holdingService;
    private final CurrentUserService currentUser;
    private final CountryCurrencyService countries;
    private final WatchlistRepository watchlist;
    private final PriceSnapshotService snapshots;
    private final TagSuggestionRepository tagSuggestions;

    public SettingsService(UserAccountRepository users, HoldingRepository holdings, CategoryRepository categories,
                           TransactionRepository transactions, HoldingService holdingService, CurrentUserService currentUser,
                           CountryCurrencyService countries, WatchlistRepository watchlist, PriceSnapshotService snapshots,
                           TagSuggestionRepository tagSuggestions) {
        this.users = users;
        this.holdings = holdings;
        this.categories = categories;
        this.transactions = transactions;
        this.holdingService = holdingService;
        this.currentUser = currentUser;
        this.watchlist = watchlist;
        this.snapshots = snapshots;
        this.countries = countries;
        this.tagSuggestions = tagSuggestions;
    }

    public SettingsResponse current() {
        UserAccount user = currentUser.currentUser();
        return toResponse(user);
    }

    private static final Set<String> NUMBER_FORMATS = Set.of("INDIAN", "INTERNATIONAL");

    @Transactional
    public SettingsResponse update(SettingsRequest request) {
        UserAccount user = currentUser.currentUser();
        // Base currency is derived from country of residence, not picked independently.
        CountryResponse country = countries.require(request.country());
        user.setCountry(country.code());
        user.setBaseCurrency(country.currency());
        if (request.displayName() != null && !request.displayName().isBlank()) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        }
        if (request.numberFormat() != null && !request.numberFormat().isBlank()) {
            String format = request.numberFormat().trim().toUpperCase();
            if (!NUMBER_FORMATS.contains(format)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "numberFormat must be one of " + NUMBER_FORMATS);
            }
            user.setNumberFormat(format);
        }
        if (request.notifyEmail() != null) user.setNotifyEmail(request.notifyEmail());
        if (request.notifySms() != null) user.setNotifySms(request.notifySms());
        if (request.notifyPush() != null) user.setNotifyPush(request.notifyPush());
        if (request.notifyThresholdPercent() != null) {
            BigDecimal threshold = request.notifyThresholdPercent();
            if (threshold.signum() < 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "notifyThresholdPercent cannot be negative");
            }
            user.setNotifyThresholdPercent(threshold);
        }
        // Hot-picks thresholds are always set (null included) so the Insights page can clear one back to "off".
        user.setDailyThresholdPercent(nonNegative(request.dailyThresholdPercent(), "dailyThresholdPercent"));
        user.setWeeklyThresholdPercent(nonNegative(request.weeklyThresholdPercent(), "weeklyThresholdPercent"));
        user.setMonthlyThresholdPercent(nonNegative(request.monthlyThresholdPercent(), "monthlyThresholdPercent"));
        user.setQuarterlyThresholdPercent(nonNegative(request.quarterlyThresholdPercent(), "quarterlyThresholdPercent"));
        user.setYearlyThresholdPercent(nonNegative(request.yearlyThresholdPercent(), "yearlyThresholdPercent"));
        return toResponse(users.save(user));
    }

    public Map<String, Object> export() {
        UserAccount user = currentUser.currentUser();
        return Map.of(
                "exportedAt", Instant.now().toString(),
                "user", Map.of("email", user.getEmail(), "displayName", user.getDisplayName(),
                        "country", user.getCountry(), "baseCurrency", user.getBaseCurrency(),
                        "memberSince", user.getCreatedAt().toString()),
                "holdings", holdingService.list());
    }

    @Transactional
    public void deleteAccount() {
        UserAccount user = currentUser.currentUser();
        transactions.deleteByUser_Id(user.getId());
        holdings.deleteByUser_Id(user.getId());
        categories.deleteByUser_Id(user.getId());
        watchlist.deleteByUser_Id(user.getId());
        snapshots.deleteForUser(user.getId());
        tagSuggestions.deleteByUser_Id(user.getId());
        users.delete(user);
    }

    private SettingsResponse toResponse(UserAccount user) {
        String countryName = countries.all().stream()
                .filter(c -> c.code().equals(user.getCountry()))
                .map(CountryResponse::name)
                .findFirst().orElse(user.getCountry());
        return new SettingsResponse(user.getEmail(), user.getDisplayName(), user.getPhone(), user.getCountry(), countryName,
                user.getBaseCurrency(), user.getNumberFormat(), user.getNotifyEmail(), user.getNotifySms(),
                user.getNotifyPush(), user.getNotifyThresholdPercent(),
                user.getDailyThresholdPercent(), user.getWeeklyThresholdPercent(), user.getMonthlyThresholdPercent(),
                user.getQuarterlyThresholdPercent(), user.getYearlyThresholdPercent(),
                CurrentUserService.DEMO_EMAIL.equalsIgnoreCase(user.getEmail()), holdingService.list().size(), user.getCreatedAt());
    }

    private BigDecimal nonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, field + " cannot be negative");
        }
        return value;
    }
}
