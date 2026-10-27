package io.destinationintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.common.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ListingReviewHandlerTest {

    private final ListingReviewHandler handler = new ListingReviewHandler(null, null, null, null,
            new Json(JsonMapper.builder().build()), null, null, null, null, null);

    @Test
    void parsesOnlyWellFormedVerdictsForKnownListings() {
        Map<Integer, ListingReviewHandler.Verdict> v = handler.parse("""
                [{"id":"1","fitsSection":false,"inDestination":"yes","issues":["wrong_section","tone"],"suggestedSection":"see","confidence":0.9,"reason":"A fort."},
                 {"id":"2","fitsSection":true,"inDestination":"maybe","issues":[],"suggestedSection":"eat","confidence":0.9,"reason":"x"},
                 {"id":"7","fitsSection":true,"inDestination":"yes","issues":[],"suggestedSection":"eat","confidence":0.9,"reason":"x"},
                 {"id":"1","fitsSection":true,"inDestination":"yes","issues":[],"suggestedSection":"eat","confidence":0.9,"reason":"dup"},
                 {"id":"3","fitsSection":true,"inDestination":"yes","issues":[],"suggestedSection":"kitchen","confidence":3,"reason":""}]""", 3);
        assertThat(v).containsOnlyKeys(1, 3);
        assertThat(v.get(1).issues()).as("unknown issue codes are dropped").containsExactly("wrong_section");
        assertThat(v.get(1).reason()).isEqualTo("A fort.");
        assertThat(v.get(3).suggestedSection()).isEqualTo("none");
        assertThat(v.get(3).confidence()).isEqualTo(1.0);
        assertThat(handler.parse("not json", 3)).isEmpty();
        assertThat(handler.parse("{\"id\":\"1\"}", 3)).isEmpty();
    }

    @Test
    void onlyConfidentDoubtsBecomeFlags() {
        assertThat(new ListingReviewHandler.Verdict(false, "yes", List.of("wrong_section"), "see", 0.9, "r").flagged(true)).isTrue();
        assertThat(new ListingReviewHandler.Verdict(false, "yes", List.of("wrong_section"), "see", 0.5, "r").flagged(true))
                .as("unsure verdicts are not put in front of an admin").isFalse();
        assertThat(new ListingReviewHandler.Verdict(true, "no", List.of(), "eat", 0.8, "r").flagged(true)).isTrue();
        assertThat(new ListingReviewHandler.Verdict(true, "unclear", List.of(), "eat", 0.95, "r").flagged(true)).isFalse();
        assertThat(new ListingReviewHandler.Verdict(true, "yes", List.of("too_thin"), "eat", 0.95, "r").flagged(true))
                .as("a short description is not a defect to remove").isFalse();
        assertThat(new ListingReviewHandler.Verdict(false, "unclear", List.of("too_thin"), "none", 0.9, "empty").flagged(false))
                .as("no text is not evidence against a listing").isFalse();
        assertThat(new ListingReviewHandler.Verdict(false, "yes", List.of("wrong_section"), "see", 0.9, "a fort").flagged(false))
                .as("a named defect from the title still counts").isTrue();
    }

    @Test
    void sameTextGivesSameHashAndChangedTextDoesNot() {
        var a = new ListingReviewHandler.Listing(null, "eat", "Arsalan", "Esplanade", "Biryani.", null);
        var b = new ListingReviewHandler.Listing(null, "eat", "Arsalan", "Esplanade", "Biryani.", null);
        var c = new ListingReviewHandler.Listing(null, "eat", "Arsalan", "Esplanade", "Biryani and kebabs.", null);
        assertThat(ListingReviewHandler.inputHash(a)).isEqualTo(ListingReviewHandler.inputHash(b));
        assertThat(ListingReviewHandler.inputHash(a)).isNotEqualTo(ListingReviewHandler.inputHash(c));
    }

    static final String LONG = "Very good dosa, a local favourite near the old market. Breakfast thali is ₹120 and coffee "
            + "comes in steel tumblers. It gets crowded at weekends, so arrive before 9AM if you want a table.";

    private static ListingReviewHandler.Result result(String summary, List<ListingReviewHandler.Tag> tags, List<AiFacts.Extracted> facts) {
        return new ListingReviewHandler.Result(new ListingReviewHandler.Verdict(true, "yes", List.of(), "eat", 0.9, "ok"),
                summary, tags, facts);
    }

    @Test
    void keepsAFaithfulSummaryAndRejectsOneThatAddsAFact() {
        var l = new ListingReviewHandler.Listing(null, "eat", "Hotel Example", "Gandhi Square", LONG, null, null, "description");
        var ok = ListingReviewHandler.enrich(l, result("A dosa place, a local favourite near the old market, that gets crowded at weekends.",
                List.of(), List.of()), "Testville");
        assertThat(ok.summaryStatus()).isEqualTo("accepted");
        var bad = ListingReviewHandler.enrich(l, result("A dosa place open since 1975 near the old market.", List.of(), List.of()),
                "Testville");
        assertThat(bad.summaryStatus()).isEqualTo("rejected");
        assertThat(bad.rejection()).contains("1975");
        var shortText = new ListingReviewHandler.Listing(null, "eat", "Cafe", null, "Good coffee.", null, null, "description");
        assertThat(ListingReviewHandler.enrich(shortText, result("Good coffee.", List.of(), List.of()), "Testville").summaryStatus())
                .as("short descriptions are left as they are").isEqualTo("not_needed");
    }

    @Test
    void tagsNeedTheirWordsInTheTextAndAKnownName() {
        var l = new ListingReviewHandler.Listing(null, "eat", "Hotel Example", null, LONG, null, null, "description");
        var e = ListingReviewHandler.enrich(l, result("", List.of(
                new ListingReviewHandler.Tag("regional_food", "a local favourite"),
                new ListingReviewHandler.Tag("nightlife", "late-night bar"),
                new ListingReviewHandler.Tag("romantic", "steel tumblers"),
                new ListingReviewHandler.Tag("regional_food", "dosa")), List.of()), "Testville");
        assertThat(e.tags()).extracting(ListingReviewHandler.Tag::tag).containsExactly("regional_food");
    }

    @Test
    void factsAreVerbatimAndOnlyFillGaps() {
        var noPrice = new ListingReviewHandler.Listing(null, "eat", "Hotel Example", null, LONG, null, null, "description");
        var e = ListingReviewHandler.enrich(noPrice, result("", List.of(), List.of(
                new AiFacts.Extracted("price", "Breakfast thali is ₹120"),
                new AiFacts.Extracted("hours", "arrive before 9AM"),
                new AiFacts.Extracted("price", "Lunch is ₹200"))), "Testville");
        assertThat(e.facts()).extracting(AiFacts.Extracted::quote).containsExactly("Breakfast thali is ₹120", "arrive before 9AM");

        var hasPrice = new ListingReviewHandler.Listing(null, "eat", "Hotel Example", null, LONG, null, null, "description,price");
        assertThat(ListingReviewHandler.enrich(hasPrice, result("", List.of(), List.of(new AiFacts.Extracted("price", "Breakfast thali is ₹120"))),
                "Testville").facts()).as("the source's own price is kept").isEmpty();
    }

    @Test
    void openingHoursNeedATimeOfDayNotADate() {
        assertThat(ListingReviewHandler.HOURS.matcher("6PM-8PM").find()).isTrue();
        assertThat(ListingReviewHandler.HOURS.matcher("open 10:30 to 18:00").find()).isTrue();
        assertThat(ListingReviewHandler.HOURS.matcher("open 24 hours").find()).isTrue();
        assertThat(ListingReviewHandler.HOURS.matcher("May 2014").find()).isFalse();
        assertThat(ListingReviewHandler.HOURS.matcher("closed on Mondays").find()).isFalse();
    }
}
