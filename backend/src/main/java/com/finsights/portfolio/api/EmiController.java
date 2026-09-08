package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.service.EmiService;
import java.time.YearMonth;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/emis")
public class EmiController {

    private final EmiService emis;

    public EmiController(EmiService emis) {
        this.emis = emis;
    }

    @GetMapping("/due")
    List<ActionItemResponse> due() {
        return emis.dueItems();
    }

    @PostMapping("/{holdingId}/pay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markPaid(@PathVariable String holdingId,
                  @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth period) {
        emis.markPaid(holdingId, period);
    }
}
