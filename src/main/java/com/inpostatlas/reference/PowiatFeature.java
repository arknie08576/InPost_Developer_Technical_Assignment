package com.inpostatlas.reference;

import org.locationtech.jts.geom.Geometry;

/**
 * A powiat polygon paired with its CSV-derived join key.
 * Held in memory for the lifetime of the application — used by
 * {@code PowiatAssigner} for point-in-polygon queries and by
 * {@code HtmlReportWriter} as part of the embedded GeoJSON layer.
 */
public record PowiatFeature(
        String key,
        String displayName,
        String voivodship,
        Geometry geometry
) {
}
