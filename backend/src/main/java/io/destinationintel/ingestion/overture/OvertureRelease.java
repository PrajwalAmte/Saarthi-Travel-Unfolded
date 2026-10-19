package io.destinationintel.ingestion.overture;

import java.util.List;

/**
 * One Overture Maps release of the places theme as described by its STAC catalog: the release
 * id, schema version and the GeoParquet files whose bounding box intersects the area of interest.
 */
public record OvertureRelease(String release, String schemaVersion, List<DataFile> files) {

    /** A GeoParquet file with the size and row count the catalog publishes for verification. */
    public record DataFile(String name, String href, long size, long rows, double[] bbox) {}
}
