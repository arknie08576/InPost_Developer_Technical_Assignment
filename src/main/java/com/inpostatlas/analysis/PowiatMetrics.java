package com.inpostatlas.analysis;

public record PowiatMetrics(
        String key,
        String displayName,
        String voivodship,
        long population,
        double areaKm2,
        long lockerCount,
        long operatingCount,
        long count247,
        long countAccessible,
        double densityPerKm2,
        double densityPer100kPeople,
        double pct247,
        double pctAccessible,
        int rankByDensityPerKm2,
        int rankByDensityPer100kPeople
) {
}
