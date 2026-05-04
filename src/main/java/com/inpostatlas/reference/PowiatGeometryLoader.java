package com.inpostatlas.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/**
 * Loads {@code powiats.geojson} (380 features), parses Polygon and MultiPolygon
 * geometries with JTS, and joins each feature with its CSV {@link PowiatReference}.
 *
 * The join is non-trivial: powiat names from the GeoJSON are not unique
 * (the same name can occur in two voivodships). To disambiguate we compute
 * each powiat's centroid and look up which voivodship polygon contains it,
 * using the already-loaded {@code voivodships.geojson}.
 *
 * Coordinate convention: JTS uses (x, y) = (longitude, latitude). The GeoJSON
 * spec stores coordinates as {@code [longitude, latitude]}, so they are passed
 * through unchanged.
 */
@Component
public class PowiatGeometryLoader {

    private static final Logger log = LoggerFactory.getLogger(PowiatGeometryLoader.class);

    private final List<PowiatFeature> features;
    private final String enrichedGeoJson;

    public PowiatGeometryLoader(@Value("classpath:reference/powiats.geojson") Resource powiatsRes,
                                @Value("classpath:reference/voivodships.geojson") Resource voivodshipsRes,
                                PowiatPopulationData populationData,
                                ObjectMapper objectMapper) {
        try {
            String raw = StreamUtils.copyToString(powiatsRes.getInputStream(), StandardCharsets.UTF_8);
            JsonNode powiatsRoot = objectMapper.readTree(raw);
            JsonNode voivRoot = objectMapper.readTree(
                    StreamUtils.copyToString(voivodshipsRes.getInputStream(), StandardCharsets.UTF_8));
            this.features = build(powiatsRoot, voivRoot, populationData);
            this.enrichedGeoJson = stampAtlasKeys(powiatsRoot, features, objectMapper);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load powiat geometries", e);
        }
    }

    public List<PowiatFeature> features() {
        return features;
    }

    /**
     * GeoJSON with an extra {@code properties.atlas_key} on every feature, so the
     * front-end can join feature → metric without re-implementing voivodship
     * disambiguation in JavaScript.
     */
    public String enrichedGeoJson() {
        return enrichedGeoJson;
    }

    private static String stampAtlasKeys(JsonNode powiatsRoot, List<PowiatFeature> features,
                                         ObjectMapper objectMapper) throws IOException {
        // The build() loop iterates GeoJSON features in order, so features.get(i) lines up
        // with powiatsRoot.features[i]. We deep-copy to avoid mutating the loader-internal tree.
        JsonNode copy = objectMapper.readTree(objectMapper.writeValueAsString(powiatsRoot));
        JsonNode arr = copy.path("features");
        for (int i = 0; i < features.size(); i++) {
            ObjectNode props = (ObjectNode) arr.get(i).path("properties");
            props.put("atlas_key", features.get(i).key());
        }
        return objectMapper.writeValueAsString(copy);
    }

    private static List<PowiatFeature> build(JsonNode powiatsRoot, JsonNode voivRoot,
                                             PowiatPopulationData populationData) {
        GeometryFactory gf = new GeometryFactory();

        List<VoivodshipPolygon> voiv = new ArrayList<>();
        for (JsonNode f : voivRoot.path("features")) {
            String nazwa = f.path("properties").path("nazwa").asText();
            Geometry g = parseGeometry(f.path("geometry"), gf);
            voiv.add(new VoivodshipPolygon(nazwa.toLowerCase(Locale.ROOT), g));
        }

        Map<String, PowiatReference> byKey = new HashMap<>();
        for (PowiatReference r : populationData.all()) {
            byKey.put(r.key(), r);
        }

        List<PowiatFeature> out = new ArrayList<>();
        int unmatched = 0;
        for (JsonNode f : powiatsRoot.path("features")) {
            String nazwa = f.path("properties").path("nazwa").asText();
            Geometry g = parseGeometry(f.path("geometry"), gf);
            Point centroid = g.getCentroid();
            String voivName = findContainingVoivodship(voiv, centroid);
            if (voivName == null) {
                log.warn("Powiat {} centroid did not fall in any voivodship polygon — skipping", nazwa);
                unmatched++;
                continue;
            }
            String key = nazwa + "__" + voivName;
            PowiatReference ref = byKey.get(key);
            if (ref == null) {
                log.warn("Powiat key {} has no row in powiats.csv — skipping", key);
                unmatched++;
                continue;
            }
            out.add(new PowiatFeature(key, ref.displayName(), voivName, g));
        }

        if (unmatched > 0) {
            throw new IllegalStateException(
                    "Powiat geometry/CSV mismatch: %d feature(s) could not be paired".formatted(unmatched));
        }
        if (out.size() != populationData.size()) {
            throw new IllegalStateException(
                    "Loaded %d powiat geometries but CSV declares %d rows".formatted(out.size(), populationData.size()));
        }
        log.info("Loaded {} powiat polygons cross-validated against CSV", out.size());
        return out;
    }

    private static String findContainingVoivodship(List<VoivodshipPolygon> voiv, Point centroid) {
        for (VoivodshipPolygon v : voiv) {
            if (v.geometry().contains(centroid)) {
                return v.name();
            }
        }
        // Fallback: nearest polygon by distance (handles rounding errors near boundaries).
        VoivodshipPolygon closest = null;
        double bestDist = Double.MAX_VALUE;
        for (VoivodshipPolygon v : voiv) {
            double d = v.geometry().distance(centroid);
            if (d < bestDist) {
                bestDist = d;
                closest = v;
            }
        }
        return closest == null ? null : closest.name();
    }

    static Geometry parseGeometry(JsonNode geom, GeometryFactory gf) {
        String type = geom.path("type").asText();
        JsonNode coords = geom.path("coordinates");
        return switch (type) {
            case "Polygon" -> parsePolygon(coords, gf);
            case "MultiPolygon" -> {
                Polygon[] polys = new Polygon[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    polys[i] = parsePolygon(coords.get(i), gf);
                }
                yield gf.createMultiPolygon(polys);
            }
            default -> throw new IllegalStateException("Unsupported geometry type: " + type);
        };
    }

    private static Polygon parsePolygon(JsonNode rings, GeometryFactory gf) {
        LinearRing outer = parseRing(rings.get(0), gf);
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = parseRing(rings.get(i), gf);
        }
        return gf.createPolygon(outer, holes);
    }

    private static LinearRing parseRing(JsonNode ring, GeometryFactory gf) {
        Coordinate[] coords = new Coordinate[ring.size()];
        Iterator<JsonNode> it = ring.elements();
        int i = 0;
        while (it.hasNext()) {
            JsonNode pt = it.next();
            double lng = pt.get(0).asDouble();
            double lat = pt.get(1).asDouble();
            coords[i++] = new Coordinate(lng, lat);
        }
        return gf.createLinearRing(coords);
    }

    private record VoivodshipPolygon(String name, Geometry geometry) {
    }
}
