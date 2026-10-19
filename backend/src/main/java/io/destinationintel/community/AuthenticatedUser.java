package io.destinationintel.community;

import io.destinationintel.community.UserRepository.AppUserRow;
import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Session principal. Only the user id is trusted from the session; roles are recomputed
 * from the database on every request by {@link UserRefreshFilter}.
 */
public record AuthenticatedUser(UUID id, String displayName) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return id.toString();
    }

    public static List<GrantedAuthority> authorities(AppUserRow u) {
        List<GrantedAuthority> out = new ArrayList<>();
        out.add(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR"));
        if (u.atLeastTrusted()) {
            out.add(new SimpleGrantedAuthority("ROLE_TRUSTED"));
        }
        if (u.steward()) {
            out.add(new SimpleGrantedAuthority("ROLE_STEWARD"));
        }
        if (u.isAdmin()) {
            out.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return out;
    }
}
