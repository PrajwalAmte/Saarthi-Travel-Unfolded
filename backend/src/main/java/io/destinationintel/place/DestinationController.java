package io.destinationintel.place;

import io.destinationintel.common.ClientIpResolver;
import io.destinationintel.common.PageResponse;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.BudgetService;
import io.destinationintel.guide.GuideDtos.BudgetView;
import io.destinationintel.guide.GuideDtos.FactSectionView;
import io.destinationintel.guide.GuideDtos.NeighbourhoodSectionView;
import io.destinationintel.guide.GuideDtos.SectionView;
import io.destinationintel.guide.GuideService;
import io.destinationintel.ranking.Preferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Destinations", description = "Search, destination details and guide sections. No account required.")
public class DestinationController {

    private static final String SLUG = "^[a-z0-9][a-z0-9-]{0,120}$";
    private static final CacheControl PUBLIC_SHORT = CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic();

    private final SearchService search;
    private final DestinationService destinations;
    private final GuideService guide;
    private final BudgetService budget;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver ip;
    private final AppProperties props;

    public DestinationController(SearchService search, DestinationService destinations, GuideService guide,
                                 BudgetService budget, RateLimiter rateLimiter, ClientIpResolver ip, AppProperties props) {
        this.search = search;
        this.destinations = destinations;
        this.guide = guide;
        this.budget = budget;
        this.rateLimiter = rateLimiter;
        this.ip = ip;
        this.props = props;
    }

    @GetMapping("/search")
    @Operation(summary = "Search indexed destinations by name, alternate spelling or partial name")
    public ResponseEntity<SearchService.SearchResult> search(
            @RequestParam("q") @Size(max = 100) String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        rateLimiter.check("search", ip.resolve(request), props.rateLimits().searchPerMinute(), Duration.ofMinutes(1));
        return ResponseEntity.ok().cacheControl(PUBLIC_SHORT)
                .body(search.search(q, limit == null ? 8 : limit));
    }

    @GetMapping("/destinations")
    @Operation(summary = "Browse indexed destinations, optionally only those with guide content or of one kind")
    public ResponseEntity<PageResponse<DestinationService.ListItem>> list(
            @RequestParam(required = false)
            @Pattern(regexp = "^(state|union_territory|district|region|city|town|village|park|island)?$") String kind,
            @RequestParam(required = false) Boolean withGuide,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        String k = kind == null || kind.isBlank() ? null : kind;
        return ResponseEntity.ok().cacheControl(PUBLIC_SHORT).body(destinations.list(k, Boolean.TRUE.equals(withGuide),
                PageResponse.clampPage(page), PageResponse.clampSize(size, 24)));
    }

    @GetMapping("/destinations/{slug}/entries")
    @Operation(summary = "Find published entries in a guide by title (used when reporting duplicates)")
    public List<DestinationService.EntryRef> entries(@PathVariable @Pattern(regexp = SLUG) String slug,
                                                     @RequestParam("q") @Size(min = 2, max = 100) String q) {
        return destinations.findEntries(destinations.require(slug).id(), q);
    }

    @GetMapping("/destinations/{slug}")
    @Operation(summary = "Destination details, coverage status and summary")
    public ResponseEntity<DestinationService.DestinationDetail> detail(@PathVariable @Pattern(regexp = SLUG) String slug) {
        return ResponseEntity.ok().cacheControl(PUBLIC_SHORT).body(destinations.detail(slug));
    }

    @GetMapping("/destinations/{slug}/recheck")
    @Operation(summary = "Prices and opening hours worth rechecking first, in the order travellers see the listings",
            description = "Top listings of Eat, Stay and Explore whose price or hours are past their review window or undated.")
    public io.destinationintel.guide.GuideDtos.RecheckView recheck(@PathVariable @Pattern(regexp = SLUG) String slug,
                                         @RequestParam(required = false) Integer perSection) {
        int n = perSection == null ? 12 : Math.max(1, Math.min(30, perSection));
        return guide.recheckWorklist(destinations.require(slug), n);
    }

    @GetMapping("/destinations/{slug}/sections/{section}")
    @Operation(summary = "Ranked guide entries for the eat, stay or explore tab",
            description = "Soft preferences (food, budget, comfort, interests) change ranking; maxPrice and accessibleOnly filter before ranking.")
    public SectionView section(
            @PathVariable @Pattern(regexp = SLUG) String slug,
            @PathVariable @Pattern(regexp = "^(eat|stay|explore)$") String section,
            @Parameter(description = "regional | popular") @RequestParam(required = false) String food,
            @Parameter(description = "budget | moderate | premium") @RequestParam(required = false) String budget,
            @Parameter(description = "basic | comfortable | luxury") @RequestParam(required = false) String comfort,
            @Parameter(description = "Comma-separated: food,culture,nature,adventure,nightlife,shopping,family")
            @RequestParam(required = false) @Size(max = 120) String interests,
            @Parameter(description = "Hard constraint: budget | moderate | premium") @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) Boolean accessibleOnly,
            @Parameter(description = "Sub-category, e.g. eat, drink, see, do, buy") @RequestParam(required = false)
            @Pattern(regexp = "^(eat|drink|stay|see|do|buy)?$") String category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PlaceRow place = destinations.require(slug);
        Preferences prefs = Preferences.parse(food, budget, comfort, interests, maxPrice, accessibleOnly);
        return guide.entriesSection(place, GuideService.parseGroup(section), prefs, category,
                PageResponse.clampPage(page), PageResponse.clampSize(size, 12));
    }

    @GetMapping("/destinations/{slug}/neighbourhoods")
    @Operation(summary = "Neighbourhoods, where-to-stay notes and listing areas")
    public NeighbourhoodSectionView neighbourhoods(@PathVariable @Pattern(regexp = SLUG) String slug) {
        return guide.neighbourhoods(destinations.require(slug));
    }

    @GetMapping("/destinations/{slug}/budget")
    @Operation(summary = "Budget ranges derived from recorded listing prices, with assumptions")
    public BudgetView budget(@PathVariable @Pattern(regexp = SLUG) String slug) {
        return budget.budget(destinations.require(slug).id());
    }

    @GetMapping("/destinations/{slug}/getting-around")
    @Operation(summary = "Transport information for getting there and getting around")
    public FactSectionView gettingAround(@PathVariable @Pattern(regexp = SLUG) String slug) {
        return guide.factSection(destinations.require(slug), "getting-around");
    }

    @GetMapping("/destinations/{slug}/tips")
    @Operation(summary = "Seasonality, climate, safety, customs and other practical tips")
    public FactSectionView tips(@PathVariable @Pattern(regexp = SLUG) String slug) {
        return guide.factSection(destinations.require(slug), "tips");
    }
}
