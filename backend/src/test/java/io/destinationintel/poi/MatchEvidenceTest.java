package io.destinationintel.poi;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.poi.MatchEvidence.Outcome;
import io.destinationintel.poi.MatchEvidence.Side;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Cases taken from the real review queue (names as the sources spell them). */
class MatchEvidenceTest {

    private static Side side(String name, String group) {
        return new Side(name, group, Set.of(), Set.of(), false);
    }

    private static Side side(String name, String group, String phone) {
        return new Side(name, group, Set.of(phone), Set.of(), false);
    }

    @Test
    void differentKindsOfPlaceAreDistinctEvenSideBySide() {
        var d = MatchEvidence.decide(side("Haagen - Dazs Vr Mall Chennai", "eat"), side("VR Mall Chennai", "buy"), 20);
        assertThat(d.outcome()).isEqualTo(Outcome.DISTINCT);
        assertThat(d.rule()).isEqualTo("different_kind_of_place");
        assertThat(MatchEvidence.decide(side("12 Union Park", "eat"), side("Union Park", "see"), 306).outcome())
                .isEqualTo(Outcome.DISTINCT);
    }

    @Test
    void sharedContactDetailsDecideSameRegardlessOfFormatting() {
        var d = MatchEvidence.decide(side("37th Crescent Hotel", "stay", "+918040373737"),
                side("37th Crescent Hotel", "stay", "+91-80-4037 3737"), 185);
        assertThat(d).isEqualTo(new MatchEvidence.Decision(Outcome.SAME, "shared_phone"));
        var web = MatchEvidence.decide(new Side("Taj Kumarakom", "stay", Set.of(), Set.of("https://www.tajhotels.com/kumarakom"), false),
                new Side("Taj - Kumarakom", "stay", Set.of(), Set.of("tajhotels.com"), false), 105);
        assertThat(web.rule()).isEqualTo("shared_website");
        var platform = MatchEvidence.decide(new Side("Hotel A", "stay", Set.of(), Set.of("https://www.facebook.com/hotela"), false),
                new Side("Lodge B", "stay", Set.of(), Set.of("facebook.com/lodgeb"), false), 300);
        assertThat(platform.outcome()).as("a shared social platform is not evidence").isEqualTo(Outcome.DISTINCT);
    }

    @Test
    void spellingAndSpacingVariantsNearbyAreTheSamePlace() {
        assertThat(MatchEvidence.decide(side("AnnaMaya", "eat"), side("Anna Maya", "eat"), 61).rule()).isEqualTo("same_name_same_spot");
        assertThat(MatchEvidence.decide(side("Hotel Ambady", "eat"), side("Hotel Ambadi", "eat"), 17).rule()).isEqualTo("spelling_variant");
        assertThat(MatchEvidence.decide(side("Wah Parantha", "eat"), side("Wah Paratha", "eat"), 8).outcome()).isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Coconut Lagoon", "stay"), side("Hotel Coconut Lagoon", "stay"), 46).outcome())
                .isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Comfort Inn Sunset", "stay"), side("Comfort Inn", "stay"), 1).rule())
                .isEqualTo("name_contains_other");
    }

    @Test
    void chainBranchesAndDifferentNumbersApartAreDistinct() {
        var chain = MatchEvidence.decide(new Side("Bikaner Sweets", "eat", Set.of(), Set.of(), true), side("Bikaner Sweets", "eat"), 218);
        assertThat(chain.rule()).isEqualTo("chain_branch");
        var phones = MatchEvidence.decide(side("Sukkubhai Biriyani", "eat", "+914465554000"), side("Sukkubhai Biryani", "eat", "+91 44 2233 0444"), 270);
        assertThat(phones.rule()).isEqualTo("different_phones");
        var far = MatchEvidence.decide(side("Hotel Rajmahal", "stay"), side("Rajmahal Palace Grounds", "stay"), 300);
        assertThat(far.outcome()).isEqualTo(Outcome.DISTINCT);
    }

    @Test
    void coreNamesDropGenericWordsAndPunctuation() {
        assertThat(MatchEvidence.core("The New Woodlands Hotel")).isEqualTo("woodlands");
        assertThat(MatchEvidence.core("Café Mondegar")).isEqualTo("mondegar");
        assertThat(MatchEvidence.phones(Set.of("044-2811 3111; +91 98400 12345"))).containsExactlyInAnyOrder("4428113111", "9840012345");
    }

    @Test
    void hotelAndEateryListingsOfOnePlaceAreNotSplitByCategory() {
        assertThat(MatchEvidence.decide(side("Hotel Abdullah", "stay"), side("Hotel Abdullah", "eat"), 44).outcome())
                .isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Light House Beach Resort", "stay"), side("Lighthouse Beach", "see"), 90).outcome())
                .as("a resort is not the beach").isEqualTo(Outcome.DISTINCT);
    }

    @Test
    void chainHeadOfficeContactsDoNotMergeBranches() {
        var ccd = MatchEvidence.decide(new Side("Cafe Coffee Day", "drink", Set.of("+91 80 4001 2345"), Set.of("cafecoffeeday.com"),
                Set.of("Cafe Coffee Day")), new Side("Cafe Coffee Day", "drink", Set.of("080-40012345"), Set.of(), Set.of()), 258);
        assertThat(ccd.rule()).isEqualTo("chain_branch");
    }

    @Test
    void aggregatorListingsAreMatchedOnTheHotelsOwnName() {
        var oyo = MatchEvidence.decide(new Side("OYO 45408 Hotel Ambassador", "stay", Set.of("+91 124 620 1600"), Set.of(),
                Set.of("OYO")), side("Hotel ambassador", "stay", "+91 98765 43210"), 257);
        assertThat(oyo.outcome()).as("OYO's call-centre number is not the hotel's").isEqualTo(Outcome.SAME);
        assertThat(oyo.rule()).isEqualTo("same_name_nearby");
    }

    @Test
    void identifyingWordsMatchDespiteFacilityWordsAndWordOrder() {
        assertThat(MatchEvidence.decide(side("Mandya KSRTC Bus Stand", "transport"), side("Mandya Bus Stand", "transport"), 22).outcome())
                .isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("HostelExp", "stay"), side("Exp Hostel", "stay"), 16).outcome()).isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Jahangir Mahal, Orchha", "see"), side("Jahangiri Mahal", "see"), 12).outcome())
                .isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("KB East Restaurant", "eat"), side("KB Restaurant", "eat"), 37).outcome())
                .as("too little identifying text to tell").isEqualTo(Outcome.DISTINCT);
        assertThat(MatchEvidence.decide(side("Milan Restaurant", "eat"), side("Midtown restaurant", "eat"), 120).outcome())
                .isEqualTo(Outcome.DISTINCT);
        assertThat(MatchEvidence.decide(side("Hotel Rahi Nashik", "stay"), side("Hotel Rahi", "stay"), 19).outcome()).isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Hotel Amardeep", "stay"), side("Amardip Hotel", "stay"), 32).outcome()).isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("SaraVana Bakeries", "eat"), side("Saravana Bakery", "eat"), 33).outcome()).isEqualTo(Outcome.SAME);
        assertThat(MatchEvidence.decide(side("Joy Restaurant", "eat"), side("Taj Restaurant", "eat"), 49).outcome()).isEqualTo(Outcome.DISTINCT);
        assertThat(MatchEvidence.decide(side("Sangeetha Veg Restaurant", "eat"), side("Geetham Veg Restaurant", "eat"), 62).outcome())
                .isEqualTo(Outcome.DISTINCT);
    }
}
