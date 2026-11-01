package io.destinationintel.ai;

import io.destinationintel.common.Json;
import io.destinationintel.place.SearchService;
import io.destinationintel.place.SearchService.SearchHit;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Natural-language search ("hill station within 4 hours of Bangalore that's cool in April"). A model only turns the
 * question into filters (start, distance, month, kind of place); the places come from Saarthi's own stored data and
 * each carries the sourced reason it matched (a quoted sentence, the elevation, the month's weather averages, the
 * drive time). The model never names a place, so it cannot invent one. Questions are cached; when the model is off or
 * its allowance for search is used up, the caller falls back to name search.
 */
@Service
public class QuestionSearch {

    /** Kinds of place a question can ask for, each with the evidence that counts for it. */
    public enum PlaceType {
        hill_station("Hill stations", "\\bhill station|\\bhill resort|\\bhills\\b|\\bghats?\\b|\\bplateau\\b"),
        beach("Beaches", "\\bbeach(es)?\\b|\\bseaside\\b|\\bcoast(al|line)?\\b"),
        heritage("Heritage", "\\bheritage\\b|\\bunesco\\b|\\bruins?\\b|\\bforts?\\b|\\bpalaces?\\b|\\bmonuments?\\b|\\bancient\\b|\\bmedieval\\b|\\barchaeolog"),
        wildlife("Wildlife", "\\bnational park\\b|\\bwildlife\\b|\\bsanctuary\\b|\\btiger reserve\\b|\\bsafari\\b|\\belephants?\\b"),
        pilgrimage("Pilgrimage", "\\bpilgrim(age|s)?\\b|\\bshrines?\\b|\\bholy\\b|\\bsacred\\b|\\btemple town\\b|\\bjyotirlinga\\b"),
        waterfall("Waterfalls", "\\bwaterfalls?\\b|\\bfalls\\b"),
        lake("Lakes and backwaters", "\\blakes?\\b|\\bbackwaters?\\b"),
        trek("Treks", "\\btrek(s|king)?\\b|\\bhik(e|es|ing)\\b|\\btrails?\\b"),
        island("Islands", "\\bislands?\\b");

        final String label;
        final Pattern pattern;

        PlaceType(String label, String regex) {
            this.label = label;
            this.pattern = Pattern.compile("(?i)" + regex);
        }
    }

    /** What the model understood; every field optional. */
    public record Intent(String origin, String near, Double maxHours, Double maxKm, Integer month, List<String> types,
                         String state, List<String> unsupported) {}

    public record PlaceRef(String slug, String name) {}

    public record Reason(String text, String sourceName, String sourceUrl) {}

    public record Result(String slug, String name, String kind, String state, Integer distanceKm, Integer driveMinutes,
                         String coverageStatus, String imageUrl, List<Reason> reasons) {}

    public record Answer(String query, String mode, List<String> understood, List<String> unsupported, List<String> notes,
                         PlaceRef origin, List<Result> results) {}

    static final int MAX_RESULTS = 15;
    /** Average speed used to turn hours into straight-line km where no drive times are computed (stated on the page). */
    static final double KM_PER_HOUR = 55;
    static final double HILL_ELEVATION_M = 1000;
    static final double MILD_MAX_C = 32;
    static final double MAX_RAINY_DAYS = 6;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
    private static final Pattern QUESTION = Pattern.compile("(?i)\\b(near|nearby|around|within|from|hours?|hrs?|km|weekend|trip|"
            + "trips|places?|getaways?|best|where|which|what|beach(es)?|hill|hills|waterfalls?|wildlife|treks?|trekking|heritage|"
            + "pilgrimage|cool|cold|warm|rain|monsoon|january|february|march|april|may|june|july|august|september|october|"
            + "november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b|\\?");

    static final String SYSTEM = """
            You turn a traveller's question about places in India into search filters for a travel guide. You never answer
            the question and never name places of your own: fill only what the question itself says.
            - origin: the town or city the trip starts from ("from Bangalore", "weekend from Pune"), as written.
            - near: a place the results should be close to, when that is different from the start ("near Mysore", "around
              Statue of Unity"), as written.
            - maxHours: the longest travel time asked for, in hours ("within 4 hours", "half a day's drive" = 4). For a
              weekend trip with no time given, leave it empty.
            - maxKm: a distance limit in kilometres, if one is stated.
            - month: the month of travel as a number 1-12 ("in April" = 4, "this December" = 12, "next month" from today).
            - types: kinds of place asked for, only from the allowed list ("hill station", "cool hills" = hill_station;
              "sea", "beach" = beach; "forts, palaces, temples, history" = heritage; "safari, tigers" = wildlife).
            - state: an Indian state or union territory the places must be in, if stated.
            - unsupported: short phrases from the question that none of these fields can express ("quiet", "cheap",
              "for kids", "less crowded"), so the guide can say it could not filter by them.
            """;

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "origin", Map.of("type", "string"),
                    "near", Map.of("type", "string"),
                    "maxHours", Map.of("type", "number"),
                    "maxKm", Map.of("type", "number"),
                    "month", Map.of("type", "integer"),
                    "types", Map.of("type", "array", "items", Map.of("type", "string",
                            "enum", java.util.Arrays.stream(PlaceType.values()).map(Enum::name).toList())),
                    "state", Map.of("type", "string"),
                    "unsupported", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("types", "unsupported"));

    private final LlmClient llm;
    private final SearchService names;
    private final JdbcClient jdbc;
    private final Json json;
    private final int dailyCap;

    public QuestionSearch(LlmClient llm, SearchService names, JdbcClient jdbc, Json json,
                          @Value("${app.ai.search-daily-cap:300}") int dailyCap) {
        this.llm = llm;
        this.names = names;
        this.jdbc = jdbc;
        this.json = json;
        this.dailyCap = dailyCap;
    }

    /** Whether the text reads like a question rather than a place name. */
    public static boolean looksLikeQuestion(String q) {
        if (q == null) {
            return false;
        }
        String t = q.trim();
        return t.split("\\s+").length >= 4 || QUESTION.matcher(t).find();
    }

    public Answer ask(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.length() < 3 || !looksLikeQuestion(q)) {
            return new Answer(q, "name", List.of(), List.of(), List.of(), null, List.of());
        }
        // A place's own name ("Nandi Hills") is answered by name search, without asking a model.
        List<SearchHit> exact = names.search(q, 1).results();
        if (!exact.isEmpty() && "exact".equals(exact.get(0).matchType())) {
            return new Answer(q, "name", List.of(), List.of(), List.of(), null, List.of());
        }
        Optional<Intent> intent = intent(q);
        if (intent.isEmpty()) {
            return new Answer(q, "unavailable", List.of(), List.of(), List.of(), null, List.of());
        }
        return search(q, intent.get());
    }

    Optional<Intent> intent(String q) {
        String key = q.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        Optional<String> cached = jdbc.sql("""
                        SELECT intent::text FROM search_intent WHERE query_norm = :k AND created_at > now() - interval '30 days'""")
                .param("k", key).query(String.class).optional();
        if (cached.isPresent()) {
            return Optional.of(toIntent(json.map(cached.get())));
        }
        if (!llm.active()) {
            return Optional.empty();
        }
        int today = jdbc.sql("SELECT count(*) FROM search_intent WHERE created_at > date_trunc('day', now())").query(Integer.class).single();
        if (today >= dailyCap) {
            return Optional.empty();
        }
        LlmClient.Answer a;
        try {
            String user = "Today is " + LocalDate.now(ZoneId.of("Asia/Kolkata")) + ".\nQuestion: " + q;
            a = llm.generateJson(SYSTEM, user, SCHEMA, LlmClient.Purpose.BULK);
        } catch (LlmClient.BudgetExhausted | LlmClient.RateLimited | LlmClient.AiUnavailable e) {
            return Optional.empty();
        }
        Map<String, Object> m;
        try {
            m = json.map(a.json());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Intent intent = toIntent(m);
        jdbc.sql("""
                        INSERT INTO search_intent (query_norm, intent, model, created_at) VALUES (:k, CAST(:i AS jsonb), :m, now())
                        ON CONFLICT (query_norm) DO UPDATE SET intent = EXCLUDED.intent, model = EXCLUDED.model, created_at = now()""")
                .param("k", key).param("i", json.write(m)).param("m", a.model()).update();
        return Optional.of(intent);
    }

    /** Reads the model's answer defensively: anything out of range or off the list is dropped, not trusted. */
    static Intent toIntent(Map<String, Object> m) {
        Double hours = num(m.get("maxHours"));
        Double km = num(m.get("maxKm"));
        Double month = num(m.get("month"));
        List<String> types = new ArrayList<>();
        if (m.get("types") instanceof List<?> l) {
            for (Object o : l) {
                for (PlaceType t : PlaceType.values()) {
                    if (t.name().equals(String.valueOf(o)) && !types.contains(t.name())) {
                        types.add(t.name());
                    }
                }
            }
        }
        List<String> unsupported = new ArrayList<>();
        if (m.get("unsupported") instanceof List<?> l) {
            l.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty() && s.length() <= 40).limit(5).forEach(unsupported::add);
        }
        return new Intent(text(m.get("origin")), text(m.get("near")),
                hours != null && hours > 0 && hours <= 24 ? hours : null,
                km != null && km > 0 && km <= 2000 ? km : null,
                month != null && month >= 1 && month <= 12 ? month.intValue() : null,
                types, text(m.get("state")), unsupported);
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String text(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || s.length() > 80 ? null : s;
    }

    record Candidate(UUID id, String slug, String name, String kind, String state, Integer km, Integer driveMinutes,
                     String coverage, String imageUrl, String description, String summary, String summarySource,
                     String summaryUrl, String climate, String smog) {}

    Answer search(String q, Intent intent) {
        List<String> notes = new ArrayList<>();
        List<String> understood = new ArrayList<>();
        PlaceRef origin = resolve(intent.origin(), "start", notes);
        PlaceRef near = resolve(intent.near(), "place", notes);
        PlaceRef centre = near != null ? near : origin;
        PlaceRef state = resolveState(intent.state(), notes);

        boolean routes = false;
        Double maxKm = intent.maxKm();
        Integer maxMinutes = null;
        if (centre != null && intent.maxHours() != null) {
            routes = origin != null && near == null && jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM road_route r JOIN place p ON p.id = r.origin_id
                                           WHERE p.slug = :s AND r.duration_s IS NOT NULL)""")
                    .param("s", centre.slug()).query(Boolean.class).single();
            if (routes) {
                maxMinutes = (int) Math.round(intent.maxHours() * 60);
                maxKm = Math.max(maxKm == null ? 0 : maxKm, intent.maxHours() * 110);
            } else {
                double est = intent.maxHours() * KM_PER_HOUR;
                maxKm = maxKm == null ? est : Math.min(maxKm, est);
                notes.add("Drive times are not computed from " + centre.name() + " yet, so this uses straight-line distance "
                        + "(about " + (int) KM_PER_HOUR + " km for each hour of driving).");
            }
        }
        if (centre != null && maxKm == null) {
            maxKm = near != null ? 150.0 : 350.0;
        }
        if (origin != null) {
            understood.add("From " + origin.name());
        }
        if (near != null) {
            understood.add("Near " + near.name());
        }
        if (intent.maxHours() != null) {
            understood.add("Up to " + fmt(intent.maxHours()) + " h " + (routes ? "by road" : "away"));
        } else if (centre != null) {
            understood.add("Within " + Math.round(maxKm) + " km");
        }
        if (intent.month() != null) {
            understood.add("Mild in " + MONTHS[intent.month() - 1]);
        }
        for (String t : intent.types()) {
            understood.add(PlaceType.valueOf(t).label);
        }
        if (state != null) {
            understood.add("In " + state.name());
        }

        List<Candidate> candidates = candidates(centre, origin != null && near == null ? origin : null, maxKm, state);
        List<Result> results = new ArrayList<>();
        int noWeather = 0;
        for (Candidate c : candidates) {
            if (maxMinutes != null && (c.driveMinutes() == null || c.driveMinutes() > maxMinutes)) {
                continue;
            }
            List<Reason> reasons = new ArrayList<>();
            if (c.driveMinutes() != null && origin != null && near == null) {
                reasons.add(new Reason("About " + drive(c.driveMinutes()) + " from " + origin.name() + " by road", "OSRM / OpenStreetMap", null));
            } else if (c.km() != null && centre != null) {
                reasons.add(new Reason("About " + c.km() + " km from " + centre.name() + " in a straight line", null, null));
            }
            boolean typesOk = true;
            for (String t : intent.types()) {
                Reason r = typeReason(PlaceType.valueOf(t), c);
                if (r == null) {
                    typesOk = false;
                    break;
                }
                reasons.add(r);
            }
            if (!typesOk) {
                continue;
            }
            if (intent.month() != null) {
                Reason r = monthReason(intent.month(), c);
                if (r == null) {
                    if (c.climate() == null) {
                        noWeather++;
                    }
                    continue;
                }
                reasons.add(r);
            }
            results.add(new Result(c.slug(), c.name(), c.kind(), c.state(), c.km(), c.driveMinutes(), c.coverage(), c.imageUrl(), reasons));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        if (noWeather > 0) {
            notes.add(noWeather + " place" + (noWeather == 1 ? " was" : "s were") + " left out because Saarthi has no weather data for them yet.");
        }
        if (centre == null && state == null && intent.types().isEmpty() && intent.month() == null) {
            notes.add("Saarthi could not find a place, distance, month or kind of place in this question.");
        }
        return new Answer(q, "question", understood, intent.unsupported(), notes, origin, results);
    }

    private List<Candidate> candidates(PlaceRef centre, PlaceRef origin, Double maxKm, PlaceRef state) {
        String sql = """
                WITH c AS (SELECT geog FROM place WHERE slug = :centre),
                     o AS (SELECT id FROM place WHERE slug = :origin)
                SELECT p.id, p.slug, p.name, p.kind, st.name AS state,
                       CASE WHEN c.geog IS NULL THEN NULL ELSE round(ST_Distance(p.geog, c.geog) / 1000)::int END AS km,
                       CASE WHEN rr.snap_m <= 5000 THEN round(rr.duration_s / 60.0)::int END AS drive_minutes,
                       dp.coverage_status AS coverage, img.image_url, p.description,
                       sf.value_text AS summary, sf.source_name AS summary_source, sf.source_url AS summary_url,
                       (SELECT value_json::text FROM fact WHERE destination_id = p.id AND kind = 'climate' AND status = 'published' LIMIT 1) AS climate,
                       (SELECT value_json::text FROM fact WHERE ingest_key = 'aq:' || p.id || ':smog' AND status = 'published') AS smog
                FROM place p
                JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                LEFT JOIN c ON true
                LEFT JOIN o ON true
                LEFT JOIN road_route rr ON rr.origin_id = o.id AND rr.destination_id = p.id
                LEFT JOIN place_image img ON img.place_id = p.id
                LEFT JOIN LATERAL (SELECT pp.name, pp.slug FROM place_relation r JOIN place pp ON pp.id = r.parent_id
                                   WHERE r.child_id = p.id AND r.relation = 'in_state' LIMIT 1) st ON true
                LEFT JOIN LATERAL (SELECT f.value_text, s.name AS source_name, coalesce(e.permalink, e.url) AS source_url
                                   FROM fact f LEFT JOIN evidence e ON e.fact_id = f.id LEFT JOIN source s ON s.id = e.source_id
                                   WHERE f.destination_id = p.id AND f.entry_id IS NULL AND f.kind = 'summary' AND f.status = 'published'
                                   ORDER BY f.created_at LIMIT 1) sf ON true
                WHERE p.is_destination AND p.lifecycle_status = 'active' AND p.geog IS NOT NULL
                  AND p.kind IN ('city', 'town', 'village', 'park', 'island', 'attraction')
                  AND (CAST(:centre AS text) IS NULL OR (p.slug <> :centre AND ST_DWithin(p.geog, c.geog, :metres)))
                  AND (CAST(:origin AS text) IS NULL OR ST_Distance(p.geog, c.geog) > 20000)
                  AND (CAST(:state AS text) IS NULL OR st.slug = :state)
                ORDER BY coalesce(rr.duration_s / 60.0 * 1.1, ST_Distance(p.geog, c.geog) / 1000),
                         CASE dp.coverage_status WHEN 'well_covered' THEN 0 WHEN 'usable' THEN 1 WHEN 'needs_refresh' THEN 1 ELSE 2 END,
                         coalesce(p.sitelink_count, 0) DESC
                LIMIT 400""";
        return jdbc.sql(sql)
                .param("centre", centre == null ? null : centre.slug()).param("origin", origin == null ? null : origin.slug())
                .param("metres", (maxKm == null ? 350 : maxKm) * 1000).param("state", state == null ? null : state.slug())
                .query(Candidate.class).list();
    }

    /** The sourced reason a place counts as this kind, or null when nothing stored says so. */
    Reason typeReason(PlaceType t, Candidate c) {
        Optional<String> fromSummary = sentence(c.summary(), t.pattern);
        if (fromSummary.isPresent()) {
            return new Reason("“" + fromSummary.get() + "”", c.summarySource() == null ? "Wikivoyage" : c.summarySource(), c.summaryUrl());
        }
        if (c.description() != null && t.pattern.matcher(c.description()).find()) {
            return new Reason("“" + c.description() + "”", "Wikidata", null);
        }
        if (t == PlaceType.wildlife && c.kind().equals("park")) {
            return new Reason("A national park or reserve", "Wikidata", null);
        }
        if (t == PlaceType.island && c.kind().equals("island")) {
            return new Reason("An island", "Wikidata", null);
        }
        if (t == PlaceType.hill_station && c.climate() != null) {
            Object elev = json.map(c.climate()).get("elevationM");
            if (elev instanceof Number n && n.doubleValue() >= HILL_ELEVATION_M) {
                return new Reason(String.format(Locale.ROOT, "About %,d m above sea level", Math.round(n.doubleValue() / 10) * 10),
                        "Open-Meteo", null);
            }
        }
        return null;
    }

    /** "Usually mild in April: average high 27°C, 2 rainy days", or null when the month is hot, wet or smoggy. */
    Reason monthReason(int month, Candidate c) {
        if (c.climate() == null) {
            return null;
        }
        if (c.smog() != null && json.map(c.smog()).get("smogMonths") instanceof List<?> smog
                && smog.stream().anyMatch(o -> o instanceof Number n && n.intValue() == month)) {
            return null;
        }
        Map<String, Object> climate = json.map(c.climate());
        if (!(climate.get("months") instanceof List<?> months)) {
            return null;
        }
        for (Object o : months) {
            if (o instanceof Map<?, ?> m && m.get("month") instanceof Number mo && mo.intValue() == month
                    && m.get("meanMaxC") instanceof Number max) {
                double rainy = m.get("rainyDays") instanceof Number r ? r.doubleValue() : 0;
                if (max.doubleValue() > MILD_MAX_C || rainy > MAX_RAINY_DAYS) {
                    return null;
                }
                return new Reason(String.format(Locale.ROOT, "Usually mild in %s: average high %d°C, %d rainy days (%s averages)",
                        MONTHS[month - 1], Math.round(max.doubleValue()), Math.round(rainy), climate.getOrDefault("period", "10-year")),
                        "Open-Meteo", null);
            }
        }
        return null;
    }

    /** The first sentence of the text that matches, trimmed to a readable quote. */
    static Optional<String> sentence(String text, Pattern p) {
        if (text == null) {
            return Optional.empty();
        }
        for (String s : text.split("(?<=[.!?])\\s+")) {
            Matcher m = p.matcher(s);
            if (m.find()) {
                String t = s.trim();
                if (t.length() > 220) {
                    int start = Math.max(0, m.start() - 100);
                    int end = Math.min(t.length(), m.end() + 100);
                    t = (start > 0 ? "…" : "") + t.substring(start, end).trim() + (end < t.length() ? "…" : "");
                }
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    private PlaceRef resolve(String name, String what, List<String> notes) {
        if (name == null) {
            return null;
        }
        List<SearchHit> hits = names.search(name, 1).results();
        if (hits.isEmpty() || hits.get(0).score() < 0.5 || hits.get(0).latitude() == null) {
            notes.add("Saarthi could not find the " + what + " “" + name + "”, so it is not used.");
            return null;
        }
        SearchHit h = hits.get(0);
        String slug = "neighbourhood".equals(h.kind()) && h.parentCitySlug() != null ? h.parentCitySlug() : h.slug();
        String label = "neighbourhood".equals(h.kind()) && h.parentCityName() != null ? h.parentCityName() : h.name();
        return new PlaceRef(slug, label);
    }

    private PlaceRef resolveState(String name, List<String> notes) {
        if (name == null) {
            return null;
        }
        return names.search(name, 5).results().stream()
                .filter(h -> ("state".equals(h.kind()) || "union_territory".equals(h.kind())) && h.score() >= 0.5)
                .findFirst().map(h -> new PlaceRef(h.slug(), h.name()))
                .or(() -> {
                    notes.add("Saarthi could not find the state “" + name + "”, so it is not used.");
                    return Optional.empty();
                }).orElse(null);
    }

    private static String fmt(double h) {
        return h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h);
    }

    static String drive(int minutes) {
        int m = Math.max(5, Math.round(minutes / 5f) * 5);
        int h = m / 60;
        int rest = m % 60;
        return h == 0 ? rest + " min" : rest == 0 ? h + " h" : h + " h " + rest + " min";
    }
}
