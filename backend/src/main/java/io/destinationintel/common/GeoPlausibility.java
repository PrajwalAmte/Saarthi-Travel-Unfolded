package io.destinationintel.common;

import java.util.Set;

/**
 * Whether a point can belong to a destination. Used for source listings and community
 * submissions alike: a point more than 80 km from a city or town (600 km from a state, region
 * or park) cannot be one of its places, and a point outside India's bounding box is never valid.
 */
public final class GeoPlausibility {

    public static final double MIN_LAT = 6.4;
    public static final double MAX_LAT = 37.6;
    public static final double MIN_LON = 68.0;
    public static final double MAX_LON = 97.5;
    private static final Set<String> LARGE = Set.of("state", "union_territory", "region", "park", "district");

    private GeoPlausibility() {}

    public static boolean insideIndia(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
    }

    public static double limitKm(String destinationKind) {
        return destinationKind != null && LARGE.contains(destinationKind) ? 600 : 80;
    }

    /** "outside_india", "far_from_destination" or null when plausible or not checkable. */
    public static String flag(String destinationKind, Double destLat, Double destLon, Double lat, Double lon) {
        if (lat == null || lon == null) {
            return null;
        }
        if (!insideIndia(lat, lon)) {
            return "outside_india";
        }
        if (destLat == null || destLon == null) {
            return null;
        }
        return haversineKm(destLat, destLon, lat, lon) > limitKm(destinationKind) ? "far_from_destination" : null;
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
