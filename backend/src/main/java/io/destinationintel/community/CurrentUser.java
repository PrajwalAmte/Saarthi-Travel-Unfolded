package io.destinationintel.community;

import io.destinationintel.common.ApiException;
import io.destinationintel.community.UserRepository.AppUserRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Access to the signed-in contributor, always re-read from the database. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public Optional<UUID> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return Optional.of(u.id());
        }
        return Optional.empty();
    }

    public Optional<AppUserRow> get() {
        return id().flatMap(users::findById).filter(AppUserRow::active);
    }

    public AppUserRow require() {
        return get().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Sign in as a contributor to do this."));
    }
}
