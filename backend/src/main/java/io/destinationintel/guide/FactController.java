package io.destinationintel.guide;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.guide.GuideDtos.FactView;
import io.destinationintel.guide.GuideRepository.VerificationRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facts")
@Tag(name = "Facts", description = "Individual facts with evidence, history and verification records")
public class FactController {

    public record EntryRef(UUID id, String title, String section) {}

    public record DestinationRef(UUID id, String slug, String name) {}

    public record ObservationView(String value, Map<String, Object> structured, LocalDate observedAt, String changeType,
                                  String sourceId, OffsetDateTime recordedAt) {}

    public record VerificationView(UUID id, String verdict, String status, String note, String evidenceUrl,
                                   String contributor, OffsetDateTime createdAt) {}

    public record FactDetail(FactView fact, String factStatus, EntryRef entry, DestinationRef destination,
                             List<ObservationView> history, List<VerificationView> verifications,
                             int reviewWindowDays) {}

    private final GuideRepository repo;
    private final FactPresenter presenter;
    private final PlaceRepository places;
    private final FreshnessPolicy freshness;
    private final Json json;

    public FactController(GuideRepository repo, FactPresenter presenter, PlaceRepository places,
                          FreshnessPolicy freshness, Json json) {
        this.repo = repo;
        this.presenter = presenter;
        this.places = places;
        this.freshness = freshness;
        this.json = json;
    }

    @GetMapping("/{id}")
    @Operation(summary = "A fact with all evidence, change history and verification records")
    public FactDetail fact(@PathVariable UUID id) {
        GuideRows.FactRow f = repo.fact(id).orElseThrow(() -> ApiException.notFound("Fact"));
        if (f.status().equals("pending") || f.status().equals("rejected")) {
            throw ApiException.notFound("Fact");
        }
        PlaceRow place = places.findById(f.destinationId()).orElseThrow(() -> ApiException.notFound("Destination"));
        EntryRef entry = f.entryId() == null ? null : repo.entry(f.entryId())
                .map(e -> new EntryRef(e.id(), e.title(), e.section())).orElse(null);
        List<ObservationView> history = repo.observations(id, 50).stream()
                .map(o -> new ObservationView(o.valueText(), o.valueJson() == null ? null : json.map(o.valueJson()),
                        o.observedAt(), o.changeType(), o.sourceId(), o.recordedAt()))
                .toList();
        List<VerificationView> verifications = repo.verifications(id, 50).stream()
                .map(FactController::view).toList();
        return new FactDetail(presenter.present(f, repo.evidenceForFact(id)), f.status(), entry,
                new DestinationRef(place.id(), place.slug(), place.name()), history, verifications,
                freshness.reviewWindowDays(f.volatility()));
    }

    private static VerificationView view(VerificationRow v) {
        return new VerificationView(v.id(), v.verdict(), v.status(), v.note(), v.evidenceUrl(), v.contributorName(),
                v.createdAt());
    }
}
