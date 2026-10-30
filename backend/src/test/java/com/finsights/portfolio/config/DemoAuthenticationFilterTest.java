package com.finsights.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class DemoAuthenticationFilterTest {

    private final DemoAuthenticationFilter filter = new DemoAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousEndpointsAreNeverPreAuthenticated() {
        for (String uri : new String[] {"/api/auth/login", "/api/auth/register", "/api/health", "/api/health/live", "/oauth2/authorization/google"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", uri))).as(uri).isTrue();
        }
    }

    @Test
    void everyOtherApiEndpointIsFiltered() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/holdings"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/auth/me"))).isFalse();
    }

    @Test
    void fillsInTheDemoUserWhenNothingIsAuthenticated() throws ServletException, IOException {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/holdings"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("demo@finsights.local");
    }

    @Test
    void honoursTheDemoUserHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/holdings");
        request.addHeader("X-Demo-User", "someone@example.com");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("someone@example.com");
    }
}
