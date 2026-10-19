package io.destinationintel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class WebConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Saarthi — Travel Unfolded API")
                .version("0.1.0")
                .description("Source-backed, community-verified destination guides for India. Public reads need no "
                        + "account; contributions require a contributor session and a CSRF token from /api/auth/session.")
                .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }

    /** Correlates log lines with a request id, echoed back in the X-Request-Id response header. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> requestIdFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String incoming = request.getHeader("X-Request-Id");
                String id = incoming != null && incoming.matches("^[A-Za-z0-9-]{8,64}$") ? incoming
                        : UUID.randomUUID().toString().substring(0, 12);
                MDC.put("requestId", id);
                response.setHeader("X-Request-Id", id);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove("requestId");
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
