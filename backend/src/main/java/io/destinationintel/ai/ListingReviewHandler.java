package io.destinationintel.ai;

import io.destinationintel.common.Json;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Job "ai_review": a model reads a destination's published listings and records a verdict for each one. It is a
 * reviewer, never an author: it cannot change text or facts. Doubtful listings become open flags for an admin.
 * Listings are only sent again when their text changes or the prompt changes, so the free quota goes far.
 */
@Component
public class ListingReviewHandler implements JobHandler {

    /** Bump when the instructions or schema change, so every listing is reviewed again under the new rules. */
    static final int PROMPT_VERSION = 3;
    /** Below this the model is unsure, and an unsure verdict is never put in front of an admin as a flag. */
    static final double FLAG_CONFIDENCE = 0.7;
    /** Issues that make a listing doubtful. "too_thin" is recorded but only informs; it is not a defect to remove. */
    static final Set<String> FLAGGING_ISSUES = Set.of("wrong_section", "outside_destination", "about_another_business",
            "broken_text", "closed");
    /** Opening hours must contain a time of day ("9AM", "18:30", "noon", "24 hours"), not just a date or year. */
    static final java.util.regex.Pattern HOURS = java.util.regex.Pattern.compile(
            "(?i)(\\b\\d{1,2}([:.]\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.)|\\b\\d{1,2}[:.]\\d{2}\\b|\\bnoon\\b|\\bmidnight\\b|\\bsunrise\\b|\\bsunset\\b|24 ?hours|24x7|24/7)");
    static final Set<String> SECTIONS = Set.of("eat", "drink", "stay", "see", "do", "buy", "none");
    /** Tags the site already understands (interests, local food, step-free access, price level). */
    static final Set<String> TAGS = Set.of("food", "culture", "nature", "adventure", "nightlife", "shopping", "family",
            "regional_food", "wheelchair", "budget", "moderate", "premium");

    static final String SYSTEM = """
            You check listings in an open travel guide for one destination in India. You are a reviewer: judge only from the
            text given, never add facts, never rewrite anything.
            Sections: eat = restaurant, cafe serving meals, food stall or food court; drink = bar, pub, cafe, tea or juice shop;
            stay = hotel, guest house, hostel, homestay, resort, campsite; see = sight, monument, museum, temple, viewpoint,
            natural feature; do = activity, tour, course, festival, sport, park; buy = shop, market, mall.
            For each listing decide:
            - fitsSection: does the text show the place serves its section's purpose? A landmark under "eat" is fine only if
              the text says food is sold there (for example food courts beside a monument).
            - inDestination: "no" only when the text clearly places the listing in another city. A booking office or head
              office elsewhere is fine. Use "unclear" when the text does not say.
            - issues (empty when fine): wrong_section; outside_destination; about_another_business (the text describes a
              different place than the title); broken_text (garbled, cut off mid-sentence, leftover markup); closed (the text
              says it closed or no longer operates); too_thin (too short to help a traveller decide).
            - suggestedSection: where it belongs, or "none" if it is not a place to list.
            - confidence: 0 to 1, how sure you are of this verdict.
            - reason: one short sentence quoting the words that decide it.
            When the description is empty or too short to tell, set fitsSection to true unless the title alone clearly shows
            the place does not belong, and add too_thin. Absence of text is not evidence against a listing.
            Long descriptions are shortened by us and then end with " [...]"; that is not broken text.

            Also, from the same text only:
            - summary: when the description is longer than two sentences, rewrite it as one or two plain sentences (at most
              45 words) telling a traveller what the place is and what stands out. Use only what the text says.
              Keep the specifics a traveller decides on: cuisines, dishes, vegetarian, vegan or gluten-free options, who
              it suits, notable features. Keep every criticism or warning in plain words (for example dirty, rude staff,
              overpriced, noisy, crowded, touts): honesty matters more than a pleasant tone. Never add praise the text
              does not give (no "famous", "popular", "best", "stunning" unless the text says so).
              Leave out prices, phone numbers, opening hours and directions (they are shown separately), jokes, sarcasm,
              first person and dated phrases like "recently opened". Do not add any name, number or claim that is not in
              the text. Return "" when the description is short or empty.
            - tags: which of these apply, each with the exact words from the description that show it (copy them
              verbatim): food, culture, nature, adventure, nightlife, shopping, family (interests); regional_food (local or
              regional cuisine); wheelchair (step-free or wheelchair access is mentioned); budget, moderate, premium (price
              level is stated). Return [] when none clearly apply.
            - facts: prices or opening hours written in the description, each copied word for word as it appears
              (for example "Thali ₹180" or "open 9AM-6PM, closed Mondays"). Return [] when there are none.
            Return exactly one result per listing id.""";

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "ARRAY",
            "items", Map.of(
                    "type", "OBJECT",
                    "properties", Map.of(
                            "id", Map.of("type", "STRING"),
                            "fitsSection", Map.of("type", "BOOLEAN"),
                            "inDestination", Map.of("type", "STRING", "enum", List.of("yes", "no", "unclear")),
                            "issues", Map.of("type", "ARRAY", "items", Map.of("type", "STRING", "enum",
                                    List.of("wrong_section", "outside_destination", "about_another_business", "broken_text", "closed", "too_thin"))),
                            "suggestedSection", Map.of("type", "STRING", "enum", List.of("eat", "drink", "stay", "see", "do", "buy", "none")),
                            "confidence", Map.of("type", "NUMBER"),
                            "reason", Map.of("type", "STRING"),
                            "summary", Map.of("type", "STRING"),
                            "tags", Map.of("type", "ARRAY", "items", Map.of("type", "OBJECT", "properties", Map.of(
                                    "tag", Map.of("type", "STRING", "enum", List.copyOf(new java.util.TreeSet<>(TAGS))),
                                    "quote", Map.of("type", "STRING")), "required", List.of("tag", "quote"))),
                            "facts", Map.of("type", "ARRAY", "items", Map.of("type", "OBJECT", "properties", Map.of(
                                    "kind", Map.of("type", "STRING", "enum", List.of("price", "hours")),
                                    "quote", Map.of("type", "STRING")), "required", List.of("kind", "quote")))),
                    "required", List.of("id", "fitsSection", "inDestination", "issues", "suggestedSection", "confidence", "reason",
                            "summary", "tags", "facts")));

    record Listing(UUID id, String section, String title, String area, String description, String address,
                   UUID descriptionFactId, String factKinds, String sourceSection) {

        Listing(UUID id, String section, String title, String area, String description, String address) {
            this(id, section, title, area, description, address, null, "", null);
        }

        Listing(UUID id, String section, String title, String area, String description, String address,
                UUID descriptionFactId, String factKinds) {
            this(id, section, title, area, description, address, descriptionFactId, factKinds, null);
        }

        /** The section the source files it under: a listing that was moved is not "new" to the reviewer. */
        String hashSection() {
            return sourceSection != null ? sourceSection : section;
        }

        boolean hasText() {
            return description != null && !description.isBlank();
        }

        boolean hasKind(String kind) {
            return factKinds != null && List.of(factKinds.split(",")).contains(kind);
        }
    }

    record Tag(String tag, String quote) {}

    /** What the model returned for one listing, before the faithfulness checks. */
    record Result(Verdict verdict, String summary, List<Tag> tags, List<AiFacts.Extracted> facts) {}

    record Verdict(boolean fitsSection, String inDestination, List<String> issues, String suggestedSection, double confidence,
                   String reason) {

        /** A listing without text can only be flagged for a named defect; "does not fit" needs words to go on. */
        boolean flagged(boolean hasText) {
            if (confidence < FLAG_CONFIDENCE) {
                return false;
            }
            boolean named = issues.stream().anyMatch(FLAGGING_ISSUES::contains);
            return hasText ? !fitsSection || "no".equals(inDestination) || named : named;
        }
    }

    private final GeminiClient gemini;
    private final AiProperties props;
    private final JdbcClient jdbc;
    private final PlaceRepository places;
    private final Json json;
    private final TransactionTemplate tx;
    private final AiFacts aiFacts;
    private final AiPublishing publishing;
    private final OverviewWriter overview;
    private final io.destinationintel.guide.GuideBaseLoader loader;
    private FlagDecider decider;

    /** Set after construction to avoid a constructor cycle (the decider does not depend on this handler). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDecider(FlagDecider decider) {
        this.decider = decider;
    }

    public ListingReviewHandler(GeminiClient gemini, AiProperties props, JdbcClient jdbc, PlaceRepository places, Json json,
                                TransactionTemplate tx, AiFacts aiFacts, AiPublishing publishing, OverviewWriter overview,
                                io.destinationintel.guide.GuideBaseLoader loader) {
        this.gemini = gemini;
        this.props = props;
        this.jdbc = jdbc;
        this.places = places;
        this.json = json;
        this.tx = tx;
        this.aiFacts = aiFacts;
        this.publishing = publishing;
        this.overview = overview;
        this.loader = loader;
    }

    @Override
    public String type() {
        return "ai_review";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!gemini.active()) {
            stats.put("skipped", "AI review is not enabled on this server");
            return stats;
        }
        PlaceRow place = places.findById(job.targetPlaceId()).orElseThrow(() -> new PermanentFailure("Destination no longer exists"));
        String state = jdbc.sql("""
                        SELECT s.name FROM place_relation r JOIN place s ON s.id = r.parent_id
                        WHERE r.child_id = :p AND r.relation = 'in_state' LIMIT 1""")
                .param("p", place.id()).query(String.class).optional().orElse(null);

        List<Listing> all = jdbc.sql("""
                        SELECT e.id, e.section, e.title, e.area,
                               (SELECT f.value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published' LIMIT 1) AS description,
                               (SELECT f.value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'address' AND f.status = 'published' LIMIT 1) AS address,
                               (SELECT f.id FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published' LIMIT 1) AS description_fact_id,
                               (SELECT string_agg(DISTINCT f.kind, ',') FROM fact f WHERE f.entry_id = e.id AND f.status = 'published'
                                  AND (f.ingest_key IS NULL OR f.ingest_key NOT LIKE 'ai:%')) AS fact_kinds,
                               CASE WHEN e.section_override IS NOT NULL THEN
                                    (SELECT r.previous_section FROM ai_review r WHERE r.entry_id = e.id AND r.resolution = 'moved'
                                     ORDER BY r.resolved_at DESC LIMIT 1) END AS source_section
                        FROM guide_entry e
                        WHERE e.destination_id = :d AND e.status = 'published' AND e.section <> 'neighbourhood'
                        ORDER BY e.section, e.title""")
                .param("d", place.id()).query(Listing.class).list();
        List<Listing> todo = new ArrayList<>();
        for (Listing l : all) {
            boolean seen = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ai_review WHERE entry_id = :e AND input_hash = :h AND prompt_version = :v)")
                    .param("e", l.id()).param("h", inputHash(l)).param("v", PROMPT_VERSION).query(Boolean.class).single();
            if (!seen) {
                todo.add(l);
            }
        }
        stats.put("listings", all.size());
        stats.put("alreadyReviewed", all.size() - todo.size());

        int reviewed = 0;
        int flagged = 0;
        int summaries = 0;
        int rejectedSummaries = 0;
        int extractedFacts = 0;
        int requests = 0;
        String model = null;
        for (int i = 0; i < todo.size(); i += props.batchSize()) {
            List<Listing> batch = todo.subList(i, Math.min(todo.size(), i + props.batchSize()));
            GeminiClient.Answer answer;
            try {
                answer = gemini.generateJson(SYSTEM, prompt(place, state, batch), SCHEMA);
            } catch (GeminiClient.BudgetExhausted e) {
                // Keep what is done; the rest waits for tomorrow's quota.
                stats.put("reviewed", reviewed);
                throw new Deferred(e.getMessage() + " (" + reviewed + " listings reviewed before it ran out)", untilTomorrowUtc());
            }
            requests++;
            model = answer.model();
            Map<Integer, Result> results = parseResults(answer.json(), batch.size());
            for (int k = 0; k < batch.size(); k++) {
                Result r = results.get(k + 1);
                if (r == null) {
                    continue; // left for the next run
                }
                Listing l = batch.get(k);
                String m = model;
                boolean isFlag = r.verdict().flagged(l.hasText());
                Enrichment e = enrich(l, r, place.name());
                tx.executeWithoutResult(s -> {
                    save(l, place.id(), r.verdict(), m, isFlag);
                    saveEnrichment(l, place.id(), e, m);
                });
                reviewed++;
                summaries += e.summaryStatus().equals("accepted") ? 1 : 0;
                rejectedSummaries += e.summaryStatus().equals("rejected") ? 1 : 0;
                extractedFacts += e.facts().size();
                if (isFlag) {
                    flagged++;
                }
            }
        }
        try {
            stats.put("overview", overview.write(place, state));
        } catch (GeminiClient.BudgetExhausted e) {
            throw new Deferred(e.getMessage() + " (listings done; the destination overview waits)", untilTomorrowUtc());
        }
        loader.evict(place.id());
        if (decider != null && flagged > 0) {
            // Confident flags are decided now; only the doubtful ones wait for an admin.
            stats.put("flagsDecidedAutomatically", decider.sweep());
        }
        stats.put("reviewed", reviewed);
        stats.put("flagged", flagged);
        stats.put("summariesAccepted", summaries);
        stats.put("summariesRejected", rejectedSummaries);
        stats.put("factsExtracted", extractedFacts);
        stats.put("requests", requests);
        if (model != null) {
            stats.put("model", model);
        }
        return stats;
    }

    private void save(Listing l, UUID destinationId, Verdict v, String model, boolean isFlag) {
        UUID id = jdbc.sql("""
                        INSERT INTO ai_review (entry_id, destination_id, input_hash, prompt_version, model, fits_section, in_destination,
                                               issues, suggested_section, confidence, reason, flagged, status)
                        VALUES (:e, :d, :h, :v, :m, :fits, :inDest, string_to_array(:issues, ','), :suggested, :conf, :reason, :flagged, :status)
                        ON CONFLICT (entry_id, input_hash, prompt_version) DO NOTHING
                        RETURNING id""")
                .param("e", l.id()).param("d", destinationId).param("h", inputHash(l)).param("v", PROMPT_VERSION)
                .param("m", model).param("fits", v.fitsSection()).param("inDest", v.inDestination())
                .param("issues", String.join(",", v.issues())).param("suggested", v.suggestedSection())
                .param("conf", (float) v.confidence()).param("reason", v.reason()).param("flagged", isFlag)
                .param("status", isFlag ? "open" : "clear")
                .query(UUID.class).optional().orElse(null);
        if (id != null) {
            // A newer reading of the same listing replaces any earlier flag that nobody acted on.
            jdbc.sql("UPDATE ai_review SET status = 'superseded' WHERE entry_id = :e AND status = 'open' AND id <> :id")
                    .param("e", l.id()).param("id", id).update();
        }
    }

    /** The parts of a result that survive the faithfulness checks. */
    record Enrichment(String summary, String summaryStatus, String rejection, List<Tag> tags, List<AiFacts.Extracted> facts) {}

    static Enrichment enrich(Listing l, Result r, String destination) {
        String text = l.description() == null ? "" : l.description();
        String summary = null;
        String status = "not_needed";
        String rejection = null;
        if (text.length() >= Faithfulness.SUMMARY_MIN_SOURCE && r.summary() != null && !r.summary().isBlank()) {
            var problem = Faithfulness.summaryProblem(r.summary().strip(), text, l.title(), String.valueOf(l.area()),
                    String.valueOf(l.address()), destination);
            if (problem.isPresent()) {
                status = "rejected";
                rejection = problem.get();
            } else {
                status = "accepted";
            }
            summary = r.summary().strip();
        }
        List<Tag> tags = new ArrayList<>();
        for (Tag t : r.tags()) {
            if (TAGS.contains(t.tag()) && Faithfulness.quotedIn(t.quote(), text)
                    && tags.stream().noneMatch(x -> x.tag().equals(t.tag()))) {
                tags.add(new Tag(t.tag(), t.quote().strip()));
            }
        }
        List<AiFacts.Extracted> facts = new ArrayList<>();
        for (AiFacts.Extracted f : r.facts()) {
            String q = f.quote() == null ? "" : f.quote().strip();
            boolean verbatim = Faithfulness.quotedIn(q, text) && q.length() <= 160;
            boolean usable = f.kind().equals("price") ? io.destinationintel.guide.PriceParser.parse(q).isPresent()
                    : HOURS.matcher(q).find();
            // Only fill a gap: a listing that already has a structured price or hours keeps the source's own value.
            if (verbatim && usable && !l.hasKind(f.kind()) && facts.stream().noneMatch(x -> x.kind().equals(f.kind()))) {
                facts.add(new AiFacts.Extracted(f.kind(), q));
            }
        }
        return new Enrichment(summary, status, rejection, tags, facts);
    }

    private void saveEnrichment(Listing l, UUID destinationId, Enrichment e, String model) {
        Integer n = jdbc.sql("""
                        INSERT INTO ai_enrichment (entry_id, destination_id, input_hash, prompt_version, model, summary, summary_status,
                                                   summary_rejection, tags, extracted)
                        VALUES (:e, :d, :h, :v, :m, :summary, :status, :rejection, CAST(:tags AS jsonb), CAST(:facts AS jsonb))
                        ON CONFLICT (entry_id, input_hash, prompt_version) DO NOTHING""")
                .param("e", l.id()).param("d", destinationId).param("h", inputHash(l)).param("v", PROMPT_VERSION).param("m", model)
                .param("summary", e.summary() == null ? null : clip(e.summary(), 400)).param("status", e.summaryStatus())
                .param("rejection", e.rejection()).param("tags", json.write(e.tags())).param("facts", json.write(e.facts()))
                .update();
        if (n > 0 && l.descriptionFactId() != null) {
            aiFacts.write(l.id(), l.descriptionFactId(), e.facts(), publishing.published());
        }
    }

    String prompt(PlaceRow place, String state, List<Listing> batch) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int k = 0; k < batch.size(); k++) {
            Listing l = batch.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(k + 1));
            m.put("section", l.section());
            m.put("title", l.title());
            if (l.area() != null) {
                m.put("area", l.area());
            }
            m.put("description", l.description() == null ? "" : shorten(l.description(), 900));
            if (l.address() != null) {
                m.put("address", clip(l.address(), 200));
            }
            items.add(m);
        }
        return "Destination: " + place.name() + (state == null ? "" : " (" + state + ")") + ", India.\nListings (JSON):\n"
                + json.write(items);
    }

    /** Verdicts plus the improvement fields; malformed parts are dropped, never guessed. */
    Map<Integer, Result> parseResults(String answerJson, int size) {
        Map<Integer, Verdict> verdicts = parse(answerJson, size);
        Map<Integer, Result> out = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = json.tree(answerJson);
        } catch (RuntimeException e) {
            return out;
        }
        for (JsonNode n : root) {
            int id;
            try {
                id = Integer.parseInt(n.path("id").asString(""));
            } catch (NumberFormatException e) {
                continue;
            }
            Verdict v = verdicts.get(id);
            if (v == null || out.containsKey(id)) {
                continue;
            }
            List<Tag> tags = new ArrayList<>();
            for (JsonNode t : n.path("tags")) {
                tags.add(new Tag(t.path("tag").asString(""), t.path("quote").asString("")));
            }
            List<AiFacts.Extracted> facts = new ArrayList<>();
            for (JsonNode f : n.path("facts")) {
                String kind = f.path("kind").asString("");
                if (kind.equals("price") || kind.equals("hours")) {
                    facts.add(new AiFacts.Extracted(kind, f.path("quote").asString("")));
                }
            }
            out.put(id, new Result(v, n.path("summary").asString(""), tags, facts));
        }
        return out;
    }

    /** Reads the model's answer defensively: unknown ids, bad values and missing fields are dropped, never guessed. */
    Map<Integer, Verdict> parse(String answerJson, int size) {
        Map<Integer, Verdict> out = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = json.tree(answerJson);
        } catch (RuntimeException e) {
            return out;
        }
        if (!root.isArray()) {
            return out;
        }
        for (JsonNode n : root) {
            int id;
            try {
                id = Integer.parseInt(n.path("id").asString(""));
            } catch (NumberFormatException e) {
                continue;
            }
            String inDest = n.path("inDestination").asString("");
            String suggested = n.path("suggestedSection").asString("none");
            if (id < 1 || id > size || out.containsKey(id) || !n.path("fitsSection").isBoolean()
                    || !Set.of("yes", "no", "unclear").contains(inDest) || !n.path("confidence").isNumber()) {
                continue;
            }
            List<String> issues = new ArrayList<>();
            for (JsonNode i : n.path("issues")) {
                String s = i.asString("");
                if ((FLAGGING_ISSUES.contains(s) || s.equals("too_thin")) && !issues.contains(s)) {
                    issues.add(s);
                }
            }
            double confidence = Math.max(0, Math.min(1, n.path("confidence").asDouble()));
            String reason = n.path("reason").asString("").strip();
            out.put(id, new Verdict(n.path("fitsSection").asBoolean(), inDest, issues,
                    SECTIONS.contains(suggested) ? suggested : "none", confidence,
                    reason.isEmpty() ? "No reason given" : clip(reason, 500)));
        }
        return out;
    }

    static String inputHash(Listing l) {
        String s = String.join("\u001f", l.hashSection(), l.title(), String.valueOf(l.area()), String.valueOf(l.description()),
                String.valueOf(l.address()));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Shortens at a sentence end and marks it, so the model never mistakes our cut for broken text. */
    static String shorten(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String cut = io.destinationintel.ingestion.wikivoyage.WikiText.truncateAtSentence(s, max);
        if (cut == null || cut.isBlank() || cut.length() > max) {
            cut = s.substring(0, max);
        }
        return cut.strip() + " [...]";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Duration untilTomorrowUtc() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime next = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).plusMinutes(5);
        return Duration.between(now, next);
    }
}
