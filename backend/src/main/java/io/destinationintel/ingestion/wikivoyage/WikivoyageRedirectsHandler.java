package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.wikidata.PlaceCoordinates;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.source.SourceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job "wikivoyage_redirects": the titles Wikivoyage redirects to each article become search names for its place, so a
 * landmark or a renamed town finds its guide ("Statue of Unity" and "Ekta Nagar" lead to Kevadia). Sub-pages,
 * airport codes and other non-names are left out; names that no longer redirect are removed on the next run.
 */
@Component
public class WikivoyageRedirectsHandler implements JobHandler {

    static final String SOURCE_ID = "wikivoyage-en";
    static final int PER_REQUEST = 50;
    /** Short all-capital codes (AGR, BLR, MYS): airport and station codes, not names a traveller searches. */
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,4}$");

    private final WikivoyageClient client;
    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final TransactionTemplate tx;
    private final PlaceCoordinates coordinates;
    private final PlaceRepository placeRepo;

    public WikivoyageRedirectsHandler(WikivoyageClient client, JdbcClient jdbc, SourceRepository sources, TransactionTemplate tx,
                                      PlaceCoordinates coordinates, PlaceRepository placeRepo) {
        this.client = client;
        this.jdbc = jdbc;
        this.sources = sources;
        this.tx = tx;
        this.coordinates = coordinates;
        this.placeRepo = placeRepo;
    }

    @Override
    public String type() {
        return "wikivoyage_redirects";
    }

    record Titled(UUID id, String title) {}

    /** Whether a redirect title is a name worth searching by. */
    static boolean usable(String title) {
        if (title == null || title.isBlank() || title.length() > 80) {
            return false;
        }
        return !title.contains("/") && !title.contains(":") && !title.contains("#") && !CODE.matcher(title).matches();
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The Wikivoyage source is disabled in the source registry");
        }
        List<Titled> places = jdbc.sql("""
                        SELECT id, wikivoyage_title AS title FROM place
                        WHERE wikivoyage_title IS NOT NULL AND lifecycle_status = 'active' ORDER BY wikivoyage_title""")
                .query(Titled.class).list();
        int added = 0;
        int removed = 0;
        int requests = 0;
        int moved = 0;
        for (int from = 0; from < places.size(); from += PER_REQUEST) {
            List<Titled> batch = places.subList(from, Math.min(places.size(), from + PER_REQUEST));
            Map<String, List<UUID>> byTitle = new HashMap<>();
            batch.forEach(p -> byTitle.computeIfAbsent(p.title(), k -> new ArrayList<>()).add(p.id()));
            Map<String, List<String>> redirects = client.redirectsTo(new ArrayList<>(byTitle.keySet()));
            requests++;
            int[] counts = tx.execute(s -> {
                int a = 0;
                int r = 0;
                for (Map.Entry<String, List<String>> e : redirects.entrySet()) {
                    List<String> names = e.getValue().stream().filter(WikivoyageRedirectsHandler::usable).distinct().toList();
                    for (UUID place : byTitle.getOrDefault(e.getKey(), List.of())) {
                        for (String name : names) {
                            a += jdbc.sql("""
                                            INSERT INTO place_alias (place_id, alias, language, alias_type, source_id)
                                            VALUES (:p, :a, 'en', 'redirect', 'wikivoyage-en')
                                            ON CONFLICT (place_id, alias, language) DO NOTHING""")
                                    .param("p", place).param("a", name).update();
                        }
                        r += jdbc.sql("""
                                        DELETE FROM place_alias WHERE place_id = :p AND alias_type = 'redirect'
                                          AND NOT (alias = ANY (CAST(:names AS text[])))""")
                                .param("p", place).param("names", names.toArray(String[]::new)).update();
                    }
                }
                return new int[] {a, r};
            });
            added += counts[0];
            removed += counts[1];
            // The same pass checks each place's stored location against its article.
            Map<String, double[]> articlePoints = client.coordinatesOf(new ArrayList<>(byTitle.keySet()));
            for (Titled p : batch) {
                double[] point = articlePoints.get(p.title().replace('_', ' '));
                if (point != null && placeRepo.findById(p.id()).map(row -> coordinates.verifyAgainst(row, point)).orElse(false)) {
                    moved++;
                }
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("articles", places.size());
        stats.put("requests", requests);
        stats.put("added", added);
        stats.put("removed", removed);
        stats.put("placesMoved", moved);
        return stats;
    }
}
