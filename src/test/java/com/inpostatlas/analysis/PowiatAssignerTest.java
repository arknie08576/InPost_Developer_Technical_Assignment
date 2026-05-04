package com.inpostatlas.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inpostatlas.reference.PowiatGeometryLoader;
import com.inpostatlas.reference.PowiatPopulationData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PowiatAssignerTest {

    private static PowiatAssigner assigner;

    @BeforeAll
    static void loadOnce() {
        PowiatPopulationData population = new PowiatPopulationData(
                new ClassPathResource("reference/powiats.csv"));
        PowiatGeometryLoader loader = new PowiatGeometryLoader(
                new ClassPathResource("reference/powiats.geojson"),
                new ClassPathResource("reference/voivodships.geojson"),
                population,
                new ObjectMapper());
        assigner = new PowiatAssigner(loader);
    }

    @Test
    void warszawaCenter_isAssignedToPowiatWarszawa() {
        // Plac Defilad / PKiN
        Optional<String> key = assigner.assign(52.2297, 21.0122);
        assertThat(key).contains("powiat Warszawa__mazowieckie");
    }

    @Test
    void krakowCenter_isAssignedToPowiatKrakow() {
        // Rynek Główny
        Optional<String> key = assigner.assign(50.0614, 19.9372);
        assertThat(key).contains("powiat Kraków__małopolskie");
    }

    @Test
    void sopot_isAssignedToCityPowiat_notSurroundingZiemski() {
        // Sopot pier — should be the city powiat, not powiat gdański.
        Optional<String> key = assigner.assign(54.4474, 18.5697);
        assertThat(key).contains("powiat Sopot__pomorskie");
    }

    @Test
    void rurarVillage_isAssignedToZiemskiPowiat() {
        // Roughly the centre of powiat białostocki, outside Białystok city limits.
        // Wasilków, ~10 km north of Białystok center.
        Optional<String> key = assigner.assign(53.2050, 23.2050);
        assertThat(key).isPresent();
        assertThat(key.get()).contains("__podlaskie");
        // It must be the ziemski (rural) powiat, not the city.
        assertThat(key.get()).doesNotContain("Białystok__");
    }

    @Test
    void pointInBalticSea_returnsEmpty() {
        // ~50 km north of the coast, well into the sea.
        Optional<String> key = assigner.assign(55.5, 18.5);
        assertThat(key).isEmpty();
    }

    @Test
    void nullCoordinates_returnEmpty() {
        assertThat(assigner.assign(null, 21.0)).isEmpty();
        assertThat(assigner.assign(52.0, null)).isEmpty();
        assertThat(assigner.assign(null, null)).isEmpty();
    }

    @Test
    void nanCoordinates_returnEmpty() {
        assertThat(assigner.assign(Double.NaN, 21.0)).isEmpty();
        assertThat(assigner.assign(52.0, Double.NaN)).isEmpty();
    }
}
