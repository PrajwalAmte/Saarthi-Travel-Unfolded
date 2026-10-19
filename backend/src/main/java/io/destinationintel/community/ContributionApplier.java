package io.destinationintel.community;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.UrlSafety;
import io.destinationintel.community.ContributionRepository.ContributionRow;
import io.destinationintel.guide.PriceParser;
import io.destinationintel.ingestion.wikivoyage.AttributeDeriver;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an accepted contribution to published data, and reverses it if a steward later
 * reverts the decision. Previous values are superseded, never deleted, so history and
 * verification records remain intact.
 */
@Component
public class ContributionApplier {

    public record Applied(UUID entryId, UUID factId) {}

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final Json json;
    private final io.destinationintel.guide.PendingVerifications pending;

    public ContributionApplier(JdbcClient jdbc, Json json, io.destinationintel.guide.PendingVerifications pending) {
        this.jdbc = jdbc;
        this.json = json;
        this.pending = pending;
    }

    private record FactRef(UUID id, UUID destinationId, UUID entryId, String section, String kind, String heading,
                           String volatility, String status) {}

    private record EntryRef(UUID id, UUID destinationId, String section, String status, String attributes) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Applied apply(ContributionRow c) {
        Map<String, Object> p = json.map(c.payload());
        LocalDate reported = c.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        return switch (c.type()) {
            case "correction" -> {
                FactRef f = fact(c.targetFactId());
                if (!f.status().equals("published")) {
                    throw ApiException.conflict("target_changed",
                            "The fact this correction targets has changed since it was submitted. Reject it and ask for a new correction.");
                }
                String value = String.valueOf(p.get("value")).trim();
                Map<String, Object> valueJson = structured(f.kind(), value);
                UUID id = insertFact(f.destinationId(), f.entryId(), f.section(), f.kind(), f.heading(), value, valueJson,
                        f.volatility(), reported, f.id(), c);
                jdbc.sql("UPDATE fact SET status = 'superseded', updated_at = now() WHERE id = :id").param("id", f.id()).update();
                pending.withdrawForReplacedFact(f.id());
                observation(id, value, valueJson, reported, "community_correction", c.id());
                if (f.kind().equals("price") && f.entryId() != null) {
                    refreshPriceTier(f.entryId(), f.section(), value, reported);
                }
                yield new Applied(f.entryId(), id);
            }
            case "closure_report" -> {
                EntryRef e = entry(c.targetEntryId());
                boolean permanent = "permanent".equals(p.get("closure"));
                String basis = "Reported " + (permanent ? "permanently" : "temporarily") + " closed by a contributor on "
                        + DAY.format(reported);
                if (permanent) {
                    jdbc.sql("UPDATE guide_entry SET status = 'closed', status_reason = :r, updated_at = now() WHERE id = :id")
                            .param("r", basis).param("id", e.id()).update();
                } else {
                    jdbc.sql("""
                                    UPDATE guide_entry SET attributes = attributes || jsonb_build_object('temporarilyClosed',
                                        jsonb_build_object('value', CAST(:d AS text), 'basis', CAST(:b AS text))), updated_at = now()
                                    WHERE id = :id
                                    """)
                            .param("d", reported.toString()).param("b", basis).param("id", e.id()).update();
                }
                descriptionFact(e.id()).ifPresent(fid -> observation(fid, basis, null, reported, "closure_report", c.id()));
                yield new Applied(e.id(), null);
            }
            case "duplicate_report" -> {
                EntryRef e = entry(c.targetEntryId());
                UUID other = UUID.fromString(String.valueOf(p.get("duplicateOfEntryId")));
                EntryRef keep = entry(other);
                if (!keep.destinationId().equals(e.destinationId())) {
                    throw ApiException.badRequest("invalid_duplicate", "Both entries must belong to the same destination.");
                }
                jdbc.sql("""
                                UPDATE guide_entry SET status = 'merged', merged_into_id = :keep,
                                       status_reason = 'Merged as a duplicate after a contributor report', updated_at = now()
                                WHERE id = :id
                                """)
                        .param("keep", keep.id()).param("id", e.id()).update();
                yield new Applied(e.id(), null);
            }
            case "incorrect_report" -> {
                if (c.targetFactId() != null) {
                    FactRef f = fact(c.targetFactId());
                    jdbc.sql("UPDATE fact SET status = 'retracted', updated_at = now() WHERE id = :id").param("id", f.id()).update();
                    yield new Applied(f.entryId(), f.id());
                }
                EntryRef e = entry(c.targetEntryId());
                jdbc.sql("""
                                UPDATE guide_entry SET status = 'hidden', status_reason = 'Removed after a contributor report',
                                       updated_at = now() WHERE id = :id
                                """).param("id", e.id()).update();
                yield new Applied(e.id(), null);
            }
            case "new_place" -> {
                String section = String.valueOf(p.get("section"));
                String name = str(p.get("name"));
                String description = str(p.get("description"));
                Optional<PriceParser.ParsedPrice> price = PriceParser.parse(str(p.get("price")));
                Map<String, Object> attrs = AttributeDeriver.derive(section, null, name, description, price, reported,
                        List.of(), null, Integer.MAX_VALUE, "a contributor's report");
                UUID entryId = jdbc.sql("""
                                INSERT INTO guide_entry (destination_id, section, title, latitude, longitude, attributes, status, origin)
                                VALUES (:d, :section, :title, :lat, :lon, CAST(:attrs AS jsonb), 'published', 'community')
                                RETURNING id
                                """)
                        .param("d", c.destinationId()).param("section", section).param("title", name)
                        .param("lat", p.get("latitude") instanceof Number n ? n.doubleValue() : null)
                        .param("lon", p.get("longitude") instanceof Number n ? n.doubleValue() : null)
                        .param("attrs", json.write(attrs)).query(UUID.class).single();
                UUID descriptionId = insertFact(c.destinationId(), entryId, section, "description", null, description, null,
                        "semi_stable", reported, null, c);
                observation(descriptionId, description, null, reported, "initial", c.id());
                optionalFact(c, entryId, section, "what_to_order", str(p.get("whatToOrder")), "semi_stable", reported);
                optionalFact(c, entryId, section, "price", str(p.get("price")), "volatile", reported);
                optionalFact(c, entryId, section, "hours", str(p.get("hours")), "volatile", reported);
                optionalFact(c, entryId, section, "address", str(p.get("address")), "semi_stable", reported);
                UrlSafety.normalize(str(p.get("website")))
                        .ifPresent(url -> optionalFact(c, entryId, section, "website", url, "semi_stable", reported));
                yield new Applied(entryId, descriptionId);
            }
            case "new_tip" -> {
                String section = String.valueOf(p.get("section"));
                String kind = switch (section) {
                    case "getting_around" -> "transport";
                    case "neighbourhood" -> "neighbourhood_character";
                    case "eat" -> "speciality";
                    case "stay" -> "accommodation_note";
                    case "practical" -> "seasonality";
                    default -> "general_tip";
                };
                String text = str(p.get("text"));
                String volatility = kind.equals("speciality") || kind.equals("neighbourhood_character") ? "stable" : "semi_stable";
                UUID id = insertFact(c.destinationId(), null, section, kind, str(p.get("heading")), text, null, volatility,
                        reported, null, c);
                observation(id, text, null, reported, "initial", c.id());
                yield new Applied(null, id);
            }
            default -> throw new IllegalStateException("Unknown contribution type " + c.type());
        };
    }

    /** Undoes an accepted contribution. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revert(ContributionRow c) {
        switch (c.type()) {
            case "correction" -> {
                if (c.resultFactId() != null) {
                    jdbc.sql("UPDATE fact SET status = 'retracted', updated_at = now() WHERE id = :id").param("id", c.resultFactId()).update();
                    jdbc.sql("""
                                    UPDATE fact SET status = 'published', updated_at = now()
                                    WHERE id = (SELECT supersedes_fact_id FROM fact WHERE id = :id) AND status = 'superseded'
                                    """).param("id", c.resultFactId()).update();
                }
            }
            case "closure_report" -> {
                jdbc.sql("""
                                UPDATE guide_entry SET status = CASE WHEN status = 'closed' THEN 'published' ELSE status END,
                                       status_reason = NULL, attributes = attributes - 'temporarilyClosed', updated_at = now()
                                WHERE id = :id
                                """).param("id", c.targetEntryId()).update();
            }
            case "duplicate_report" -> jdbc.sql("""
                            UPDATE guide_entry SET status = 'published', merged_into_id = NULL, status_reason = NULL, updated_at = now()
                            WHERE id = :id AND status = 'merged'
                            """).param("id", c.targetEntryId()).update();
            case "incorrect_report" -> {
                if (c.targetFactId() != null) {
                    jdbc.sql("UPDATE fact SET status = 'published', updated_at = now() WHERE id = :id AND status = 'retracted'")
                            .param("id", c.targetFactId()).update();
                } else {
                    jdbc.sql("""
                                    UPDATE guide_entry SET status = 'published', status_reason = NULL, updated_at = now()
                                    WHERE id = :id AND status = 'hidden'
                                    """).param("id", c.targetEntryId()).update();
                }
            }
            case "new_place" -> jdbc.sql("UPDATE guide_entry SET status = 'removed', status_reason = 'Contribution reverted', updated_at = now() WHERE id = :id")
                    .param("id", c.resultEntryId()).update();
            case "new_tip" -> jdbc.sql("UPDATE fact SET status = 'retracted', updated_at = now() WHERE id = :id")
                    .param("id", c.resultFactId()).update();
            default -> throw new IllegalStateException("Unknown contribution type " + c.type());
        }
    }

    private void optionalFact(ContributionRow c, UUID entryId, String section, String kind, String value,
                              String volatility, LocalDate reported) {
        if (value == null || value.isBlank()) {
            return;
        }
        Map<String, Object> structured = structured(kind, value);
        UUID id = insertFact(c.destinationId(), entryId, section, kind, null, value, structured, volatility, reported, null, c);
        observation(id, value, structured, reported, "initial", c.id());
    }

    private UUID insertFact(UUID destinationId, UUID entryId, String section, String kind, String heading, String value,
                            Map<String, Object> valueJson, String volatility, LocalDate observed, UUID supersedes,
                            ContributionRow c) {
        UUID id = jdbc.sql("""
                        INSERT INTO fact (destination_id, entry_id, section, kind, heading, value_text, value_json, volatility,
                                          status, origin, observed_at, observed_basis, supersedes_fact_id, contribution_id)
                        VALUES (:d, :e, :section, :kind, :heading, :value, CAST(:json AS jsonb), :volatility, 'published',
                                'community', :observed, 'community_report', :supersedes, :contribution)
                        RETURNING id
                        """)
                .param("d", destinationId).param("e", entryId).param("section", section).param("kind", kind)
                .param("heading", heading).param("value", value).param("json", valueJson == null ? null : json.write(valueJson))
                .param("volatility", volatility).param("observed", observed).param("supersedes", supersedes)
                .param("contribution", c.id())
                .query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO evidence (fact_id, source_id, url, title, excerpt, extraction_method, observed_at,
                                              retrieved_at, contributor_user_id)
                        VALUES (:fact, 'community', :url, 'Contributor report', :excerpt, 'community_submission', :observed,
                                now(), :user)
                        """)
                .param("fact", id).param("url", c.evidenceUrl()).param("excerpt", c.note())
                .param("observed", observed).param("user", c.userId()).update();
        return id;
    }

    private void observation(UUID factId, String value, Map<String, Object> valueJson, LocalDate observed, String change,
                             UUID contributionId) {
        jdbc.sql("""
                        INSERT INTO fact_observation (fact_id, value_text, value_json, observed_at, change_type, source_id, contribution_id)
                        VALUES (:f, :v, CAST(:j AS jsonb), :o, :c, 'community', :contribution)
                        """)
                .param("f", factId).param("v", value).param("j", valueJson == null ? null : json.write(valueJson))
                .param("o", observed).param("c", change).param("contribution", contributionId).update();
    }

    private void refreshPriceTier(UUID entryId, String section, String priceText, LocalDate observed) {
        EntryRef e = entry(entryId);
        Map<String, Object> attrs = new LinkedHashMap<>(json.map(e.attributes()));
        Object tier = attrs.get("priceTier");
        boolean priceBased = tier instanceof Map<?, ?> m && String.valueOf(m.get("basis")).startsWith("Price ");
        if (!priceBased && tier != null) {
            return;
        }
        Map<String, Object> derived = AttributeDeriver.derive(section, null, "", "", PriceParser.parse(priceText),
                observed, List.of(), null, Integer.MAX_VALUE, "a contributor's report");
        if (derived.containsKey("priceTier")) {
            attrs.put("priceTier", derived.get("priceTier"));
        } else {
            attrs.remove("priceTier");
        }
        jdbc.sql("UPDATE guide_entry SET attributes = CAST(:a AS jsonb), updated_at = now() WHERE id = :id")
                .param("a", json.write(attrs)).param("id", entryId).update();
    }

    private Map<String, Object> structured(String kind, String value) {
        if (kind.equals("price")) {
            return PriceParser.parse(value).map(pp -> pp.toMap(value)).orElse(null);
        }
        if (kind.equals("website")) {
            return UrlSafety.normalize(value).<Map<String, Object>>map(u -> Map.of("url", u)).orElse(null);
        }
        return null;
    }

    private Optional<UUID> descriptionFact(UUID entryId) {
        return jdbc.sql("SELECT id FROM fact WHERE entry_id = :e AND kind = 'description' AND status = 'published' LIMIT 1")
                .param("e", entryId).query(UUID.class).optional();
    }

    private FactRef fact(UUID id) {
        return jdbc.sql("SELECT id, destination_id, entry_id, section, kind, heading, volatility, status FROM fact WHERE id = :id FOR UPDATE")
                .param("id", id).query(FactRef.class).optional()
                .orElseThrow(() -> ApiException.notFound("Target fact"));
    }

    private EntryRef entry(UUID id) {
        return jdbc.sql("SELECT id, destination_id, section, status, attributes::text AS attributes FROM guide_entry WHERE id = :id FOR UPDATE")
                .param("id", id).query(EntryRef.class).optional()
                .orElseThrow(() -> ApiException.notFound("Target entry"));
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
