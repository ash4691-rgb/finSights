package com.finsights.portfolio.api;

import com.finsights.portfolio.service.InterestPayoutService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interest-payouts")
public class InterestPayoutController {

    private final InterestPayoutService payouts;

    public InterestPayoutController(InterestPayoutService payouts) {
        this.payouts = payouts;
    }

    @PostMapping("/{holdingId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@PathVariable String holdingId,
                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        payouts.confirm(holdingId, dueDate);
    }
}
