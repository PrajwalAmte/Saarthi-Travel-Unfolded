package io.destinationintel.common;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Validation for URLs that are stored and rendered as links (source links, evidence
 * links, business websites). Only absolute http(s) URLs with a plausible public host and
 * no embedded credentials are accepted. The platform never fetches user-supplied URLs.
 */
public final class UrlSafety {

    private static final int MAX_LENGTH = 2000;

    private UrlSafety() {}

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        } else if (!candidate.contains("://") && candidate.matches("^(www\\.)?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return Optional.empty();
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }
            if (uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return Optional.empty();
            }
            String asciiHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
            if (!asciiHost.contains(".") || asciiHost.equals("localhost") || asciiHost.endsWith(".localhost")
                    || asciiHost.endsWith(".local") || asciiHost.endsWith(".internal")
                    || asciiHost.matches("^[0-9.]+$") || asciiHost.startsWith("[")) {
                return Optional.empty();
            }
            return Optional.of(uri.normalize().toString());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isSafe(String raw) {
        return normalize(raw).isPresent();
    }
}
