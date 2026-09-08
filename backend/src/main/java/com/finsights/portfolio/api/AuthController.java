package com.finsights.portfolio.api;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.AuthConfigResponse;
import com.finsights.portfolio.dto.CurrentUserResponse;
import com.finsights.portfolio.dto.LoginRequest;
import com.finsights.portfolio.dto.RegisterRequest;
import com.finsights.portfolio.service.CurrentUserService;
import com.finsights.portfolio.service.LocalAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CurrentUserService currentUser;
    private final LocalAuthService localAuth;
    private final SecurityContextRepository contextRepository;
    @Value("${app.auth-mode}") private String authMode;

    public AuthController(CurrentUserService currentUser, LocalAuthService localAuth, SecurityContextRepository contextRepository) {
        this.currentUser = currentUser;
        this.localAuth = localAuth;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/config")
    AuthConfigResponse config() {
        boolean google = "google".equalsIgnoreCase(authMode);
        return new AuthConfigResponse(google, !google);
    }

    @GetMapping("/me")
    CurrentUserResponse me() {
        return toResponse(currentUser.currentUser());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    CurrentUserResponse register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserAccount user = localAuth.register(request.email(), request.displayName(), request.password());
        establishSession(user.getEmail(), httpRequest, httpResponse);
        return toResponse(user);
    }

    @PostMapping("/login")
    CurrentUserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserAccount user = localAuth.login(request.email(), request.password());
        establishSession(user.getEmail(), httpRequest, httpResponse);
        return toResponse(user);
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void establishSession(String email, HttpServletRequest request, HttpServletResponse response) {
        var authentication = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private CurrentUserResponse toResponse(UserAccount user) {
        return new CurrentUserResponse(user.getEmail(), user.getDisplayName(),
                CurrentUserService.DEMO_EMAIL.equalsIgnoreCase(user.getEmail()));
    }
}
