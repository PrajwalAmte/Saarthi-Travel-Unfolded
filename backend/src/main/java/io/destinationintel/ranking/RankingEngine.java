package io.destinationintel.ranking;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ranking.Preferences.Interest;
import io.destinationintel.ranking.Preferences.PriceTier;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Deterministic, explainable ranking: S(p,u) = sum_i w_i * f_i(p,u), followed by a greedy
 * diversity re-rank. Hard constraints filter candidates first and are never overridden by
 * score. Ties are broken by title and id, so identical inputs always yield identical output.
 * Explanations are produced only from attributes that actually exist on the entry.
 */
@Component
public class RankingEngine {

    /** Which tab is being ranked; decides which preferences apply. */
    public enum Group { EAT, STAY, EXPLORE }

    public record Candidate(
            UUID id,
            String title,
            String section,
            String subsection,
            String area,
            EntryAttributes attributes,
            Double distanceKm,
            boolean hasCoordinates,
            int detailFacts,
            int independentSources,
            double recency,
            LocalDate freshnessBasis,
            int confirmations,
            int denials,
            OffsetDateTime lastConfirmationAt,
            boolean hasDescription) {}

    public record Reason(String code, String text) {}

    public record Ranked(Candidate candidate, double score, Map<String, Double> factors, List<Reason> reasons) {}

    public record Result(
            List<Ranked> ranked,
            int excludedByConstraints,
            List<String> constraintNotes,
            boolean personalizationApplied,
            String personalizationNote) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final AppProperties.Ranking config;

    @Autowired
    public RankingEngine(AppProperties props) {
        this(props.ranking());
    }

    public RankingEngine(AppProperties.Ranking config) {
        this.config = config;
    }

    public Result rank(Group group, List<Candidate> candidates, Preferences prefs) {
        // 1. Hard constraints.
        List<Candidate> eligible = new ArrayList<>();
        int excludedNoPrice = 0;
        int excludedAbovePrice = 0;
        int excludedNoAccess = 0;
        // A maximum price only applies to food and stays, where recorded prices describe the
        // cost of the recommendation itself.
        boolean priceConstraintApplies = prefs.maxPrice() != null && group != Group.EXPLORE;
        for (Candidate c : candidates) {
            if (priceConstraintApplies) {
                Optional<PriceTier> tier = c.attributes().tier();
                if (tier.isEmpty()) {
                    excludedNoPrice++;
                    continue;
                }
                if (tier.get().ordinal() > prefs.maxPrice().ordinal()) {
                    excludedAbovePrice++;
                    continue;
                }
            }
            if (prefs.accessibleOnly() && c.attributes().accessibility() == null) {
                excludedNoAccess++;
                continue;
            }
            eligible.add(c);
        }
        List<String> notes = new ArrayList<>();
        if (excludedAbovePrice > 0) {
            notes.add(excludedAbovePrice + " hidden because their recorded price is above " + prefs.maxPrice().label().toLowerCase(Locale.ROOT) + ".");
        }
        if (excludedNoPrice > 0) {
            notes.add(excludedNoPrice + " hidden because no price information is recorded for them.");
        }
        if (excludedNoAccess > 0) {
            notes.add(excludedNoAccess + " hidden because no accessibility information is recorded for them.");
        }

        // 2. Score.
        int withPreferenceEvidence = 0;
        List<Ranked> scored = new ArrayList<>(eligible.size());
        for (Candidate c : eligible) {
            PreferenceMatch pm = preferenceMatch(group, c, prefs);
            if (pm.evidenced) {
                withPreferenceEvidence++;
            }
            Map<String, Double> f = new LinkedHashMap<>();
            f.put("preference", pm.score);
            f.put("evidence", evidenceFactor(c));
            f.put("recency", c.recency());
            f.put("community", communityFactor(c));
            f.put("geography", geographyFactor(group, c));
            AppProperties.Ranking.Weights w = config.weights();
            double score = (prefs.hasSoftPreferences() ? w.preference() * f.get("preference") : 0)
                    + w.evidence() * f.get("evidence")
                    + w.recency() * f.get("recency")
                    + w.community() * f.get("community")
                    + w.geography() * f.get("geography");
            List<Reason> reasons = new ArrayList<>(pm.reasons);
            reasons.addAll(evidenceReasons(c));
            scored.add(new Ranked(c, round(score), f, reasons));
        }

        // 3. Diversity re-rank (greedy, deterministic).
        Comparator<Ranked> base = Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(r -> r.candidate().title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.candidate().id());
        List<Ranked> pool = new ArrayList<>(scored);
        pool.sort(base);
        List<Ranked> ordered = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            Ranked best = null;
            double bestAdjusted = Double.NEGATIVE_INFINITY;
            for (Ranked r : pool) {
                double adjusted = r.score() - diversityPenalty(r, ordered);
                if (adjusted > bestAdjusted + 1e-9) {
                    best = r;
                    bestAdjusted = adjusted;
                }
            }
            pool.remove(best);
            ordered.add(best);
        }

        boolean personalized = prefs.hasSoftPreferences() && !eligible.isEmpty()
                && (double) withPreferenceEvidence / eligible.size() >= 0.3;
        String note = null;
        if (prefs.hasSoftPreferences() && !eligible.isEmpty() && !personalized) {
            note = "Only " + withPreferenceEvidence + " of " + eligible.size()
                    + " entries have recorded attributes that match your preferences, so this list is ranked mainly"
                    + " by evidence, freshness and community verification.";
        }
        return new Result(ordered, candidates.size() - eligible.size(), notes, personalized, note);
    }

    private double diversityPenalty(Ranked r, List<Ranked> ordered) {
        int window = Math.max(0, config.diversityWindow());
        int from = Math.max(0, ordered.size() - window);
        int same = 0;
        for (int i = from; i < ordered.size(); i++) {
            Candidate prev = ordered.get(i).candidate();
            Candidate cur = r.candidate();
            if (Objects.equals(prev.subsection(), cur.subsection()) && cur.subsection() != null
                    || Objects.equals(prev.area(), cur.area()) && cur.area() != null) {
                same++;
            }
        }
        return same * config.weights().diversityPenalty() / Math.max(1, window);
    }

    private record PreferenceMatch(double score, boolean evidenced, List<Reason> reasons) {}

    PreferenceMatch preferenceMatch(Group group, Candidate c, Preferences prefs) {
        List<Double> parts = new ArrayList<>();
        List<Reason> reasons = new ArrayList<>();
        boolean evidenced = false;
        EntryAttributes a = c.attributes();

        PriceTier wanted = group == Group.STAY && prefs.comfort() != null ? prefs.comfort().asTier() : prefs.budget();
        boolean priceApplies = group != Group.EXPLORE || "buy".equals(c.section()) || "do".equals(c.section());
        if (wanted != null && priceApplies) {
            Optional<PriceTier> tier = a.tier();
            if (tier.isPresent()) {
                evidenced = true;
                int distance = Math.abs(tier.get().ordinal() - wanted.ordinal());
                parts.add(distance == 0 ? 1.0 : distance == 1 ? 0.45 : 0.0);
                if (distance == 0) {
                    reasons.add(new Reason("price_match", tier.get().label() + ": " + a.priceTier().basis()));
                }
            } else {
                parts.add(0.35);
            }
        }

        if (prefs.food() != null && group == Group.EAT) {
            String style = prefs.food() == Preferences.FoodStyle.REGIONAL ? "regional" : "popular";
            Optional<EntryAttributes.Tag> tag = a.food(style);
            if (tag.isPresent()) {
                evidenced = true;
                parts.add(1.0);
                reasons.add(new Reason("food_style", (style.equals("regional") ? "Regional food: " : "Widely popular: ")
                        + tag.get().basis()));
            } else {
                parts.add(a.foodStyle().isEmpty() ? 0.35 : 0.2);
                if (!a.foodStyle().isEmpty()) {
                    evidenced = true;
                }
            }
        }

        if (!prefs.interests().isEmpty()) {
            Optional<EntryAttributes.Tag> match = prefs.interests().stream()
                    .sorted()
                    .map(a::interest)
                    .flatMap(Optional::stream)
                    .findFirst();
            boolean applies = group == Group.EXPLORE
                    || group == Group.EAT && (prefs.interests().contains(Interest.FOOD) || prefs.interests().contains(Interest.NIGHTLIFE));
            if (applies) {
                if (match.isPresent()) {
                    evidenced = true;
                    parts.add(1.0);
                    reasons.add(new Reason("interest", "Matches your interest in "
                            + match.get().tag() + ": " + match.get().basis()));
                } else {
                    parts.add(a.interests().isEmpty() ? 0.3 : 0.15);
                    if (!a.interests().isEmpty()) {
                        evidenced = true;
                    }
                }
            }
        }
        double score = parts.isEmpty() ? 0.5 : parts.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        return new PreferenceMatch(score, evidenced, reasons);
    }

    double evidenceFactor(Candidate c) {
        double f = c.hasDescription() ? 0.4 : 0.15;
        if (c.hasCoordinates()) {
            f += 0.2;
        }
        f += Math.min(0.2, 0.1 * c.detailFacts());
        if (c.independentSources() >= 2) {
            f += 0.2;
        }
        return round(Math.min(1.0, f));
    }

    double communityFactor(Candidate c) {
        double f = 1 - Math.exp(-0.7 * c.confirmations());
        f -= 0.5 * c.denials();
        return round(Math.max(-0.5, Math.min(1.0, f)));
    }

    double geographyFactor(Group group, Candidate c) {
        if (c.distanceKm() == null) {
            return 0.5;
        }
        double radius = config.nearbyRadiusKm() * (group == Group.EXPLORE ? 3 : 1);
        if (c.distanceKm() <= radius) {
            return round(1.0 - 0.5 * (c.distanceKm() / radius));
        }
        return 0.2;
    }

    private List<Reason> evidenceReasons(Candidate c) {
        List<Reason> reasons = new ArrayList<>();
        EntryAttributes a = c.attributes();
        if (a.major() != null && a.major().basis() != null) {
            reasons.add(new Reason("major", "Major attraction: " + a.major().basis()));
        }
        if (c.confirmations() > 0 && c.lastConfirmationAt() != null) {
            reasons.add(new Reason("community", "Confirmed by " + c.confirmations() + " contributor"
                    + (c.confirmations() == 1 ? "" : "s") + ", most recently on "
                    + DATE.format(c.lastConfirmationAt())));
        }
        if (c.distanceKm() != null) {
            reasons.add(new Reason("distance", String.format(Locale.ENGLISH, "%.1f km from the destination centre", c.distanceKm())));
        }
        if (c.independentSources() >= 2) {
            reasons.add(new Reason("sources", "Supported by " + c.independentSources() + " independent sources"));
        }
        return reasons;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
