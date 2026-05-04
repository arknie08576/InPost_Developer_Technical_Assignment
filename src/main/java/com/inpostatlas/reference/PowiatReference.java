package com.inpostatlas.reference;

/**
 * One row of the powiats.csv reference table.
 *
 * The {@code key} is the join column shared with {@link com.inpostatlas.analysis.PowiatAssigner}
 * and is composed as {@code "<geojsonNazwa>__<voivodship>"}. We need the
 * voivodship in the key because two GeoJSON features carry duplicate names —
 * "powiat średzki" and "powiat świdnicki" each appear in two voivodships.
 */
public record PowiatReference(
        String key,
        String displayName,
        String geojsonNazwa,
        String voivodship,
        long population,
        double areaKm2
) {
}
