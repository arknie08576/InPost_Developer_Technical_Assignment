package com.inpostatlas.reference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class PopulationData {

    private static final String UTF8_BOM = "﻿";
    private final Map<String, VoivodshipReference> byKey;

    public PopulationData(@Value("classpath:reference/voivodships.csv") Resource csv) {
        this.byKey = load(csv);
    }

    public Optional<VoivodshipReference> get(String normalizedKey) {
        return Optional.ofNullable(byKey.get(normalizedKey));
    }

    public Collection<VoivodshipReference> all() {
        return byKey.values();
    }

    public int size() {
        return byKey.size();
    }

    private static Map<String, VoivodshipReference> load(Resource csv) {
        Map<String, VoivodshipReference> result = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(csv.getInputStream(), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) {
                throw new IllegalStateException("voivodships.csv is empty");
            }
            String line;
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                if (lineNo == 2 && line.startsWith(UTF8_BOM)) {
                    line = line.substring(1);
                }
                String[] parts = line.split(",");
                if (parts.length < 4) {
                    throw new IllegalStateException(
                            "voivodships.csv line %d: expected 4 columns, got %d".formatted(lineNo, parts.length));
                }
                String key = parts[0].trim();
                String display = parts[1].trim();
                long population = Long.parseLong(parts[2].trim());
                double area = Double.parseDouble(parts[3].trim());
                result.put(key, new VoivodshipReference(key, display, population, area));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read voivodships.csv", e);
        }
        if (result.size() != 16) {
            throw new IllegalStateException(
                    "voivodships.csv must contain 16 rows, found " + result.size());
        }
        return result;
    }
}
