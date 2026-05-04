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
public class PowiatPopulationData {

    private static final int EXPECTED_ROWS = 380;
    private final Map<String, PowiatReference> byKey;

    public PowiatPopulationData(@Value("classpath:reference/powiats.csv") Resource csv) {
        this.byKey = load(csv);
    }

    public Optional<PowiatReference> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Collection<PowiatReference> all() {
        return byKey.values();
    }

    public int size() {
        return byKey.size();
    }

    private static Map<String, PowiatReference> load(Resource csv) {
        Map<String, PowiatReference> result = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(csv.getInputStream(), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) {
                throw new IllegalStateException("powiats.csv is empty");
            }
            String line;
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 6) {
                    throw new IllegalStateException(
                            "powiats.csv line %d: expected 6 columns, got %d".formatted(lineNo, parts.length));
                }
                String key = parts[0].trim();
                String display = parts[1].trim();
                String geojsonNazwa = parts[2].trim();
                String voivodship = parts[3].trim();
                long population = Long.parseLong(parts[4].trim());
                double area = Double.parseDouble(parts[5].trim());
                result.put(key, new PowiatReference(key, display, geojsonNazwa, voivodship, population, area));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read powiats.csv", e);
        }
        if (result.size() != EXPECTED_ROWS) {
            throw new IllegalStateException(
                    "powiats.csv must contain %d rows, found %d".formatted(EXPECTED_ROWS, result.size()));
        }
        return result;
    }
}
