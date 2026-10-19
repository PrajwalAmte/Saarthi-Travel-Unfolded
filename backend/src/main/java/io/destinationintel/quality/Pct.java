package io.destinationintel.quality;

/** Percentage helper that treats an empty population as 0% rather than dividing by zero. */
final class Pct {

    private Pct() {}

    static double of(long part, long whole) {
        return whole == 0 ? 0.0 : Math.round(10000.0 * part / whole) / 100.0;
    }
}
