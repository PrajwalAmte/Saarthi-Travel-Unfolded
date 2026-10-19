package io.destinationintel.guide;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.FreshnessPolicy.Assessment;
import io.destinationintel.guide.FreshnessPolicy.Freshness;
import io.destinationintel.guide.FreshnessPolicy.VerificationStatus;
import io.destinationintel.guide.GuideBaseLoader.GuideBase;
import io.destinationintel.guide.GuideRows.EntryRow;
import io.destinationintel.guide.GuideRows.FactRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a destination's coverage status from stored data using explicit, configurable
 * quality rules. Every rule's outcome is stored so the UI can explain the status.
 *
 * <ul>
 *   <li><b>indexed</b>: the place exists but no guide content is published.</li>
 *   <li><b>building</b>: some content, but no summary or too few core sections.</li>
 *   <li><b>needs_refresh</b>: enough content, but too little of it is within its review window.</li>
 *   <li><b>usable</b>: summary plus enough items in enough core sections, reasonably fresh.</li>
 *   <li><b>well_covered</b>: every core section is deep and a share of items is community-verified.</li>
 * </ul>
 */
@Service
public class CoverageService {

    public record Check(String rule, boolean passed, String detail) {}

    public record SectionStat(int items, int fresh, int verified) {}

    public record CoverageResult(String status, Map<String, SectionStat> sections, List<Check> checks,
                                 double freshShare, double verifiedShare, boolean graduated) {}

    public record ProfileRow(UUID placeId, String coverageStatus, String sectionStats, String qualityChecks,
                             int version, OffsetDateTime computedAt, OffsetDateTime graduatedAt) {}

    private final JdbcClient jdbc;
    private final GuideBaseLoader loader;
    private final FactPresenter presenter;
    private final AppProperties.Coverage config;
    private final Json json;
    private final AuditService audit;

    public CoverageService(JdbcClient jdbc, GuideBaseLoader loader, FactPresenter presenter, AppProperties props,
                           Json json, AuditService audit) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.presenter = presenter;
        this.config = props.coverage();
        this.json = json;
        this.audit = audit;
    }

    public CoverageResult evaluate(GuideBase base) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (String s : config.coreSections()) {
            stats.put(s, new int[3]);
        }
        int totalItems = base.entries().size() + base.destinationFacts().size();

        for (EntryRow e : base.entries()) {
            String core = coreSectionOfEntry(e.section());
            if (core == null || !stats.containsKey(core)) {
                continue;
            }
            int[] st = stats.get(core);
            st[0]++;
            List<FactRow> facts = base.factsFor(e.id());
            boolean fresh = false;
            boolean verified = false;
            for (FactRow f : facts) {
                Assessment a = presenter.assess(f);
                if (a.freshness() == Freshness.CURRENT && a.status() != VerificationStatus.CONFLICTING_REPORTS
                        && (f.kind().equals("description") || facts.size() == 1)) {
                    fresh = true;
                }
                if (a.status() == VerificationStatus.COMMUNITY_VERIFIED) {
                    verified = true;
                }
            }
            if (fresh || verified) {
                st[1]++;
            }
            if (verified) {
                st[2]++;
            }
        }
        for (FactRow f : base.destinationFacts()) {
            String core = coreSectionOfFact(f);
            if (core == null || !stats.containsKey(core)) {
                continue;
            }
            int[] st = stats.get(core);
            st[0]++;
            Assessment a = presenter.assess(f);
            boolean verified = a.status() == VerificationStatus.COMMUNITY_VERIFIED;
            boolean fresh = a.freshness() == Freshness.CURRENT && a.status() != VerificationStatus.CONFLICTING_REPORTS
                    || a.freshness() == Freshness.UNKNOWN && "stable".equals(f.volatility()) && a.status() == VerificationStatus.SOURCE_BACKED;
            if (fresh || verified) {
                st[1]++;
            }
            if (verified) {
                st[2]++;
            }
        }

        Map<String, SectionStat> sections = new LinkedHashMap<>();
        int coreItems = 0;
        int freshItems = 0;
        int verifiedItems = 0;
        for (var en : stats.entrySet()) {
            int[] st = en.getValue();
            sections.put(en.getKey(), new SectionStat(st[0], st[1], st[2]));
            coreItems += st[0];
            freshItems += st[1];
            verifiedItems += st[2];
        }
        double freshShare = coreItems == 0 ? 0 : (double) freshItems / coreItems;
        double verifiedShare = coreItems == 0 ? 0 : (double) verifiedItems / coreItems;
        boolean hasSummary = base.destinationFacts().stream().anyMatch(f -> f.kind().equals("summary"));
        long sectionsMeetingMin = sections.values().stream().filter(s -> s.items() >= config.minEntriesPerCoreSection()).count();
        boolean allDeep = sections.values().stream().allMatch(s -> s.items() >= config.wellCoveredEntriesPerSection());

        List<Check> checks = new ArrayList<>();
        checks.add(new Check("has_content", totalItems > 0, totalItems + " published items"));
        checks.add(new Check("has_summary", hasSummary, hasSummary ? "A source-backed summary is available" : "No summary yet"));
        checks.add(new Check("core_sections", sectionsMeetingMin >= config.minCoreSectionsForUsable(),
                sectionsMeetingMin + " of " + sections.size() + " core sections have at least "
                        + config.minEntriesPerCoreSection() + " items (" + config.minCoreSectionsForUsable() + " required)"));
        checks.add(new Check("freshness", freshShare >= config.minFreshShare(),
                Math.round(freshShare * 100) + "% of core items are within their review window or community-verified ("
                        + Math.round(config.minFreshShare() * 100) + "% required)"));
        checks.add(new Check("depth", allDeep, "Every core section has at least " + config.wellCoveredEntriesPerSection()
                + " items: " + (allDeep ? "yes" : "no")));
        checks.add(new Check("community_verification", verifiedShare >= config.wellCoveredVerifiedShare(),
                Math.round(verifiedShare * 100) + "% of core items are community-verified ("
                        + Math.round(config.wellCoveredVerifiedShare() * 100) + "% required for well-covered)"));

        String status;
        if (totalItems == 0) {
            status = "indexed";
        } else if (!hasSummary || sectionsMeetingMin < config.minCoreSectionsForUsable()) {
            status = "building";
        } else if (freshShare < config.minFreshShare()) {
            status = "needs_refresh";
        } else if (allDeep && verifiedShare >= config.wellCoveredVerifiedShare()) {
            status = "well_covered";
        } else {
            status = "usable";
        }
        boolean graduated = coreItems > 0 && verifiedShare >= config.graduationVerifiedShare();
        return new CoverageResult(status, sections, checks, freshShare, verifiedShare, graduated);
    }

    static String coreSectionOfEntry(String section) {
        return switch (section) {
            case "eat", "drink" -> "eat";
            case "stay" -> "stay";
            case "see", "do", "buy" -> "explore";
            default -> null;
        };
    }

    static String coreSectionOfFact(FactRow f) {
        if (Set.of("getting_around", "getting_there").contains(f.section())) {
            return "getting_around";
        }
        if (f.kind().equals("speciality") && Set.of("eat", "drink").contains(f.section())) {
            return "eat";
        }
        if (f.kind().equals("accommodation_note")) {
            return "stay";
        }
        return null;
    }

    /** Recomputes and stores the profile from the database (bypassing the read cache). */
    @Transactional
    public CoverageResult refresh(UUID destinationId) {
        loader.evict(destinationId);
        CoverageResult result = evaluate(loader.load(destinationId));
        Optional<ProfileRow> previous = profile(destinationId);
        jdbc.sql("""
                        INSERT INTO destination_profile (place_id, coverage_status, section_stats, quality_checks, version,
                                                         computed_at, graduated_at)
                        VALUES (:id, :status, CAST(:stats AS jsonb), CAST(:checks AS jsonb), 1, now(),
                                CASE WHEN :graduated THEN now() END)
                        ON CONFLICT (place_id) DO UPDATE SET
                            coverage_status = EXCLUDED.coverage_status,
                            section_stats = EXCLUDED.section_stats,
                            quality_checks = EXCLUDED.quality_checks,
                            version = destination_profile.version + 1,
                            computed_at = now(),
                            graduated_at = coalesce(destination_profile.graduated_at, EXCLUDED.graduated_at)
                        """)
                .param("id", destinationId)
                .param("status", result.status())
                .param("stats", json.write(Map.of("sections", result.sections(),
                        "freshShare", result.freshShare(), "verifiedShare", result.verifiedShare())))
                .param("checks", json.write(result.checks()))
                .param("graduated", result.graduated())
                .update();
        if (previous.isEmpty() || !previous.get().coverageStatus().equals(result.status())) {
            audit.record(null, "coverage.status_changed", "place", destinationId, destinationId,
                    Map.of("from", previous.map(ProfileRow::coverageStatus).orElse("none"), "to", result.status()));
        }
        loader.evict(destinationId);
        return result;
    }

    public Optional<ProfileRow> profile(UUID destinationId) {
        return jdbc.sql("""
                        SELECT place_id, coverage_status, section_stats::text AS section_stats,
                               quality_checks::text AS quality_checks, version, computed_at, graduated_at
                        FROM destination_profile WHERE place_id = :id
                        """)
                .param("id", destinationId).query(ProfileRow.class).optional();
    }
}
