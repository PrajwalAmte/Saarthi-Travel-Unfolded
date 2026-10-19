package io.destinationintel.common;

import io.destinationintel.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the client address used for rate limiting. X-Forwarded-For is only honoured
 * when explicitly enabled, and then the right-most entry (appended by the trusted proxy)
 * is used so that clients cannot spoof it by sending their own header.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwarded;

    public ClientIpResolver(AppProperties props) {
        this.trustForwarded = props.security() != null && props.security().trustForwardedHeaders();
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwarded) {
            String xff = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
