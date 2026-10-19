package io.destinationintel.community;

import io.destinationintel.community.UserRepository.AppUserRow;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-derives the signed-in user's roles from the database on every request, so promotions,
 * demotions and suspensions take effect immediately and a session can never carry
 * privileges the user no longer has. Suspended or deleted users are signed out.
 */
public class UserRefreshFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public UserRefreshFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            Optional<AppUserRow> user = users.findById(principal.id());
            if (user.isEmpty() || !user.get().active()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else {
                AppUserRow u = user.get();
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new AuthenticatedUser(u.id(), u.displayName()), null, AuthenticatedUser.authorities(u)));
                SecurityContextHolder.setContext(ctx);
            }
        }
        chain.doFilter(request, response);
    }
}
