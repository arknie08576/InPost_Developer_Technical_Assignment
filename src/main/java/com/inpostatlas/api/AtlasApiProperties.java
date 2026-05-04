package com.inpostatlas.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.api")
public record AtlasApiProperties(
        String baseUrl,
        String pointsPath,
        int perPage,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int maxRetries,
        long retryBackoffMs
) {
}
