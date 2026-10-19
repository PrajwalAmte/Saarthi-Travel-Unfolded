package io.destinationintel.community;

import io.destinationintel.community.UserRepository.AppUserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Establishes an authenticated session, rotating the session id to prevent fixation. */
@Component
public class SessionAuthenticator {

    private final SecurityContextRepository repository;

    public SessionAuthenticator(SecurityContextRepository repository) {
        this.repository = repository;
    }

    public void signIn(AppUserRow user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.id(), user.displayName()), null, AuthenticatedUser.authorities(user)));
        SecurityContextHolder.setContext(ctx);
        repository.saveContext(ctx, request, response);
    }
}
