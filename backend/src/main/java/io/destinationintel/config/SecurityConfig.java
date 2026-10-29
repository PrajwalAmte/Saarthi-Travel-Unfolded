package io.destinationintel.config;

import io.destinationintel.common.GlobalExceptionHandler;
import io.destinationintel.community.AccountService;
import io.destinationintel.community.SessionAuthenticator;
import io.destinationintel.community.UserRefreshFilter;
import io.destinationintel.community.UserRepository;
import io.destinationintel.community.UserRepository.AppUserRow;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Readers need no account: every public GET is open. Contribution endpoints require a
 * signed-in contributor; moderation requires trusted/steward roles and administration the
 * admin role. Roles come from the database on each request, never from the client.
 * Sessions are cookie-based (Spring Session JDBC) with SPA-style CSRF protection.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contextRepository,
                                    UserRepository users, OAuthProviders oauth, AccountService accounts,
                                    SessionAuthenticator sessions, AppProperties props, JsonMapper mapper) throws Exception {
        String publicUrl = props.publicUrl().replaceAll("/+$", "");
        http
                .securityContext(c -> c.securityContextRepository(contextRepository))
                .csrf(c -> c.spa())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .addFilterAfter(new UserRefreshFilter(users), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/requests", "/api/requests/*/choose", "/api/requests/*/notify").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search", "/api/destinations/**", "/api/facts/**",
                                "/api/sources/**", "/api/requests/**", "/api/meta", "/api/status/**", "/api/quality/**", "/api/trips/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/moderation/**").hasRole("TRUSTED")
                        .requestMatchers("/api/**").hasRole("CONTRIBUTOR")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(res, mapper, HttpStatus.UNAUTHORIZED,
                                "unauthenticated", "Please sign in to continue."))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                // The client refreshes its token and retries once on this code.
                                writeProblem(res, mapper, HttpStatus.FORBIDDEN, "invalid_csrf",
                                        "Your session token was missing or out of date. Please try again.");
                            } else {
                                writeProblem(res, mapper, HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to do that.");
                            }
                        }))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .deleteCookies("SESSION"));

        if (oauth.repository().isPresent()) {
            http.oauth2Login(o -> o
                    .clientRegistrationRepository(oauth.repository().get())
                    .successHandler((req, res, auth) -> {
                        try {
                            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) auth;
                            AppUserRow user = mapOAuthUser(accounts, token.getAuthorizedClientRegistrationId(), token.getPrincipal());
                            sessions.signIn(user, req, res);
                            res.sendRedirect(publicUrl + "/account?signedIn=1");
                        } catch (RuntimeException e) {
                            log.warn("OAuth sign-in could not be completed: {}", e.getMessage());
                            res.sendRedirect(publicUrl + "/signin?error=oauth");
                        }
                    })
                    .failureHandler((req, res, ex) -> res.sendRedirect(publicUrl + "/signin?error=oauth")));
        }
        return http.build();
    }

    /** Writes the same problem+json body as {@link GlobalExceptionHandler}, so every error has one shape. */
    static void writeProblem(HttpServletResponse res, JsonMapper mapper, HttpStatus status, String code, String detail)
            throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.problem(status, code, detail));
    }

    static AppUserRow mapOAuthUser(AccountService accounts, String provider, OAuth2User principal) {
        Map<String, Object> a = principal.getAttributes();
        if (provider.equals("google")) {
            return accounts.oauthSignIn("google", String.valueOf(a.get("sub")), (String) a.get("email"),
                    Boolean.TRUE.equals(a.get("email_verified")), (String) a.get("name"));
        }
        if (provider.equals("github")) {
            Object name = a.get("name") != null ? a.get("name") : a.get("login");
            // GitHub does not assert that the email is verified, so it is not used to link accounts.
            return accounts.oauthSignIn("github", String.valueOf(a.get("id")), null, false, name == null ? null : name.toString());
        }
        throw new IllegalStateException("Unsupported OAuth provider " + provider);
    }
}
