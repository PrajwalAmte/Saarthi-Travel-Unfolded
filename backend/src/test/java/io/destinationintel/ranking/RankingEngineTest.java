package io.destinationintel.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.TestConfigs;
import io.destinationintel.ranking.RankingEngine.Candidate;
import io.destinationintel.ranking.RankingEngine.Group;
import io.destinationintel.ranking.RankingEngine.Ranked;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingEngineTest {

    private final RankingEngine engine = new RankingEngine(TestConfigs.ranking());

    private static EntryAttributes attrs(String tier, List<EntryAttributes.Tag> interests, List<EntryAttributes.Tag> food) {
        return new EntryAttributes(tier == null ? null : new EntryAttributes.Attribute(tier, "Listed under \"" + tier + "\""),
                interests, food, null, null, null);
    }

    private static Candidate candidate(String title, String section, EntryAttributes a, int confirmations, double recency) {
        return new Candidate(UUID.nameUUIDFromBytes(title.getBytes()), title, section, null, null, a, 2.0, true, 1, 1,
                recency, LocalDate.of(2024, 1, 1), confirmations, 0,
                confirmations > 0 ? OffsetDateTime.parse("2026-09-01T10:00:00Z") : null, true);
    }

    @Test
    void sameInputsAlwaysProduceTheSameOrder() {
        List<Candidate> cs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cs.add(candidate("Place " + i, "eat", attrs(i % 2 == 0 ? "budget" : "premium", List.of(), List.of()), i % 3, 0.5));
        }
        Preferences prefs = Preferences.parse(null, "budget", null, null, null, null);
        List<String> first = titles(engine.rank(Group.EAT, cs, prefs).ranked());
        Collections.shuffle(cs);
        List<String> second = titles(engine.rank(Group.EAT, cs, prefs).ranked());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void budgetPreferenceMovesMatchingEntriesUp() {
        Candidate cheap = candidate("Cheap", "eat", attrs("budget", List.of(), List.of()), 0, 0.5);
        Candidate fancy = candidate("Fancy", "eat", attrs("premium", List.of(), List.of()), 0, 0.5);
        assertThat(titles(engine.rank(Group.EAT, List.of(fancy, cheap), Preferences.parse(null, "budget", null, null, null, null)).ranked()))
                .first().isEqualTo("Cheap");
        assertThat(titles(engine.rank(Group.EAT, List.of(cheap, fancy), Preferences.parse(null, "premium", null, null, null, null)).ranked()))
                .first().isEqualTo("Fancy");
    }

    @Test
    void comfortPreferenceAppliesToStays() {
        Candidate basic = candidate("Hostel", "stay", attrs("budget", List.of(), List.of()), 0, 0.5);
        Candidate lux = candidate("Palace hotel", "stay", attrs("premium", List.of(), List.of()), 0, 0.5);
        var result = engine.rank(Group.STAY, List.of(basic, lux), Preferences.parse(null, null, "luxury", null, null, null));
        assertThat(result.ranked().get(0).candidate().title()).isEqualTo("Palace hotel");
        assertThat(result.ranked().get(0).reasons()).anyMatch(r -> r.code().equals("price_match"));
    }

    @Test
    void hardPriceConstraintFiltersBeforeRankingAndExplainsExclusions() {
        Candidate cheap = candidate("Cheap", "eat", attrs("budget", List.of(), List.of()), 0, 0.5);
        Candidate fancy = candidate("Fancy", "eat", attrs("premium", List.of(), List.of()), 50, 1.0);
        Candidate unknown = candidate("Unknown price", "eat", attrs(null, List.of(), List.of()), 0, 0.5);
        var result = engine.rank(Group.EAT, List.of(cheap, fancy, unknown), Preferences.parse(null, null, null, null, "budget", null));
        assertThat(titles(result.ranked())).containsExactly("Cheap");
        assertThat(result.excludedByConstraints()).isEqualTo(2);
        assertThat(result.constraintNotes()).anyMatch(n -> n.contains("above")).anyMatch(n -> n.contains("no price information"));
    }

    @Test
    void accessibilityConstraintRequiresRecordedEvidence() {
        Candidate noted = new Candidate(UUID.randomUUID(), "Museum", "see", null, null,
                new EntryAttributes(null, List.of(), List.of(), new EntryAttributes.Attribute("noted", "Mentions wheelchair"), null, null),
                null, false, 0, 1, 0.5, null, 0, 0, null, true);
        Candidate other = candidate("Fort", "see", attrs(null, List.of(), List.of()), 0, 0.5);
        var result = engine.rank(Group.EXPLORE, List.of(noted, other), Preferences.parse(null, null, null, null, null, true));
        assertThat(titles(result.ranked())).containsExactly("Museum");
    }

    @Test
    void explanationsComeOnlyFromRecordedAttributes() {
        Candidate plain = candidate("Plain", "see", attrs(null, List.of(), List.of()), 0, 0.5);
        var result = engine.rank(Group.EXPLORE, List.of(plain), Preferences.parse(null, null, null, "culture", null, null));
        Ranked r = result.ranked().get(0);
        assertThat(r.reasons()).noneMatch(reason -> reason.code().equals("interest"));
        assertThat(r.reasons()).noneMatch(reason -> reason.code().equals("community"));

        Candidate temple = candidate("Temple", "see",
                attrs(null, List.of(new EntryAttributes.Tag("culture", "Source text mentions \"temple\"")), List.of()), 2, 0.5);
        Ranked t = engine.rank(Group.EXPLORE, List.of(temple), Preferences.parse(null, null, null, "culture", null, null)).ranked().get(0);
        assertThat(t.reasons()).anyMatch(reason -> reason.text().contains("temple"));
        assertThat(t.reasons()).anyMatch(reason -> reason.text().startsWith("Confirmed by 2 contributors"));
    }

    @Test
    void saysSoWhenThereIsNotEnoughEvidenceToPersonalise() {
        List<Candidate> cs = List.of(candidate("A", "see", attrs(null, List.of(), List.of()), 0, 0.5),
                candidate("B", "see", attrs(null, List.of(), List.of()), 0, 0.5));
        var result = engine.rank(Group.EXPLORE, cs, Preferences.parse(null, null, null, "nightlife", null, null));
        assertThat(result.personalizationApplied()).isFalse();
        assertThat(result.personalizationNote()).contains("0 of 2");
        assertThat(engine.rank(Group.EXPLORE, cs, Preferences.none()).personalizationNote()).isNull();
    }

    @Test
    void communityVerificationAndFreshnessRaiseScoreButDenialsLowerIt() {
        Candidate verified = candidate("Verified", "eat", attrs(null, List.of(), List.of()), 3, 0.9);
        Candidate stale = candidate("Stale", "eat", attrs(null, List.of(), List.of()), 0, 0.1);
        assertThat(titles(engine.rank(Group.EAT, List.of(stale, verified), Preferences.none()).ranked())).first().isEqualTo("Verified");

        Candidate disputed = new Candidate(UUID.randomUUID(), "Disputed", "eat", null, null, attrs(null, List.of(), List.of()),
                2.0, true, 1, 1, 0.9, null, 0, 2, null, true);
        assertThat(engine.communityFactor(disputed)).isNegative();
    }

    @Test
    void popularityAloneDoesNotDecideOrder() {
        // An entry with no confirmations but fresh, well-evidenced data is not buried by a merely popular one.
        Candidate popular = candidate("Popular", "eat",
                attrs(null, List.of(), List.of(new EntryAttributes.Tag("popular", "Described as \"famous\""))), 0, 0.2);
        Candidate fresh = candidate("Fresh", "eat", attrs(null, List.of(), List.of()), 0, 1.0);
        assertThat(titles(engine.rank(Group.EAT, List.of(popular, fresh), Preferences.none()).ranked())).first().isEqualTo("Fresh");
    }

    @Test
    void diversityAvoidsLongRunsOfTheSameSubsection() {
        List<Candidate> cs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cs.add(new Candidate(UUID.randomUUID(), "Temple " + i, "see", "Temples", null, attrs(null, List.of(), List.of()),
                    1.0, true, 1, 1, 1.0 - i * 0.01, null, 0, 0, null, true));
        }
        cs.add(new Candidate(UUID.randomUUID(), "Lake", "see", "Nature", null, attrs(null, List.of(), List.of()),
                1.0, true, 1, 1, 0.9, null, 0, 0, null, true));
        List<String> order = titles(engine.rank(Group.EXPLORE, cs, Preferences.none()).ranked());
        assertThat(order.indexOf("Lake")).isLessThan(4);
    }

    @Test
    void preferencesParseIgnoresUnknownValues() {
        Preferences p = Preferences.parse("authentic", "splurge", "fancy", "culture,unknown,family-friendly", "nope", null);
        assertThat(p.food()).isEqualTo(Preferences.FoodStyle.REGIONAL);
        assertThat(p.budget()).isEqualTo(Preferences.PriceTier.PREMIUM);
        assertThat(p.comfort()).isNull();
        assertThat(p.interests()).isEqualTo(Set.of(Preferences.Interest.CULTURE, Preferences.Interest.FAMILY));
        assertThat(p.maxPrice()).isNull();
    }

    @Test
    void attributesParseFromStoredJson() {
        EntryAttributes a = EntryAttributes.fromMap(Map.of(
                "priceTier", Map.of("value", "moderate", "basis", "Price ₹300"),
                "interests", List.of(Map.of("tag", "nature", "basis", "mentions lake")),
                "major", Map.of("sitelinks", 40, "basis", "40 languages")));
        assertThat(a.tier()).contains(Preferences.PriceTier.MODERATE);
        assertThat(a.interest(Preferences.Interest.NATURE)).isPresent();
        assertThat(a.major().value()).isEqualTo("40");
    }

    private static List<String> titles(List<Ranked> ranked) {
        return ranked.stream().map(r -> r.candidate().title()).toList();
    }
}
