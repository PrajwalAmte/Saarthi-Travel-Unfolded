package io.destinationintel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.wikivoyage.WikiText;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.ParsedArticle;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.MappedArticle;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.MappedEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WikivoyageParsingTest {

    static String fixture(String name) throws IOException {
        try (var in = WikivoyageParsingTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private MappedArticle map(String fixture) throws IOException {
        ParsedArticle a = WikivoyageArticleParser.parse(fixture(fixture));
        List<String> food = a.foodTerms();
        var ctx = new WikivoyageMapper.ArticleContext(UUID.randomUUID(), "Testville", 42, 1001,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), null, true, true, food, Map.of("Q999999001", 40), 15);
        return WikivoyageMapper.map(a, ctx);
    }

    private static MappedEntry entry(MappedArticle m, String title) {
        return m.entries().stream().filter(e -> e.entry().title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void parsesStructure() throws IOException {
        ParsedArticle a = WikivoyageArticleParser.parse(fixture("testville-v1.wiki"));
        assertThat(a.articleStatus()).isEqualTo("usable");
        assertThat(a.isPartOf()).containsExactly("Test State");
        assertThat(a.regions()).extracting(WikivoyageArticleParser.Region::label).containsExactly("Old Town", "Lakeside");
        assertThat(a.regions().get(0).linkTarget()).isEqualTo("Testville/Old Town");
        assertThat(a.regions().get(1).items()).containsExactly("Boat Club", "Sunset Point");
        assertThat(a.listings()).hasSize(9);
        assertThat(a.foodTerms()).contains("Testville Pak");
    }

    @Test
    void specialitiesExcludeVenueBullets() throws IOException {
        ParsedArticle a = WikivoyageArticleParser.parse(fixture("testville-v1.wiki"));
        assertThat(a.specialities()).extracting(WikivoyageArticleParser.Speciality::name).containsExactly("Bisi Test Bath");
    }

    @Test
    void mapsListingsToTypedFacts() throws IOException {
        MappedArticle m = map("testville-v1.wiki");
        MappedEntry palace = entry(m, "Testville Palace");
        assertThat(palace.entry().section()).isEqualTo("see");
        assertThat(palace.entry().altTitle()).isEqualTo("Raja Mahal");
        assertThat(palace.entry().latitude()).isEqualTo(12.30);
        assertThat(palace.facts()).extracting(f -> f.fact().kind())
                .containsExactlyInAnyOrder("description", "price", "hours", "address", "phone", "website");
        var price = palace.facts().stream().filter(f -> f.fact().kind().equals("price")).findFirst().orElseThrow();
        assertThat(price.fact().volatility()).isEqualTo("volatile");
        assertThat(price.fact().observedAt()).isEqualTo(LocalDate.of(2024, 10, 28));
        assertThat(price.fact().valueJson()).containsEntry("min", 100).containsEntry("currency", "INR");
        assertThat(price.evidence().permalink()).isEqualTo("https://en.wikivoyage.org/w/index.php?title=Testville&oldid=1001");
        var description = palace.facts().stream().filter(f -> f.fact().kind().equals("description")).findFirst().orElseThrow();
        assertThat(description.fact().volatility()).isEqualTo("semi_stable");
        @SuppressWarnings("unchecked")
        Map<String, Object> major = (Map<String, Object>) palace.entry().attributes().get("major");
        assertThat(major).containsEntry("sitelinks", 40);
        assertThat(palace.entry().attributes()).containsKey("accessibility");
    }

    @Test
    void skipsListingsWithoutUsableInformation() throws IOException {
        MappedArticle m = map("testville-v1.wiki");
        assertThat(m.entries()).noneMatch(e -> e.entry().title().equals("Nameless stub"));
        assertThat(m.skippedListings()).isEqualTo(1);
    }

    @Test
    void duplicateNamesGetStableDistinctKeys() throws IOException {
        MappedArticle m = map("testville-v1.wiki");
        List<String> keys = m.entries().stream().filter(e -> e.entry().title().equals("Hotel Example"))
                .map(e -> e.entry().ingestKey()).toList();
        assertThat(keys).containsExactly("wv:42:eat:hotel-example:1", "wv:42:eat:hotel-example:2");
    }

    @Test
    void derivesAttributesWithTheirBasis() throws IOException {
        MappedArticle m = map("testville-v1.wiki");
        Map<String, Object> hotel = entry(m, "Hotel Example").entry().attributes();
        assertThat(hotel.get("priceTier").toString()).contains("budget").contains("Listed under");
        assertThat(hotel.get("foodStyle").toString()).contains("regional").contains("Testville Pak");
        Map<String, Object> splurge = entry(m, "Palace Heritage Hotel").entry().attributes();
        assertThat(splurge.get("priceTier").toString()).contains("premium");
        Map<String, Object> pub = entry(m, "Hop Pub").entry().attributes();
        assertThat(pub.get("interests").toString()).contains("nightlife");
        Map<String, Object> lake = entry(m, "Quiet Lake").entry().attributes();
        assertThat(lake.get("interests").toString()).contains("nature");
        assertThat(lake).doesNotContainKey("major");
    }

    @Test
    void mapsProseIntoSections() throws IOException {
        MappedArticle m = map("testville-v1.wiki");
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("summary") && f.fact().valueText().startsWith("Testville is a fictional"));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().section().equals("getting_around") && "By bus".equals(f.fact().heading()));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().section().equals("getting_there") && "By train".equals(f.fact().heading()));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("seasonality"));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("safety"));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("customs") && f.fact().volatility().equals("stable"));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("accommodation_note"));
        assertThat(m.destinationFacts()).anyMatch(f -> f.fact().kind().equals("speciality") && "Bisi Test Bath".equals(f.fact().heading()));
        assertThat(m.entries()).anyMatch(e -> e.entry().section().equals("neighbourhood") && e.entry().title().equals("Lakeside"));
    }

    @Test
    void wikiTextToPlain() {
        String plain = WikiText.toPlain("A '''bold''' [[Mysore Palace|palace]] near {{IATA|MYQ}}<ref>x</ref>. [https://e.org site] [[File:x.jpg|thumb]]");
        assertThat(plain).isEqualTo("A bold palace near MYQ. site");
        assertThat(WikiText.truncateAtSentence("One. Two. Three is longer.", 12)).isEqualTo("One. Two.");
        assertThat(WikiText.templates("{{eat|name=A {{nested|x}}|content=y}} text {{see|name=B}}")).hasSize(2);
    }

    @Test
    void droppedTemplatesLeaveNoEmptyBracketsBehind() {
        assertThat(WikiText.toPlain("Booking office: 11A, Free School St, Kolkata ({{phone|+91 33 1234}})."))
                .isEqualTo("Booking office: 11A, Free School St, Kolkata.");
        assertThat(WikiText.toPlain("There is a heritage cafe on the first floor (see {{eat}}), with a view."))
                .isEqualTo("There is a heritage cafe on the first floor, with a view.");
        assertThat(WikiText.toPlain("Open late [{{x}}] daily ( ) ."))
                .isEqualTo("Open late daily.");
        // Real parentheses are untouched.
        assertThat(WikiText.toPlain("Try the biryani (mutton) at the Park Street branch (see below)."))
                .isEqualTo("Try the biryani (mutton) at the Park Street branch (see below).");
        assertThat(WikiText.hasArtifacts("Kolkata ()")).isTrue();
        assertThat(WikiText.hasArtifacts("(see )")).isTrue();
        assertThat(WikiText.hasArtifacts("Kolkata (West Bengal)")).isFalse();
    }
}
