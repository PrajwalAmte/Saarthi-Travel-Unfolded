package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import io.destinationintel.moderation.ModerationQueue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Abuse reports (spam, self-promotion, offensive or misleading content) for stewards to review. */
@Service
public class FlagService {

    public record FlagRequest(
            @NotBlank @Pattern(regexp = "contribution|verification|entry|fact|user") String targetType,
            @NotNull UUID targetId,
            @NotBlank @Pattern(regexp = "spam|self_promotion|offensive|misleading|other") String reason,
            @Size(max = 1000) String details) {}

    private final JdbcClient jdbc;
    private final ModerationQueue queue;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public FlagService(JdbcClient jdbc, ModerationQueue queue, AuditService audit, RateLimiter rateLimiter, AppProperties props) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @Transactional
    public UUID flag(AppUserRow user, FlagRequest req) {
        rateLimiter.check("flag", user.id().toString(), props.rateLimits().flagsPerHour(), Duration.ofHours(1));
        UUID destination = destinationOf(req.targetType(), req.targetId());
        if (destination == null && !req.targetType().equals("user")) {
            throw ApiException.notFound("Flagged item");
        }
        if (req.targetType().equals("user") && req.targetId().equals(user.id())) {
            throw ApiException.badRequest("invalid_target", "You cannot flag your own account.");
        }
        boolean already = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM abuse_flag WHERE reporter_user_id = :u AND target_type = :t
                                       AND target_id = :id AND status = 'open')
                        """)
                .param("u", user.id()).param("t", req.targetType()).param("id", req.targetId()).query(Boolean.class).single();
        if (already) {
            throw ApiException.conflict("already_flagged", "You have already reported this item.");
        }
        UUID id = jdbc.sql("""
                        INSERT INTO abuse_flag (reporter_user_id, target_type, target_id, reason, details)
                        VALUES (:u, :t, :id, :reason, :details) RETURNING id
                        """)
                .param("u", user.id()).param("t", req.targetType()).param("id", req.targetId())
                .param("reason", req.reason()).param("details", req.details() == null ? null : req.details().trim())
                .query(UUID.class).single();
        queue.open("abuse_flag", id, destination, List.of("abuse_report:" + req.reason()), "steward", 8);
        audit.record(user.id(), "flag.created", req.targetType(), req.targetId(), destination, Map.of("reason", req.reason()));
        return id;
    }

    UUID destinationOf(String type, UUID id) {
        String sql = switch (type) {
            case "contribution" -> "SELECT destination_id FROM contribution WHERE id = :id";
            case "verification" -> "SELECT f.destination_id FROM verification v JOIN fact f ON f.id = v.fact_id WHERE v.id = :id";
            case "entry" -> "SELECT destination_id FROM guide_entry WHERE id = :id";
            case "fact" -> "SELECT destination_id FROM fact WHERE id = :id";
            case "user" -> null;
            default -> throw ApiException.badRequest("invalid_target", "Unknown target type.");
        };
        if (sql == null) {
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id)").param("id", id)
                    .query(Boolean.class).single();
            if (!exists) {
                throw ApiException.notFound("User");
            }
            return null;
        }
        return jdbc.sql(sql).param("id", id).query(UUID.class).optional().orElse(null);
    }
}
