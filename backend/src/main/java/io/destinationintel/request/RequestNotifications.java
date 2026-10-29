package io.destinationintel.request;

import io.destinationintel.common.ApiException;
import io.destinationintel.community.MailService;
import io.destinationintel.config.AppProperties;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optional "email me when it is ready" for destination requests. Opt-in only; the address is kept
 * just until the one email has been sent, then erased. Nothing else is ever sent to it.
 */
@Service
public class RequestNotifications {

    private static final Logger log = LoggerFactory.getLogger(RequestNotifications.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}$");
    private static final int MAX_PENDING_PER_REQUEST = 500;
    private static final int MAX_ATTEMPTS = 5;
    static final int RETENTION_DAYS = 90;

    private record Due(UUID id, String email, String trackingCode, String queryText, String requestStatus,
                       String placeName, String placeSlug, String coverage, String placeKind, int attempts) {}

    private final JdbcClient jdbc;
    private final MailService mail;
    private final AppProperties props;

    public RequestNotifications(JdbcClient jdbc, MailService mail, AppProperties props) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.props = props;
    }

    public boolean available() {
        return mail.configured();
    }

    /** Normalises and validates an address; returns null when the reader left it blank. */
    static String clean(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("invalid_email", "Enter a valid email address, or leave it blank.");
        }
        return email;
    }

    /** Records the opt-in. Asking twice with the same address is harmless. */
    @Transactional
    public void subscribe(UUID requestId, String email) {
        if (!available()) {
            throw ApiException.unavailable("email_unavailable", "Email updates are not available on this server right now.");
        }
        long pending = jdbc.sql("SELECT count(*) FROM request_notification WHERE request_id = :r AND email IS NOT NULL")
                .param("r", requestId).query(Long.class).single();
        if (pending >= MAX_PENDING_PER_REQUEST) {
            throw ApiException.conflict("too_many_subscribers", "Too many people are already waiting on this request.");
        }
        jdbc.sql("""
                        INSERT INTO request_notification (request_id, email) VALUES (:r, :e)
                        ON CONFLICT (request_id, email) WHERE email IS NOT NULL DO NOTHING""")
                .param("r", requestId).param("e", email).update();
    }

    /** Sends the emails that are due, erasing each address as soon as its email has gone out. */
    @Transactional
    public int deliverDue() {
        if (!available()) {
            return 0;
        }
        jdbc.sql("""
                        UPDATE request_notification SET email = NULL, outcome = 'expired', resolved_at = now()
                        WHERE email IS NOT NULL AND created_at < now() - make_interval(days => :d)""")
                .param("d", RETENTION_DAYS).update();
        List<Due> due = jdbc.sql("""
                        SELECT n.id, n.email, r.tracking_code, r.query_text, r.status AS request_status,
                               p.name AS place_name, p.slug AS place_slug, dp.coverage_status AS coverage, p.kind AS place_kind,
                               n.attempts
                        FROM request_notification n
                        JOIN destination_request r ON r.id = n.request_id
                        LEFT JOIN destination_profile dp ON dp.place_id = r.resolved_place_id
                        LEFT JOIN place p ON p.id = r.resolved_place_id
                        WHERE n.email IS NOT NULL AND n.next_attempt_at <= now()
                          AND (r.status = 'unresolvable'
                               OR (dp.coverage_status IS NOT NULL AND dp.coverage_status <> 'indexed')
                               OR (p.kind IN ('state', 'union_territory') AND EXISTS (
                                     SELECT 1 FROM place_relation pr
                                     JOIN destination_profile cdp ON cdp.place_id = pr.child_id AND cdp.coverage_status <> 'indexed'
                                     WHERE pr.parent_id = p.id AND pr.relation = 'in_state')))
                        ORDER BY n.created_at LIMIT 20
                        FOR UPDATE OF n SKIP LOCKED""")
                .query(Due.class).list();
        int sent = 0;
        for (Due d : due) {
            boolean notFound = d.requestStatus().equals("unresolvable");
            try {
                mail.send(d.email(), subject(d, notFound), body(d, notFound));
                jdbc.sql("""
                                UPDATE request_notification SET email = NULL, outcome = :o, resolved_at = now(),
                                       attempts = attempts + 1, last_error = NULL
                                WHERE id = :id""")
                        .param("o", notFound ? "sent_not_found" : "sent_published").param("id", d.id()).update();
                sent++;
            } catch (RuntimeException e) {
                int attempts = d.attempts() + 1;
                log.warn("Request notification {} could not be sent (attempt {}): {}", d.id(), attempts, e.getClass().getSimpleName());
                if (attempts >= MAX_ATTEMPTS) {
                    jdbc.sql("""
                                    UPDATE request_notification SET email = NULL, outcome = 'failed', resolved_at = now(),
                                           attempts = :a, last_error = :err WHERE id = :id""")
                            .param("a", attempts).param("err", e.getClass().getSimpleName()).param("id", d.id()).update();
                } else {
                    jdbc.sql("""
                                    UPDATE request_notification SET attempts = :a, last_error = :err,
                                           next_attempt_at = now() + make_interval(mins => :wait) WHERE id = :id""")
                            .param("a", attempts).param("err", e.getClass().getSimpleName())
                            .param("wait", 10 * attempts).param("id", d.id()).update();
                }
            }
        }
        return sent;
    }

    private String subject(Due d, boolean notFound) {
        if (notFound) {
            return "We could not find “" + d.queryText() + "” on Saarthi";
        }
        if ("state".equals(d.placeKind()) || "union_territory".equals(d.placeKind())) {
            return "Guides for places in " + d.placeName();
        }
        return "building".equals(d.coverage()) ? "A starter guide for " + d.placeName() + " is ready"
                : "Your guide for " + d.placeName() + " is ready";
    }

    private String body(Due d, boolean notFound) {
        String base = props.publicUrl().replaceAll("/+$", "");
        String footer = "\n\nYou asked us to email you about this request. This is the only email we will send about it, "
                + "and your address has now been removed from our records.\n\nSaarthi — Travel Unfolded";
        if (notFound) {
            return "Hello,\n\nWe looked for a place called “" + d.queryText() + "” in India and could not find it. "
                    + "Check the spelling, or request it again with the state added to narrow it down:\n\n"
                    + base + "/request?q=" + java.net.URLEncoder.encode(d.queryText(), java.nio.charset.StandardCharsets.UTF_8)
                    + footer;
        }
        if ("state".equals(d.placeKind()) || "union_territory".equals(d.placeKind())) {
            return "Hello,\n\n" + d.placeName() + " is a " + ("state".equals(d.placeKind()) ? "state" : "union territory")
                    + ", so its guide is made of guides for the places in it. Start here and pick a town or city:\n\n"
                    + base + "/d/" + d.placeSlug() + footer;
        }
        if ("building".equals(d.coverage())) {
            return "Hello,\n\nWe imported everything open travel guides have on " + d.placeName() + ". There is not much yet, "
                    + "so it is a starter guide: a place to begin, not a complete guide.\n\n" + base + "/d/" + d.placeSlug()
                    + "\n\nIf you know " + d.placeName() + ", you can add the places it is missing:\n" + base + "/contribute/"
                    + d.placeSlug() + footer;
        }
        return "Hello,\n\nThe guide you asked for is ready: " + d.placeName() + ".\n\n" + base + "/d/" + d.placeSlug()
                + "\n\nIt is built from open sources, and every tip shows where it came from. "
                + "If something is out of date, you can correct it right on the page."
                + footer;
    }
}
