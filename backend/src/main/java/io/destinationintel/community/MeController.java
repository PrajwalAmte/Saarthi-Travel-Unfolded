package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.PageResponse;
import io.destinationintel.community.ContributionRepository.HistoryRow;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@Tag(name = "Contributor account", description = "Profile, contribution history and account deletion")
public class MeController {

    public record Profile(UUID id, String displayName, String email, String tier, boolean admin, String attribution,
                          String contributorLicence, OffsetDateTime contributorLicenceAcceptedAt, OffsetDateTime createdAt,
                          Map<String, Long> contributions, long verificationsAccepted, long verificationsPending,
                          List<StewardPlace> stewardOf, TrustService.TrustStats trust, TrustCriteria nextTier) {}

    public record StewardPlace(String slug, String name) {}

    public record TrustCriteria(String tier, int minAccepted, double maxRejectionRatio, int minAccountAgeDays) {}

    public record UpdateProfile(@Size(min = 1, max = 60) String displayName,
                                @Pattern(regexp = "display_name|anonymous") String attribution) {}

    public record AcceptLicence(@AssertTrue(message = "must be accepted") boolean accept) {}

    public record HistoryItem(UUID id, String type, String status, String destinationSlug, String destinationName,
                              String targetTitle, Map<String, Object> payload, String decisionReason,
                              OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    private final CurrentUser current;
    private final UserRepository users;
    private final ContributionRepository contributions;
    private final ContributionService contributionService;
    private final AccountService accounts;
    private final TrustService trust;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final AppProperties props;

    public MeController(CurrentUser current, UserRepository users, ContributionRepository contributions,
                        ContributionService contributionService, AccountService accounts, TrustService trust,
                        AuditService audit, JdbcClient jdbc, Json json, AppProperties props) {
        this.current = current;
        this.users = users;
        this.contributions = contributions;
        this.contributionService = contributionService;
        this.accounts = accounts;
        this.trust = trust;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    @GetMapping
    @Operation(summary = "The signed-in contributor's profile and contribution statistics")
    public Profile me() {
        AppUserRow u = current.require();
        long verAccepted = jdbc.sql("SELECT count(*) FROM verification WHERE user_id = :u AND status = 'accepted'")
                .param("u", u.id()).query(Long.class).single();
        long verPending = jdbc.sql("SELECT count(*) FROM verification WHERE user_id = :u AND status = 'pending'")
                .param("u", u.id()).query(Long.class).single();
        List<StewardPlace> places = jdbc.sql("""
                        SELECT p.slug, p.name FROM steward_assignment sa JOIN place p ON p.id = sa.place_id
                        WHERE sa.user_id = :u ORDER BY p.name
                        """).param("u", u.id()).query(StewardPlace.class).list();
        AppProperties.Trust t = props.trust();
        return new Profile(u.id(), u.displayName(), u.email(), u.tier(), u.isAdmin(), u.attribution(),
                u.contributorLicence(), u.contributorLicenceAt(), u.createdAt(), contributions.statusCounts(u.id()),
                verAccepted, verPending, places, trust.stats(u.id()),
                "contributor".equals(u.tier()) ? new TrustCriteria("trusted", t.promoteMinAccepted(),
                        t.promoteMaxRejectionRatio(), t.promoteMinAccountAgeDays()) : null);
    }

    @PatchMapping
    @Transactional
    @Operation(summary = "Update display name or attribution preference")
    public Profile update(@Valid @RequestBody UpdateProfile body) {
        AppUserRow u = current.require();
        users.updateProfile(u.id(), body.displayName() == null ? null : body.displayName().trim(), body.attribution());
        audit.record(u.id(), "user.profile_updated", "user", u.id(), null, Map.of());
        return me();
    }

    @PostMapping("/licence")
    @Transactional
    @Operation(summary = "Accept the contributor licence (CC BY-SA 4.0) required before contributing")
    public Profile acceptLicence(@Valid @RequestBody AcceptLicence body) {
        AppUserRow u = current.require();
        users.acceptLicence(u.id(), props.contributorLicence());
        audit.record(u.id(), "user.licence_accepted", "user", u.id(), null, Map.of("licence", props.contributorLicence()));
        return me();
    }

    @GetMapping("/contributions")
    @Operation(summary = "The signed-in contributor's contribution history")
    public PageResponse<HistoryItem> history(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        AppUserRow u = current.require();
        int p = PageResponse.clampPage(page);
        int s = PageResponse.clampSize(size, 20);
        List<HistoryItem> items = contributions.history(u.id(), s, p * s).stream().map(this::item).toList();
        return PageResponse.of(items, p, s, contributions.countForUser(u.id()));
    }

    @DeleteMapping("/contributions/{id}")
    @Operation(summary = "Withdraw a pending contribution, or remove your name from a decided one")
    public Map<String, String> withdraw(@PathVariable UUID id) {
        return Map.of("result", contributionService.withdraw(current.require(), id));
    }

    @DeleteMapping
    @Operation(summary = "Delete the account: personal data is removed and contributions become anonymous")
    public ResponseEntity<Void> delete(HttpServletRequest request) {
        AppUserRow u = current.require();
        accounts.deleteAccount(u.id());
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    private HistoryItem item(HistoryRow r) {
        return new HistoryItem(r.id(), r.type(), r.status(), r.destinationSlug(), r.destinationName(), r.targetTitle(),
                json.map(r.payload()), r.decisionReason(), r.createdAt(), r.decidedAt());
    }

    static void requireNotNull(Object o, String what) {
        if (o == null) {
            throw ApiException.badRequest("missing", what + " is required");
        }
    }
}
