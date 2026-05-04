#!/usr/bin/env python3
"""
Builds src/main/resources/reference/powiats.csv from:
  - the Polish Wikipedia article "Lista powiatów w Polsce" (population, area,
    voivodship), and
  - src/main/resources/reference/powiats.geojson (cross-validation).

Run from the repository root:
    python scripts/build_powiats_csv.py

The produced CSV has the columns:
    key, display_name, geojson_nazwa, voivodship, population, area_km2

The `key` column is the join key used by Java side
(PowiatPopulationData / PowiatAssigner) and is composed as
"<geojsonNazwa>__<voivodship>". The voivodship suffix is required because two
GeoJSON `nazwa` values are duplicated across voivodships (powiat średzki,
powiat świdnicki).

This script is run-once-and-commit. The CSV in resources is the source of
truth — the script just keeps the provenance reproducible.
"""

import csv
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

WIKI_URL = (
    "https://pl.wikipedia.org/wiki/"
    "Lista_powiat%C3%B3w_w_Polsce"
)

# Wikipedia uses post-2021 names; the bundled powiats.geojson predates the
# rename of "powiat jeleniogórski" → "powiat karkonoski".
GEOJSON_ALIASES = {
    "powiat karkonoski": "powiat jeleniogórski",
}


def fetch_html(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "inpost-atlas-build"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def extract_rows(html: str) -> list[list[str]]:
    rows = re.findall(r"<tr[^>]*>(.*?)</tr>", html, re.DOTALL)
    out: list[list[str]] = []
    for row in rows:
        cells = re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", row, re.DOTALL)
        if not cells:
            continue
        text_cells = []
        for c in cells:
            c = re.sub(r"<[^>]+>", "", c)
            c = re.sub(r"&nbsp;", " ", c)
            c = re.sub(r"&amp;", "&", c)
            c = re.sub(r"&#160;", " ", c)
            c = re.sub(r"\s+", " ", c).strip()
            text_cells.append(c)
        if len(text_cells) >= 6:
            out.append(text_cells)
    return [r for r in out if r[0] != "Powiat"]


def parse_area(s: str) -> float:
    return float(s.replace(" ", "").replace(",", "."))


def parse_pop(s: str) -> int:
    return int(re.sub(r"[^\d]", "", s))


def build_records(rows: list[list[str]]) -> list[dict]:
    records: list[dict] = []
    for r in rows:
        name = re.sub(r"\[[a-z]\]", "", r[0]).strip()
        voivodship = r[3].strip().lower()
        area = parse_area(r[4])
        population = parse_pop(r[5])
        if name.startswith("powiat "):
            geojson_nazwa = name
            display = name[0].upper() + name[1:]
        else:
            geojson_nazwa = "powiat " + name
            display = f"{name} (miasto na prawach powiatu)"
        geojson_nazwa = GEOJSON_ALIASES.get(geojson_nazwa, geojson_nazwa)
        records.append({
            "key": f"{geojson_nazwa}__{voivodship}",
            "display_name": display,
            "geojson_nazwa": geojson_nazwa,
            "voivodship": voivodship,
            "population": population,
            "area_km2": area,
        })
    return records


def cross_check(records: list[dict], geojson_path: Path) -> None:
    with geojson_path.open(encoding="utf-8") as f:
        geo = json.load(f)
    geo_names = {feat["properties"]["nazwa"] for feat in geo["features"]}
    csv_geo_names = {r["geojson_nazwa"] for r in records}
    missing = csv_geo_names - geo_names
    extra = geo_names - csv_geo_names
    if missing or extra:
        sys.stderr.write(
            f"Cross-check failed.\n"
            f"  In CSV but not in GeoJSON ({len(missing)}): {missing}\n"
            f"  In GeoJSON but not in CSV ({len(extra)}): {extra}\n"
        )
        sys.exit(1)


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    out_csv = repo_root / "src/main/resources/reference/powiats.csv"
    in_geojson = repo_root / "src/main/resources/reference/powiats.geojson"
    if not in_geojson.exists():
        sys.stderr.write(f"Missing {in_geojson}\n")
        sys.exit(1)

    print(f"Fetching {WIKI_URL} ...", file=sys.stderr)
    html = fetch_html(WIKI_URL)
    print(f"  got {len(html)} bytes", file=sys.stderr)

    rows = extract_rows(html)
    if len(rows) != 380:
        sys.stderr.write(f"Expected 380 powiat rows, got {len(rows)}\n")
        sys.exit(1)

    records = build_records(rows)
    cross_check(records, in_geojson)

    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with out_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["key", "display_name", "geojson_nazwa", "voivodship", "population", "area_km2"])
        for r in sorted(records, key=lambda x: x["key"]):
            writer.writerow([
                r["key"],
                r["display_name"],
                r["geojson_nazwa"],
                r["voivodship"],
                r["population"],
                f"{r['area_km2']:.2f}",
            ])
    print(f"Wrote {out_csv} ({len(records)} rows)", file=sys.stderr)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    main()
