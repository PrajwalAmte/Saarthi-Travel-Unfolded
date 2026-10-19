package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.common.UrlSafety;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.GuideChanges;
import io.destinationintel.moderation.ModerationQueue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * "Still true?" verifications. Every click creates a verification record; counts shown to
 * readers are aggregated from accepted records only. Volatile facts and denials from new
 * contributors need a second, independent contributor (or a reviewer) before they count.
 */
@Service
public class VerificationService {

    static final int REVERIFY_AFTER_DAYS = 30;

    public record VerifyRequest(
            @NotBlank @Pattern(regexp = "confirm|deny") String verdict,
            @Size(max = 500) String note,
            @Size(max = 2000) String evidenceUrl) {}

    public record VerifyResult(UUID id, String status, String message) {}

    private record FactInfo(UUID id, UUID destinationId, String status, String volatility, UUID contributionId) {}

    private final JdbcClient jdbc;
    private final ModerationQueue queue;
    private final AuditService audit;
    private final GuideChanges changes;
    private final RateLimiter rateLimiter;
    private final TrustService trust;
    private final AppProperties props;

    public VerificationService(JdbcClient jdbc, ModerationQueue queue, AuditService audit, GuideChanges changes,
                               RateLimiter rateLimiter, TrustService trust, AppProperties props) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.audit = audit;
        this.changes = changes;
        this.rateLimiter = rateLimiter;
        this.trust = trust;
        this.props = props;
    }

    @Transactional
    public VerifyResult verify(AppUserRow user, UUID factId, VerifyRequest req) {
        rateLimiter.check("verification", user.id().toString(), props.rateLimits().verificationsPerHour(), Duration.ofHours(1));
        FactInfo fact = jdbc.sql("SELECT id, destination_id, status, volatility, contribution_id FROM fact WHERE id = :id FOR UPDATE")
                .param("id", factId).query(FactInfo.class).optional()
                .orElseThrow(() -> ApiException.notFound("Fact"));
        if (!fact.status().equals("published")) {
            throw ApiException.conflict("fact_not_current", "This fact has been replaced or removed, so it can no longer be verified.");
        }
        if (fact.contributionId() != null) {
            UUID author = jdbc.sql("SELECT user_id FROM contribution WHERE id = :c").param("c", fact.contributionId())
                    .query(UUID.class).optional().orElse(null);
            if (user.id().equals(author)) {
                throw ApiException.conflict("own_contribution", "You cannot verify a fact you contributed yourself.");
            }
        }
        boolean recent = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM verification WHERE fact_id = :f AND user_id = :u
                                       AND status IN ('pending', 'accepted')
                                       AND created_at > now() - make_interval(days => :days))
                        """)
                .param("f", factId).param("u", user.id()).param("days", REVERIFY_AFTER_DAYS).query(Boolean.class).single();
        if (recent) {
            throw ApiException.conflict("already_verified",
                    "You have already responded for this fact in the last " + REVERIFY_AFTER_DAYS + " days.");
        }
        String evidence = null;
        if (StringUtils.hasText(req.evidenceUrl())) {
            evidence = UrlSafety.normalize(req.evidenceUrl()).orElseThrow(() -> ApiException.badRequest("invalid_url",
                    "The evidence link must be a public http(s) address."));
        }
        String note = StringUtils.hasText(req.note()) ? req.note().trim() : null;

        boolean trusted = user.atLeastTrusted();
        boolean needsSecond = !trusted && (req.verdict().equals("deny") || "volatile".equals(fact.volatility()));
        Optional<UUID> corroborating = Optional.empty();
        if (needsSecond) {
            corroborating = jdbc.sql("""
                            SELECT id FROM verification
                            WHERE fact_id = :f AND verdict = :v AND status = 'pending' AND user_id IS DISTINCT FROM :u
                              AND created_at > now() - make_interval(days => :days)
                            ORDER BY created_at LIMIT 1
                            """)
                    .param("f", factId).param("v", req.verdict()).param("u", user.id()).param("days", REVERIFY_AFTER_DAYS)
                    .query(UUID.class).optional();
        }
        boolean accept = !needsSecond || corroborating.isPresent();
        UUID id = jdbc.sql("""
                        INSERT INTO verification (fact_id, user_id, verdict, note, evidence_url, status, decision_reason, decided_at)
                        VALUES (:f, :u, :v, :note, :url, :status, :reason, CASE WHEN :accept THEN now() END)
                        RETURNING id
                        """)
                .param("f", factId).param("u", user.id()).param("v", req.verdict()).param("note", note)
                .param("url", evidence).param("status", accept ? "accepted" : "pending")
                .param("reason", !accept ? null : trusted ? "Accepted from a trusted contributor"
                        : corroborating.isPresent() ? "Independently corroborated by a second contributor"
                        : "Accepted confirmation")
                .param("accept", accept)
                .query(UUID.class).single();
        audit.record(user.id(), "verification.submitted", "fact", factId, fact.destinationId(),
                Map.of("verification", id, "verdict", req.verdict(), "status", accept ? "accepted" : "pending"));

        if (corroborating.isPresent()) {
            UUID other = corroborating.get();
            jdbc.sql("""
                            UPDATE verification SET status = 'accepted', decided_at = now(),
                                   decision_reason = 'Independently corroborated by a second contributor'
                            WHERE id = :id
                            """).param("id", other).update();
            queue.resolve("verification", other, "accepted: corroborated", null);
            audit.record(null, "verification.accepted", "fact", factId, fact.destinationId(),
                    Map.of("verification", other, "reason", "corroborated"));
            UUID otherUser = jdbc.sql("SELECT user_id FROM verification WHERE id = :id").param("id", other)
                    .query(UUID.class).optional().orElse(null);
            if (otherUser != null) {
                trust.evaluate(otherUser);
            }
        }
        if (accept) {
            if (req.verdict().equals("confirm")) {
                jdbc.sql("UPDATE fact SET last_verified_at = now(), updated_at = now() WHERE id = :f").param("f", factId).update();
            }
            trust.evaluate(user.id());
            changes.destinationChanged(fact.destinationId());
            return new VerifyResult(id, "accepted", req.verdict().equals("confirm")
                    ? "Thanks! Your confirmation is recorded." : "Thanks! Your report is recorded and shown on this fact.");
        }
        queue.open("verification", id, fact.destinationId(),
                List.of(req.verdict().equals("deny") ? "denial" : "volatile_fact", "needs_second_contributor"), "trusted", 3);
        return new VerifyResult(id, "pending", "Thanks! This will count once another contributor agrees or a reviewer accepts it.");
    }
}
