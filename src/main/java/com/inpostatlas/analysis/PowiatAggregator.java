package com.inpostatlas.analysis;

import com.inpostatlas.reference.PowiatPopulationData;
import com.inpostatlas.reference.PowiatReference;
import com.inpostatlas.storage.LockerRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PowiatAggregator {

    private static final String OPERATING_STATUS = "Operating";

    private final PowiatAssigner assigner;
    private final PowiatPopulationData populationData;

    public PowiatAggregator(PowiatAssigner assigner, PowiatPopulationData populationData) {
        this.assigner = assigner;
        this.populationData = populationData;
    }

    public PowiatAggregationResult aggregate(List<LockerRow> lockers) {
        Map<String, Bucket> buckets = new HashMap<>();
        long unmatched = 0;
        for (LockerRow row : lockers) {
            var key = assigner.assign(row.latitude(), row.longitude());
            if (key.isEmpty()) {
                unmatched++;
                continue;
            }
            buckets.computeIfAbsent(key.get(), k -> new Bucket()).add(row);
        }

        List<PowiatMetrics> raw = new ArrayList<>(populationData.size());
        for (PowiatReference ref : populationData.all()) {
            Bucket b = buckets.getOrDefault(ref.key(), Bucket.EMPTY);
            double densKm2 = ref.areaKm2() <= 0 ? 0.0 : b.count / ref.areaKm2();
            double dens100k = ref.population() <= 0 ? 0.0 : (b.count * 100_000.0) / ref.population();
            double pct247 = b.count == 0 ? 0.0 : b.count247 * 100.0 / b.count;
            double pctAcc = b.count == 0 ? 0.0 : b.countAccessible * 100.0 / b.count;
            raw.add(new PowiatMetrics(
                    ref.key(),
                    ref.displayName(),
                    ref.voivodship(),
                    ref.population(),
                    ref.areaKm2(),
                    b.count,
                    b.operating,
                    b.count247,
                    b.countAccessible,
                    densKm2,
                    dens100k,
                    pct247,
                    pctAcc,
                    0,
                    0
            ));
        }

        Map<String, Integer> rankKm2 = computeRanks(raw, Comparator.comparingDouble(PowiatMetrics::densityPerKm2).reversed());
        Map<String, Integer> rank100k = computeRanks(raw, Comparator.comparingDouble(PowiatMetrics::densityPer100kPeople).reversed());

        List<PowiatMetrics> ranked = new ArrayList<>(raw.size());
        for (PowiatMetrics m : raw) {
            ranked.add(new PowiatMetrics(
                    m.key(), m.displayName(), m.voivodship(),
                    m.population(), m.areaKm2(),
                    m.lockerCount(), m.operatingCount(), m.count247(), m.countAccessible(),
                    m.densityPerKm2(), m.densityPer100kPeople(),
                    m.pct247(), m.pctAccessible(),
                    rankKm2.get(m.key()),
                    rank100k.get(m.key())
            ));
        }
        ranked.sort(Comparator.comparingInt(PowiatMetrics::rankByDensityPerKm2));

        return new PowiatAggregationResult(ranked, lockers.size(), unmatched);
    }

    private static Map<String, Integer> computeRanks(List<PowiatMetrics> raw, Comparator<PowiatMetrics> cmp) {
        List<PowiatMetrics> sorted = new ArrayList<>(raw);
        sorted.sort(cmp);
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).key(), i + 1);
        }
        return ranks;
    }

    private static final class Bucket {
        static final Bucket EMPTY = new Bucket();
        long count;
        long operating;
        long count247;
        long countAccessible;

        void add(LockerRow row) {
            count++;
            if (OPERATING_STATUS.equalsIgnoreCase(row.status())) {
                operating++;
            }
            if (Boolean.TRUE.equals(row.location247())) {
                count247++;
            }
            if (Boolean.TRUE.equals(row.easyAccessZone())) {
                countAccessible++;
            }
        }
    }
}
