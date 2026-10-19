package io.destinationintel.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * OAuth sign-in providers, registered only when their client id and secret are configured.
 * The redirect URI is pinned to the public URL because the API is reached through the
 * frontend's reverse proxy.
 */
@Component
public class OAuthProviders {

    private final ClientRegistrationRepository repository;
    private final List<String> enabled;

    public OAuthProviders(AppProperties props) {
        List<ClientRegistration> regs = new ArrayList<>();
        String redirect = props.publicUrl().replaceAll("/+$", "") + "/login/oauth2/code/{registrationId}";
        AppProperties.OAuth oauth = props.oauth();
        if (oauth != null && oauth.google() != null && oauth.google().configured()) {
            regs.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(oauth.google().clientId()).clientSecret(oauth.google().clientSecret())
                    .redirectUri(redirect).scope("openid", "profile", "email").build());
        }
        if (oauth != null && oauth.github() != null && oauth.github().configured()) {
            regs.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(oauth.github().clientId()).clientSecret(oauth.github().clientSecret())
                    .redirectUri(redirect).scope("read:user").build());
        }
        this.repository = regs.isEmpty() ? null : new InMemoryClientRegistrationRepository(regs);
        this.enabled = regs.stream().map(ClientRegistration::getRegistrationId).toList();
    }

    public Optional<ClientRegistrationRepository> repository() {
        return Optional.ofNullable(repository);
    }

    public List<String> enabled() {
        return enabled;
    }
}
