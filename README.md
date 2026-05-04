# InPost Density Atlas — Polska

> **InPost Developer Technical Assignment.** Coverage density analysis of the
> InPost parcel locker network in Poland — pulled from the public points API,
> joined with GUS reference data, and rendered as CSVs + a standalone Leaflet
> choropleth that toggles between two granularities: 16 voivodships and
> 380 powiats.

---

## What it does

1. **Fetches** every `country=PL` point from `https://api-global-points.easypack24.net/v1/points`,
   page by page (paginated, retried with exponential backoff on 5xx / network errors),
   into a local SQLite file.
2. **Joins** the lockers with reference data shipped in `src/main/resources/reference/`:
   - `voivodships.csv` + `voivodships.geojson` — population, area, and
     administrative boundaries of the 16 voivodships (GUS, 31.12.2023)
   - `powiats.csv` + `powiats.geojson` — population, area, and boundaries
     of the 380 powiats (Wikipedia / GUS, 30.06.2020)
3. **Aggregates** at two granularities:
   - **Voivodship**: groups lockers by `address_details.province` (string match
     after normalization).
   - **Powiat**: groups lockers by point-in-polygon against the 380 powiat
     polygons, using a JTS STR-tree spatial index. Computed on the fly during
     `--analyze`; the `lockers` table stays unchanged.
   - Both layers compute: locker count, density per km², density per 100k
     inhabitants, share of 24/7 lockers, share of accessible lockers,
     and dual rankings.
4. **Reports** in three artefacts:
   - `output/voivodship_density.csv` — 16-row flat table
   - `output/powiat_density.csv` — 380-row flat table
   - `output/voivodship_density.html` — single self-contained HTML with a
     Leaflet choropleth, **toggleable between the voivodship and powiat
     layers**, sortable table, and tooltip drill-down. Open in a browser,
     no server required.

---

## Quick start

Requires JDK 21+. The Maven Wrapper is included; no system Maven needed.

```bash
# 1. Build + run tests
./mvnw clean package

# 2. Print help
java -jar target/inpost-atlas-0.1.0-SNAPSHOT.jar --help

# 3. Full pipeline: fetch → analyze → write CSV + HTML
java -jar target/inpost-atlas-0.1.0-SNAPSHOT.jar --all

# 4. Or run stages separately
java -jar target/inpost-atlas-0.1.0-SNAPSHOT.jar --fetch          # ~1–2 min, ~33k lockers
java -jar target/inpost-atlas-0.1.0-SNAPSHOT.jar --analyze --report
```

Outputs land in `./output/`. The local DB lives at `./atlas.db` (gitignored).
Re-running `--fetch` upserts on `name`, so subsequent runs are idempotent.

Open `output/voivodship_density.html` in any modern browser. The HTML file
embeds the GeoJSON and the metrics; the only external dependency is Leaflet
served from `unpkg.com` via CDN.

---

## Why this scope

The brief leaves the choice of angle to the candidate. The obvious play is a
smart finder over the API — pin every locker on a map, add filters, ship it.
That's what most candidates will build. Density Atlas takes a different angle
on purpose: an analytical pipeline that surfaces **where coverage concentrates
and where it doesn't**, which is the question a logistics operator actually
asks about its own network.

The narrow scope (Poland only) is deliberate. Within that scope the project
goes deeper than a single granularity: the voivodship layer answers
"which region is best covered" in 16 buckets, the powiat layer goes a level
finer to surface where the metropolitan halos and the rural gaps actually
sit. Depth over breadth on a 3-day deadline.

---

## Architecture

```
        InPost API              CSV + GeoJSON reference
            │                     (woj. + powiat)
            ▼                              │
      InPostClient                         │
       (paginated,                         │
        retry+backoff)                     │
            │                              │
            ▼                              │
       SQLite (lockers)                    │
            │                              │
            ├─────► VoivodshipAggregator ◄─┤
            │                              │
            └─────► PowiatAggregator     ◄─┘
                          ▲
                          │ uses
                          │
                    PowiatAssigner (JTS STR-tree, point-in-polygon)
                          │
            ┌─────────────┴───────────────┐
            ▼                             ▼
       CsvReportWriter            HtmlReportWriter
       PowiatCsvReportWriter      (single file, two togglable layers)
            │                             │
            ▼                             ▼
   output/*_density.csv      output/voivodship_density.html
```

Package layout (`com.inpostatlas.*`):

| Package | Responsibility |
|---|---|
| `api` | HTTP client + DTOs + retry policy |
| `storage` | SQLite repository, batch upsert, mapping |
| `reference` | GUS / Wikipedia loaders + JTS GeoJSON parser for both layers |
| `analysis` | Voivodship aggregator, powiat aggregator, JTS-based `PowiatAssigner`, province normalizer |
| `report` | CSV writers (woj. + powiat) and the HTML choropleth writer |
| `cli` | `CommandLineRunner`-based entry point |

---

## Decisions log

| Decision | Choice | Reason |
|---|---|---|
| Geography | Poland only | GUS data trivially available; depth > breadth on a 3-day deadline. |
| Granularity (voivodship) | 16 voivodships | The API returns `address_details.province` per locker, so no point-in-polygon work is needed. Normalization handles inconsistent casing. |
| Granularity (powiat) | 380 powiats via point-in-polygon | The API has no powiat field, so we compute it. Done on the fly during `--analyze` rather than denormalised into the `lockers` table. |
| Powiat assignment | JTS STR-tree + `Geometry.contains` | Build phase loads 380 polygons once; query phase is ~1 ms per locker (full 33k dataset assigned in ~950 ms). Lighter than a database extension or a service-side spatial join. |
| Powiat join key | `"<geojsonNazwa>__<voivodship>"` | Two GeoJSON features share `nazwa`: `średzki` and `świdnicki` each appear in two voivodships. Disambiguate by combining name with the parent voivodship, computed by point-in-polygon of the centroid. |
| Stack | Spring Boot 3.5 on Java 21 | Java records for DTOs, sensible auto-config for `RestClient` + `JdbcTemplate`. No web starter — this is a CLI/batch app, not a service. |
| Persistence | SQLite (xerial JDBC) | Zero-infra; one file; easy to inspect from any SQLite client; ideal for demo. No schema migrations needed when adding the powiat layer. |
| HTTP client | Spring `RestClient` | Modern blocking API in Spring Web 6.x; fits the synchronous, paginated workload better than `WebClient`. |
| Retry policy | Hand-rolled exponential backoff (max 4 retries) | Did not pull in Resilience4j just to retry one method on two exception types. |
| Map rendering | Standalone HTML + Leaflet via CDN | One file the reviewer can double-click. Renders without a server. Both GeoJSON layers and both metrics arrays are embedded — no fetch on load. |
| Testing | JUnit 5 + AssertJ + Mockito for unit; WireMock for the HTTP integration | Pagination, retry, the JTS coordinate-order trap, and reference-data joins are the parts most likely to break — that's where the tests focus. |

---

## Rejected paths (and why)

- **Live availability tracker** — `locker_availability` always returns
  `NO_DATA` in the public API. Sampled 500 Warsaw lockers; every single one
  came back `NO_DATA`. The field exists in the schema but isn't populated
  publicly. Not a real signal.
- **Smart finder using `recommended_low_interest_box_machines_list`** — this
  was the alternative pitch (a finder that suggests less-busy nearby lockers).
  Genuinely interesting and probably what most candidates will build, which is
  exactly why I picked the analysis route.
- **Cross-country comparison (PL vs DE vs FR vs …)** — too wide for the
  deadline, and the per-country reference data quality would vary.
- **Gmina-level granularity (~2,500 buckets)** — at that scale individual
  locker placements become statistical noise (one new locker shifts a small
  gmina from #2,000 to #800), and the reference-data effort to map gmina
  names cleanly is a project of its own. Powiat is the last meaningful
  granularity for a coverage analysis.
- **Resilience4j + WebClient + a reactive pipeline** — overengineered for one
  endpoint with predictable pagination.
- **Storing the powiat key in the `lockers` table** — would require a
  schema migration step and re-fetching the whole dataset on every reference
  data update. Computing on-the-fly during `--analyze` is ~1 second for the
  full PL dataset, which is well below the bar where a denormalisation pays
  for itself.

---

## Findings worth noting

- **Total points returned by the API: ~153k** (FR, GB, DE, ES, IT, PT, BE, NL,
  CZ, SK, HU, AT, RO, …). The assignment brief says 90k+ — the network has
  grown.
- **Poland: 33,961 points** as of the run that produced the bundled CSV.
  Pagination at `per_page=500` means 68 pages, ~3 min end-to-end.
- **The API response shape is `{count, page, per_page, total_pages, items, …}`**
  — no `_links.next` HAL block, contrary to what some older docs suggest.
  `InPostClient` paginates on `total_pages` and falls back to `_links.next.href`
  if the schema changes again.
- **The public dataset contains ~664 test rows** (`province` = `"TEST"` /
  `"test"`) plus a handful of one-off oddities (`"Planowany"`, `"x"`, `"xx"`,
  `"teste"`, and one row with `"malopolskie"` typed without the diacritic).
  These are flagged in the run log as `Unknown province` and excluded from the
  aggregation. They surfaced because the analysis JOINs on a closed set of 16
  voivodship keys — a less strict pipeline would have silently absorbed them.
- **`address_details.province`** is provided as a free-form string — sometimes
  capitalized, sometimes not. `ProvinceNormalizer` lowercases + trims. Polish
  diacritics are preserved (the GUS / GeoJSON dataset uses them too, so the
  join key is stable).
- **`locker_availability`** is dead in the public feed. Don't build on it.
- **Voivodship-level top-line results**: Śląskie leads on density per
  km² (0.32, smallest area + dense urban core); Wielkopolskie leads on density
  per 100k inhabitants (102.65); Podlaskie is bottom on both. Lubuskie ranks
  surprisingly well per capita (#2) thanks to its small population.
- **Powiat-level top-line results**:
  - **Density per km²** is dominated entirely by *miasta na prawach powiatu*
    (cities counted as their own powiat). Top 5: Warszawa (3.44/km²),
    Białystok (2.60), Kraków (2.49), Chorzów (2.41), Poznań (2.33).
  - **Density per 100k people** flips the picture — the densest 10 are all
    *suburban / commuter-belt powiats* around major metros, and they cluster
    in three voivodships:
    *wolsztyński* and *nowotomyski* (133.8 / 133.5 — both Wielkopolskie),
    *poznański* (131.4), *leszczyński* (128.6), *wrocławski* (127.4),
    *gdański* (122.3), *wielicki* (120.8), *pucki* (120.4),
    *trzebnicki* (115.9), *krakowski* (115.6).
  - **Bottom 5 per km²** are eastern-border powiats:
    *bieszczadzki* (0.012), *moniecki* (0.014), *suwalski* (0.014),
    *sejneński* (0.015), *sokólski* (0.018) — all sub-1 locker per 50 km².
  - **Powiat assignment runtime**: 33,955 lockers indexed against 380 polygons
    in ~950 ms on a laptop. JTS R-tree pruning makes most queries one-pass.
- **CRL revocation check on Windows** — `curl` in Git Bash on Windows can
  fail with `CRYPT_E_NO_REVOCATION_CHECK` when validating certs. Workaround:
  `curl --ssl-no-revoke ...`. PowerShell's `Invoke-RestMethod` doesn't have
  the issue. (Not a runtime concern — just useful when poking the API by hand.)

---

## Future work

- Population-weighted accessibility (% of population within X km of a
  24/7 + accessible locker), which would surface coverage gaps better than
  raw density.
- Time-series: snapshot the DB on a cron, track network growth per
  voivodship and per powiat over time.
- Refresh the powiat reference dataset: the Wikipedia table used here is from
  30.06.2020. Ranks shift only marginally with more recent data, but the
  populations in the top-10 per-100k commuter belts have grown notably since.
- Bigger Europe view if reliable per-country reference data can be sourced.

---

## Data sources & attribution

- Locker positions: `https://api-global-points.easypack24.net/v1/points`
  (InPost public API, no authentication required).
- Population & area per voivodship: GUS (Główny Urząd Statystyczny),
  state on 31.12.2023.
- Population & area per powiat: Polish Wikipedia article *Lista powiatów w Polsce*,
  state on 30.06.2020 (sourced from GUS BDL but more convenient to scrape).
  The CSV is committed; you can regenerate it by running
  `python scripts/build_powiats_csv.py` from the repo root — the script
  fetches Wikipedia, parses the table, applies the post-2021 name aliases,
  and cross-validates against the GeoJSON before writing.
- Voivodship boundaries: `github.com/ppatrzyk/polska-geojson`
  (`wojewodztwa-min.geojson`), embedded in `src/main/resources/reference/`.
- Powiat boundaries: same repository, `powiaty-min.geojson` (380 features,
  one per powiat). The dataset still uses the pre-2021 name *"powiat
  jeleniogórski"* — `build_powiats_csv.py` aliases it to the current
  *"powiat karkonoski"* during CSV construction.
- Map tiles: OpenStreetMap contributors.
- Map library: Leaflet 1.9.4, served from `unpkg.com` CDN.
- JTS Topology Suite 1.20.0 for the spatial index and point-in-polygon test.
