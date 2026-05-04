package com.inpostatlas.report;

import com.inpostatlas.analysis.AggregationResult;
import com.inpostatlas.analysis.VoivodshipMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@Component
public class CsvReportWriter {

    private static final String FILE_NAME = "voivodship_density.csv";
    private static final String HEADER = "normalized_name,display_name,population,area_km2," +
            "locker_count,operating_count,count_247,count_accessible," +
            "density_per_km2,density_per_100k_people,pct_247,pct_accessible," +
            "rank_density_km2,rank_density_100k";

    private final Path outputDir;

    public CsvReportWriter(@Value("${atlas.output.directory}") String outputDir) {
        this.outputDir = Paths.get(outputDir);
    }

    public Path write(AggregationResult result) throws IOException {
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(FILE_NAME);
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write(HEADER);
            w.newLine();
            for (VoivodshipMetrics m : result.metrics()) {
                w.write(formatRow(m));
                w.newLine();
            }
        }
        return target;
    }

    private static String formatRow(VoivodshipMetrics m) {
        return String.join(",",
                m.key(),
                m.displayName(),
                Long.toString(m.population()),
                String.format(Locale.ROOT, "%.0f", m.areaKm2()),
                Long.toString(m.lockerCount()),
                Long.toString(m.operatingCount()),
                Long.toString(m.count247()),
                Long.toString(m.countAccessible()),
                String.format(Locale.ROOT, "%.4f", m.densityPerKm2()),
                String.format(Locale.ROOT, "%.2f", m.densityPer100kPeople()),
                String.format(Locale.ROOT, "%.2f", m.pct247()),
                String.format(Locale.ROOT, "%.2f", m.pctAccessible()),
                Integer.toString(m.rankByDensityPerKm2()),
                Integer.toString(m.rankByDensityPer100kPeople()));
    }
}
