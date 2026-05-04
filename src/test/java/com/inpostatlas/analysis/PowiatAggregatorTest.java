package com.inpostatlas.analysis;

import com.inpostatlas.reference.PowiatPopulationData;
import com.inpostatlas.reference.PowiatReference;
import com.inpostatlas.storage.LockerRow;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PowiatAggregatorTest {

    private static final PowiatReference KRAKOW = new PowiatReference(
            "powiat Kraków__małopolskie", "Kraków (miasto na prawach powiatu)",
            "powiat Kraków", "małopolskie", 780981L, 326.85);
    private static final PowiatReference KRAKOWSKI = new PowiatReference(
            "powiat krakowski__małopolskie", "Powiat krakowski",
            "powiat krakowski", "małopolskie", 281000L, 1230.0);
    private static final List<PowiatReference> REFS = List.of(KRAKOW, KRAKOWSKI);

    private static PowiatAggregator aggregatorAssigning(String fixedKey) {
        PowiatAssigner assigner = Mockito.mock(PowiatAssigner.class);
        Mockito.when(assigner.assign(Mockito.anyDouble(), Mockito.anyDouble()))
                .thenReturn(Optional.ofNullable(fixedKey));
        return new PowiatAggregator(assigner, refs());
    }

    private static PowiatAggregator aggregatorAssigningPerLat() {
        // Lockers with lat >= 50.05 → KRAKOW, otherwise → KRAKOWSKI.
        PowiatAssigner assigner = Mockito.mock(PowiatAssigner.class);
        Mockito.when(assigner.assign(Mockito.anyDouble(), Mockito.anyDouble()))
                .thenAnswer(inv -> {
                    Double lat = inv.getArgument(0);
                    if (lat == null) return Optional.empty();
                    return Optional.of(lat >= 50.05 ? KRAKOW.key() : KRAKOWSKI.key());
                });
        return new PowiatAggregator(assigner, refs());
    }

    private static PowiatPopulationData refs() {
        PowiatPopulationData data = Mockito.mock(PowiatPopulationData.class);
        Mockito.when(data.all()).thenReturn((Collection<PowiatReference>) (Collection<?>) REFS);
        Mockito.when(data.size()).thenReturn(REFS.size());
        return data;
    }

    private static LockerRow locker(String name, Double lat, Double lng,
                                    String status, Boolean l247, Boolean acc) {
        return new LockerRow(
                name, "PL", "parcel_locker", status,
                lat, lng, "city", "Małopolskie", "małopolskie",
                "30-001", "Street", "1",
                l247, acc, "parcel_collect", null,
                Instant.parse("2026-05-04T10:00:00Z")
        );
    }

    @Test
    void emptyInput_yieldsZeroMetricsForEachPowiat() {
        var agg = aggregatorAssigning(null);
        var result = agg.aggregate(List.of());

        assertThat(result.totalLockers()).isZero();
        assertThat(result.unmatchedLockers()).isZero();
        assertThat(result.metrics()).hasSize(2);
        assertThat(result.metrics()).allSatisfy(m -> {
            assertThat(m.lockerCount()).isZero();
            assertThat(m.densityPerKm2()).isZero();
        });
    }

    @Test
    void unassignedLockers_areCountedAsUnmatched() {
        var agg = aggregatorAssigning(null);
        var result = agg.aggregate(List.of(
                locker("A", 49.0, 19.0, "Operating", true, true),
                locker("B", 49.1, 19.1, "Operating", true, false)
        ));

        assertThat(result.totalLockers()).isEqualTo(2);
        assertThat(result.unmatchedLockers()).isEqualTo(2);
        assertThat(result.metrics()).allSatisfy(m -> assertThat(m.lockerCount()).isZero());
    }

    @Test
    void countsLockersWithCorrectDensity() {
        var agg = aggregatorAssigning(KRAKOW.key());
        var rows = List.of(
                locker("L1", 50.06, 19.93, "Operating", true, true),
                locker("L2", 50.07, 19.94, "Operating", true, false),
                locker("L3", 50.05, 19.95, "NonOperating", false, true)
        );
        var result = agg.aggregate(rows);

        var krk = result.metrics().stream()
                .filter(m -> m.key().equals(KRAKOW.key()))
                .findFirst().orElseThrow();
        assertThat(krk.lockerCount()).isEqualTo(3);
        assertThat(krk.operatingCount()).isEqualTo(2);
        assertThat(krk.count247()).isEqualTo(2);
        assertThat(krk.densityPerKm2()).isEqualTo(3.0 / 326.85);
        assertThat(krk.densityPer100kPeople()).isEqualTo(3 * 100_000.0 / 780981L);
    }

    @Test
    void rankingPlacesDensestPowiatFirst() {
        // 6 lockers in Kraków city (326 km²), 6 in krakowski (1230 km²) — Kraków denser /km².
        var agg = aggregatorAssigningPerLat();
        var rows = new java.util.ArrayList<LockerRow>();
        for (int i = 0; i < 6; i++) {
            rows.add(locker("city" + i, 50.06, 19.94, "Operating", true, true));
            rows.add(locker("rural" + i, 49.95, 19.95, "Operating", false, false));
        }

        var result = agg.aggregate(rows);
        var byKey = result.metrics().stream().collect(
                java.util.stream.Collectors.toMap(PowiatMetrics::key, m -> m));

        assertThat(byKey.get(KRAKOW.key()).rankByDensityPerKm2()).isEqualTo(1);
        assertThat(byKey.get(KRAKOWSKI.key()).rankByDensityPerKm2()).isEqualTo(2);
    }

    @Test
    void zeroPopulationOrArea_inReferenceData_yieldsZeroDensity() {
        PowiatReference ghost = new PowiatReference(
                "powiat ghost__nowhere", "Ghost", "powiat ghost", "nowhere", 0L, 0.0);
        PowiatPopulationData data = Mockito.mock(PowiatPopulationData.class);
        Mockito.when(data.all()).thenReturn((Collection<PowiatReference>) (Collection<?>) List.of(ghost));
        Mockito.when(data.size()).thenReturn(1);

        PowiatAssigner assigner = Mockito.mock(PowiatAssigner.class);
        Mockito.when(assigner.assign(Mockito.anyDouble(), Mockito.anyDouble()))
                .thenReturn(Optional.of(ghost.key()));

        var agg = new PowiatAggregator(assigner, data);
        var result = agg.aggregate(List.of(
                locker("X", 50.0, 19.0, "Operating", true, true)));

        var m = result.metrics().get(0);
        assertThat(m.lockerCount()).isEqualTo(1);
        assertThat(m.densityPerKm2()).isZero();
        assertThat(m.densityPer100kPeople()).isZero();
    }
}
