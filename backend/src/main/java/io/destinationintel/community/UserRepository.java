package io.destinationintel.community;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record AppUserRow(
            UUID id,
            String displayName,
            String email,
            OffsetDateTime emailVerifiedAt,
            String tier,
            boolean isAdmin,
            String attribution,
            String contributorLicence,
            OffsetDateTime contributorLicenceAt,
            String status,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt) {

        public boolean active() {
            return "active".equals(status);
        }

        public boolean atLeastTrusted() {
            return isAdmin || "trusted".equals(tier) || "steward".equals(tier);
        }

        public boolean steward() {
            return isAdmin || "steward".equals(tier);
        }
    }

    private static final String COLUMNS = """
            id, display_name, email, email_verified_at, tier, is_admin, attribution, contributor_licence,
            contributor_licence_at, status, last_login_at, created_at""";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUserRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = :id").param("id", id)
                .query(AppUserRow.class).optional();
    }

    public Optional<AppUserRow> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE email = :email").param("email", email)
                .query(AppUserRow.class).optional();
    }

    public Optional<AppUserRow> findByIdentity(String provider, String subject) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM app_user
                        WHERE id = (SELECT user_id FROM user_identity WHERE provider = :provider AND subject = :subject)""")
                .param("provider", provider).param("subject", subject)
                .query(AppUserRow.class).optional();
    }

    public UUID create(String displayName, String email, boolean emailVerified) {
        return jdbc.sql("""
                        INSERT INTO app_user (display_name, email, email_verified_at)
                        VALUES (:name, :email, CASE WHEN :verified THEN now() END)
                        RETURNING id
                        """)
                .param("name", displayName).param("email", email).param("verified", emailVerified)
                .query(UUID.class).single();
    }

    public void linkIdentity(UUID userId, String provider, String subject) {
        jdbc.sql("""
                        INSERT INTO user_identity (user_id, provider, subject) VALUES (:u, :p, :s)
                        ON CONFLICT (provider, subject) DO NOTHING
                        """)
                .param("u", userId).param("p", provider).param("s", subject).update();
    }

    public void recordLogin(UUID userId) {
        jdbc.sql("UPDATE app_user SET last_login_at = now(), updated_at = now() WHERE id = :id").param("id", userId).update();
    }

    public void markEmailVerified(UUID userId) {
        jdbc.sql("UPDATE app_user SET email_verified_at = coalesce(email_verified_at, now()) WHERE id = :id")
                .param("id", userId).update();
    }

    public boolean grantAdmin(UUID userId) {
        return jdbc.sql("UPDATE app_user SET is_admin = true, updated_at = now() WHERE id = :id AND NOT is_admin")
                .param("id", userId).update() > 0;
    }

    public void updateProfile(UUID userId, String displayName, String attribution) {
        jdbc.sql("""
                        UPDATE app_user SET display_name = coalesce(:name, display_name),
                               attribution = coalesce(:attribution, attribution), updated_at = now()
                        WHERE id = :id
                        """)
                .param("name", displayName).param("attribution", attribution).param("id", userId).update();
    }

    public void acceptLicence(UUID userId, String licence) {
        jdbc.sql("UPDATE app_user SET contributor_licence = :l, contributor_licence_at = now(), updated_at = now() WHERE id = :id")
                .param("l", licence).param("id", userId).update();
    }

    public void setTier(UUID userId, String tier) {
        jdbc.sql("UPDATE app_user SET tier = :tier, updated_at = now() WHERE id = :id")
                .param("tier", tier).param("id", userId).update();
    }

    public void setStatus(UUID userId, String status) {
        jdbc.sql("UPDATE app_user SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status).param("id", userId).update();
    }

    /** Removes personal data while keeping the row so licensed contributions stay attributable as anonymous. */
    public void anonymize(UUID userId) {
        jdbc.sql("""
                        UPDATE app_user SET display_name = 'Former contributor', email = NULL, email_verified_at = NULL,
                               attribution = 'anonymous', status = 'deleted', updated_at = now()
                        WHERE id = :id
                        """).param("id", userId).update();
        jdbc.sql("DELETE FROM user_identity WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM steward_assignment WHERE user_id = :id").param("id", userId).update();
    }

    public List<AppUserRow> search(String query, int limit) {
        String like = "%" + query.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM app_user WHERE status <> 'deleted'
                          AND (lower(display_name) LIKE :q ESCAPE '\\' OR email LIKE :q ESCAPE '\\')
                        ORDER BY created_at DESC LIMIT :limit""")
                .param("q", like).param("limit", limit).query(AppUserRow.class).list();
    }

    public void recordTrustEvent(UUID userId, String type, String fromTier, String toTier, String reason, UUID actor) {
        jdbc.sql("""
                        INSERT INTO trust_event (user_id, event_type, from_tier, to_tier, reason, actor_user_id)
                        VALUES (:u, :t, :from, :to, :reason, :actor)
                        """)
                .param("u", userId).param("t", type).param("from", fromTier).param("to", toTier)
                .param("reason", reason).param("actor", actor).update();
    }

    public List<UUID> stewardPlaces(UUID userId) {
        return jdbc.sql("SELECT place_id FROM steward_assignment WHERE user_id = :u").param("u", userId)
                .query(UUID.class).list();
    }
}
