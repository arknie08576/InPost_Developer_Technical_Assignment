package com.inpostatlas.analysis;

import java.util.Locale;

/**
 * Normalises a free-form province string from the API to a canonical key
 * usable as a join column with the GUS reference data.
 *
 * Why: address_details.province is supplied per-locker by InPost and is not
 * guaranteed to be consistently cased ("Mazowieckie" vs "mazowieckie") nor
 * trimmed. We chose to keep Polish diacritics (matching the GUS / GeoJSON
 * spelling) instead of folding to ASCII — the sample data uses diacritics
 * everywhere, and ASCII-folding would lose information without buying anything.
 */
public final class ProvinceNormalizer {

    private ProvinceNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
