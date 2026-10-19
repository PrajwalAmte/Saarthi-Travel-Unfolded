package io.destinationintel.ingestion.commons;

import io.destinationintel.common.UrlSafety;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.wikidata.EntityInfo;
import io.destinationintel.ingestion.wikidata.WikidataClient;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.source.SourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "place_image": the destination image Wikidata links (P18), with author and licence
 * from Wikimedia Commons. Images without a stated free licence are not stored.
 */
@Component
public class PlaceImageHandler implements JobHandler {

    static final String SOURCE_ID = "wikimedia-commons";

    private final WikidataClient wikidata;
    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final PlaceRepository places;
    private final SourceRepository sources;
    private final JdbcClient jdbc;
    private final String commonsApi;
    private final io.destinationintel.ingestion.run.SyncRunRecorder recorder;
    private final io.destinationintel.quality.QualityService quality;

    public PlaceImageHandler(WikidataClient wikidata, HttpFetcher http, JsonMapper mapper, PlaceRepository places,
                             SourceRepository sources, JdbcClient jdbc, AppProperties props, io.destinationintel.ingestion.run.SyncRunRecorder recorder,
                             io.destinationintel.quality.QualityService quality) {
        this.wikidata = wikidata;
        this.http = http;
        this.mapper = mapper;
        this.places = places;
        this.sources = sources;
        this.jdbc = jdbc;
        this.commonsApi = props.ingestion().commonsApiUrl();
        this.recorder = recorder;
        this.quality = quality;
    }

    @Override
    public String type() {
        return "place_image";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The Wikimedia Commons source is disabled in the source registry");
        }
        PlaceRow place = places.findById(job.targetPlaceId())
                .orElseThrow(() -> new PermanentFailure("Target destination no longer exists"));
        if (place.wikidataId() == null) {
            throw new PermanentFailure(place.name() + " has no Wikidata item");
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        io.destinationintel.ingestion.run.SyncRunRecorder.Run run = recorder.start(SOURCE_ID, job.id(), "place:" + place.slug());
        try {
            JsonNode entity = wikidata.entities(List.of(place.wikidataId()), "claims").path(place.wikidataId());
            String file = EntityInfo.parse(entity).imageFile();
            if (file == null) {
                stats.put("image", "none linked in Wikidata");
                return finish(run, stats, "no_image_linked_in_wikidata");
            }
            Map<String, String> q = new LinkedHashMap<>();
            q.put("action", "query");
            q.put("titles", "File:" + file);
            q.put("prop", "imageinfo");
            q.put("iiprop", "url|size|extmetadata");
            q.put("iiurlwidth", "1600");
            q.put("iiextmetadatafilter", "Artist|Credit|LicenseShortName|LicenseUrl|NonFree|AttributionRequired");
            q.put("format", "json");
            q.put("formatversion", "2");
            JsonNode page = mapper.readTree(http.get(commonsApi, q, "application/json")).path("query").path("pages").path(0);
            JsonNode info = page.path("imageinfo").path(0);
            run.fetched.incrementAndGet();
            if (info.isMissingNode()) {
                stats.put("image", "file not found on Commons");
                return finish(run, stats, "file_missing_on_commons");
            }
            JsonNode meta = info.path("extmetadata");
            String licence = text(meta.path("LicenseShortName").path("value").asString(null));
            boolean nonFree = "true".equalsIgnoreCase(meta.path("NonFree").path("value").asString(""));
            if (licence == null || licence.isBlank() || nonFree) {
                stats.put("image", "skipped: no free licence stated");
                return finish(run, stats, "no_free_licence");
            }
            String thumb = stripTracking(info.path("thumburl").asString(info.path("url").asString(null)));
            String pageUrl = info.path("descriptionurl").asString(null);
            if (thumb == null || !thumb.startsWith("https://") || pageUrl == null || !UrlSafety.isSafe(pageUrl)) {
                throw new PermanentFailure("Commons returned an unexpected image URL");
            }
            String artist = text(meta.path("Artist").path("value").asString(null));
            if (artist == null || artist.isBlank()) {
                artist = text(meta.path("Credit").path("value").asString(null));
            }
            if (artist == null || artist.isBlank()) {
                artist = authorFromWikitext(file);
            }
            boolean attributionRequired = !"false".equalsIgnoreCase(meta.path("AttributionRequired").path("value").asString("true"));
            if ((artist == null || artist.isBlank()) && attributionRequired) {
                // The licence requires crediting the author, and no source names one: the image is not used.
                stats.put("image", "skipped: the author required by the licence is not stated");
                jdbc.sql("DELETE FROM place_image WHERE place_id = :p AND file_title = :f").param("p", place.id()).param("f", file).update();
                return finish(run, stats, "no_author_credit");
            }
            String licenceUrl = UrlSafety.normalize(meta.path("LicenseUrl").path("value").asString(null)).orElse(null);
            Boolean inserted = jdbc.sql("""
                            INSERT INTO place_image (place_id, source_id, file_title, image_url, width, height, page_url, artist,
                                                     licence_name, licence_url, fetched_at)
                            VALUES (:p, :s, :f, :u, :w, :h, :page, :artist, :lic, :licUrl, now())
                            ON CONFLICT (place_id) DO UPDATE SET file_title = EXCLUDED.file_title, image_url = EXCLUDED.image_url,
                                width = EXCLUDED.width, height = EXCLUDED.height, page_url = EXCLUDED.page_url,
                                artist = EXCLUDED.artist, licence_name = EXCLUDED.licence_name,
                                licence_url = EXCLUDED.licence_url, fetched_at = now()
                            RETURNING (xmax = 0)
                            """)
                    .param("p", place.id()).param("s", SOURCE_ID).param("f", file).param("u", thumb)
                    .param("w", info.path("thumbwidth").asInt(0) == 0 ? null : info.path("thumbwidth").asInt())
                    .param("h", info.path("thumbheight").asInt(0) == 0 ? null : info.path("thumbheight").asInt())
                    .param("page", pageUrl).param("artist", artist == null ? null : truncate(artist, 300))
                    .param("lic", licence).param("licUrl", licenceUrl).query(Boolean.class).single();
            run.parsed.incrementAndGet();
            (Boolean.TRUE.equals(inserted) ? run.inserted : run.updated).incrementAndGet();
            stats.put("image", file);
            stats.put("licence", licence);
            return finish(run, stats, null);
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }

    /** Records the run (a skip reason counts as a rejection of the one candidate) and evaluates quality. */
    private Map<String, Object> finish(io.destinationintel.ingestion.run.SyncRunRecorder.Run run, Map<String, Object> stats, String skipped) {
        if (skipped != null) {
            run.parsed.incrementAndGet();
            run.reject(skipped);
        }
        recorder.stage(run, "quality");
        String status = quality.evaluate(SOURCE_ID, run.id, run.scope).status();
        if (!"failed".equals(status)) {
            run.published.set(skipped == null ? 1 : 0);
            recorder.markPublished(SOURCE_ID);
        }
        stats.put("quality", status);
        recorder.finish(run, "failed".equals(status), false);
        return stats;
    }

    private static final java.util.regex.Pattern FLICKR_REVIEW = java.util.regex.Pattern.compile(
            "\\{\\{\\s*User:FlickreviewR/reviewed-pass(?:-change)?\\s*\\|([^|}]+)\\|");
    private static final java.util.regex.Pattern INFO_AUTHOR = java.util.regex.Pattern.compile(
            "(?im)^\\s*\\|\\s*author\\s*=\\s*(.+?)\\s*$");

    /**
     * The author from the file page itself when the structured metadata has none: the Information
     * template's author field, or the Flickr user recorded by Commons' Flickr review of a transferred photo.
     */
    String authorFromWikitext(String file) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("titles", "File:" + file);
        q.put("prop", "revisions");
        q.put("rvprop", "content");
        q.put("rvslots", "main");
        q.put("format", "json");
        q.put("formatversion", "2");
        String wikitext = mapper.readTree(http.get(commonsApi, q, "application/json")).path("query").path("pages").path(0)
                .path("revisions").path(0).path("slots").path("main").path("content").asString("");
        return authorFromWikitextContent(wikitext);
    }

    static String authorFromWikitextContent(String wikitext) {
        var info = INFO_AUTHOR.matcher(wikitext);
        if (info.find()) {
            String a = info.group(1).replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*)]]", "$1")
                    .replaceAll("\\[https?://\\S+\\s+([^\\]]+)]", "$1").replaceAll("\\{\\{[^}]*}}", "").trim();
            if (!a.isBlank()) {
                return a;
            }
        }
        var flickr = FLICKR_REVIEW.matcher(wikitext);
        if (flickr.find()) {
            return flickr.group(1).trim() + " (Flickr)";
        }
        return null;
    }

    static String text(String html) {
        if (html == null) {
            return null;
        }
        String t = Jsoup.parse(html).text().trim();
        return t.isEmpty() ? null : t;
    }

    static String stripTracking(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf("?utm_");
        return q > 0 ? url.substring(0, q) : url;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
