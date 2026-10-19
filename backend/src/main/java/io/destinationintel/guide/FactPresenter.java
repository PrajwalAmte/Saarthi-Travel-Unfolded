package io.destinationintel.guide;

import io.destinationintel.common.Json;
import io.destinationintel.guide.FreshnessPolicy.Assessment;
import io.destinationintel.guide.GuideDtos.EvidenceView;
import io.destinationintel.guide.GuideDtos.FactView;
import io.destinationintel.guide.GuideRows.EvidenceRow;
import io.destinationintel.guide.GuideRows.FactRow;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Turns stored facts and evidence into API views with derived freshness and status. */
@Component
public class FactPresenter {

    private final FreshnessPolicy freshness;
    private final Json json;

    public FactPresenter(FreshnessPolicy freshness, Json json) {
        this.freshness = freshness;
        this.json = json;
    }

    public Assessment assess(FactRow f) {
        return freshness.assess(f.volatility(), f.origin(), f.observedAt(), f.lastConfirmationAt(),
                f.acceptedConfirmations(), f.acceptedDenials(), f.lastDenialAt());
    }

    public FactView present(FactRow f, List<EvidenceRow> evidence) {
        Assessment a = assess(f);
        Map<String, Object> value = f.valueJson() == null ? null : json.map(f.valueJson());
        return new FactView(
                f.id(),
                f.entryId(),
                f.kind(),
                f.section(),
                f.heading(),
                f.valueText(),
                value,
                f.volatility(),
                f.origin(),
                f.observedAt(),
                f.observedBasis(),
                f.sourceRefreshedAt(),
                f.lastConfirmationAt(),
                a.reviewDueAt(),
                a.freshness().name().toLowerCase(Locale.ROOT),
                a.status().name().toLowerCase(Locale.ROOT),
                f.acceptedConfirmations(),
                f.acceptedDenials(),
                f.pendingCorrections(),
                f.ownerSubmitted(),
                evidence.stream().map(FactPresenter::evidenceView).toList());
    }

    static EvidenceView evidenceView(EvidenceRow e) {
        return new EvidenceView(e.sourceId(), e.sourceName(), e.sourceCategory(), e.licenceName(), e.licenceUrl(),
                e.url(), e.permalink(), e.title(), e.excerpt(), e.extractionMethod(), e.sourceRevision(),
                e.sourceRevisionAt(), e.observedAt(), e.retrievedAt(), e.contributorName());
    }
}
