package io.destinationintel.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides AI flags without an admin when the evidence is strong, so only genuinely doubtful listings wait for a
 * person. Every automatic decision is recorded and can be undone in one click.
 *
 * <ul>
 *   <li>Wrong section, a real section suggested, at least 80% sure: <b>move</b> it (nothing is lost).</li>
 *   <li>Not a place to list (ATM, forex office), says it closed, or in another city, at least 90% sure: <b>hide</b> it,
 *       unless its own map location puts it inside the destination (then it is <b>kept</b>).</li>
 *   <li>Only a text problem (broken text, describes another business): <b>noted</b>, the listing stays.</li>
 *   <li>Anything less certain: left open for an admin.</li>
 * </ul>
 */
@Component
public class FlagDecider {

    private static final Logger log = LoggerFactory.getLogger(FlagDecider.class);
    static final double MOVE_CONFIDENCE = 0.8;
    static final double HIDE_CONFIDENCE = 0.9;
    /** A listing whose coordinates are within this distance is in the destination, whatever its text suggests. */
    static final double IN_DESTINATION_METRES = 30_000;

    public enum Decision { MOVE, HIDE, KEEP, NOTE, ASK }

    record Candidate(UUID id, String section, boolean fitsSection, String inDestination, String issues, String suggestedSection,
                     double confidence, Double distanceM) {}

    private final JdbcClient jdbc;
    private final ListingFixes fixes;
    private final AiProperties props;

    public FlagDecider(JdbcClient jdbc, ListingFixes fixes, AiProperties props) {
        this.jdbc = jdbc;
        this.fixes = fixes;
        this.props = props;
    }

    static Decision decide(String section, boolean fitsSection, String inDestination, List<String> issues, String suggested,
                           double confidence, Double distanceM) {
        boolean closed = issues.contains("closed");
        boolean elsewhere = "no".equals(inDestination) || issues.contains("outside_destination");
        boolean wrongSection = issues.contains("wrong_section") || !fitsSection;
        boolean notAPlace = wrongSection && "none".equals(suggested);
        if ((closed || elsewhere || notAPlace) && confidence >= HIDE_CONFIDENCE) {
            if (elsewhere && !closed && !notAPlace && distanceM != null && distanceM <= IN_DESTINATION_METRES) {
                return Decision.KEEP;
            }
            return Decision.HIDE;
        }
        if (wrongSection && !closed && !elsewhere && suggested != null && ListingFixes.SECTIONS.contains(suggested)
                && !suggested.equals(section) && confidence >= MOVE_CONFIDENCE) {
            return Decision.MOVE;
        }
        if (!wrongSection && !closed && !elsewhere
                && (issues.contains("broken_text") || issues.contains("about_another_business"))) {
            return Decision.NOTE;
        }
        return Decision.ASK;
    }

    /** Decides one open flag if it is clear enough; returns what was done. */
    public Optional<Decision> apply(UUID flagId) {
        Candidate c = jdbc.sql("""
                        SELECT r.id, e.section, r.fits_section, r.in_destination, array_to_string(r.issues, ',') AS issues,
                               r.suggested_section, r.confidence,
                               CASE WHEN e.geog IS NOT NULL AND p.geog IS NOT NULL THEN ST_Distance(e.geog, p.geog) END AS distance_m
                        FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id JOIN place p ON p.id = r.destination_id
                        WHERE r.id = :id AND r.status = 'open' AND e.status = 'published'""")
                .param("id", flagId).query(Candidate.class).optional().orElse(null);
        if (c == null) {
            return Optional.empty();
        }
        List<String> issues = c.issues() == null || c.issues().isEmpty() ? List.of() : List.of(c.issues().split(","));
        Decision d = decide(c.section(), c.fitsSection(), c.inDestination(), issues, c.suggestedSection(), c.confidence(), c.distanceM());
        switch (d) {
            case MOVE -> fixes.move(flagId, c.suggestedSection(), null, true);
            case HIDE -> fixes.hide(flagId, null, true);
            case KEEP -> fixes.keep(flagId, null, true, false);
            case NOTE -> fixes.keep(flagId, null, true, true);
            case ASK -> {
                return Optional.of(Decision.ASK);
            }
        }
        return Optional.of(d);
    }

    /** Decides every open flag that is clear enough; the rest stay for an admin. */
    public int sweep() {
        int decided = 0;
        for (UUID id : jdbc.sql("SELECT id FROM ai_review WHERE status = 'open' ORDER BY created_at").query(UUID.class).list()) {
            try {
                if (apply(id).filter(d -> d != Decision.ASK).isPresent()) {
                    decided++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not decide AI flag {}: {}", id, e.toString());
            }
        }
        return decided;
    }

    /** Catches flags created before these rules, or left over by an interrupted review. Runs where AI review is on. */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT2M")
    public void scheduledSweep() {
        if (!props.active()) {
            return;
        }
        int n = sweep();
        if (n > 0) {
            log.info("Decided {} AI flags automatically", n);
        }
    }
}
