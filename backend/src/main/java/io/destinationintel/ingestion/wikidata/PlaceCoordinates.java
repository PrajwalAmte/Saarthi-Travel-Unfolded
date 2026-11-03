package io.destinationintel.ingestion.wikidata;

import io.destinationintel.common.GeoPlausibility;
import io.destinationintel.ingestion.wikivoyage.WikivoyageClient;
import io.destinationintel.place.PlaceRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Corrects a place stored far from where it is. Some Wikidata items hold several coordinates of equal rank and the
 * nationwide index samples one, so Kevadia was stored 200 km from the Statue of Unity. Wikivoyage alone is not trusted
 * (its coordinates are sometimes the wrong ones), so a place moves only when two sources agree: Wikidata holds more
 * than one statement and one of them matches the Wikivoyage article. Data computed from the old location is cleared
 * so it is worked out again.
 */
@Service
public class PlaceCoordinates {

    private static final Logger log = LoggerFactory.getLogger(PlaceCoordinates.class);

    /** Further apart than this, the stored point and Wikivoyage disagree. */
    static final double DISAGREE_KM = 25;
    /** Within this, a Wikidata statement and Wikivoyage agree. */
    static final double AGREE_KM = 5;
    /** States and regions have many reasonable centre points; only places with one real location are corrected. */
    private static final Set<String> KINDS = Set.of("city", "town", "village", "park", "island", "attraction", "neighbourhood");

    private final WikivoyageClient wikivoyage;
    private final WikidataClient wikidata;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PlaceCoordinates(WikivoyageClient wikivoyage, WikidataClient wikidata, JdbcClient jdbc, TransactionTemplate tx) {
        this.wikivoyage = wikivoyage;
        this.wikidata = wikidata;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Checks one place against its Wikivoyage article; true when it was moved. */
    public boolean verify(PlaceRow place) {
        if (!checkable(place)) {
            return false;
        }
        double[] article = wikivoyage.coordinatesOf(List.of(place.wikivoyageTitle())).get(place.wikivoyageTitle().replace('_', ' '));
        return article != null && verifyAgainst(place, article);
    }

    /** As {@link #verify(PlaceRow)}, when the article's coordinates are already known. */
    public boolean verifyAgainst(PlaceRow place, double[] article) {
        if (!checkable(place) || !GeoPlausibility.insideIndia(article[0], article[1])
                || km(place.latitude(), place.longitude(), article[0], article[1]) <= DISAGREE_KM) {
            return false;
        }
        JsonNode statements = wikidata.entities(List.of(place.wikidataId()), "claims").path(place.wikidataId()).path("claims").path("P625");
        double[] agreed = agreeing(statements, article);
        if (agreed == null) {
            return false;
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'two_sources', updated_at = now()
                            WHERE id = :id""")
                    .param("lat", agreed[0]).param("lon", agreed[1]).param("id", place.id()).update();
            jdbc.sql("DELETE FROM place_source_sync WHERE place_id = :id AND source_id IN ('open-meteo', 'open-meteo-air', 'ourairports')")
                    .param("id", place.id()).update();
            jdbc.sql("DELETE FROM road_route WHERE origin_id = :id OR destination_id = :id").param("id", place.id()).update();
        });
        log.info("Moved {} from {},{} to {},{}: Wikidata and Wikivoyage agree", place.slug(), place.latitude(), place.longitude(), agreed[0], agreed[1]);
        return true;
    }

    private static boolean checkable(PlaceRow p) {
        return p.hasCoordinates() && p.wikidataId() != null && p.wikivoyageTitle() != null && KINDS.contains(p.kind());
    }

    /** The Wikidata statement matching the article, when the item holds more than one; otherwise null. */
    static double[] agreeing(JsonNode statements, double[] article) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode st : statements) {
            JsonNode v = st.path("mainsnak").path("datavalue").path("value");
            if (!"deprecated".equals(st.path("rank").asString()) && v.has("latitude") && v.has("longitude")) {
                points.add(new double[] {v.path("latitude").asDouble(), v.path("longitude").asDouble()});
            }
        }
        if (points.size() < 2) {
            return null;
        }
        for (double[] p : points) {
            if (km(p[0], p[1], article[0], article[1]) <= AGREE_KM) {
                return p;
            }
        }
        return null;
    }

    static double km(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(a));
    }
}
