package com.inpostatlas.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inpostatlas.analysis.AggregationResult;
import com.inpostatlas.analysis.PowiatAggregationResult;
import com.inpostatlas.analysis.PowiatMetrics;
import com.inpostatlas.analysis.VoivodshipMetrics;
import com.inpostatlas.reference.GeometryLoader;
import com.inpostatlas.reference.PowiatGeometryLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HtmlReportWriter {

    private static final String FILE_NAME = "voivodship_density.html";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'xxx");

    private final Path outputDir;
    private final GeometryLoader voivodshipGeometry;
    private final PowiatGeometryLoader powiatGeometry;
    private final ObjectMapper objectMapper;
    private final Resource template;

    public HtmlReportWriter(@Value("${atlas.output.directory}") String outputDir,
                            GeometryLoader voivodshipGeometry,
                            PowiatGeometryLoader powiatGeometry,
                            ObjectMapper objectMapper,
                            @Value("classpath:templates/report.html") Resource template) {
        this.outputDir = Paths.get(outputDir);
        this.voivodshipGeometry = voivodshipGeometry;
        this.powiatGeometry = powiatGeometry;
        this.objectMapper = objectMapper;
        this.template = template;
    }

    public Path write(AggregationResult voivodshipResult, PowiatAggregationResult powiatResult) throws IOException {
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(FILE_NAME);

        String tpl = StreamUtils.copyToString(template.getInputStream(), StandardCharsets.UTF_8);
        String summary = renderSummary(voivodshipResult, powiatResult);
        String generatedAt = ZonedDateTime.now(ZoneId.of("Europe/Warsaw")).format(TS);
        String voivData = objectMapper.writeValueAsString(toVoivodshipViewModel(voivodshipResult.metrics()));
        String powiatData = objectMapper.writeValueAsString(toPowiatViewModel(powiatResult.metrics()));

        String html = tpl
                .replace("{{GENERATED_AT}}", escape(generatedAt))
                .replace("{{SUMMARY}}", escape(summary))
                .replace("{{GEOJSON}}", voivodshipGeometry.rawGeoJson())
                .replace("{{DATA}}", voivData)
                .replace("{{POWIAT_GEOJSON}}", powiatGeometry.enrichedGeoJson())
                .replace("{{POWIAT_DATA}}", powiatData);

        Files.writeString(target, html, StandardCharsets.UTF_8);
        return target;
    }

    private static List<Map<String, Object>> toVoivodshipViewModel(List<VoivodshipMetrics> metrics) {
        return metrics.stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", m.key());
            view.put("displayName", m.displayName());
            view.put("population", m.population());
            view.put("areaKm2", m.areaKm2());
            view.put("lockerCount", m.lockerCount());
            view.put("operatingCount", m.operatingCount());
            view.put("count247", m.count247());
            view.put("countAccessible", m.countAccessible());
            view.put("densityPerKm2", round(m.densityPerKm2(), 4));
            view.put("densityPer100kPeople", round(m.densityPer100kPeople(), 2));
            view.put("pct247", round(m.pct247(), 2));
            view.put("pctAccessible", round(m.pctAccessible(), 2));
            view.put("rankByDensityPerKm2", m.rankByDensityPerKm2());
            view.put("rankByDensityPer100kPeople", m.rankByDensityPer100kPeople());
            return view;
        }).toList();
    }

    private static List<Map<String, Object>> toPowiatViewModel(List<PowiatMetrics> metrics) {
        return metrics.stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", m.key());
            view.put("displayName", m.displayName());
            view.put("voivodship", m.voivodship());
            view.put("population", m.population());
            view.put("areaKm2", m.areaKm2());
            view.put("lockerCount", m.lockerCount());
            view.put("operatingCount", m.operatingCount());
            view.put("count247", m.count247());
            view.put("countAccessible", m.countAccessible());
            view.put("densityPerKm2", round(m.densityPerKm2(), 4));
            view.put("densityPer100kPeople", round(m.densityPer100kPeople(), 2));
            view.put("pct247", round(m.pct247(), 2));
            view.put("pctAccessible", round(m.pctAccessible(), 2));
            view.put("rankByDensityPerKm2", m.rankByDensityPerKm2());
            view.put("rankByDensityPer100kPeople", m.rankByDensityPer100kPeople());
            return view;
        }).toList();
    }

    private static double round(double v, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(v * scale) / scale;
    }

    private static String renderSummary(AggregationResult voiv, PowiatAggregationResult pow) {
        StringBuilder sb = new StringBuilder();
        sb.append("Łącznie paczkomatów: ").append(voiv.totalLockers());
        sb.append(" • woj.: ").append(voiv.totalLockers() - voiv.unmatchedLockers())
                .append(" przypisanych");
        if (voiv.unmatchedLockers() > 0) {
            sb.append(" / ").append(voiv.unmatchedLockers()).append(" nieprzypisanych");
        }
        sb.append(" • powiaty: ").append(pow.totalLockers() - pow.unmatchedLockers())
                .append(" przypisanych");
        if (pow.unmatchedLockers() > 0) {
            sb.append(" / ").append(pow.unmatchedLockers()).append(" poza polskimi powiatami");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
