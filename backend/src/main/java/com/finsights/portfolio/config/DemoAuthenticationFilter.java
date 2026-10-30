package com.finsights.portfolio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.finsights.portfolio.service.CurrentUserService;

public class DemoAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Real email/password sign-in must be able to run without the demo identity pre-filling the context.
        // Health probes are anonymous and fire on a schedule — authenticating them would make Spring
        // persist a session row (and set a cookie) on every single ping.
        return !uri.startsWith("/api/") || uri.equals("/api/auth/login") || uri.equals("/api/auth/register")
                || uri.equals("/api/health") || uri.equals("/api/health/live");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = request.getHeader("X-Demo-User");
            if (email == null || email.isBlank()) email = CurrentUserService.DEMO_EMAIL;
            var authentication = new UsernamePasswordAuthenticationToken(email, "N/A",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}

