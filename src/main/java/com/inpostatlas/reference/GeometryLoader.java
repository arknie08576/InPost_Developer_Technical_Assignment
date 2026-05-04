package com.inpostatlas.reference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class GeometryLoader {

    private final String rawGeoJson;

    public GeometryLoader(@Value("classpath:reference/voivodships.geojson") Resource res) {
        try {
            this.rawGeoJson = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load voivodships.geojson", e);
        }
    }

    public String rawGeoJson() {
        return rawGeoJson;
    }
}
