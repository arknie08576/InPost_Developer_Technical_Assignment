package com.inpostatlas.analysis;

import java.util.List;
import java.util.Map;

public record AggregationResult(
        List<VoivodshipMetrics> metrics,
        long totalLockers,
        long unmatchedLockers,
        Map<String, Long> unmatchedByRawProvince
) {
}
