package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trust tiers. Promotion from contributor to trusted contributor and demotion back are
 * automatic and based only on decided records (accepted vs rejected or reverted work and
 * actioned abuse flags). City stewards are appointed by administrators, never automatically.
 */
@Service
public class TrustService {

    public record TrustStats(int accepted, int rejected, int reverted, int recentRejectedOrReverted, int recentDecided,
                             int actionedFlags, long accountAgeDays) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final AppProperties.Trust config;

    public TrustService(JdbcClient jdbc, UserRepository users, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.config = props.trust();
    }

    public TrustStats stats(UUID userId) {
        OffsetDateTime windowStart = OffsetDateTime.now().minusDays(config.demoteWindowDays());
        // One round trip: a contributor's decided work (contributions and verifications) and actioned flags.
        return jdbc.sql("""
                        WITH work AS (
                            SELECT 'contribution' AS kind, status, decided_at AS decided FROM contribution WHERE user_id = :u
                            UNION ALL
                            SELECT 'verification', status, coalesce(decided_at, created_at) FROM verification WHERE user_id = :u)
                        SELECT
                            count(*) FILTER (WHERE status = 'accepted') AS accepted,
                            count(*) FILTER (WHERE status = 'rejected') AS rejected,
                            count(*) FILTER (WHERE status = 'reverted' AND kind = 'contribution') AS reverted,
                            count(*) FILTER (WHERE decided > :since AND (status = 'rejected'
                                             OR (status = 'reverted' AND kind = 'contribution'))) AS recent_bad,
                            count(*) FILTER (WHERE decided > :since AND (status IN ('accepted', 'rejected')
                                             OR (status = 'reverted' AND kind = 'contribution'))) AS recent_decided,
                            (SELECT count(*) FROM abuse_flag f
                             WHERE f.status = 'actioned' AND f.resolved_at > :since AND (
                                   (f.target_type = 'user' AND f.target_id = :u)
                                OR (f.target_type = 'contribution' AND f.target_id IN (SELECT id FROM contribution WHERE user_id = :u))
                                OR (f.target_type = 'verification' AND f.target_id IN (SELECT id FROM verification WHERE user_id = :u))))
                                AS flags,
                            (SELECT extract(day FROM now() - created_at)::bigint FROM app_user WHERE id = :u) AS account_age_days
                        FROM work
                        """).param("u", userId).param("since", windowStart)
                .query((rs, i) -> new TrustStats(rs.getInt("accepted"), rs.getInt("rejected"), rs.getInt("reverted"),
                        rs.getInt("recent_bad"), rs.getInt("recent_decided"), rs.getInt("flags"), rs.getLong("account_age_days")))
                .single();
    }

    /** Re-evaluates a contributor's tier after a decision on their work. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String evaluate(UUID userId) {
        AppUserRow user = users.findById(userId).orElse(null);
        if (user == null || !user.active() || "steward".equals(user.tier())) {
            return user == null ? null : user.tier();
        }
        TrustStats s = stats(userId);
        int decided = s.accepted() + s.rejected() + s.reverted();
        double badRatio = decided == 0 ? 0 : (double) (s.rejected() + s.reverted()) / decided;
        double recentRatio = s.recentDecided() == 0 ? 0 : (double) s.recentRejectedOrReverted() / s.recentDecided();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accepted", s.accepted());
        details.put("rejected", s.rejected());
        details.put("reverted", s.reverted());
        details.put("actionedFlags", s.actionedFlags());

        if ("contributor".equals(user.tier())
                && s.accepted() >= config.promoteMinAccepted()
                && badRatio <= config.promoteMaxRejectionRatio()
                && s.accountAgeDays() >= config.promoteMinAccountAgeDays()
                && s.actionedFlags() == 0) {
            users.setTier(userId, "trusted");
            String reason = s.accepted() + " accepted contributions with a "
                    + Math.round(badRatio * 100) + "% rejection rate";
            users.recordTrustEvent(userId, "promoted", "contributor", "trusted", reason, null);
            audit.record(null, "user.promoted", "user", userId, null, details);
            return "trusted";
        }
        if ("trusted".equals(user.tier())
                && (s.actionedFlags() > 0
                || s.recentRejectedOrReverted() >= config.demoteMinRejected() && recentRatio >= config.demoteRejectionRatio())) {
            users.setTier(userId, "contributor");
            String reason = s.actionedFlags() > 0 ? "An abuse report against this account was upheld"
                    : s.recentRejectedOrReverted() + " rejected or reverted contributions in the last "
                    + config.demoteWindowDays() + " days";
            users.recordTrustEvent(userId, "demoted", "trusted", "contributor", reason, null);
            audit.record(null, "user.demoted", "user", userId, null, details);
            return "contributor";
        }
        return user.tier();
    }

    /** Whether a steward or admin may moderate content for this destination. */
    public boolean canModerateDestination(AppUserRow user, UUID destinationId) {
        if (user.isAdmin()) {
            return true;
        }
        if (!"steward".equals(user.tier()) || destinationId == null) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM steward_assignment sa
                            WHERE sa.user_id = :u AND (sa.place_id = :d OR EXISTS (
                                SELECT 1 FROM place_relation r WHERE r.child_id = :d AND r.parent_id = sa.place_id)))
                        """)
                .param("u", user.id()).param("d", destinationId).query(Boolean.class).single();
    }

}
