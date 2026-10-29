package io.destinationintel.trips;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@Tag(name = "Trips", description = "Trip ideas assembled from sourced guide data")
public class WeekendTripsController {

    private final WeekendTripsService trips;

    public WeekendTripsController(WeekendTripsService trips) {
        this.trips = trips;
    }

    @GetMapping("/origins")
    @Operation(summary = "Cities that have a weekend-trips page")
    public List<WeekendTripsService.Origin> origins() {
        return trips.origins();
    }

    @GetMapping("/weekend-from/{slug}")
    @Operation(summary = "Places with guides within reach of a city, by distance, with sourced notes and comfortable months")
    public ResponseEntity<WeekendTripsService.WeekendTrips> weekendFrom(@PathVariable String slug,
                                                                       @RequestParam(required = false) Integer maxKm) {
        int radius = maxKm != null ? maxKm : trips.origins().stream().filter(o -> o.slug().equals(slug))
                .mapToInt(WeekendTripsService.Origin::radiusKm).findFirst().orElse(350);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(trips.weekendTrips(slug, radius));
    }
}
