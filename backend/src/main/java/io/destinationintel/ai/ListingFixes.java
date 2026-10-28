package io.destinationintel.ai;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.guide.GuideChanges;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three things a flag can lead to, and their undo. Used both by admins and by the automatic decisions, so a
 * decision is always recorded the same way (resolution, who, previous section) and can always be reversed.
 */
@Component
public class ListingFixes {

    public static final Set<String> SECTIONS = Set.of("eat", "drink", "stay", "see", "do", "buy");
    static final String HIDDEN_PREFIX = "Hidden after review: ";

    record Flag(UUID id, UUID entryId, UUID destinationId, String section, String reason, String status, String resolution,
                String previousSection) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final GuideChanges changes;

    public ListingFixes(JdbcClient jdbc, AuditService audit, GuideChanges changes) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.changes = changes;
    }

    Flag flag(UUID id) {
        return jdbc.sql("""
                        SELECT r.id, r.entry_id, r.destination_id, e.section, r.reason, r.status, r.resolution, r.previous_section
                        FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id WHERE r.id = :id""")
                .param("id", id).query(Flag.class).optional().orElseThrow(() -> ApiException.notFound("Flag"));
    }

    /** Moves the listing (and its facts) to another section. The override survives later re-imports. */
    @Transactional
    public void move(UUID flagId, String to, UUID admin, boolean automatic) {
        Flag f = flag(flagId);
        if (!SECTIONS.contains(to)) {
            throw ApiException.badRequest("invalid_section", "Choose eat, drink, stay, see, do or buy. To remove the listing, hide it instead.");
        }
        if (to.equals(f.section())) {
            throw ApiException.badRequest("same_section", "The listing is already in that section.");
        }
        jdbc.sql("UPDATE guide_entry SET section = :s, section_override = :s, updated_at = now() WHERE id = :e")
                .param("s", to).param("e", f.entryId()).update();
        jdbc.sql("UPDATE fact SET section = :s, updated_at = now() WHERE entry_id = :e").param("s", to).param("e", f.entryId()).update();
        resolve(flagId, "accepted", "moved", f.section(), admin, automatic);
        audit.record(admin, automatic ? "ai_flag.auto_moved" : "ai_flag.moved", "guide_entry", f.entryId(), f.destinationId(),
                Map.of("flag", flagId.toString(), "from", f.section(), "to", to));
        changes.destinationChanged(f.destinationId());
    }

    /** Takes the listing out of the guide, with the reviewer's reason recorded. */
    @Transactional
    public void hide(UUID flagId, UUID admin, boolean automatic) {
        Flag f = flag(flagId);
        jdbc.sql("""
                        UPDATE guide_entry SET status = 'hidden', status_reason = :reason, updated_at = now()
                        WHERE id = :e AND status = 'published'""")
                .param("reason", HIDDEN_PREFIX + f.reason()).param("e", f.entryId()).update();
        resolve(flagId, "accepted", "hidden", null, admin, automatic);
        audit.record(admin, automatic ? "ai_flag.auto_hidden" : "ai_flag.accepted", "guide_entry", f.entryId(), f.destinationId(),
                Map.of("flag", flagId.toString()));
        changes.destinationChanged(f.destinationId());
    }

    /** Leaves the listing as it is: either an admin disagrees ("kept") or it is a note that needs no action ("noted"). */
    @Transactional
    public void keep(UUID flagId, UUID admin, boolean automatic, boolean noteOnly) {
        Flag f = flag(flagId);
        resolve(flagId, "dismissed", noteOnly ? "noted" : "kept", null, admin, automatic);
        audit.record(admin, automatic ? "ai_flag.auto_noted" : "ai_flag.dismissed", "guide_entry", f.entryId(), f.destinationId(),
                Map.of("flag", flagId.toString()));
    }

    /** Reverses a move or a hide; the listing goes back to exactly how the source has it. */
    @Transactional
    public void undo(UUID flagId, UUID admin) {
        Flag f = flag(flagId);
        if (!"accepted".equals(f.status()) || f.resolution() == null) {
            throw ApiException.conflict("nothing_to_undo", "This flag did not change the listing.");
        }
        if ("moved".equals(f.resolution()) && f.previousSection() != null) {
            jdbc.sql("UPDATE guide_entry SET section = :s, section_override = NULL, updated_at = now() WHERE id = :e")
                    .param("s", f.previousSection()).param("e", f.entryId()).update();
            jdbc.sql("UPDATE fact SET section = :s, updated_at = now() WHERE entry_id = :e")
                    .param("s", f.previousSection()).param("e", f.entryId()).update();
        } else if ("hidden".equals(f.resolution())) {
            jdbc.sql("""
                            UPDATE guide_entry SET status = 'published', status_reason = NULL, updated_at = now()
                            WHERE id = :e AND status = 'hidden' AND status_reason LIKE 'Hidden after review:%'""")
                    .param("e", f.entryId()).update();
        }
        resolve(flagId, "dismissed", "kept", null, admin, false);
        audit.record(admin, "ai_flag.undone", "guide_entry", f.entryId(), f.destinationId(),
                Map.of("flag", flagId.toString(), "was", f.resolution()));
        changes.destinationChanged(f.destinationId());
    }

    private void resolve(UUID flagId, String status, String resolution, String previousSection, UUID admin, boolean automatic) {
        jdbc.sql("""
                        UPDATE ai_review SET status = :s, resolution = :r, previous_section = :p, decided_automatically = :auto,
                               resolved_by = :u, resolved_at = now()
                        WHERE id = :id""")
                .param("s", status).param("r", resolution).param("p", previousSection).param("auto", automatic)
                .param("u", admin).param("id", flagId).update();
    }
}
