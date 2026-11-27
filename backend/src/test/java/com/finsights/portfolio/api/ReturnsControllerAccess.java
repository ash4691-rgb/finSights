package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.ReturnsResponse;
import com.finsights.portfolio.service.ReturnsService;

/** Lets tests in other packages call the package-private controller method. */
public final class ReturnsControllerAccess {
    private ReturnsControllerAccess() { }

    public static ReturnsResponse call(ReturnsService service, String currency) {
        return new ReturnsController(service).returns(currency);
    }
}
