package io.destinationintel.ingestion.openmeteo;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns daily reanalysis values into monthly climate averages and a short, data-derived
 * summary. Pure function; the summary only states what the numbers show.
 */
public final class ClimateAggregator {

    public record Daily(LocalDate date, Double tempMax, Double tempMin, Double precipitation) {}

    public record MonthStats(int month, double meanMaxC, double meanMinC, double precipitationMm, double rainyDays) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month);
            m.put("meanMaxC", round1(meanMaxC));
            m.put("meanMinC", round1(meanMinC));
            m.put("precipitationMm", Math.round(precipitationMm));
            m.put("rainyDays", round1(rainyDays));
            return m;
        }
    }

    public record Climate(List<MonthStats> months, int startYear, int endYear, String summary) {}

    private ClimateAggregator() {}

    public static Climate aggregate(List<Daily> days) {
        Map<Integer, double[]> sums = new HashMap<>();
        Map<String, double[]> perMonthYear = new HashMap<>();
        int startYear = Integer.MAX_VALUE;
        int endYear = Integer.MIN_VALUE;
        for (Daily d : days) {
            if (d.tempMax() == null || d.tempMin() == null || d.precipitation() == null) {
                continue;
            }
            int m = d.date().getMonthValue();
            startYear = Math.min(startYear, d.date().getYear());
            endYear = Math.max(endYear, d.date().getYear());
            double[] s = sums.computeIfAbsent(m, k -> new double[3]);
            s[0] += d.tempMax();
            s[1] += d.tempMin();
            s[2]++;
            double[] my = perMonthYear.computeIfAbsent(d.date().getYear() + "-" + m, k -> new double[2]);
            my[0] += d.precipitation();
            if (d.precipitation() >= 1.0) {
                my[1]++;
            }
        }
        if (sums.size() < 12) {
            throw new IllegalArgumentException("Climate data does not cover all twelve months");
        }
        List<MonthStats> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            double[] s = sums.get(m);
            double precip = 0;
            double rainy = 0;
            int years = 0;
            for (int y = startYear; y <= endYear; y++) {
                double[] my = perMonthYear.get(y + "-" + m);
                if (my != null) {
                    precip += my[0];
                    rainy += my[1];
                    years++;
                }
            }
            months.add(new MonthStats(m, s[0] / s[2], s[1] / s[2], years == 0 ? 0 : precip / years, years == 0 ? 0 : rainy / years));
        }
        return new Climate(months, startYear, endYear, summary(months, startYear, endYear));
    }

    static String summary(List<MonthStats> months, int startYear, int endYear) {
        MonthStats warmest = months.stream().max(Comparator.comparingDouble(MonthStats::meanMaxC)).orElseThrow();
        MonthStats coolest = months.stream().min(Comparator.comparingDouble(MonthStats::meanMinC)).orElseThrow();
        MonthStats wettest = months.stream().max(Comparator.comparingDouble(MonthStats::precipitationMm)).orElseThrow();
        List<String> dry = months.stream().filter(m -> m.precipitationMm() < 20).map(m -> name(m.month())).toList();
        double annual = months.stream().mapToDouble(MonthStats::precipitationMm).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ENGLISH, "Warmest month: %s, with an average daily high of %.0f°C. ",
                name(warmest.month()), warmest.meanMaxC()));
        sb.append(String.format(Locale.ENGLISH, "Coolest nights: %s, averaging %.0f°C. ", name(coolest.month()), coolest.meanMinC()));
        sb.append(String.format(Locale.ENGLISH, "Wettest month: %s, with about %d mm of rain over %.0f rainy days. ",
                name(wettest.month()), Math.round(wettest.precipitationMm()), wettest.rainyDays()));
        if (!dry.isEmpty()) {
            sb.append("Months with under 20 mm of rain on average: ").append(String.join(", ", dry)).append(". ");
        }
        sb.append(String.format(Locale.ENGLISH, "Average annual rainfall: about %d mm. ", Math.round(annual)));
        sb.append("Averages for ").append(startYear).append("–").append(endYear)
                .append(" from ERA5 reanalysis; individual years and local microclimates vary.");
        return sb.toString();
    }

    private static String name(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
