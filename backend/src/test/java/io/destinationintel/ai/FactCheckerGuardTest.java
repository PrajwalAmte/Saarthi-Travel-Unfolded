package io.destinationintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.guide.GuideDtos.FactView;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The checks that stand between a model's answer and the guide. */
class FactCheckerGuardTest {

    private static FactView fact(String kind, String text) {
        return new FactView(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), kind, "stay", null, text, null, "volatile",
                "ingested", LocalDate.of(2019, 1, 1), "source_listing_date", null, null, null, "due", "needs_reverification",
                0, 0, 0, false, List.of());
    }

    private static final SiteReader.Page PAGE = new SiteReader.Page("https://hotel.example/tariff", "Tariff",
            "Heritage rooms from ₹10,500 per night. Open daily 9 am to 9 pm. Closed on Mondays.", List.of());

    private static FactChecker.Verdict verify(FactView f, String verdict, String value, String quote) {
        return FactChecker.verify(f, new FactChecker.Step("answer", null, verdict, value, quote, "r"), PAGE, 1, "m");
    }

    @Test
    void aChangeNeedsAQuoteOnThePageThatContainsTheNewNumbers() {
        assertThat(verify(fact("price", "₹9000"), "changed", "from ₹10,500", "Heritage rooms from ₹10,500 per night").outcome())
                .isEqualTo("applied");
        assertThat(verify(fact("price", "₹9000"), "changed", "from ₹10,500", "Rooms from ₹10,500 a night").verdict())
                .as("quote not on the page").isEqualTo("rejected");
        assertThat(verify(fact("price", "₹9000"), "changed", "from ₹12,000", "Heritage rooms from ₹10,500 per night").verdict())
                .as("number not in the quote").isEqualTo("rejected");
        assertThat(verify(fact("price", "₹10500"), "changed", "₹10,500", "Heritage rooms from ₹10,500 per night").verdict())
                .as("same number is not a change").isEqualTo("rejected");
    }

    @Test
    void aConfirmationMustShowTheRecordedValue() {
        assertThat(verify(fact("hours", "9AM-9PM"), "confirmed", null, "Open daily 9 am to 9 pm").outcome()).isEqualTo("applied");
        assertThat(verify(fact("hours", "10AM-6PM"), "confirmed", null, "Open daily 9 am to 9 pm").verdict()).isEqualTo("rejected");
    }

    @Test
    void closuresAreNeverAppliedAndClosedOnSomeDaysIsNotAClosure() {
        assertThat(verify(fact("hours", "9AM-9PM"), "closed", null, "Closed on Mondays").verdict()).isEqualTo("rejected");
        SiteReader.Page gone = new SiteReader.Page("https://x.example/", "X", "We have permanently closed. Thank you!", List.of());
        FactChecker.Verdict v = FactChecker.verify(fact("hours", "9AM-9PM"),
                new FactChecker.Step("answer", null, "closed", null, "We have permanently closed", "r"), gone, 1, "m");
        assertThat(v.verdict()).isEqualTo("closed");
        assertThat(v.outcome()).as("left for a person").isEqualTo("noted");
    }

    @Test
    void robotsRulesAndSameSiteLinks() {
        String robots = "User-agent: *\nDisallow: /private/\n\nUser-agent: SaarthiBot\nDisallow: /tariff\n";
        assertThat(SiteReader.disallowed(robots, "SaarthiBot/1.0 (+https://github.com/x)")).containsExactly("/tariff");
        assertThat(SiteReader.disallowed(robots, "OtherBot")).containsExactly("/private/");
        assertThat(SiteReader.sameSite("www.hotel.example", "hotel.example")).isTrue();
        assertThat(SiteReader.sameSite("evil.example", "hotel.example")).isFalse();
        SiteReader.Page p = SiteReader.parse(URI.create("https://hotel.example/"),
                "<a href='/tariff'>Tariff</a><a href='https://other.example/'>Partner</a><script>var x='₹1'</script><p>Hi</p>", "hotel.example");
        assertThat(p.links()).extracting(SiteReader.Link::text).containsExactly("Tariff");
        assertThat(p.text()).doesNotContain("₹1");
    }
}
