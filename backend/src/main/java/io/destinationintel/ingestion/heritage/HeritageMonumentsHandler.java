package io.destinationintel.ingestion.heritage;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.ingestion.wikidata.WikidataClient;
import io.destinationintel.poi.PoiCategories.Category;
import io.destinationintel.poi.PoiPipeline;
import io.destinationintel.poi.RawPoi;
import io.destinationintel.source.SourceRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Job "heritage_monuments": protected monuments identified by an Archaeological Survey of India
 * monument number (Wikidata P1371). ASI publishes its register only as per-circle web pages and
 * PDFs, so the structured route is Wikidata, whose statements are CC0 and cite the ASI number;
 * no ASI page is scraped. One record per Wikidata item (an item can carry several ASI numbers).
 * Monuments without coordinates cannot be placed on a map; they are counted as a coverage gap
 * of the source, not silently dropped.
 */
@Component
public class HeritageMonumentsHandler implements JobHandler {

    public static final String SOURCE_ID = "asi-heritage";

    private static final String CORE = """
            SELECT ?item ?asiId ?coord ?label ?sitelinks WHERE {
              ?item wdt:P1371 ?asiId .
              OPTIONAL { ?item wdt:P625 ?coord }
              OPTIONAL { ?item rdfs:label ?label FILTER(LANG(?label) = "en") }
              OPTIONAL { ?item wikibase:sitelinks ?sitelinks }
            }""";
    private static final String TYPES = """
            SELECT ?item ?typeLabel WHERE {
              ?item wdt:P1371 [] ; wdt:P31 ?type .
              ?type rdfs:label ?typeLabel FILTER(LANG(?typeLabel) = "en")
            }""";
    private static final String CONTEXT = """
            SELECT ?item ?designationLabel ?admin ?localLabel WHERE {
              ?item wdt:P1371 [] .
              OPTIONAL { ?item wdt:P1435 ?d . ?d rdfs:label ?designationLabel FILTER(LANG(?designationLabel) = "en") }
              OPTIONAL { ?item wdt:P131 ?a . ?a rdfs:label ?admin FILTER(LANG(?admin) = "en") }
              OPTIONAL { ?item rdfs:label ?localLabel
                         FILTER(LANG(?localLabel) IN ("hi", "kn", "ta", "te", "ml", "mr", "bn", "gu", "pa", "or", "as", "ur")) }
            }""";

    private final WikidataClient wikidata;
    private final PoiPipeline pipeline;
    private final SyncRunRecorder recorder;
    private final SourceRepository sources;
    private final AppProperties.Ingestion config;

    public HeritageMonumentsHandler(WikidataClient wikidata, PoiPipeline pipeline, SyncRunRecorder recorder,
                                    SourceRepository sources, AppProperties props) {
        this.wikidata = wikidata;
        this.pipeline = pipeline;
        this.recorder = recorder;
        this.sources = sources;
        this.config = props.ingestion();
    }

    @Override
    public String type() {
        return "heritage_monuments";
    }

    /** Everything known about one Wikidata item carrying an ASI number. */
    static final class Monument {
        final String qid;
        final TreeSet<String> asiIds = new TreeSet<>();
        final TreeSet<String> types = new TreeSet<>();
        final TreeSet<String> designations = new TreeSet<>();
        final TreeSet<String> adminAreas = new TreeSet<>();
        final TreeMap<String, String> localLabels = new TreeMap<>();
        String label;
        double[] point;
        Integer sitelinks;

        Monument(String qid) {
            this.qid = qid;
        }
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The ASI heritage source is disabled in the source registry");
        }
        Run run = recorder.start(SOURCE_ID, job.id(), "india:asi-monument-ids");
        try {
            String version = "wikidata-query@" + LocalDate.now(ZoneOffset.UTC);
            run.datasetVersion(version);
            recorder.stage(run, "fetch");
            Map<String, Monument> items = new LinkedHashMap<>();
            for (Map<String, String> row : wikidata.select(CORE)) {
                run.fetched.incrementAndGet();
                String qid = WikidataClient.qid(row.get("item"));
                if (qid == null) {
                    run.reject("invalid_entity_id");
                    continue;
                }
                Monument m = items.computeIfAbsent(qid, Monument::new);
                if (row.get("asiId") != null && !row.get("asiId").isBlank()) {
                    m.asiIds.add(row.get("asiId").trim());
                }
                if (m.point == null) {
                    m.point = WikidataClient.point(row.get("coord"));
                }
                if (m.label == null) {
                    m.label = row.get("label");
                }
                if (row.get("sitelinks") != null) {
                    m.sitelinks = Integer.valueOf(row.get("sitelinks"));
                }
            }
            for (Map<String, String> row : wikidata.select(TYPES)) {
                Monument m = items.get(WikidataClient.qid(row.get("item")));
                if (m != null && row.get("typeLabel") != null) {
                    m.types.add(row.get("typeLabel"));
                }
            }
            for (Map<String, String> row : wikidata.select(CONTEXT)) {
                Monument m = items.get(WikidataClient.qid(row.get("item")));
                if (m == null) {
                    continue;
                }
                if (row.get("designationLabel") != null) {
                    m.designations.add(row.get("designationLabel"));
                }
                if (row.get("admin") != null) {
                    m.adminAreas.add(row.get("admin"));
                }
                if (row.get("localLabel") != null) {
                    m.localLabels.putIfAbsent("local", row.get("localLabel"));
                }
            }

            OffsetDateTime runStart = OffsetDateTime.now(ZoneOffset.UTC);
            recorder.stage(run, "parse");
            PoiPipeline.Session session = pipeline.open(run, runStart);
            Map<String, Long> designations = new TreeMap<>();
            for (Monument m : items.values()) {
                m.designations.forEach(d -> designations.merge(d, 1L, Long::sum));
                if (m.point == null) {
                    // In scope but unplaceable: a gap in the source, reported in the quality report.
                    run.parsed.incrementAndGet();
                    run.reject("missing_coordinates");
                    continue;
                }
                session.accept(toRaw(m, version));
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("wikidataItems", items.size());
            stats.put("designations", designations);
            stats.putAll(pipeline.finish(run, session, runStart, PoiPipeline.Scope.fullRelease(), config.poiDestinationRadiusKm()));
            stats.put("run", run.summary());
            return stats;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }

    static RawPoi toRaw(Monument m, String datasetVersion) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("asi_monument_ids", List.copyOf(m.asiIds));
        if (!m.designations.isEmpty()) {
            attrs.put("designation", List.copyOf(m.designations));
        }
        if (!m.adminAreas.isEmpty()) {
            attrs.put("administrative_area", String.join(", ", m.adminAreas));
        }
        if (!m.localLabels.isEmpty()) {
            attrs.put("local_name", m.localLabels.firstEntry().getValue());
        }
        attrs.put("wikidata", m.qid);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("item", "http://www.wikidata.org/entity/" + m.qid);
        raw.put("P1371", List.copyOf(m.asiIds));
        raw.put("P31", List.copyOf(m.types));
        raw.put("P1435", List.copyOf(m.designations));
        raw.put("P131", List.copyOf(m.adminAreas));
        raw.put("P625", m.point == null ? null : List.of(m.point[0], m.point[1]));
        raw.put("label_en", m.label);
        raw.put("sitelinks", m.sitelinks);
        return new RawPoi(SOURCE_ID, m.qid, null, null, datasetVersion, m.label, String.join("|", m.types),
                category(m.types), m.point == null ? null : m.point[0], m.point == null ? null : m.point[1], m.qid, attrs, raw);
    }

    /** Category from the item's "instance of" labels; every monument is something to see. */
    static Category category(java.util.Collection<String> types) {
        List<String> t = new ArrayList<>();
        types.forEach(x -> t.add(x.toLowerCase(Locale.ROOT)));
        if (t.stream().anyMatch(x -> x.contains("fort") || x.contains("castle"))) {
            return new Category("fort", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("palace"))) {
            return new Category("palace", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("tomb") || x.contains("mausoleum") || x.contains("cenotaph") || x.contains("chhatri"))) {
            return new Category("tomb", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("temple") || x.contains("mosque") || x.contains("church") || x.contains("shrine")
                || x.contains("stupa") || x.contains("monastery") || x.contains("gurdwara") || x.contains("dargah"))) {
            return new Category("heritage_place_of_worship", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("archaeological") || x.contains("ruins") || x.contains("mound")
                || x.contains("cairn") || x.contains("cist") || x.contains("megalith") || x.contains("stone circle"))) {
            return new Category("archaeological_site", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("cave") || x.contains("rock-cut"))) {
            return new Category("cave", "see");
        }
        if (t.stream().anyMatch(x -> x.contains("museum"))) {
            return new Category("museum", "see");
        }
        return new Category("protected_monument", "see");
    }
}
