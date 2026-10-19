package io.destinationintel.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlSafetyTest {

    @Test
    void acceptsPublicHttpUrls() {
        assertThat(UrlSafety.normalize("https://mysorepalace.gov.in")).contains("https://mysorepalace.gov.in");
        assertThat(UrlSafety.normalize("www.example.org/menu")).contains("https://www.example.org/menu");
        assertThat(UrlSafety.normalize("//example.org/x")).contains("https://example.org/x");
    }

    @Test
    void rejectsDangerousOrPrivateUrls() {
        assertThat(UrlSafety.normalize("javascript:alert(1)")).isEmpty();
        assertThat(UrlSafety.normalize("data:text/html,hi")).isEmpty();
        assertThat(UrlSafety.normalize("http://localhost:8080/admin")).isEmpty();
        assertThat(UrlSafety.normalize("http://127.0.0.1/")).isEmpty();
        assertThat(UrlSafety.normalize("https://user:pass@example.org")).isEmpty();
        assertThat(UrlSafety.normalize("ftp://example.org")).isEmpty();
        assertThat(UrlSafety.normalize("not a url")).isEmpty();
        assertThat(UrlSafety.normalize(null)).isEmpty();
    }

    @Test
    void slugsAreAsciiAndStable() {
        assertThat(Slugs.slugify("Mysūru (Karnataka)")).isEqualTo("mysuru-karnataka");
        assertThat(Slugs.slugify("ಮೈಸೂರು")).isEmpty();
        assertThat(Slugs.normalizeKey("Hotel  Mylari!")).isEqualTo("hotel mylari");
    }
}
