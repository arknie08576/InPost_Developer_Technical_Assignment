package com.inpostatlas.analysis;

import com.inpostatlas.reference.VoivodshipReference;
import com.inpostatlas.storage.LockerRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class VoivodshipAggregator {

    private static final String OPERATING_STATUS = "Operating";

    public AggregationResult aggregate(List<LockerRow> lockers, Collection<VoivodshipReference> references) {
        Set<String> validKeys = references.stream()
                .map(VoivodshipReference::normalizedName)
                .collect(Collectors.toCollection(HashSet::new));

        Map<String, List<LockerRow>> byProvince = lockers.stream()
                .filter(l -> l.provinceNormalized() != null && validKeys.contains(l.provinceNormalized()))
                .collect(Collectors.groupingBy(LockerRow::provinceNormalized));

        List<VoivodshipMetrics> raw = new ArrayList<>();
        for (VoivodshipReference ref : references) {
            List<LockerRow> bucket = byProvince.getOrDefault(ref.normalizedName(), Collections.emptyList());
            long count = bucket.size();
            long operating = bucket.stream()
                    .filter(b -> OPERATING_STATUS.equalsIgnoreCase(b.status()))
                    .count();
            long c247 = bucket.stream()
                    .filter(b -> Boolean.TRUE.equals(b.location247()))
                    .count();
            long cAcc = bucket.stream()
                    .filter(b -> Boolean.TRUE.equals(b.easyAccessZone()))
                    .count();

            double densKm2 = ref.areaKm2() <= 0 ? 0.0 : count / ref.areaKm2();
            double dens100k = ref.population() <= 0 ? 0.0 : (count * 100_000.0) / ref.population();
            double pct247 = count == 0 ? 0.0 : c247 * 100.0 / count;
            double pctAcc = count == 0 ? 0.0 : cAcc * 100.0 / count;

            raw.add(new VoivodshipMetrics(
                    ref.normalizedName(),
                    ref.displayName(),
                    ref.population(),
                    ref.areaKm2(),
                    count,
                    operating,
                    c247,
                    cAcc,
                    densKm2,
                    dens100k,
                    pct247,
                    pctAcc,
                    0,
                    0));
        }

        List<VoivodshipMetrics> ranked = withRanks(raw);

        long unmatched = lockers.stream()
                .filter(l -> l.provinceNormalized() == null || !validKeys.contains(l.provinceNormalized()))
                .count();

        Map<String, Long> unmatchedByRaw = lockers.stream()
                .filter(l -> l.provinceNormalized() == null || !validKeys.contains(l.provinceNormalized()))
                .collect(Collectors.groupingBy(
                        l -> l.provinceRaw() == null ? "<null>" : l.provinceRaw(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        return new AggregationResult(ranked, lockers.size(), unmatched, unmatchedByRaw);
    }

    private static List<VoivodshipMetrics> withRanks(List<VoivodshipMetrics> raw) {
        Map<String, Integer> rankKm2 = computeRanks(raw, Comparator.comparingDouble(VoivodshipMetrics::densityPerKm2).reversed());
        Map<String, Integer> rank100k = computeRanks(raw, Comparator.comparingDouble(VoivodshipMetrics::densityPer100kPeople).reversed());
        return raw.stream()
                .map(m -> new VoivodshipMetrics(
                        m.key(), m.displayName(), m.population(), m.areaKm2(),
                        m.lockerCount(), m.operatingCount(), m.count247(), m.countAccessible(),
                        m.densityPerKm2(), m.densityPer100kPeople(),
                        m.pct247(), m.pctAccessible(),
                        rankKm2.get(m.key()),
                        rank100k.get(m.key())))
                .sorted(Comparator.comparingInt(VoivodshipMetrics::rankByDensityPerKm2))
                .toList();
    }

    private static Map<String, Integer> computeRanks(List<VoivodshipMetrics> raw, Comparator<VoivodshipMetrics> cmp) {
        List<VoivodshipMetrics> sorted = new ArrayList<>(raw);
        sorted.sort(cmp);
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).key(), i + 1);
        }
        return ranks;
    }
}
