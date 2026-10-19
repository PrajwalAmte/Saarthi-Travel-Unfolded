package io.destinationintel.quality;

import io.destinationintel.common.ApiException;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Data quality", description = "Sync runs and data-quality reports per source")
public class QualityController {

    private static final String SOURCE = "^[a-z0-9-]{1,40}$";

    private final QualityService quality;
    private final SyncRunRecorder runs;

    public QualityController(QualityService quality, SyncRunRecorder runs) {
        this.quality = quality;
        this.runs = runs;
    }

    @GetMapping("/api/sources/{id}/runs")
    @Operation(summary = "Recent sync runs of a source with real record counts, warnings and errors")
    public List<SyncRunRecorder.RunRow> runs(@PathVariable @Pattern(regexp = SOURCE) String id,
                                             @RequestParam(defaultValue = "20") int limit) {
        return runs.recent(id, Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/api/sources/{id}/quality")
    @Operation(summary = "Latest data-quality report of a source")
    public QualityService.ReportView latest(@PathVariable @Pattern(regexp = SOURCE) String id) {
        return quality.latest(id).orElseThrow(() -> ApiException.notFound("Quality report"));
    }

    @GetMapping("/api/sources/{id}/quality/history")
    public List<QualityService.ReportView> history(@PathVariable @Pattern(regexp = SOURCE) String id) {
        return quality.history(id, 20);
    }

    @GetMapping("/api/quality/reports/{reportId}")
    public QualityService.ReportView report(@PathVariable UUID reportId) {
        return quality.find(reportId).orElseThrow(() -> ApiException.notFound("Quality report"));
    }

    @PostMapping("/api/admin/quality/{id}/evaluate")
    @Operation(summary = "Evaluate a source's stored data now (admins)")
    public QualityService.ReportView evaluate(@PathVariable @Pattern(regexp = SOURCE) String id) {
        if (!quality.supports(id)) {
            throw ApiException.badRequest("no_evaluator", "No quality evaluator is defined for " + id);
        }
        return quality.evaluate(id, null, "manual");
    }
}
