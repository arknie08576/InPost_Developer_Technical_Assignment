package com.inpostatlas.storage;

import java.time.Instant;

public record LockerRow(
        String name,
        String country,
        String type,
        String status,
        Double latitude,
        Double longitude,
        String city,
        String provinceRaw,
        String provinceNormalized,
        String postCode,
        String street,
        String buildingNumber,
        Boolean location247,
        Boolean easyAccessZone,
        String functions,
        String imageUrl,
        Instant fetchedAt
) {
}
