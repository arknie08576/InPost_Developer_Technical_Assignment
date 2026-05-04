package com.inpostatlas.analysis;

import java.util.List;

public record PowiatAggregationResult(
        List<PowiatMetrics> metrics,
        long totalLockers,
        long unmatchedLockers
) {
}
