package io.destinationintel.moderation;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.PageResponse;
import io.destinationintel.community.CurrentUser;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/moderation")
@Tag(name = "Moderation", description = "Review queue for trusted contributors, city stewards and admins")
public class ModerationController {

    public record Decision(@NotBlank @Pattern(regexp = "accept|reject") String decision,
                           @Size(max = 500) String reason,
                           @Pattern(regexp = "none|hide_entry|retract_fact|reject_contribution|suspend_user") String action) {}

    public record Revert(@Size(max = 500) String reason) {}

    private final CurrentUser current;
    private final ModerationService moderation;
    private final AuditService audit;

    public ModerationController(CurrentUser current, ModerationService moderation, AuditService audit) {
        this.current = current;
        this.moderation = moderation;
        this.audit = audit;
    }

    @GetMapping("/queue")
    @Operation(summary = "Items the signed-in reviewer may decide")
    public PageResponse<ModerationService.QueueItem> queue(@RequestParam(required = false) String status,
                                                          @RequestParam(required = false) UUID destinationId,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return moderation.queue(current.require(), status, destinationId, PageResponse.clampPage(page),
                PageResponse.clampSize(size, 20));
    }

    @PostMapping("/items/{id}/decision")
    @Operation(summary = "Accept or reject an item; for abuse reports, accept upholds the report")
    public Map<String, String> decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return Map.of("outcome", moderation.decide(current.require(), id, body.decision(), body.reason(), body.action()));
    }

    @PostMapping("/contributions/{id}/revert")
    @Operation(summary = "Revert an accepted contribution (stewards and admins)")
    public Map<String, String> revert(@PathVariable UUID id, @Valid @RequestBody Revert body) {
        moderation.revert(current.require(), id, body.reason());
        return Map.of("outcome", "reverted");
    }

    @GetMapping("/audit")
    @Operation(summary = "Audit history (stewards and admins)")
    public List<AuditService.AuditEntry> audit(@RequestParam(required = false) String entityType,
                                               @RequestParam(required = false) String entityId,
                                               @RequestParam(required = false) UUID destinationId,
                                               @RequestParam(required = false) Integer page) {
        AppUserRow u = current.require();
        if (!u.steward()) {
            throw ApiException.forbidden("Audit history is available to stewards and administrators.");
        }
        if (entityType != null && entityId != null) {
            return audit.forEntity(entityType, entityId, 100);
        }
        int p = PageResponse.clampPage(page);
        return audit.recent(destinationId, 50, p * 50);
    }
}
