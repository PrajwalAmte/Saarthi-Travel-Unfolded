package io.destinationintel.guide;

import io.destinationintel.common.ApiException;
import io.destinationintel.guide.GuideDtos.BudgetRange;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Whether a destination's guide is clean and complete enough to put in front of travellers. "Ready" is about the
 * content being sound; "verified" additionally needs facts that local contributors have confirmed. Used before a
 * destination is promoted as a launch guide, so a guide is never advertised on the strength of its size.
 */
@Service
public class GuideReadinessService {

    public record Check(String key, boolean passed, String detail) {}

    public record Readiness(String slug, String name, String coverageStatus, boolean ready, boolean verified, List<Check> checks) {}

    /** Places (not branches) a launch guide should list per core section. */
    static final int MIN_EAT = 10;
    static final int MIN_STAY = 8;
    static final int MIN_EXPLORE = 10;
    static final int MIN_CONFIRMED_FACTS = 5;
    static final double MIN_WITH_COORDINATES_PCT = 80;
    static final double MIN_CURRENT_PRICES_AND_HOURS_PCT = 60;

    private final JdbcClient jdbc;
    private final PlaceRepository places;
    private final BudgetService budget;

    public GuideReadinessService(JdbcClient jdbc, PlaceRepository places, BudgetService budget) {
        this.jdbc = jdbc;
        this.places = places;
        this.budget = budget;
    }

    public Readiness check(String slug) {
        PlaceRow p = places.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Destination"));
        UUID id = p.id();
        List<Check> checks = new ArrayList<>();

        checks.add(depth("eat_places", id, "('eat', 'drink')", MIN_EAT, "places to eat and drink"));
        checks.add(depth("stay_places", id, "('stay')", MIN_STAY, "places to stay"));
        checks.add(depth("explore_places", id, "('see', 'do', 'buy')", MIN_EXPLORE, "things to see and do"));

        boolean summary = count("SELECT count(*) FROM fact WHERE destination_id = :d AND kind = 'summary' AND status = 'published'", id) > 0;
        checks.add(new Check("summary", summary, summary ? "A source-backed summary is published" : "No summary yet"));

        long artifacts = count("""
                SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published'
                  AND (value_text ~* '\\((?:see|see also|cf\\.?|compare|also)[ \\t,;:]*\\)' OR value_text ~ '[(\\[][ \\t]*[)\\]]')""", id);
        long unbalanced = count("""
                SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published'
                  AND kind IN ('description', 'address', 'what_to_order', 'summary')
                  AND length(value_text) - length(replace(value_text, '(', '')) <> length(value_text) - length(replace(value_text, ')', ''))""", id);
        checks.add(new Check("clean_text", artifacts + unbalanced == 0,
                artifacts + unbalanced == 0 ? "No empty brackets or broken markup" : artifacts + " texts with empty brackets, " + unbalanced + " with unbalanced brackets"));

        long misfiled = count("""
                SELECT count(*) FROM guide_entry e WHERE e.destination_id = :d AND e.status = 'published' AND e.section IN ('eat', 'drink', 'stay')
                  AND e.title ~* '\\m(gate|fort|temple|palace|museum|memorial|tomb|mosque|church|monument|bridge|lake|beach|garden|zoo|ghat)\\M'
                  AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published'
                                  AND f.value_text ~* '(food|restaurant|caf[eé]|eat|meal|dine|dining|cuisine|snack|stall|thali|biryani|hotel|room|guest ?house|lodg|dorm|hostel|bed|stay)')""", id);
        checks.add(new Check("relevant_listings", misfiled == 0,
                misfiled == 0 ? "Every eat and stay listing says what it offers" : misfiled + " landmarks under Eat or Stay say nothing about food or lodging"));

        long sameAddress = count("""
                SELECT count(*) FROM guide_entry a JOIN guide_entry b ON b.id > a.id AND b.destination_id = a.destination_id
                     AND b.status = 'published' AND b.section = a.section AND b.title_normalized = a.title_normalized
                JOIN fact fa ON fa.entry_id = a.id AND fa.kind = 'address' AND fa.status = 'published'
                JOIN fact fb ON fb.entry_id = b.id AND fb.kind = 'address' AND fb.status = 'published'
                  AND idi_normalize(fa.value_text) = idi_normalize(fb.value_text)
                WHERE a.destination_id = :d AND a.status = 'published'""", id);
        checks.add(new Check("no_repeated_listings", sameAddress == 0,
                sameAddress == 0 ? "No place is listed twice at the same address" : sameAddress + " places are listed twice at the same address"));

        long entries = count("SELECT count(*) FROM guide_entry WHERE destination_id = :d AND status = 'published' AND section <> 'neighbourhood'", id);
        long withCoords = count("SELECT count(*) FROM guide_entry WHERE destination_id = :d AND status = 'published' AND section <> 'neighbourhood' AND latitude IS NOT NULL", id);
        double coordsPct = entries == 0 ? 0 : 100.0 * withCoords / entries;
        checks.add(new Check("map_locations", coordsPct >= MIN_WITH_COORDINATES_PCT,
                Math.round(coordsPct) + "% of listings have a map location (" + Math.round(MIN_WITH_COORDINATES_PCT) + "% required)"));

        long timed = count("SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published' AND kind IN ('price', 'hours')", id);
        long current = count("""
                SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published' AND kind IN ('price', 'hours')
                  AND (coalesce(last_verified_at, observed_at::timestamptz) > now() - interval '120 days')""", id);
        double currentPct = timed == 0 ? 0 : 100.0 * current / timed;
        checks.add(new Check("current_prices_and_hours", timed > 0 && currentPct >= MIN_CURRENT_PRICES_AND_HOURS_PCT,
                timed == 0 ? "No prices or opening hours are recorded"
                        : Math.round(currentPct) + "% of prices and hours were checked in the last 120 days (" + Math.round(MIN_CURRENT_PRICES_AND_HOURS_PCT) + "% required)"));

        List<BudgetRange> ranges = budget.budget(id).ranges();
        boolean mealsOk = ranges.stream().anyMatch(r -> r.category().equals("meals") && r.reliability().equals("ok"));
        boolean staysOk = ranges.stream().anyMatch(r -> r.category().equals("stays") && r.reliability().equals("ok"));
        checks.add(new Check("budget", mealsOk && staysOk,
                (mealsOk ? "Food" : "Food: not enough recent prices") + "; " + (staysOk ? "Stays" : "Stays: not enough recent prices")));

        long openFlags = count("SELECT count(*) FROM ai_review WHERE destination_id = :d AND status = 'open'", id);
        long aiReviewed = count("""
                SELECT count(DISTINCT r.entry_id) FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id
                WHERE r.destination_id = :d AND e.status = 'published'""", id);
        checks.add(new Check("ai_flags_resolved", openFlags == 0,
                openFlags > 0 ? openFlags + " listings flagged by the AI reviewer wait for an admin decision"
                        : aiReviewed == 0 ? "Not reviewed by the AI reviewer yet" : aiReviewed + " listings reviewed, no open flags"));

        boolean ready = checks.stream().allMatch(Check::passed);
        long confirmed = count("SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published' AND last_verified_at IS NOT NULL", id);
        boolean verified = ready && confirmed >= MIN_CONFIRMED_FACTS;
        checks.add(new Check("confirmed_by_locals", confirmed >= MIN_CONFIRMED_FACTS,
                confirmed + " facts confirmed by contributors (" + MIN_CONFIRMED_FACTS + " required for \"verified\")"));
        String coverage = jdbc.sql("SELECT coverage_status FROM destination_profile WHERE place_id = :d").param("d", id)
                .query(String.class).optional().orElse("indexed");
        return new Readiness(p.slug(), p.name(), coverage, ready, verified, checks);
    }

    private Check depth(String key, UUID id, String sections, int min, String what) {
        long n = count("SELECT count(DISTINCT (section, title_normalized)) FROM guide_entry WHERE destination_id = :d AND status = 'published' AND section IN " + sections, id);
        return new Check(key, n >= min, n + " " + what + " (" + min + " required)");
    }

    private long count(String sql, UUID destination) {
        return jdbc.sql(sql).param("d", destination).query(Long.class).single();
    }
}
