package com.finsights.portfolio.config;

import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the app in demo auth mode against anything other than a local H2 database.
 *
 * <p>Demo mode signs every {@code /api/**} request in as whoever the {@code X-Demo-User} header
 * names (see {@link DemoAuthenticationFilter}), so a deployment that forgot {@code APP_AUTH_MODE=google}
 * would let anyone read or change any user's portfolio. A non-H2 datasource is the signal that this
 * is a real deployment. {@code APP_ALLOW_DEMO_ON_EXTERNAL_DB=true} is a deliberate escape hatch for
 * a throwaway staging database, never for production.
 */
@Component
public class DemoModeGuard implements InitializingBean {
    static final String DEMO_MODE = "demo";
    private static final String H2_URL_PREFIX = "jdbc:h2:";

    private final String authMode;
    private final String datasourceUrl;
    private final boolean allowDemoOnExternalDb;

    public DemoModeGuard(@Value("${app.auth-mode}") String authMode,
                         @Value("${spring.datasource.url:}") String datasourceUrl,
                         @Value("${app.allow-demo-on-external-db:false}") boolean allowDemoOnExternalDb) {
        this.authMode = authMode;
        this.datasourceUrl = datasourceUrl;
        this.allowDemoOnExternalDb = allowDemoOnExternalDb;
    }

    @Override
    public void afterPropertiesSet() {
        if (!isDemoMode()) return;
        if (isLocalDatabase()) return;
        if (allowDemoOnExternalDb) return;
        throw new IllegalStateException("Refusing to start: APP_AUTH_MODE=demo trusts the X-Demo-User header and "
                + "must only run against a local H2 database. Set APP_AUTH_MODE=google for a real deployment, "
                + "or APP_ALLOW_DEMO_ON_EXTERNAL_DB=true for a throwaway staging database.");
    }

    private boolean isDemoMode() {
        return authMode == null || authMode.isBlank() || DEMO_MODE.equalsIgnoreCase(authMode.trim());
    }

    private boolean isLocalDatabase() {
        if (datasourceUrl == null || datasourceUrl.isBlank()) return true;
        return datasourceUrl.trim().toLowerCase(Locale.ROOT).startsWith(H2_URL_PREFIX);
    }
}
