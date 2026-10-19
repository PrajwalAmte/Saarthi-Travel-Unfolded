package io.destinationintel.community;

import io.destinationintel.common.ClientIpResolver;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.OAuthProviders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Contributor sign-in. Readers never need an account.")
public class AuthController {

    public record SessionUser(UUID id, String displayName, String tier, boolean admin, boolean licenceAccepted) {}

    public record SessionView(boolean authenticated, SessionUser user, boolean emailSignInAvailable,
                              List<String> oauthProviders, String csrfToken) {}

    public record EmailStart(@NotBlank @Email @Size(max = 254) String email, @Size(max = 60) String displayName) {}

    public record EmailVerify(@NotBlank @Size(max = 200) String token) {}

    private final CurrentUser current;
    private final AccountService accounts;
    private final SessionAuthenticator sessions;
    private final MailService mail;
    private final OAuthProviders oauth;
    private final ClientIpResolver ip;

    public AuthController(CurrentUser current, AccountService accounts, SessionAuthenticator sessions, MailService mail,
                          OAuthProviders oauth, ClientIpResolver ip) {
        this.current = current;
        this.accounts = accounts;
        this.sessions = sessions;
        this.mail = mail;
        this.oauth = oauth;
        this.ip = ip;
    }

    @GetMapping("/session")
    @Operation(summary = "Current session, available sign-in methods and a CSRF token for state-changing requests")
    public SessionView session(CsrfToken csrf) {
        AppUserRow u = current.get().orElse(null);
        return new SessionView(u != null, u == null ? null : view(u), mail.configured(), oauth.enabled(), csrf.getToken());
    }

    @PostMapping("/email/start")
    @Operation(summary = "Email a single-use sign-in link (always answers the same way to avoid revealing accounts)")
    public ResponseEntity<Map<String, String>> start(@Valid @RequestBody EmailStart body, HttpServletRequest request) {
        accounts.startEmailSignIn(body.email(), body.displayName(), ip.resolve(request));
        return ResponseEntity.accepted().body(Map.of("message",
                "If the address can receive email, a sign-in link is on its way. It expires in 15 minutes."));
    }

    @PostMapping("/email/verify")
    @Operation(summary = "Exchange a sign-in link token for a session")
    public SessionView verify(@Valid @RequestBody EmailVerify body, HttpServletRequest request, HttpServletResponse response) {
        AppUserRow user = accounts.verifyEmailSignIn(body.token());
        sessions.signIn(user, request, response);
        return new SessionView(true, view(user), mail.configured(), oauth.enabled(), null);
    }

    static SessionUser view(AppUserRow u) {
        return new SessionUser(u.id(), u.displayName(), u.tier(), u.isAdmin(), u.contributorLicenceAt() != null);
    }
}
