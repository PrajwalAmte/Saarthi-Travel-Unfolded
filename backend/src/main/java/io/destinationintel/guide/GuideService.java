package io.destinationintel.guide;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.PageResponse;
import io.destinationintel.guide.FreshnessPolicy.Assessment;
import io.destinationintel.guide.GuideBaseLoader.GuideBase;
import io.destinationintel.guide.GuideDtos.AreaCount;
import io.destinationintel.guide.GuideDtos.CategoryCount;
import io.destinationintel.guide.GuideDtos.ClimateMonth;
import io.destinationintel.guide.GuideDtos.ClimateView;
import io.destinationintel.guide.GuideDtos.EntryView;
import io.destinationintel.guide.GuideDtos.ExternalLink;
import io.destinationintel.guide.GuideDtos.FactGroup;
import io.destinationintel.guide.GuideDtos.FactSectionView;
import io.destinationintel.guide.GuideDtos.FactView;
import io.destinationintel.guide.GuideDtos.Label;
import io.destinationintel.guide.GuideDtos.NeighbourhoodSectionView;
import io.destinationintel.guide.GuideDtos.NeighbourhoodView;
import io.destinationintel.guide.GuideDtos.SectionView;
import io.destinationintel.guide.GuideRows.EntryRow;
import io.destinationintel.guide.GuideRows.EvidenceRow;
import io.destinationintel.guide.GuideRows.FactRow;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.ranking.EntryAttributes;
import io.destinationintel.ranking.Preferences;
import io.destinationintel.ranking.RankingEngine;
import io.destinationintel.ranking.RankingEngine.Candidate;
import io.destinationintel.ranking.RankingEngine.Group;
import io.destinationintel.ranking.RankingEngine.Ranked;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The guide read path: destination + preferences -> cached database snapshot ->
 * deterministic ranking -> evidence-backed views. No external calls happen here.
 */
@Service
public class GuideService {

    public static final Set<String> DETAIL_KINDS = Set.of("price", "hours", "address", "phone", "website",
            "what_to_order", "accessibility");

    private static final Map<Group, List<String>> GROUP_SECTIONS = Map.of(
            Group.EAT, List.of("eat", "drink"),
            Group.STAY, List.of("stay"),
            Group.EXPLORE, List.of("see", "do", "buy"));

    private static final Map<String, String> SECTION_LABELS = Map.of(
            "eat", "Places to eat", "drink", "Cafés & drinks", "stay", "Places to stay",
            "see", "Sights", "do", "Activities", "buy", "Shopping");

    private final GuideBaseLoader loader;
    private final FactPresenter presenter;
    private final FreshnessPolicy freshness;
    private final RankingEngine ranking;
    private final Json json;

    public GuideService(GuideBaseLoader loader, FactPresenter presenter, FreshnessPolicy freshness,
                        RankingEngine ranking, Json json) {
        this.loader = loader;
        this.presenter = presenter;
        this.freshness = freshness;
        this.ranking = ranking;
        this.json = json;
    }

    public static Group parseGroup(String section) {
        return switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "eat" -> Group.EAT;
            case "stay" -> Group.STAY;
            case "explore" -> Group.EXPLORE;
            default -> throw ApiException.badRequest("unknown_section", "Unknown guide section: " + section);
        };
    }

    public SectionView entriesSection(PlaceRow destination, Group group, Preferences prefs, String category,
                                      int page, int size) {
        GuideBase base = loader.load(destination.id());
        List<String> sections = GROUP_SECTIONS.get(group);
        List<EntryRow> inGroup = base.entries().stream().filter(e -> sections.contains(e.section())).toList();

        List<CategoryCount> categories = sections.stream()
                .map(s -> new CategoryCount(s, SECTION_LABELS.get(s), (int) inGroup.stream().filter(e -> e.section().equals(s)).count()))
                .filter(c -> c.count() > 0)
                .toList();

        List<EntryRow> filtered = category == null || category.isBlank() ? inGroup
                : inGroup.stream().filter(e -> e.section().equals(category)).toList();

        Map<UUID, EntryRow> byId = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (EntryRow e : filtered) {
            byId.put(e.id(), e);
            candidates.add(candidate(e, base));
        }
        RankingEngine.Result result = ranking.rank(group, candidates, prefs);
        PageResponse<Ranked> slice = PageResponse.slice(result.ranked(), page, size);
        PageResponse<EntryView> views = slice.map(r -> entryView(destination, byId.get(r.candidate().id()), base, r));

        List<FactView> notes = new ArrayList<>();
        List<FactView> specialities = new ArrayList<>();
        List<ExternalLink> links = new ArrayList<>();
        if (group == Group.EAT) {
            specialities.addAll(destinationFacts(base, Set.of("eat", "drink"), Set.of("speciality")));
            notes.addAll(destinationFacts(base, Set.of("eat", "drink"), Set.of("general_tip", "what_to_order")));
        } else if (group == Group.STAY) {
            notes.addAll(destinationFacts(base, Set.of("stay"), Set.of("accommodation_note", "general_tip")));
            String q = URLEncoder.encode(destination.name() + ", India", StandardCharsets.UTF_8);
            links.add(new ExternalLink("Booking.com", "https://www.booking.com/searchresults.html?ss=" + q,
                    "External site. Live prices and availability are not checked by this platform."));
            links.add(new ExternalLink("Google Hotels", "https://www.google.com/travel/hotels?q=" + q,
                    "External site. Live prices and availability are not checked by this platform."));
        } else {
            specialities.addAll(destinationFacts(base, Set.of("buy"), Set.of("speciality")));
            notes.addAll(destinationFacts(base, Set.of("see", "do", "buy"), Set.of("general_tip")));
        }

        return new SectionView(group.name().toLowerCase(Locale.ROOT), categories, views, filtered.size(),
                prefs.hasSoftPreferences(), result.personalizationApplied(), result.personalizationNote(),
                result.excludedByConstraints(), result.constraintNotes(), notes, specialities, links);
    }

    Candidate candidate(EntryRow e, GuideBase base) {
        List<FactRow> facts = base.factsFor(e.id());
        FactRow description = facts.stream().filter(f -> f.kind().equals("description")).findFirst().orElse(null);
        int detail = (int) facts.stream().filter(f -> DETAIL_KINDS.contains(f.kind())).count();
        Set<String> sources = new HashSet<>();
        int confirmations = 0;
        int denials = 0;
        OffsetDateTime lastConfirmation = null;
        LocalDate basis = null;
        String basisVolatility = "semi_stable";
        for (FactRow f : facts) {
            base.evidenceFor(f.id()).forEach(ev -> sources.add(ev.sourceId()));
            confirmations += f.acceptedConfirmations();
            Assessment a = presenter.assess(f);
            if (a.status() == FreshnessPolicy.VerificationStatus.CONFLICTING_REPORTS) {
                denials += f.acceptedDenials();
            }
            if (f.lastConfirmationAt() != null && (lastConfirmation == null || f.lastConfirmationAt().isAfter(lastConfirmation))) {
                lastConfirmation = f.lastConfirmationAt();
            }
        }
        if (description != null) {
            Assessment a = presenter.assess(description);
            basis = a.basisDate();
            basisVolatility = description.volatility();
        } else {
            for (FactRow f : facts) {
                LocalDate b = presenter.assess(f).basisDate();
                if (b != null && (basis == null || b.isAfter(basis))) {
                    basis = b;
                    basisVolatility = f.volatility();
                }
            }
        }
        return new Candidate(e.id(), e.title(), e.section(), e.subsection(), e.area(), attributes(e), e.distanceKm(),
                e.latitude() != null && e.longitude() != null, detail, sources.size(),
                freshness.recencyFactor(basisVolatility, basis), basis, confirmations, denials, lastConfirmation,
                description != null);
    }

    EntryAttributes attributes(EntryRow e) {
        return EntryAttributes.fromMap(json.map(e.attributes()));
    }

    public EntryView entryView(PlaceRow destination, EntryRow e, GuideBase base, Ranked ranked) {
        List<FactRow> facts = base.factsFor(e.id());
        FactView description = null;
        List<FactView> details = new ArrayList<>();
        for (FactRow f : facts) {
            FactView v = presenter.present(f, base.evidenceFor(f.id()));
            if (f.kind().equals("description") && description == null) {
                description = v;
            } else {
                details.add(v);
            }
        }
        details.sort(Comparator.comparingInt(v -> detailOrder(v.kind())));
        return new EntryView(e.id(), e.section(), e.subsection(), e.area(), e.title(), e.altTitle(), e.latitude(),
                e.longitude(), e.distanceKm(), e.origin(), labels(e, attributes(e)), description, details,
                mapUrl(destination, e), ranked == null ? null : ranked.score(),
                ranked == null ? List.of() : ranked.reasons());
    }

    private static int detailOrder(String kind) {
        return switch (kind) {
            case "what_to_order" -> 0;
            case "price" -> 1;
            case "hours" -> 2;
            case "address" -> 3;
            case "phone" -> 4;
            case "website" -> 5;
            default -> 6;
        };
    }

    List<Label> labels(EntryRow e, EntryAttributes a) {
        List<Label> labels = new ArrayList<>();
        if (a.temporarilyClosed() != null) {
            labels.add(new Label("temporarily_closed", "Reported temporarily closed", a.temporarilyClosed().basis()));
        }
        if (a.major() != null) {
            labels.add(new Label("major", "Major attraction", a.major().basis()));
        }
        a.tier().ifPresent(t -> labels.add(new Label("price_tier", t.label(), a.priceTier().basis())));
        a.food("regional").ifPresent(t -> labels.add(new Label("regional", "Regional food", t.basis())));
        a.food("popular").ifPresent(t -> labels.add(new Label("popular", "Widely popular", t.basis())));
        for (EntryAttributes.Tag t : a.interests()) {
            labels.add(new Label("interest_" + t.tag(), capitalize(t.tag()), t.basis()));
        }
        if (a.accessibility() != null) {
            labels.add(new Label("accessibility", "Accessibility noted", a.accessibility().basis()));
        }
        if ("community".equals(e.origin())) {
            labels.add(new Label("local_contribution", "Local contribution", "Added by a contributor and approved through moderation"));
        }
        return labels;
    }

    /** Location links open Google Maps: coordinates when known, otherwise a search by name and destination. */
    static String mapUrl(PlaceRow destination, EntryRow e) {
        if (e.latitude() != null && e.longitude() != null) {
            return String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f%%2C%.6f",
                    e.latitude(), e.longitude());
        }
        String q = URLEncoder.encode(e.title() + ", " + destination.name(), StandardCharsets.UTF_8);
        return "https://www.google.com/maps/search/?api=1&query=" + q;
    }

    List<FactView> destinationFacts(GuideBase base, Set<String> sections, Set<String> kinds) {
        return base.destinationFacts().stream()
                .filter(f -> sections.contains(f.section()) && kinds.contains(f.kind()))
                .map(f -> presenter.present(f, base.evidenceFor(f.id())))
                .toList();
    }

    public FactSectionView factSection(PlaceRow destination, String section) {
        GuideBase base = loader.load(destination.id());
        return switch (section) {
            case "getting-around" -> {
                List<FactGroup> groups = new ArrayList<>();
                groups.add(group("getting_around", "Getting around", base, Set.of("getting_around"), null));
                groups.add(group("getting_there", "Getting there", base, Set.of("getting_there"), null));
                yield new FactSectionView(section, nonEmpty(groups), countFacts(groups), null);
            }
            case "tips" -> {
                List<FactGroup> groups = new ArrayList<>();
                groups.add(group("seasonality", "When to go", base, Set.of("practical"), Set.of("seasonality")));
                groups.add(group("safety", "Staying safe", base, Set.of("tips"), Set.of("safety")));
                groups.add(group("health", "Staying healthy", base, Set.of("tips"), Set.of("health")));
                groups.add(group("customs", "Local customs and etiquette", base, Set.of("tips"), Set.of("customs")));
                groups.add(group("connectivity", "Connectivity", base, Set.of("tips"), Set.of("connectivity")));
                groups.add(group("general", "Local tips", base, Set.of("tips", "practical"), Set.of("general_tip", "accessibility")));
                ClimateView climate = climate(base);
                yield new FactSectionView(section, nonEmpty(groups), countFacts(groups) + (climate == null ? 0 : 1), climate);
            }
            default -> throw ApiException.badRequest("unknown_section", "Unknown guide section: " + section);
        };
    }

    private FactGroup group(String key, String title, GuideBase base, Set<String> sections, Set<String> kinds) {
        List<FactView> facts = base.destinationFacts().stream()
                .filter(f -> sections.contains(f.section()) && (kinds == null || kinds.contains(f.kind()))
                        && !f.kind().equals("climate"))
                .map(f -> presenter.present(f, base.evidenceFor(f.id())))
                .toList();
        return new FactGroup(key, title, facts);
    }

    private static List<FactGroup> nonEmpty(List<FactGroup> groups) {
        return groups.stream().filter(g -> !g.facts().isEmpty()).toList();
    }

    private static int countFacts(List<FactGroup> groups) {
        return groups.stream().mapToInt(g -> g.facts().size()).sum();
    }

    @SuppressWarnings("unchecked")
    ClimateView climate(GuideBase base) {
        return base.destinationFacts().stream()
                .filter(f -> f.kind().equals("climate") && f.valueJson() != null)
                .findFirst()
                .map(f -> {
                    Map<String, Object> v = json.map(f.valueJson());
                    List<ClimateMonth> months = new ArrayList<>();
                    if (v.get("months") instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof Map<?, ?> m) {
                                months.add(new ClimateMonth(((Number) m.get("month")).intValue(), num(m.get("meanMaxC")),
                                        num(m.get("meanMinC")), num(m.get("precipitationMm")), num(m.get("rainyDays"))));
                            }
                        }
                    }
                    return new ClimateView(presenter.present(f, base.evidenceFor(f.id())),
                            Objects.toString(v.get("period"), null), months);
                })
                .orElse(null);
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    public NeighbourhoodSectionView neighbourhoods(PlaceRow destination) {
        GuideBase base = loader.load(destination.id());
        List<NeighbourhoodView> hoods = new ArrayList<>();
        for (EntryRow e : base.entries()) {
            if (!e.section().equals("neighbourhood")) {
                continue;
            }
            Map<String, Object> attrs = json.map(e.attributes());
            List<String> localities = attrs.get("localities") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            hoods.add(new NeighbourhoodView(entryView(destination, e, base, null), localities));
        }
        hoods.sort(Comparator.comparing(h -> h.entry().title(), String.CASE_INSENSITIVE_ORDER));
        Map<String, Map<String, Integer>> areas = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (EntryRow e : base.entries()) {
            if (e.area() != null && !e.section().equals("neighbourhood")) {
                areas.computeIfAbsent(e.area(), k -> new TreeMap<>()).merge(e.section(), 1, Integer::sum);
            }
        }
        List<AreaCount> areaCounts = areas.entrySet().stream()
                .map(en -> new AreaCount(en.getKey(), en.getValue(), en.getValue().values().stream().mapToInt(Integer::intValue).sum()))
                .toList();
        return new NeighbourhoodSectionView(hoods,
                destinationFacts(base, Set.of("stay"), Set.of("accommodation_note")),
                destinationFacts(base, Set.of("practical", "neighbourhood", "overview"), Set.of("orientation", "neighbourhood_character")),
                areaCounts);
    }

    public FactView summary(UUID destinationId) {
        GuideBase base = loader.load(destinationId);
        return base.destinationFacts().stream()
                .filter(f -> f.kind().equals("summary"))
                .findFirst()
                .map(f -> presenter.present(f, base.evidenceFor(f.id())))
                .orElse(null);
    }

    /** Count of published items per guide tab, used for navigation and coverage display. */
    public Map<String, Integer> sectionCounts(UUID destinationId) {
        GuideBase base = loader.load(destinationId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("eat", (int) base.entries().stream().filter(e -> Set.of("eat", "drink").contains(e.section())).count()
                + (int) base.destinationFacts().stream().filter(f -> f.kind().equals("speciality") && Set.of("eat", "drink").contains(f.section())).count());
        counts.put("stay", (int) base.entries().stream().filter(e -> e.section().equals("stay")).count()
                + (int) base.destinationFacts().stream().filter(f -> f.section().equals("stay")).count());
        counts.put("explore", (int) base.entries().stream().filter(e -> Set.of("see", "do", "buy").contains(e.section())).count());
        counts.put("neighbourhoods", (int) base.entries().stream().filter(e -> e.section().equals("neighbourhood")).count()
                + (int) base.destinationFacts().stream().filter(f -> Set.of("accommodation_note", "orientation", "neighbourhood_character").contains(f.kind())).count());
        counts.put("budget", (int) base.factsByEntry().values().stream().flatMap(List::stream).filter(f -> f.kind().equals("price")).count());
        counts.put("getting-around", (int) base.destinationFacts().stream().filter(f -> Set.of("getting_around", "getting_there").contains(f.section())).count());
        counts.put("tips", (int) base.destinationFacts().stream().filter(f -> Set.of("tips", "practical").contains(f.section())).count());
        return counts;
    }

    public Set<String> sourcesUsed(UUID destinationId) {
        GuideBase base = loader.load(destinationId);
        return base.evidenceByFact().values().stream().flatMap(List::stream).map(EvidenceRow::sourceId)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
