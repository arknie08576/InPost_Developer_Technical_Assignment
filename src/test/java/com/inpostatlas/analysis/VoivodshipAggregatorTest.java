package com.inpostatlas.analysis;

import com.inpostatlas.reference.VoivodshipReference;
import com.inpostatlas.storage.LockerRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoivodshipAggregatorTest {

    private final VoivodshipAggregator aggregator = new VoivodshipAggregator();

    private static final List<VoivodshipReference> REFS = List.of(
            new VoivodshipReference("mazowieckie", "Mazowieckie", 5_000_000L, 35_000.0),
            new VoivodshipReference("opolskie", "Opolskie", 1_000_000L, 9_000.0)
    );

    private static LockerRow locker(String name, String province, String status, Boolean l247, Boolean acc) {
        return new LockerRow(
                name, "PL", "parcel_locker", status,
                52.0, 21.0, "Warszawa", province,
                province == null ? null : province.toLowerCase(),
                "00-001", "Marszałkowska", "1",
                l247, acc, "parcel_collect", null, Instant.parse("2026-05-04T10:00:00Z")
        );
    }

    @Test
    void emptyInput_yieldsZeroMetricsForEachVoivodship() {
        var result = aggregator.aggregate(List.of(), REFS);

        assertThat(result.totalLockers()).isZero();
        assertThat(result.unmatchedLockers()).isZero();
        assertThat(result.metrics()).hasSize(2);
        assertThat(result.metrics()).allSatisfy(m -> {
            assertThat(m.lockerCount()).isZero();
            assertThat(m.densityPerKm2()).isZero();
            assertThat(m.densityPer100kPeople()).isZero();
        });
    }

    @Test
    void countsLockersPerVoivodshipUsingNormalizedKey() {
        var lockers = List.of(
                locker("M1", "Mazowieckie", "Operating", true, true),
                locker("M2", "mazowieckie", "Operating", false, true),
                locker("M3", "MAZOWIECKIE", "NonOperating", true, false),
                locker("O1", "Opolskie", "Operating", true, true)
        );

        var result = aggregator.aggregate(lockers, REFS);

        var byKey = result.metrics().stream().collect(
                java.util.stream.Collectors.toMap(VoivodshipMetrics::key, m -> m));
        assertThat(byKey.get("mazowieckie").lockerCount()).isEqualTo(3);
        assertThat(byKey.get("mazowieckie").operatingCount()).isEqualTo(2);
        assertThat(byKey.get("opolskie").lockerCount()).isEqualTo(1);
    }

    @Test
    void computesDensityPer100kAndPerKm2() {
        // 5 lockers in mazowieckie: 5/35000 = 0.000142..; 5*100k/5M = 0.1
        var lockers = List.of(
                locker("M1", "mazowieckie", "Operating", true, true),
                locker("M2", "mazowieckie", "Operating", true, true),
                locker("M3", "mazowieckie", "Operating", true, true),
                locker("M4", "mazowieckie", "Operating", true, true),
                locker("M5", "mazowieckie", "Operating", true, true)
        );

        var result = aggregator.aggregate(lockers, REFS);

        var maz = result.metrics().stream().filter(m -> m.key().equals("mazowieckie")).findFirst().orElseThrow();
        assertThat(maz.densityPerKm2()).isEqualTo(5.0 / 35_000.0);
        assertThat(maz.densityPer100kPeople()).isEqualTo(0.1);
    }

    @Test
    void computesPercentagesFor247AndAccessible() {
        var lockers = List.of(
                locker("O1", "opolskie", "Operating", true, true),
                locker("O2", "opolskie", "Operating", true, false),
                locker("O3", "opolskie", "Operating", false, false),
                locker("O4", "opolskie", "Operating", false, true)
        );

        var result = aggregator.aggregate(lockers, REFS);

        var op = result.metrics().stream().filter(m -> m.key().equals("opolskie")).findFirst().orElseThrow();
        assertThat(op.count247()).isEqualTo(2);
        assertThat(op.pct247()).isEqualTo(50.0);
        assertThat(op.countAccessible()).isEqualTo(2);
        assertThat(op.pctAccessible()).isEqualTo(50.0);
    }

    @Test
    void rankingByDensityPerKm2_higherDensityGetsRank1() {
        // Mazowieckie: 10 / 35000 = 0.000286; Opolskie: 10 / 9000 = 0.00111 → Opolskie wins.
        var lockers = new java.util.ArrayList<LockerRow>();
        for (int i = 0; i < 10; i++) {
            lockers.add(locker("M" + i, "mazowieckie", "Operating", true, true));
            lockers.add(locker("O" + i, "opolskie", "Operating", true, true));
        }

        var result = aggregator.aggregate(lockers, REFS);
        var byKey = result.metrics().stream().collect(
                java.util.stream.Collectors.toMap(VoivodshipMetrics::key, m -> m));

        assertThat(byKey.get("opolskie").rankByDensityPerKm2()).isEqualTo(1);
        assertThat(byKey.get("mazowieckie").rankByDensityPerKm2()).isEqualTo(2);
    }

    @Test
    void unmatchedLockers_areCountedSeparately_notAttributedToAnyVoivodship() {
        var lockers = List.of(
                locker("X1", "Pomorskie", "Operating", true, true),  // not in REFS
                locker("X2", null, "Operating", true, true),
                locker("M1", "mazowieckie", "Operating", true, true)
        );

        var result = aggregator.aggregate(lockers, REFS);

        assertThat(result.totalLockers()).isEqualTo(3);
        assertThat(result.unmatchedLockers()).isEqualTo(2);
        assertThat(result.unmatchedByRawProvince())
                .containsEntry("Pomorskie", 1L)
                .containsEntry("<null>", 1L);

        var maz = result.metrics().stream().filter(m -> m.key().equals("mazowieckie")).findFirst().orElseThrow();
        assertThat(maz.lockerCount()).isEqualTo(1);
    }

    @Test
    void zeroPopulationOrAreaInReference_doesNotDivideByZero() {
        var refs = List.of(
                new VoivodshipReference("ghost", "Ghost", 0L, 0.0)
        );
        var lockers = List.of(locker("G1", "ghost", "Operating", true, true));

        var result = aggregator.aggregate(lockers, refs);

        assertThat(result.metrics().get(0).densityPerKm2()).isZero();
        assertThat(result.metrics().get(0).densityPer100kPeople()).isZero();
    }
}
