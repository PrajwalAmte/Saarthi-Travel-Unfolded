package io.destinationintel.ai;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.UrlSafety;
import io.destinationintel.guide.GuideChanges;
import io.destinationintel.guide.GuideDtos.FactView;
import io.destinationintel.guide.GuideDtos.RecheckItem;
import io.destinationintel.guide.GuideService;
import io.destinationintel.guide.PriceParser;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job "fact_check", the checker agent: for the stale prices and opening hours a traveller sees first (the recheck
 * worklist), it reads the website already on record for that place and decides, a step at a time, whether to answer
 * or follow one of the site's own links (at most three pages). The model must quote the page word for word; the quote
 * and its numbers are verified here before anything changes.
 *
 * <ul>
 *   <li>Confirmed: the fact is re-dated to today, with the website as new evidence.</li>
 *   <li>Changed: a new fact replaces it (the old one is kept as superseded), with the quote as evidence.</li>
 *   <li>Closed or not stated: nothing changes; the check is recorded so the worklist can show it.</li>
 * </ul>
 * Every applied check can be undone. It never adds places, never touches descriptions and never reads another site.
 */
@Component
public class FactChecker implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FactChecker.class);

    /** Facts checked per job; each check is one to three model requests. */
    static final int PER_JOB = 12;
    static final int MAX_PAGES = 3;
    /** A fact is not checked again for this long, whatever the outcome. */
    static final int RECHECK_DAYS = 30;
    static final int PROMPT_TEXT = 8_000;
    static final Pattern CLOSED = Pattern.compile(
            "(?i)(permanently closed|closed permanently|closed down|has closed|have closed|ceased operations|no longer (open|operating|operational)|shut down)");

    static final String SYSTEM = """
            You check one fact about a place in India against that place's own website, for a travel guide that must never
            state anything its source does not say. You see one page at a time, with its links numbered.
            Rules:
            - Use only the page text you are given. Never use your own knowledge.
            - "quote" must be copied exactly, character for character, from the page text: the shortest passage that shows
              your answer (at most 300 characters).
            - confirmed: the page states the same value as the recorded one (the same price or the same opening times).
            - changed: the page states a different current value for the same thing; put it in "value", written briefly in
              the style of the recorded value (for prices keep the currency, for a range give lowest and highest).
            - closed: only when the page plainly says the place has closed for good. Closed on certain days is not closed.
            - not_stated: the page does not state this value.
            - If this page does not state it but one of the numbered links very likely leads to it (tariff, rooms, prices,
              menu, timings, visit, contact), set action "follow_link" with that link's number. Otherwise action "answer".
            """;

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("answer", "follow_link")),
                    "link", Map.of("type", "integer"),
                    "verdict", Map.of("type", "string", "enum", List.of("confirmed", "changed", "not_stated", "closed")),
                    "value", Map.of("type", "string"),
                    "quote", Map.of("type", "string"),
                    "reason", Map.of("type", "string")),
            "required", List.of("action", "verdict", "reason"));

    private final LlmClient llm;
    private final SiteReader reader;
    private final GuideService guide;
    private final PlaceRepository places;
    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate tx;
    private final GuideChanges changes;
    private final AiPublishing publishing;
    private final JobRepository jobs;

    public FactChecker(LlmClient llm, SiteReader reader, GuideService guide, PlaceRepository places, JdbcClient jdbc, Json json,
                       TransactionTemplate tx, GuideChanges changes, AiPublishing publishing, JobRepository jobs) {
        this.llm = llm;
        this.reader = reader;
        this.guide = guide;
        this.places = places;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.changes = changes;
        this.publishing = publishing;
        this.jobs = jobs;
    }

    @Override
    public String type() {
        return "fact_check";
    }

    /** One step's answer from the model. */
    record Step(String action, Integer link, String verdict, String value, String quote, String reason) {}

    /** The outcome of checking one fact, after verification. */
    record Verdict(String verdict, String outcome, String pageUrl, String quote, String value, String reason, int pages, String model) {}

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!llm.active()) {
            throw new PermanentFailure("AI is off, so facts cannot be checked automatically");
        }
        PlaceRow place = places.findById(job.targetPlaceId())
                .orElseThrow(() -> new PermanentFailure("Target destination no longer exists"));
        reader.forgetRobots();
        List<RecheckItem> todo = new ArrayList<>();
        for (RecheckItem item : guide.recheckWorklist(place, 10).items()) {
            if (todo.size() >= PER_JOB) {
                break;
            }
            if (!checkedRecently(item.fact().id()) && website(item.entryId()).isPresent()) {
                todo.add(item);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int done = 0;
        for (RecheckItem item : todo) {
            Verdict v;
            try {
                v = check(place, item, website(item.entryId()).orElseThrow());
            } catch (LlmClient.RateLimited e) {
                throw new Deferred(e.getMessage() + " (" + done + " facts checked so far)", Duration.ofMinutes(5));
            } catch (LlmClient.BudgetExhausted e) {
                throw new Deferred(e.getMessage() + " (" + done + " facts checked before it ran out)", llm.untilQuotaReset());
            }
            record(place, item, v);
            counts.merge(v.verdict() + ":" + v.outcome(), 1, Integer::sum);
            done++;
        }
        if (counts.keySet().stream().anyMatch(k -> k.endsWith(":applied"))) {
            changes.destinationChanged(place.id());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("checked", done);
        stats.put("results", counts);
        return stats;
    }

    /** The agent loop for one fact: read, ask, maybe follow a link, and verify the final answer. */
    Verdict check(PlaceRow place, RecheckItem item, String site) {
        String host = URI.create(site).getHost();
        Optional<SiteReader.Page> page = reader.read(site, host);
        if (page.isEmpty()) {
            return new Verdict("unreadable", "skipped", site, null, null, "The website could not be read (blocked, offline or not a web page).", 0, null);
        }
        int pages = 1;
        String model = null;
        while (true) {
            SiteReader.Page p = page.get();
            LlmClient.Answer answer = llm.generateJson(SYSTEM, prompt(place, item, p), SCHEMA, LlmClient.Purpose.BULK);
            model = answer.model();
            Step step = parse(answer.json());
            if (step == null) {
                return new Verdict("rejected", "skipped", p.url(), null, null, "The model's answer could not be read.", pages, model);
            }
            if ("follow_link".equals(step.action()) && pages < MAX_PAGES && step.link() != null
                    && step.link() >= 1 && step.link() <= p.links().size()) {
                Optional<SiteReader.Page> next = reader.read(p.links().get(step.link() - 1).url(), host);
                if (next.isPresent()) {
                    page = next;
                    pages++;
                    continue;
                }
            }
            return verify(item.fact(), step, p, pages, model);
        }
    }

    /** Accepts the model's answer only when the page bears it out; otherwise records why not. */
    static Verdict verify(FactView fact, Step step, SiteReader.Page page, int pages, String model) {
        String verdict = step.verdict() == null ? "not_stated" : step.verdict();
        String quote = step.quote() == null ? "" : step.quote().trim();
        String reason = step.reason();
        if (verdict.equals("not_stated")) {
            return new Verdict("not_stated", "noted", page.url(), null, null, reason, pages, model);
        }
        if (quote.isEmpty() || quote.length() > 400 || !Faithfulness.quotedIn(quote, page.text())) {
            return new Verdict("rejected", "skipped", page.url(), quote, step.value(), "The quote is not on the page.", pages, model);
        }
        Set<String> recorded = Faithfulness.numbers(fact.text());
        Set<String> quoted = Faithfulness.numbers(quote);
        switch (verdict) {
            case "confirmed" -> {
                boolean same = recorded.isEmpty() ? Faithfulness.quotedIn(fact.text(), quote) : quoted.containsAll(recorded);
                return same ? new Verdict("confirmed", "applied", page.url(), quote, null, reason, pages, model)
                        : new Verdict("rejected", "skipped", page.url(), quote, null, "The quote does not show the recorded value.", pages, model);
            }
            case "changed" -> {
                String value = step.value() == null ? "" : step.value().trim();
                Set<String> numbers = Faithfulness.numbers(value);
                boolean ok = !value.isEmpty() && value.length() <= 200 && !numbers.isEmpty() && quoted.containsAll(numbers)
                        && !numbers.equals(recorded)
                        && (fact.kind().equals("price") ? PriceParser.parse(value).isPresent() : ListingReviewHandler.HOURS.matcher(quote).find());
                return ok ? new Verdict("changed", "applied", page.url(), quote, value, reason, pages, model)
                        : new Verdict("rejected", "skipped", page.url(), quote, value, "The new value is not borne out by the quote.", pages, model);
            }
            case "closed" -> {
                // A closure is never applied automatically: it is left in the worklist with the quote for a person.
                return CLOSED.matcher(quote).find() ? new Verdict("closed", "noted", page.url(), quote, null, reason, pages, model)
                        : new Verdict("rejected", "skipped", page.url(), quote, null, "The quote does not say the place has closed.", pages, model);
            }
            default -> {
                return new Verdict("rejected", "skipped", page.url(), quote, null, "Unknown verdict.", pages, model);
            }
        }
    }

    private String prompt(PlaceRow place, RecheckItem item, SiteReader.Page page) {
        FactView f = item.fact();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < page.links().size(); i++) {
            links.append(i + 1).append(". ").append(page.links().get(i).text()).append('\n');
        }
        String text = page.text().length() > PROMPT_TEXT ? page.text().substring(0, PROMPT_TEXT) : page.text();
        return "Check this fact.\n"
                + "Place: " + item.title() + (item.area() == null ? "" : " (" + item.area() + ")") + ", " + place.name() + "\n"
                + "Fact: " + (f.kind().equals("price") ? "price" : "opening hours") + "\n"
                + "Recorded value: " + f.text() + (f.observedAt() == null ? " (undated)" : " (recorded " + f.observedAt() + ")") + "\n\n"
                + "Page: " + page.url() + "\nTitle: " + page.title() + "\nText:\n" + text + "\n\nLinks:\n" + links;
    }

    private Step parse(String answer) {
        try {
            Map<String, Object> m = json.map(answer);
            return new Step(str(m.get("action")), m.get("link") instanceof Number n ? n.intValue() : null, str(m.get("verdict")),
                    str(m.get("value")), str(m.get("quote")), str(m.get("reason")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private boolean checkedRecently(UUID factId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM fact_check WHERE fact_id = :f AND created_at > now() - make_interval(days => :d))")
                .param("f", factId).param("d", RECHECK_DAYS).query(Boolean.class).single();
    }

    /** The website on record for a listing (from its source), normalised; none means it cannot be checked. */
    Optional<String> website(UUID entryId) {
        return jdbc.sql("""
                        SELECT coalesce(value_json->>'url', value_text) FROM fact
                        WHERE entry_id = :e AND kind = 'website' AND status = 'published' ORDER BY created_at LIMIT 1""")
                .param("e", entryId).query(String.class).optional().flatMap(UrlSafety::normalize);
    }

    /** Stores the check and, when it was verified, applies it (re-date or replace), all in one transaction. */
    private void record(PlaceRow place, RecheckItem item, Verdict v) {
        FactView f = item.fact();
        boolean apply = v.outcome().equals("applied") && publishing.published();
        String outcome = v.outcome().equals("applied") && !apply ? "noted" : v.outcome();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        tx.executeWithoutResult(s -> {
            UUID newFact = null;
            UUID evidence = null;
            if (apply && v.verdict().equals("confirmed")) {
                evidence = evidence(f.id(), v, today);
                jdbc.sql("UPDATE fact SET observed_at = :d, observed_basis = 'source_listing_date', updated_at = now() WHERE id = :id")
                        .param("d", today).param("id", f.id()).update();
                observation(f.id(), f.text(), today);
            } else if (apply && v.verdict().equals("changed")) {
                Map<String, Object> value = f.kind().equals("price") ? PriceParser.parse(v.value()).map(pp -> pp.toMap(v.value())).orElse(null) : null;
                newFact = jdbc.sql("""
                                INSERT INTO fact (destination_id, entry_id, section, kind, heading, value_text, value_json, volatility,
                                                  status, origin, observed_at, observed_basis, supersedes_fact_id, ingest_key)
                                SELECT destination_id, entry_id, section, kind, heading, :v, CAST(:j AS jsonb), volatility,
                                       'published', 'ingested', :d, 'source_listing_date', id, :key
                                FROM fact WHERE id = :old RETURNING id""")
                        .param("v", v.value()).param("j", value == null ? null : json.write(value)).param("d", today)
                        .param("key", "check:" + f.id() + ":" + today).param("old", f.id()).query(UUID.class).single();
                jdbc.sql("UPDATE fact SET status = 'superseded', updated_at = now() WHERE id = :id").param("id", f.id()).update();
                evidence = evidence(newFact, v, today);
                observation(newFact, v.value(), today);
            }
            jdbc.sql("""
                            INSERT INTO fact_check (destination_id, entry_id, fact_id, verdict, outcome, page_url, quote, new_value, reason,
                                                    new_fact_id, evidence_id, previous_observed_at, previous_basis, model, pages_read)
                            VALUES (:d, :e, :f, :verdict, :outcome, :url, :quote, :value, :reason, :newFact, :ev, :prevObs, :prevBasis,
                                    :model, :pages)""")
                    .param("d", place.id()).param("e", f.entryId() == null ? item.entryId() : f.entryId()).param("f", f.id())
                    .param("verdict", v.verdict()).param("outcome", outcome).param("url", v.pageUrl()).param("quote", v.quote())
                    .param("value", v.value()).param("reason", v.reason() == null ? null : v.reason().length() > 500 ? v.reason().substring(0, 500) : v.reason())
                    .param("newFact", newFact).param("ev", evidence).param("prevObs", f.observedAt()).param("prevBasis", f.observedBasis())
                    .param("model", v.model()).param("pages", v.pages()).update();
        });
    }

    private UUID evidence(UUID factId, Verdict v, LocalDate today) {
        return jdbc.sql("""
                        INSERT INTO evidence (fact_id, source_id, url, title, excerpt, extraction_method, observed_at, retrieved_at)
                        VALUES (:f, 'official-website', :url, 'Official website (checked automatically)', :quote, 'automatic_check', :d, now())
                        RETURNING id""")
                .param("f", factId).param("url", v.pageUrl()).param("quote", v.quote()).param("d", today).query(UUID.class).single();
    }

    private void observation(UUID factId, String value, LocalDate today) {
        jdbc.sql("""
                        INSERT INTO fact_observation (fact_id, value_text, observed_at, change_type, source_id)
                        VALUES (:f, :v, :d, 'automatic_check', 'official-website')""")
                .param("f", factId).param("v", value).param("d", today).update();
    }

    /** Reverses an applied check: the old date or the old value comes back exactly as it was. */
    public void undo(UUID checkId, UUID adminId) {
        UUID destination = tx.execute(s -> {
            var c = jdbc.sql("""
                            SELECT destination_id, fact_id, verdict, new_fact_id, evidence_id, previous_observed_at, previous_basis
                            FROM fact_check WHERE id = :id AND outcome = 'applied' AND undone_at IS NULL FOR UPDATE""")
                    .param("id", checkId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("Applied check"));
            UUID fact = (UUID) c.get("fact_id");
            if ("confirmed".equals(c.get("verdict"))) {
                jdbc.sql("UPDATE fact SET observed_at = :o, observed_basis = :b, updated_at = now() WHERE id = :id")
                        .param("o", c.get("previous_observed_at")).param("b", c.get("previous_basis") == null ? "unknown" : c.get("previous_basis"))
                        .param("id", fact).update();
                if (c.get("evidence_id") != null) {
                    jdbc.sql("DELETE FROM evidence WHERE id = :id").param("id", c.get("evidence_id")).update();
                }
            } else {
                jdbc.sql("UPDATE fact SET status = 'retracted', updated_at = now() WHERE id = :id").param("id", c.get("new_fact_id")).update();
                jdbc.sql("UPDATE fact SET status = 'published', updated_at = now() WHERE id = :id").param("id", fact).update();
            }
            jdbc.sql("UPDATE fact_check SET undone_at = now(), undone_by = :a WHERE id = :id").param("a", adminId).param("id", checkId).update();
            return (UUID) c.get("destination_id");
        });
        changes.destinationChanged(destination);
    }

    /**
     * Plans checks: destinations with stale prices or hours on listings that have a website, biggest guides first,
     * a few per run so the free model allowance is shared with the listing review. Runs every six hours.
     */
    @Scheduled(cron = "0 40 */6 * * *")
    public int plan() {
        if (!llm.active()) {
            return 0;
        }
        List<UUID> due = jdbc.sql("""
                        SELECT p.id FROM place p JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        WHERE EXISTS (SELECT 1 FROM fact f JOIN fact w ON w.entry_id = f.entry_id AND w.kind = 'website' AND w.status = 'published'
                                      WHERE f.destination_id = p.id AND f.kind IN ('price', 'hours') AND f.status = 'published'
                                        AND (f.observed_at IS NULL OR f.observed_at < now() - interval '1 year'))
                          AND NOT EXISTS (SELECT 1 FROM fact_check c WHERE c.destination_id = p.id
                                          AND c.created_at > now() - make_interval(days => :days))
                          AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.target_place_id = p.id AND j.job_type = 'fact_check'
                                          AND j.status IN ('queued', 'running'))
                        ORDER BY (SELECT count(*) FROM guide_entry e WHERE e.destination_id = p.id AND e.status = 'published') DESC
                        LIMIT 5""")
                .param("days", RECHECK_DAYS).query(UUID.class).list();
        due.forEach(id -> jobs.enqueue("fact_check", "official-website", id, Map.of(), "fact_check:" + id, 2, "scheduler"));
        if (!due.isEmpty()) {
            log.info("Queued automatic fact checks for {} destinations", due.size());
        }
        return due.size();
    }
}
