package com.inpostatlas.cli;

import com.inpostatlas.analysis.AggregationResult;
import com.inpostatlas.analysis.PowiatAggregationResult;
import com.inpostatlas.analysis.PowiatAggregator;
import com.inpostatlas.analysis.VoivodshipAggregator;
import com.inpostatlas.api.InPostClient;
import com.inpostatlas.reference.PopulationData;
import com.inpostatlas.report.CsvReportWriter;
import com.inpostatlas.report.HtmlReportWriter;
import com.inpostatlas.report.PowiatCsvReportWriter;
import com.inpostatlas.storage.LockerMapper;
import com.inpostatlas.storage.LockerRepository;
import com.inpostatlas.storage.LockerRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AtlasCommandRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AtlasCommandRunner.class);

    private final InPostClient client;
    private final LockerRepository repository;
    private final PopulationData populationData;
    private final VoivodshipAggregator voivodshipAggregator;
    private final PowiatAggregator powiatAggregator;
    private final CsvReportWriter csvWriter;
    private final PowiatCsvReportWriter powiatCsvWriter;
    private final HtmlReportWriter htmlWriter;
    private final String country;

    public AtlasCommandRunner(InPostClient client,
                              LockerRepository repository,
                              PopulationData populationData,
                              VoivodshipAggregator voivodshipAggregator,
                              PowiatAggregator powiatAggregator,
                              CsvReportWriter csvWriter,
                              PowiatCsvReportWriter powiatCsvWriter,
                              HtmlReportWriter htmlWriter,
                              @Value("${atlas.fetch.country}") String country) {
        this.client = client;
        this.repository = repository;
        this.populationData = populationData;
        this.voivodshipAggregator = voivodshipAggregator;
        this.powiatAggregator = powiatAggregator;
        this.csvWriter = csvWriter;
        this.powiatCsvWriter = powiatCsvWriter;
        this.htmlWriter = htmlWriter;
        this.country = country;
    }

    @Override
    public void run(String... args) throws Exception {
        Set<String> flags = new HashSet<>(Arrays.asList(args));
        if (args.length == 0 || flags.contains("--help") || flags.contains("-h")) {
            printHelp();
            return;
        }

        boolean all = flags.contains("--all");
        boolean fetch = all || flags.contains("--fetch");
        boolean analyze = all || flags.contains("--analyze") || flags.contains("--report");
        boolean writeCsv = all || flags.contains("--report") || flags.contains("--csv");
        boolean writeHtml = all || flags.contains("--report") || flags.contains("--html");

        if (fetch) {
            doFetch();
        }
        if (analyze || writeCsv || writeHtml) {
            List<LockerRow> rows = loadLockers();
            AggregationResult voivResult = analyzeVoivodships(rows);
            PowiatAggregationResult powiatResult = analyzePowiats(rows);

            if (writeCsv) {
                Path csv = csvWriter.write(voivResult);
                log.info("Voivodship CSV: {}", csv.toAbsolutePath());
                Path powiatCsv = powiatCsvWriter.write(powiatResult);
                log.info("Powiat CSV:     {}", powiatCsv.toAbsolutePath());
            }
            if (writeHtml) {
                Path html = htmlWriter.write(voivResult, powiatResult);
                log.info("HTML report:    {}", html.toAbsolutePath());
            }
        }
    }

    private List<LockerRow> loadLockers() {
        List<LockerRow> rows = repository.findByCountry(country);
        if (rows.isEmpty()) {
            log.error("No lockers in DB for country={}. Run with --fetch first.", country);
            throw new IllegalStateException("Empty database - run --fetch first");
        }
        return rows;
    }

    private AggregationResult analyzeVoivodships(List<LockerRow> rows) {
        log.info("Aggregating per voivodship...");
        AggregationResult result = voivodshipAggregator.aggregate(rows, populationData.all());
        log.info("Voivodships: {} buckets, {} lockers, {} unmatched",
                result.metrics().size(), result.totalLockers(), result.unmatchedLockers());
        return result;
    }

    private PowiatAggregationResult analyzePowiats(List<LockerRow> rows) {
        log.info("Aggregating per powiat (point-in-polygon over 380 polygons)...");
        long t0 = System.currentTimeMillis();
        PowiatAggregationResult result = powiatAggregator.aggregate(rows);
        long t1 = System.currentTimeMillis();
        log.info("Powiats: {} buckets, {} lockers, {} unmatched (took {} ms)",
                result.metrics().size(), result.totalLockers(), result.unmatchedLockers(), t1 - t0);
        return result;
    }

    private void doFetch() {
        log.info("Fetching country={} from InPost API...", country);
        Instant fetchedAt = Instant.now();
        int[] writtenRef = {0};
        int total = client.fetchByCountry(country, page -> {
            List<LockerRow> rows = page.stream()
                    .map(p -> LockerMapper.toRow(p, fetchedAt))
                    .toList();
            int n = repository.upsertBatch(rows);
            writtenRef[0] += n;
        });
        log.info("Fetch complete: {} items received, {} rows upserted, total in DB: {}",
                total, writtenRef[0], repository.countByCountry(country));
        warnOnUnknownProvinces();
    }

    private void warnOnUnknownProvinces() {
        Set<String> known = populationData.all().stream()
                .map(v -> v.normalizedName())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Long> seen = repository.distinctProvinceCountsRaw(country);
        long suspicious = 0;
        for (var entry : seen.entrySet()) {
            String raw = entry.getKey();
            String norm = raw == null ? null : com.inpostatlas.analysis.ProvinceNormalizer.normalize(raw);
            if (norm == null || !known.contains(norm)) {
                log.warn("Unknown province in DB: '{}' ({} lockers) - will not be aggregated", raw, entry.getValue());
                suspicious += entry.getValue();
            }
        }
        if (suspicious == 0) {
            log.info("All {} province values map cleanly to the 16 known voivodships", seen.size());
        }
    }

    private void printHelp() {
        System.out.println("""
                InPost Density Atlas - coverage analysis for the Polish parcel locker network.

                Usage: java -jar inpost-atlas.jar [flags]

                Flags:
                  --fetch       Pull all PL lockers from the public API into the local SQLite DB
                  --analyze     Aggregate metrics per voivodship and per powiat (requires data in DB)
                  --csv         Write CSV reports (voivodship + powiat)
                  --html        Write the standalone HTML choropleth (voivodship + powiat layers)
                  --report      Write both CSVs and HTML
                  --all         Equivalent to --fetch --analyze --report
                  --help, -h    Print this help

                Output goes to ${atlas.output.directory} (default ./output).
                Database lives at ${atlas.database.path} (default ./atlas.db).
                """);
    }
}
