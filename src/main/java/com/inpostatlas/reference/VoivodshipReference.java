package com.inpostatlas.reference;

public record VoivodshipReference(
        String normalizedName,
        String displayName,
        long population,
        double areaKm2
) {
}
