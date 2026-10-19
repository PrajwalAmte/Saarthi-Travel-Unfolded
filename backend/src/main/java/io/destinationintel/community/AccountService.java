package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Contributor accounts: passwordless email sign-in, OAuth sign-in mapping, admin bootstrap
 * from configuration, and account deletion. Readers never need any of this.
 */
@Service
public class AccountService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final MailService mail;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public AccountService(UserRepository users, MailService mail, RateLimiter rateLimiter, AuditService audit,
                          JdbcClient jdbc, AppProperties props) {
        this.users = users;
        this.mail = mail;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Sends a single-use sign-in link. The response never reveals whether an account exists. */
    @Transactional
    public void startEmailSignIn(String rawEmail, String displayName, String clientIp) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        int perHour = props.rateLimits().signInPerHour();
        rateLimiter.check("signin-email", email, perHour, Duration.ofHours(1));
        rateLimiter.check("signin-ip", clientIp, perHour * 4, Duration.ofHours(1));
        if (!mail.configured()) {
            throw ApiException.unavailable("email_unavailable",
                    "Email sign-in is not configured on this server. Ask the operator to set SMTP_HOST.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO login_token (email, token_hash, display_name, expires_at)
                        VALUES (:email, :hash, :name, now() + make_interval(mins => :mins))
                        """)
                .param("email", email).param("hash", sha256(token))
                .param("name", StringUtils.hasText(displayName) ? displayName.trim() : null)
                .param("mins", (int) TOKEN_TTL.toMinutes()).update();
        String link = props.publicUrl().replaceAll("/+$", "") + "/auth/verify#token=" + token;
        mail.send(email, "Your sign-in link for Saarthi Travel Unfolded", """
                Hello,

                Use this link to sign in as a contributor. It works once and expires in %d minutes:

                %s

                If you did not ask to sign in, you can ignore this email.
                """.formatted(TOKEN_TTL.toMinutes(), link));
    }

    @Transactional
    public AppUserRow verifyEmailSignIn(String token) {
        if (!StringUtils.hasText(token) || token.length() > 200) {
            throw ApiException.badRequest("invalid_token", "This sign-in link is invalid or has expired.");
        }
        record Consumed(String email, String displayName) {}
        Optional<Consumed> consumed = jdbc.sql("""
                        UPDATE login_token SET consumed_at = now()
                        WHERE token_hash = :hash AND consumed_at IS NULL AND expires_at > now()
                        RETURNING email, display_name
                        """)
                .param("hash", sha256(token)).query(Consumed.class).optional();
        if (consumed.isEmpty()) {
            throw ApiException.badRequest("invalid_token", "This sign-in link is invalid or has expired.");
        }
        String email = consumed.get().email();
        AppUserRow user = users.findByEmail(email).orElseGet(() -> {
            String name = consumed.get().displayName() != null ? consumed.get().displayName() : generatedName();
            UUID id = users.create(name, email, true);
            audit.record(id, "user.created", "user", id, null, Map.of("provider", "email"));
            return users.findById(id).orElseThrow();
        });
        users.markEmailVerified(user.id());
        users.linkIdentity(user.id(), "email", email);
        return completeSignIn(user.id(), "email");
    }

    /** Maps an OAuth identity to a contributor account, creating one if needed. */
    @Transactional
    public AppUserRow oauthSignIn(String provider, String subject, String email, boolean emailVerified, String name) {
        Optional<AppUserRow> existing = users.findByIdentity(provider, subject);
        AppUserRow user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
            Optional<AppUserRow> byEmail = emailVerified && normalizedEmail != null
                    ? users.findByEmail(normalizedEmail) : Optional.empty();
            if (byEmail.isPresent()) {
                user = byEmail.get();
            } else {
                String display = StringUtils.hasText(name) ? truncate(name.trim(), 60) : generatedName();
                UUID id = users.create(display, emailVerified ? normalizedEmail : null, emailVerified && normalizedEmail != null);
                audit.record(id, "user.created", "user", id, null, Map.of("provider", provider));
                user = users.findById(id).orElseThrow();
            }
            users.linkIdentity(user.id(), provider, subject);
        }
        return completeSignIn(user.id(), provider);
    }

    private AppUserRow completeSignIn(UUID userId, String provider) {
        AppUserRow user = users.findById(userId).orElseThrow();
        if (!user.active()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "account_suspended",
                    "This contributor account is suspended. Contact the maintainers if you think this is a mistake.");
        }
        if (user.email() != null && user.emailVerifiedAt() != null && props.adminEmails().contains(user.email())
                && users.grantAdmin(user.id())) {
            users.recordTrustEvent(user.id(), "admin_granted", user.tier(), user.tier(),
                    "Email listed in APP_ADMIN_EMAILS", null);
            audit.record(null, "user.admin_granted", "user", user.id(), null, Map.of("reason", "configured admin email"));
        }
        users.recordLogin(user.id());
        audit.record(user.id(), "user.signed_in", "user", user.id(), null, Map.of("provider", provider));
        return users.findById(user.id()).orElseThrow();
    }

    /** Deletes personal data; licensed contributions remain, attributed anonymously. */
    @Transactional
    public void deleteAccount(UUID userId) {
        int withdrawn = jdbc.sql("""
                        UPDATE contribution SET status = 'withdrawn', updated_at = now()
                        WHERE user_id = :u AND status = 'pending'
                        """).param("u", userId).update();
        jdbc.sql("UPDATE verification SET status = 'withdrawn' WHERE user_id = :u AND status = 'pending'")
                .param("u", userId).update();
        jdbc.sql("""
                        UPDATE moderation_item SET status = 'resolved', resolution = 'withdrawn', resolved_at = now()
                        WHERE status = 'open' AND ((item_type = 'contribution' AND item_id IN (SELECT id FROM contribution WHERE user_id = :u))
                           OR (item_type = 'verification' AND item_id IN (SELECT id FROM verification WHERE user_id = :u)))
                        """).param("u", userId).update();
        jdbc.sql("DELETE FROM login_token WHERE email = (SELECT email FROM app_user WHERE id = :u)").param("u", userId).update();
        users.anonymize(userId);
        audit.record(userId, "user.deleted", "user", userId, null, Map.of("pendingWithdrawn", withdrawn));
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String generatedName() {
        return "Contributor " + (1000 + RANDOM.nextInt(9000));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
