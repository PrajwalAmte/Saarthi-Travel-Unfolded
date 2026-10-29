package io.destinationintel.request;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Destination requests from readers (no account). Requests are normalised, de-duplicated by
 * name and by resolved place, counted as demand, and connected to ingestion. A request is
 * reported as published only when the destination's coverage actually passes the rules.
 */
@Service
public class RequestService {

    public record RequestRow(
            UUID id,
            String trackingCode,
            String queryText,
            String normalizedQuery,
            String stateHint,
            UUID resolvedPlaceId,
            String status,
            String statusDetail,
            UUID mergedIntoId,
            int demandCount,
            String candidates,
            UUID lastJobId,
            OffsetDateTime firstRequestedAt,
            OffsetDateTime lastRequestedAt) {}

    public record Candidate(String wikidataId, String label, String description) {}

    public record RequestView(
            String trackingCode,
            String query,
            String stateHint,
            String status,
            String statusLabel,
            String statusDetail,
            int demandCount,
            DestinationRef destination,
            List<Candidate> candidates,
            String jobStatus,
            GuideEstimator.Estimate estimate,
            boolean emailUpdates,
            OffsetDateTime firstRequestedAt,
            OffsetDateTime lastRequestedAt) {}

    public record DestinationRef(String slug, String name, String kind, String coverageStatus) {}

    private static final String COLUMNS = """
            id, tracking_code, query_text, normalized_query, state_hint, resolved_place_id, status, status_detail,
            merged_into_id, demand_count, candidates::text AS candidates, last_job_id, first_requested_at, last_requested_at""";
    /** Reader requests jump ahead of bulk imports (20) but not behind-the-scenes follow-ups such as photos (29-30). */
    static final int RESOLVE_PRIORITY = 26;
    static final int GUIDE_PRIORITY = 25;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final JdbcClient jdbc;
    private final PlaceRepository places;
    private final JobRepository jobs;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final Json json;
    private final AppProperties props;
    private final GuideEstimator estimator;
    private final RequestNotifications notifications;

    public RequestService(JdbcClient jdbc, PlaceRepository places, JobRepository jobs, RateLimiter rateLimiter,
                          AuditService audit, Json json, AppProperties props, GuideEstimator estimator,
                          RequestNotifications notifications) {
        this.jdbc = jdbc;
        this.places = places;
        this.jobs = jobs;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.json = json;
        this.props = props;
        this.estimator = estimator;
        this.notifications = notifications;
    }

    @Transactional
    public RequestView submit(String rawQuery, String rawStateHint, String rawEmail, String clientIp) {
        rateLimiter.check("destination-request", clientIp, props.rateLimits().requestsPerHour(), Duration.ofHours(1));
        String email = RequestNotifications.clean(rawEmail);
        RequestView created = create(rawQuery, rawStateHint, clientIp);
        if (email != null) {
            notifications.subscribe(byCode(created.trackingCode()).id(), email);
        }
        return created;
    }

    /** Adds an email to an existing request; the reader gets one email when the guide is ready. */
    @Transactional
    public RequestView notifyMe(String trackingCode, String rawEmail, String clientIp) {
        rateLimiter.check("destination-request-notify", clientIp, props.rateLimits().requestsPerHour(), Duration.ofHours(1));
        String email = RequestNotifications.clean(rawEmail);
        if (email == null) {
            throw ApiException.badRequest("invalid_email", "Enter your email address.");
        }
        RequestRow r = followMerge(byCode(trackingCode));
        notifications.subscribe(r.id(), email);
        return view(r.trackingCode());
    }

    private RequestView create(String rawQuery, String rawStateHint, String clientIp) {
        String query = rawQuery == null ? "" : rawQuery.trim().replaceAll("\\s+", " ");
        if (query.length() < 2 || query.length() > 120 || !query.matches(".*\\p{L}.*")) {
            throw ApiException.badRequest("invalid_query", "Enter the name of a place in India (2 to 120 characters).");
        }
        String stateHint = StringUtils.hasText(rawStateHint) ? rawStateHint.trim() : null;
        if (stateHint != null && stateHint.length() > 60) {
            throw ApiException.badRequest("invalid_state", "The state name is too long.");
        }
        String normalized = places.normalize(query);
        if (normalized.isBlank()) {
            throw ApiException.badRequest("invalid_query", "Enter the name of a place in India.");
        }

        Optional<RequestRow> existing = jdbc.sql("SELECT " + COLUMNS + """
                         FROM destination_request WHERE normalized_query = :n AND coalesce(state_hint, '') = coalesce(:s, '')
                        FOR UPDATE""")
                .param("n", normalized).param("s", stateHint).query(RequestRow.class).optional();
        if (existing.isPresent()) {
            RequestRow target = followMerge(existing.get());
            addDemand(target.id());
            return view(target.trackingCode());
        }

        Optional<PlaceRow> local = exactLocalMatch(normalized, stateHint);
        if (local.isPresent()) {
            Optional<RequestRow> forPlace = jdbc.sql("SELECT " + COLUMNS + """
                             FROM destination_request WHERE resolved_place_id = :p AND status <> 'merged'
                            ORDER BY first_requested_at LIMIT 1 FOR UPDATE""")
                    .param("p", local.get().id()).query(RequestRow.class).optional();
            if (forPlace.isPresent()) {
                addDemand(forPlace.get().id());
                return view(forPlace.get().trackingCode());
            }
        }

        String code = trackingCode();
        UUID id = jdbc.sql("""
                        INSERT INTO destination_request (tracking_code, query_text, normalized_query, state_hint, status)
                        VALUES (:code, :q, :n, :s, 'received') RETURNING id
                        """)
                .param("code", code).param("q", query).param("n", normalized).param("s", stateHint)
                .query(UUID.class).single();
        audit.record(null, "request.created", "destination_request", id, null, Map.of("query", query));
        if (local.isPresent()) {
            attachPlace(id, local.get());
        } else {
            UUID job = jobs.enqueue("resolve_request", "wikidata", null, Map.of("requestId", id.toString()),
                    "resolve_request:" + id, RESOLVE_PRIORITY, "request");
            jdbc.sql("UPDATE destination_request SET status = 'queued', last_job_id = :j, status_detail = :d, updated_at = now() WHERE id = :id")
                    .param("j", job).param("d", "Waiting to look this place up in Wikidata.").param("id", id).update();
        }
        return view(code);
    }

    /** Connects a request to an indexed place and queues guide ingestion when an open source covers it. */
    @Transactional
    public void attachPlace(UUID requestId, PlaceRow place) {
        Optional<RequestRow> other = jdbc.sql("SELECT " + COLUMNS + """
                         FROM destination_request WHERE resolved_place_id = :p AND status <> 'merged' AND id <> :id
                        ORDER BY first_requested_at LIMIT 1""")
                .param("p", place.id()).param("id", requestId).query(RequestRow.class).optional();
        if (other.isPresent()) {
            RequestRow me = find(requestId).orElseThrow();
            jdbc.sql("""
                            UPDATE destination_request SET status = 'merged', merged_into_id = :o, resolved_place_id = :p,
                                   status_detail = 'Merged with an earlier request for the same place', updated_at = now()
                            WHERE id = :id
                            """).param("o", other.get().id()).param("p", place.id()).param("id", requestId).update();
            jdbc.sql("UPDATE destination_request SET demand_count = demand_count + :n, last_requested_at = now(), updated_at = now() WHERE id = :o")
                    .param("n", me.demandCount()).param("o", other.get().id()).update();
            return;
        }
        UUID job = null;
        String status;
        String detail;
        String coverage = coverageStatus(place.id());
        int guidedPlaces = guidedPlacesIn(place);
        if (guidedPlaces > 0) {
            // A state's guide is its places. The overview article is still imported for travel basics.
            if (place.wikivoyageTitle() != null) {
                job = jobs.enqueue("wikivoyage_guide", "wikivoyage-en", place.id(), Map.of(), "wikivoyage_guide:" + place.id(), GUIDE_PRIORITY, "request");
            }
            status = "published";
            detail = hubDetail(place, guidedPlaces);
        } else if (coverage != null && !coverage.equals("indexed")) {
            status = coverage.equals("building") ? "building" : "published";
            detail = coverage.equals("building") ? starterDetail(place) : "A guide is available.";
        } else if (place.wikivoyageTitle() != null) {
            job = jobs.enqueue("wikivoyage_guide", "wikivoyage-en", place.id(), Map.of(), "wikivoyage_guide:" + place.id(), GUIDE_PRIORITY, "request");
            status = "processing";
            detail = "Ingesting the open Wikivoyage guide for this place. It will be published only if it passes the quality checks.";
        } else {
            if (place.hasCoordinates()) {
                job = jobs.enqueue("climate", "open-meteo", place.id(), Map.of(), "climate:" + place.id(), 3, "request");
            }
            status = "indexed_no_guide";
            detail = "This place is in the index, but no open guide source covers it yet. Local contributors can start its guide.";
        }
        jdbc.sql("""
                        UPDATE destination_request SET resolved_place_id = :p, status = :s, status_detail = :d,
                               last_job_id = coalesce(:j, last_job_id), updated_at = now()
                        WHERE id = :id
                        """)
                .param("p", place.id()).param("s", status).param("d", detail).param("j", job).param("id", requestId).update();
        audit.record(null, "request.resolved", "destination_request", requestId, place.id(), Map.of("status", status));
    }

    @Transactional
    public void markUnresolvable(UUID requestId, String detail) {
        jdbc.sql("UPDATE destination_request SET status = 'unresolvable', status_detail = :d, updated_at = now() WHERE id = :id")
                .param("d", detail).param("id", requestId).update();
    }

    @Transactional
    public void markNeedsReview(UUID requestId, List<Candidate> candidates, String detail) {
        jdbc.sql("""
                        UPDATE destination_request SET status = 'needs_review', candidates = CAST(:c AS jsonb),
                               status_detail = :d, updated_at = now()
                        WHERE id = :id
                        """)
                .param("c", json.write(candidates)).param("d", detail).param("id", requestId).update();
    }

    /** Lets the requester (or an admin) pick one of the stored candidates for an ambiguous request. */
    @Transactional
    public RequestView choose(String trackingCode, String wikidataId, String clientIp) {
        rateLimiter.check("destination-request-choose", clientIp, props.rateLimits().requestsPerHour(), Duration.ofHours(1));
        RequestRow r = byCode(trackingCode);
        if (!r.status().equals("needs_review")) {
            throw ApiException.conflict("not_ambiguous", "This request does not need a choice.");
        }
        boolean listed = candidates(r).stream().anyMatch(c -> c.wikidataId().equals(wikidataId));
        if (!listed) {
            throw ApiException.badRequest("invalid_candidate", "Choose one of the listed places.");
        }
        UUID job = jobs.enqueue("resolve_request", "wikidata", null, Map.of("requestId", r.id().toString(), "wikidataId", wikidataId),
                "resolve_request:" + r.id(), RESOLVE_PRIORITY, "request");
        jdbc.sql("""
                        UPDATE destination_request SET status = 'queued', last_job_id = :j,
                               status_detail = 'Looking up the place you chose.', updated_at = now()
                        WHERE id = :id
                        """).param("j", job).param("id", r.id()).update();
        return view(trackingCode);
    }

    public RequestView view(String trackingCode) {
        RequestRow r = followMerge(byCode(trackingCode));
        DestinationRef dest = null;
        String status = r.status();
        String detail = r.statusDetail();
        String jobStatus = r.lastJobId() == null ? null
                : jdbc.sql("SELECT status FROM ingestion_job WHERE id = :id").param("id", r.lastJobId())
                .query(String.class).optional().orElse(null);
        if (r.resolvedPlaceId() != null) {
            PlaceRow p = places.findById(r.resolvedPlaceId()).orElse(null);
            if (p != null) {
                String coverage = coverageStatus(p.id());
                dest = new DestinationRef(p.slug(), p.name(), p.kind(), coverage == null ? "indexed" : coverage);
                int guidedPlaces = guidedPlacesIn(p);
                if (guidedPlaces > 0) {
                    status = "published";
                    detail = hubDetail(p, guidedPlaces);
                } else if (coverage != null && !coverage.equals("indexed")) {
                    status = coverage.equals("building") ? "building" : "published";
                    detail = coverage.equals("building") ? starterDetail(p) : "A guide is available.";
                } else if ("failed".equals(jobStatus)) {
                    status = "needs_review";
                    detail = "Ingestion did not complete. The maintainers can see the error and retry it.";
                } else if ("processing".equals(status) && "succeeded".equals(jobStatus)) {
                    status = "indexed_no_guide";
                    detail = "Ingestion finished, but the source did not yield enough content for a guide yet.";
                }
                if (!status.equals(r.status())) {
                    jdbc.sql("UPDATE destination_request SET status = :s, status_detail = :d, updated_at = now() WHERE id = :id")
                            .param("s", status).param("d", detail).param("id", r.id()).update();
                }
            }
        }
        GuideEstimator.Estimate estimate = null;
        if (status.equals("received") || status.equals("queued") || status.equals("processing")) {
            estimate = estimator.estimate(r.lastJobId(), !status.equals("processing")).orElse(null);
        }
        return new RequestView(r.trackingCode(), r.queryText(), r.stateHint(), status, label(status), detail,
                r.demandCount(), dest, status.equals("needs_review") ? candidates(r) : List.of(), jobStatus, estimate,
                notifications.available(), r.firstRequestedAt(), r.lastRequestedAt());
    }

    public List<RequestRow> list(String status, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM destination_request WHERE (CAST(:s AS text) IS NULL OR status = :s) AND status <> 'merged'
                        ORDER BY demand_count DESC, last_requested_at DESC LIMIT :limit OFFSET :offset""")
                .param("s", status).param("limit", limit).param("offset", offset).query(RequestRow.class).list();
    }

    public Optional<RequestRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM destination_request WHERE id = :id").param("id", id)
                .query(RequestRow.class).optional();
    }

    RequestRow byCode(String code) {
        if (code == null || !code.matches("^[a-z0-9]{6,16}$")) {
            throw ApiException.notFound("Request");
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM destination_request WHERE tracking_code = :c").param("c", code)
                .query(RequestRow.class).optional().orElseThrow(() -> ApiException.notFound("Request"));
    }

    private RequestRow followMerge(RequestRow r) {
        RequestRow cur = r;
        for (int i = 0; i < 5 && cur.mergedIntoId() != null; i++) {
            cur = find(cur.mergedIntoId()).orElse(cur);
        }
        return cur;
    }

    private void addDemand(UUID id) {
        jdbc.sql("UPDATE destination_request SET demand_count = demand_count + 1, last_requested_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    /** Exact name/alias match among indexed destinations, optionally narrowed by state. */
    Optional<PlaceRow> exactLocalMatch(String normalized, String stateHint) {
        List<UUID> ids = jdbc.sql("""
                        SELECT DISTINCT p.id FROM place_alias a JOIN place p ON p.id = a.place_id
                        WHERE a.normalized = :n AND p.is_destination AND p.lifecycle_status = 'active'
                          AND (CAST(:state AS text) IS NULL OR EXISTS (
                                SELECT 1 FROM place_relation r JOIN place s ON s.id = r.parent_id
                                WHERE r.child_id = p.id AND r.relation = 'in_state'
                                  AND s.name_normalized = idi_normalize(:state)))
                        LIMIT 3
                        """)
                .param("n", normalized).param("state", stateHint).query(UUID.class).list();
        return ids.size() == 1 ? places.findById(ids.get(0)) : Optional.empty();
    }

    /** For states, union territories and districts: how many of their places have a guide (0 for anything else). */
    int guidedPlacesIn(PlaceRow place) {
        if (!io.destinationintel.place.DestinationService.HUB_KINDS.contains(place.kind())) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(DISTINCT r.child_id) FROM place_relation r
                        JOIN place c ON c.id = r.child_id AND c.kind NOT IN ('district', 'neighbourhood')
                        JOIN destination_profile dp ON dp.place_id = r.child_id AND dp.coverage_status <> 'indexed'
                        WHERE r.parent_id = :p AND r.relation IN ('in_state', 'in_district')""")
                .param("p", place.id()).query(Long.class).single().intValue();
    }

    String hubDetail(PlaceRow place, int guidedPlaces) {
        return place.name() + " is " + io.destinationintel.place.DestinationService.hubNoun(place.kind())
                + ", so its guide is the guides for the places in it: " + guidedPlaces
                + (guidedPlaces == 1 ? " place has" : " places have") + " one. Start from the " + place.name() + " page.";
    }

    /**
     * A request is finished once everything the open sources have is imported, even when that is little. Say what the
     * guide has, honestly, instead of leaving the reader waiting for content that is not coming.
     */
    String starterDetail(PlaceRow place) {
        return "We imported everything open travel guides have on " + place.name() + ": " + guideSize(place.id())
                + ". It is a starter guide, so treat it as a starting point. People who know " + place.name()
                + " can add the places it is missing.";
    }

    /** "7 places so far: 5 to see and do, 1 to eat, none to stay", counting places, not branches. */
    String guideSize(UUID placeId) {
        Map<String, Object> c = jdbc.sql("""
                        SELECT count(DISTINCT (section, title_normalized)) FILTER (WHERE section IN ('see', 'do', 'buy')) AS explore,
                               count(DISTINCT (section, title_normalized)) FILTER (WHERE section IN ('eat', 'drink')) AS eat,
                               count(DISTINCT (section, title_normalized)) FILTER (WHERE section = 'stay') AS stay
                        FROM guide_entry WHERE destination_id = :p AND status = 'published'""")
                .param("p", placeId).query().singleRow();
        long explore = ((Number) c.get("explore")).longValue();
        long eat = ((Number) c.get("eat")).longValue();
        long stay = ((Number) c.get("stay")).longValue();
        long total = explore + eat + stay;
        if (total == 0) {
            return "a summary and travel basics, but no places to list yet";
        }
        return total + (total == 1 ? " place" : " places") + " so far (" + count(explore, "to see and do") + ", "
                + count(eat, "to eat and drink") + ", " + count(stay, "to stay") + ")";
    }

    private static String count(long n, String what) {
        return (n == 0 ? "none" : String.valueOf(n)) + " " + what;
    }

    private String coverageStatus(UUID placeId) {
        return jdbc.sql("SELECT coverage_status FROM destination_profile WHERE place_id = :p").param("p", placeId)
                .query(String.class).optional().orElse(null);
    }

    private List<Candidate> candidates(RequestRow r) {
        return json.list(r.candidates()).stream()
                .filter(o -> o instanceof Map<?, ?>)
                .map(o -> json.convert(o, Candidate.class))
                .toList();
    }

    static String label(String status) {
        return switch (status) {
            case "received", "queued" -> "Received";
            case "processing" -> "Being ingested";
            case "indexed_no_guide" -> "Indexed, no guide yet";
            case "building" -> "Starter guide ready";
            case "published" -> "Guide available";
            case "needs_review" -> "Needs a decision";
            case "unresolvable" -> "Place not found";
            default -> status;
        };
    }

    private static String trackingCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
