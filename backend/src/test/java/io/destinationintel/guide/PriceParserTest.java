package io.destinationintel.guide;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ranking.Preferences.PriceTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "₹75 for lunch|75|75",
            "Rs. 150-300|150|300",
            "₹1,200–2,500 per night|1200|2500",
            "INR 500|500|500",
            "₹30 for tea (average quality). ₹85 for Masala Dosai (good).|30|85",
            "Dorm beds ₹400, doubles ₹1200|400|1200",
            "200 rupees|200|200",
            "₹2k-4k|2000|4000",
            "Free entry; camera ₹50|0|50"
    })
    void parsesRupeeAmounts(String text, int min, int max) {
        var p = PriceParser.parse(text).orElseThrow();
        assertThat(p.min()).isEqualTo(min);
        assertThat(p.max()).isEqualTo(max);
    }

    @Test
    void freeWithoutAmountsIsZero() {
        assertThat(PriceParser.parse("Free").orElseThrow().max()).isZero();
    }

    @Test
    void textWithoutRupeesIsNotParsed() {
        assertThat(PriceParser.parse("Cheap and cheerful")).isEmpty();
        assertThat(PriceParser.parse("US$30")).isEmpty();
        assertThat(PriceParser.parse(null)).isEmpty();
    }

    @Test
    void tiersDependOnSection() {
        var p = PriceParser.parse("₹600").orElseThrow();
        assertThat(PriceParser.tierFor("eat", p)).contains(PriceTier.MODERATE);
        assertThat(PriceParser.tierFor("stay", p)).contains(PriceTier.BUDGET);
        assertThat(PriceParser.tierFor("eat", PriceParser.parse("₹2500").orElseThrow())).contains(PriceTier.PREMIUM);
        assertThat(PriceParser.tierFor("neighbourhood", p)).isEmpty();
    }
}
