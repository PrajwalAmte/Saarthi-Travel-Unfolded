package io.destinationintel.guide;

import io.destinationintel.common.Json;
import io.destinationintel.guide.GuideBaseLoader.GuideBase;
import io.destinationintel.guide.GuideDtos.BudgetRange;
import io.destinationintel.guide.GuideDtos.BudgetView;
import io.destinationintel.guide.GuideRows.EntryRow;
import io.destinationintel.guide.GuideRows.FactRow;
import io.destinationintel.ranking.EntryAttributes;
import io.destinationintel.ranking.Preferences.PriceTier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Budget ranges derived on read from price facts recorded in guide listings. Nothing here
 * is stored as a fact: the ranges are recomputed whenever the underlying prices change.
 */
@Service
public class BudgetService {

    static final int MIN_OBSERVATIONS_FOR_RANGE = 3;

    private record Observation(UUID factId, int value, LocalDate observedAt, boolean due, PriceTier tier) {}

    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("eat", new String[] {"meals", "Food", "per dish or meal, as listed"});
        CATEGORIES.put("drink", new String[] {"drinks", "Cafés and drinks", "per item, as listed"});
        CATEGORIES.put("stay", new String[] {"stays", "Accommodation", "per room per night, as listed"});
        CATEGORIES.put("see", new String[] {"entry_fees", "Sights and entry fees", "per person, as listed"});
        CATEGORIES.put("do", new String[] {"activities", "Activities", "per person, as listed"});
    }

    private final GuideBaseLoader loader;
    private final FactPresenter presenter;
    private final FreshnessPolicy freshness;
    private final Json json;

    public BudgetService(GuideBaseLoader loader, FactPresenter presenter, FreshnessPolicy freshness, Json json) {
        this.loader = loader;
        this.presenter = presenter;
        this.freshness = freshness;
        this.json = json;
    }

    public BudgetView budget(UUID destinationId) {
        GuideBase base = loader.load(destinationId);
        Map<String, List<Observation>> bySection = new LinkedHashMap<>();
        int considered = 0;
        int withoutAmount = 0;
        for (EntryRow e : base.entries()) {
            if (!CATEGORIES.containsKey(e.section())) {
                continue;
            }
            for (FactRow f : base.factsFor(e.id())) {
                if (!f.kind().equals("price")) {
                    continue;
                }
                considered++;
                Map<String, Object> v = f.valueJson() == null ? Map.of() : json.map(f.valueJson());
                if (!(v.get("min") instanceof Number min) || !(v.get("max") instanceof Number max)
                        || !"INR".equals(v.get("currency"))) {
                    withoutAmount++;
                    continue;
                }
                var assessment = presenter.assess(f);
                PriceTier tier = EntryAttributes.fromMap(json.map(e.attributes())).tier().orElse(null);
                bySection.computeIfAbsent(e.section(), k -> new ArrayList<>()).add(new Observation(f.id(),
                        (min.intValue() + max.intValue()) / 2, f.observedAt(),
                        assessment.freshness() != FreshnessPolicy.Freshness.CURRENT, tier));
            }
        }

        List<BudgetRange> ranges = new ArrayList<>();
        LocalDate earliest = null;
        LocalDate latest = null;
        for (Map.Entry<String, String[]> cat : CATEGORIES.entrySet()) {
            List<Observation> obs = bySection.getOrDefault(cat.getKey(), List.of());
            if (obs.isEmpty()) {
                continue;
            }
            ranges.add(range(cat.getValue()[0], cat.getValue()[1], null, cat.getValue()[2], obs));
            if (cat.getKey().equals("stay")) {
                for (PriceTier tier : PriceTier.values()) {
                    List<Observation> tiered = obs.stream().filter(o -> o.tier() == tier).toList();
                    if (tiered.size() >= 2) {
                        ranges.add(range("stays_" + tier.name().toLowerCase(Locale.ROOT),
                                tier.label() + " stays", tier.name().toLowerCase(Locale.ROOT), cat.getValue()[2], tiered));
                    }
                }
            }
            for (Observation o : obs) {
                if (o.observedAt() != null) {
                    earliest = earliest == null || o.observedAt().isBefore(earliest) ? o.observedAt() : earliest;
                    latest = latest == null || o.observedAt().isAfter(latest) ? o.observedAt() : latest;
                }
            }
        }

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Figures come from prices written in guide listings and contributor reports. They are not live quotes.");
        assumptions.add("Each listing contributes the midpoint of the price it states. Where at least "
                + MIN_OBSERVATIONS_FOR_RANGE + " listings have prices, the range shows the middle half (25th to 75th percentile).");
        assumptions.add("Amounts are in Indian rupees. Prices given in other currencies are left out.");
        assumptions.add("Prices are volatile facts: anything observed more than " + freshness.reviewWindowDays("volatile")
                + " days ago, or without a recorded date, is counted as needing re-verification.");
        if (earliest != null) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            assumptions.add("Dated prices were observed between " + fmt.format(earliest) + " and " + fmt.format(latest) + ".");
        }
        String empty = ranges.isEmpty()
                ? (considered == 0 ? "No prices have been recorded for this destination yet."
                : "Prices are recorded, but none state a rupee amount that can be compared.")
                : null;
        return new BudgetView("INR", ranges, assumptions, considered, withoutAmount, empty);
    }

    private static BudgetRange range(String key, String label, String tier, String unit, List<Observation> obs) {
        List<Observation> sorted = obs.stream().sorted(Comparator.comparingInt(Observation::value)).toList();
        int n = sorted.size();
        Integer low;
        Integer high;
        if (n >= MIN_OBSERVATIONS_FOR_RANGE) {
            low = sorted.get(percentileIndex(n, 0.25)).value();
            high = sorted.get(percentileIndex(n, 0.75)).value();
        } else {
            low = sorted.get(0).value();
            high = sorted.get(n - 1).value();
        }
        int median = sorted.get(percentileIndex(n, 0.5)).value();
        LocalDate earliest = sorted.stream().map(Observation::observedAt).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        LocalDate latest = sorted.stream().map(Observation::observedAt).filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        int due = (int) sorted.stream().filter(Observation::due).count();
        return new BudgetRange(key, label, tier, low, median, high, n, earliest, latest, due, unit,
                sorted.stream().map(Observation::factId).toList(), reliability(n, due));
    }

    /**
     * How far a range can be trusted, so the page never presents two old prices as an estimate:
     * "too_few" (fewer than 3 prices), "all_stale" (every price is past its review window),
     * "mostly_stale" (half or more are) or "ok".
     */
    static String reliability(int observations, int due) {
        if (observations < MIN_OBSERVATIONS_FOR_RANGE) {
            return "too_few";
        }
        if (due >= observations) {
            return "all_stale";
        }
        return due * 2 >= observations ? "mostly_stale" : "ok";
    }

    static int percentileIndex(int n, double p) {
        int idx = (int) Math.ceil(p * n) - 1;
        return Math.max(0, Math.min(n - 1, idx));
    }
}
