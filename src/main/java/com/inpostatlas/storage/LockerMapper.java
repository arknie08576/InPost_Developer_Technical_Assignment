package com.inpostatlas.storage;

import com.inpostatlas.analysis.ProvinceNormalizer;
import com.inpostatlas.api.dto.PointDto;

import java.time.Instant;
import java.util.List;

public final class LockerMapper {

    private LockerMapper() {
    }

    public static LockerRow toRow(PointDto p, Instant fetchedAt) {
        var addr = p.address_details();
        var loc = p.location();
        String provinceRaw = addr != null ? addr.province() : null;
        return new LockerRow(
                p.name(),
                p.country(),
                joinList(p.type()),
                p.status(),
                loc != null ? loc.latitude() : null,
                loc != null ? loc.longitude() : null,
                addr != null ? addr.city() : null,
                provinceRaw,
                ProvinceNormalizer.normalize(provinceRaw),
                addr != null ? addr.post_code() : null,
                addr != null ? addr.street() : null,
                addr != null ? addr.building_number() : null,
                p.location_247(),
                p.easy_access_zone(),
                joinList(p.functions()),
                p.image_url(),
                fetchedAt
        );
    }

    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }
}
