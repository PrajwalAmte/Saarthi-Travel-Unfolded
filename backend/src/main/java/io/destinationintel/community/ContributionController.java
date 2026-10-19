package io.destinationintel.community;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Contributions", description = "Verifications, corrections, reports and proposals from signed-in contributors")
public class ContributionController {

    private final CurrentUser current;
    private final ContributionService contributions;
    private final VerificationService verifications;
    private final FlagService flags;

    public ContributionController(CurrentUser current, ContributionService contributions,
                                  VerificationService verifications, FlagService flags) {
        this.current = current;
        this.contributions = contributions;
        this.verifications = verifications;
        this.flags = flags;
    }

    @PostMapping("/api/facts/{id}/verifications")
    @Operation(summary = "\"Still true?\": confirm or deny a fact")
    public ResponseEntity<VerificationService.VerifyResult> verify(@PathVariable UUID id,
                                                                   @Valid @RequestBody VerificationService.VerifyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(verifications.verify(current.require(), id, body));
    }

    @PostMapping("/api/contributions")
    @Operation(summary = "Submit a correction, closure/duplicate/incorrect report, new place or local tip")
    public ResponseEntity<ContributionService.SubmitResult> submit(@Valid @RequestBody ContributionService.SubmitRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contributions.submit(current.require(), body));
    }

    @PostMapping("/api/flags")
    @Operation(summary = "Report spam, self-promotion, offensive or misleading content")
    public ResponseEntity<Map<String, Object>> flag(@Valid @RequestBody FlagService.FlagRequest body) {
        UUID id = flags.flag(current.require(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "status", "open",
                "message", "Thanks. A steward will review this report."));
    }
}
