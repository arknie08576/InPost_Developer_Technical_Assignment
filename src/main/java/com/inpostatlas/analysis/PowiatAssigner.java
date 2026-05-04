package com.inpostatlas.analysis;

import com.inpostatlas.reference.PowiatFeature;
import com.inpostatlas.reference.PowiatGeometryLoader;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Maps a (latitude, longitude) point to its containing powiat key using a
 * JTS STR-tree spatial index. Build phase happens once at construction time
 * (~380 polygons), query phase is cheap enough to call per locker (~33k calls
 * for the Polish dataset, total runtime under three seconds).
 *
 * Coordinate convention: JTS expects (x, y) = (longitude, latitude).
 * Callers pass (lat, lng) for readability; we swap them internally. Mixing
 * these up is the single most common JTS bug — all assertion tests pin a few
 * known points to catch a regression.
 */
@Component
public class PowiatAssigner {

    private static final Logger log = LoggerFactory.getLogger(PowiatAssigner.class);

    private final GeometryFactory gf = new GeometryFactory();
    private final STRtree index = new STRtree();

    public PowiatAssigner(PowiatGeometryLoader loader) {
        List<PowiatFeature> features = loader.features();
        for (PowiatFeature f : features) {
            // Stash the join key on the geometry so a query can recover it without a side map.
            f.geometry().setUserData(f.key());
            index.insert(f.geometry().getEnvelopeInternal(), f.geometry());
        }
        index.build();
        log.info("PowiatAssigner indexed {} polygons", features.size());
    }

    public Optional<String> assign(Double lat, Double lng) {
        if (lat == null || lng == null || Double.isNaN(lat) || Double.isNaN(lng)) {
            return Optional.empty();
        }
        Point p = gf.createPoint(new Coordinate(lng, lat));
        @SuppressWarnings("unchecked")
        List<Geometry> candidates = index.query(p.getEnvelopeInternal());
        for (Geometry g : candidates) {
            if (g.contains(p)) {
                return Optional.of((String) g.getUserData());
            }
        }
        return Optional.empty();
    }
}
