package io.destinationintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FaithfulnessTest {

    static final String SEACOM = "An extremely drab and dark hotel that appears not to have received a lick of paint since its "
            + "founding in the mid-19th century. However, in some ways, that is the hotel's distinctive charm, and so it may "
            + "appeal to strict traditionalists. There is a heritage café on the first floor, with the balcony offering a "
            + "great view of the buzzing city below.";

    @Test
    void aPlainerRewriteOfTheSameFactsPasses() {
        assertThat(Faithfulness.summaryProblem("A drab, dark hotel from the mid-19th century with a heritage café "
                + "on the first floor and a balcony view over the city.", SEACOM, "Seacom Inn YMCA", "Kolkata")).isEmpty();
    }

    @Test
    void aRewriteMustKeepTheOriginalsCriticism() {
        assertThat(Faithfulness.summaryProblem("An old-fashioned hotel from the mid-19th century with a heritage café.", SEACOM))
                .hasValueSatisfying(r -> assertThat(r).contains("criticism").contains("drab"));
        String bar = "The bar fills up each day with tourists looking for a beer. There is nothing cosy about the place and the staff are quite lazy.";
        assertThat(Faithfulness.summaryProblem("A busy bar where tourists meet for a beer.", bar)).isPresent();
        assertThat(Faithfulness.summaryProblem("A busy tourist bar with lazy staff and nothing cosy about it.", bar)).isEmpty();
    }

    @Test
    void aRewriteMayNotSellHarderThanItsSource() {
        String temple = "For followers of Lord Krishna, this is the most important temple after Vrindavan.";
        assertThat(Faithfulness.summaryProblem("A famous temple for followers of Lord Krishna.", temple))
                .hasValueSatisfying(r -> assertThat(r).contains("famous"));
        assertThat(Faithfulness.summaryProblem("An important temple for followers of Lord Krishna.", temple)).isEmpty();
        assertThat(Faithfulness.summaryProblem("The church where a revolt took place, leading to a new church.",
                "Here a revolt took place in the church, and the revolt led to a new church being formed.")).as("a verb, not praise").isEmpty();
    }

    @Test
    void namesAndNumbersTheReaderAlreadySeesAreAllowed() {
        String lounge = "Indoor and rooftop seating, with an international menu from spring rolls to burritos and more.";
        assertThat(Faithfulness.summaryProblem("District 9 Lounge has indoor and rooftop seating and an international menu.",
                lounge, "District 9 Lounge")).isEmpty();
        assertThat(Faithfulness.summaryProblem("A park in Chanakyapuri, good for sunny winter afternoons and long walks with friends.",
                "A large park, good for hanging out on sunny winter afternoons and long walks with friends, near the embassies.",
                "Nehru Park", "Chanakyapuri, New Delhi")).isEmpty();
    }

    @Test
    void compoundsAndPluralsOfKnownNamesPass() {
        assertThat(Faithfulness.nameSupported("Dutch-styled", "a dutch styled garden")).isTrue();
        assertThat(Faithfulness.nameSupported("Jewish-themed", "an affluent jewish couple")).isTrue();
        assertThat(Faithfulness.nameSupported("TVs", "rooms with lcd tv")).isTrue();
        assertThat(Faithfulness.nameSupported("Hindu", "dedicated to ganapati")).isFalse();
        assertThat(Faithfulness.nameSupported("Bengal-type", "a sloped roof")).isFalse();
    }

    @Test
    void aRewriteThatAddsANumberOrANameIsRejected() {
        assertThat(Faithfulness.summaryProblem("A heritage hotel from 1852 with a café.", SEACOM))
                .hasValueSatisfying(r -> assertThat(r).contains("1852"));
        assertThat(Faithfulness.summaryProblem("A heritage hotel near Park Street with a café.", SEACOM))
                .hasValueSatisfying(r -> assertThat(r).contains("Park"));
        assertThat(Faithfulness.summaryProblem("A hotel run by the Salvation Army.", SEACOM)).isPresent();
    }

    @Test
    void namesTheReaderAlreadySeesMayBeRepeated() {
        assertThat(Faithfulness.summaryProblem("A drab hotel in Kolkata with a heritage café on the first floor.", SEACOM, "Kolkata")).isEmpty();
    }

    @Test
    void numbersMatchWithOrWithoutThousandsSeparators() {
        String src = "Rooms from ₹1,500 a night; open 10:30 to 22:00.";
        assertThat(Faithfulness.summaryProblem("Rooms from ₹1500 a night.", src)).isEmpty();
        assertThat(Faithfulness.summaryProblem("Rooms from ₹1,800 a night.", src)).isPresent();
    }

    @Test
    void sentenceStartsAreNotMistakenForNames() {
        assertThat(Faithfulness.names("Good coffee. Try the dosa at Hotel Example. Open daily.")).containsExactly("Hotel", "Example");
    }

    @Test
    void longOrEmptyRewritesAreRejected() {
        assertThat(Faithfulness.summaryProblem("", SEACOM)).isPresent();
        assertThat(Faithfulness.summaryProblem("x".repeat(400), SEACOM)).isPresent();
        assertThat(Faithfulness.summaryProblem("A small hotel with a café and a view.", "Café.")).hasValue("longer than the original");
    }

    @Test
    void quotesMustBeVerbatim() {
        assertThat(Faithfulness.quotedIn("heritage café", SEACOM)).isTrue();
        assertThat(Faithfulness.quotedIn("“Heritage  Café”", SEACOM)).isTrue();
        assertThat(Faithfulness.quotedIn("rooftop bar", SEACOM)).isFalse();
        assertThat(Faithfulness.quotedIn("a", SEACOM)).as("too short to be evidence").isFalse();
    }

    @Test
    void citedSentencesMayOnlyUseTheirSources() {
        List<String> facts = List.of("The best time to visit is October to March, when days are around 25°C.");
        assertThat(Faithfulness.citedSentenceProblem("Visit between October and March, when days are about 25°C.", facts, "Jaipur")).isEmpty();
        assertThat(Faithfulness.citedSentenceProblem("Visit between October and March; avoid Diwali crowds.", facts, "Jaipur")).isPresent();
        assertThat(Faithfulness.citedSentenceProblem("Visit in winter.", List.of(), "Jaipur")).hasValue("cites no fact");
        assertThat(Faithfulness.citedSentenceProblem("Get around by metro, bus or auto-rickshaw.",
                List.of("The metro and buses are cheap; beware of auto-rickshaw drivers who overcharge."), "Jaipur"))
                .as("a getting-around sentence need not repeat the scam warning").isEmpty();
    }
}
