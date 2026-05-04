# InPost Density Atlas — Polska

> **InPost Developer Technical Assignment.** Coverage density analysis of the
> InPost parcel locker network in Poland — pulled from the public points API,
> joined with GUS reference data, and rendered as CSV + a standalone Leaflet
> choropleth map per voivodship.

---

## What it does

1. **Fetches** every `country=PL` point from `https://api-global-points.easypack24.net/v1/points`,
   page by page (paginated, retried with exponential backoff on 5xx / network errors),
   into a local SQLite file.
2. **Joins** the lockers with reference data shipped in `src/main/resources/reference/`:
   - `voivodships.csv` — population and area per voivodship (GUS, 31.12.2023)
   - `voivodships.geojson` — administrative boundaries of the 16 voivodships
3. **Aggregates** per-voivodship metrics: locker count, density per km², density
   per 100k inhabitants, share of 24/7 lockers, share of accessible lockers,
   and rankings.
4. **Reports** the result in two formats:
   - `output/voivodship_density.csv` — flat table for downstream tooling
   - `output/voivodship_density.html` — single self-contained HTML file with a
     Leaflet choropleth, sortable table, and tooltip drill-down. Open in a
     browser, no server required.

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

The narrow scope (Poland only, voivodship granularity, 16 buckets) is
deliberate. Depth over breadth on a 3-day deadline.

---

## Architecture

```
       InPost API                  GUS CSV / GeoJSON
            │                              │
            ▼                              │
      InPostClient                         │
       (paginated,                         │
        retry+backoff)                     │
            │                              │
            ▼                              │
       SQLite (lockers)                    │
            │                              │
            └─────────────► VoivodshipAggregator ◄─┘
                                    │
                            AggregationResult
                                    │
                          ┌─────────┴─────────┐
                          ▼                   ▼
                   CsvReportWriter     HtmlReportWriter
                          │                   │
                          ▼                   ▼
                output/*.csv           output/*.html
                                  (Leaflet choropleth)
```

Package layout (`com.inpostatlas.*`):

| Package | Responsibility |
|---|---|
| `api` | HTTP client + DTOs + retry policy |
| `storage` | SQLite repository, batch upsert, mapping |
| `reference` | GUS population + GeoJSON loaders |
| `analysis` | Voivodship aggregator + province normalizer |
| `report` | CSV + HTML writers |
| `cli` | `CommandLineRunner`-based entry point |

---

## Decisions log

| Decision | Choice | Reason |
|---|---|---|
| Geography | Poland only | GUS data trivially available; depth > breadth on a 3-day deadline. |
| Granularity | 16 voivodships | The API returns `address_details.province` per locker, so no point-in-polygon work is needed. Normalization handles inconsistent casing. |
| Stack | Spring Boot 3.5 on Java 21 | Java records for DTOs, sensible auto-config for `RestClient` + `JdbcTemplate`. No web starter — this is a CLI/batch app, not a service. |
| Persistence | SQLite (xerial JDBC) | Zero-infra; one file; easy to inspect from any SQLite client; ideal for demo. |
| HTTP client | Spring `RestClient` | Modern blocking API in Spring Web 6.x; fits the synchronous, paginated workload better than `WebClient`. |
| Retry policy | Hand-rolled exponential backoff (max 4 retries) | Did not pull in Resilience4j just to retry one method on two exception types. |
| Map rendering | Standalone HTML + Leaflet via CDN | One file the reviewer can double-click. Renders without a server. The GeoJSON and metrics are embedded — no fetch on load. |
| Testing | JUnit 5 + AssertJ for unit; WireMock for the HTTP integration | Pagination, retry, and 4xx-vs-5xx behavior is the part most likely to break — that's where the integration test focuses. |

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
- **Powiat-level granularity (380 buckets)** — would require point-in-polygon
  joins against powiat polygons. A worthwhile stretch goal, dropped for time.
- **Resilience4j + WebClient + a reactive pipeline** — overengineered for one
  endpoint with predictable pagination.

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
- **Top-line results from the bundled run**: Śląskie leads on density per
  km² (0.32, smallest area + dense urban core); Wielkopolskie leads on density
  per 100k inhabitants (102.65); Podlaskie is bottom on both. Lubuskie ranks
  surprisingly well per capita (#2) thanks to its small population.
- **CRL revocation check on Windows** — `curl` in Git Bash on Windows can
  fail with `CRYPT_E_NO_REVOCATION_CHECK` when validating certs. Workaround:
  `curl --ssl-no-revoke ...`. PowerShell's `Invoke-RestMethod` doesn't have
  the issue. (Not a runtime concern — just useful when poking the API by hand.)

---

## Future work

- Powiat-level analysis (point-in-polygon against powiat boundaries).
- Population-weighted accessibility (% of population within X km of a
  24/7 + accessible locker), which would surface coverage gaps better than
  raw density.
- Time-series: snapshot the DB on a cron, track network growth per
  voivodship over time.
- Bigger Europe view if reliable per-country reference data can be sourced.

---

## Data sources & attribution

- Locker positions: `https://api-global-points.easypack24.net/v1/points`
  (InPost public API, no authentication required).
- Population & area per voivodship: GUS (Główny Urząd Statystyczny),
  state on 31.12.2023.
- Voivodship boundaries: `github.com/ppatrzyk/polska-geojson`
  (`wojewodztwa-min.geojson`), embedded in `src/main/resources/reference/`.
- Map tiles: OpenStreetMap contributors.
- Map library: Leaflet 1.9.4, served from `unpkg.com` CDN.
