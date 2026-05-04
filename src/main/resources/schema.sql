CREATE TABLE IF NOT EXISTS lockers (
    name                 TEXT PRIMARY KEY,
    country              TEXT    NOT NULL,
    type                 TEXT,
    status               TEXT,
    latitude             REAL,
    longitude            REAL,
    city                 TEXT,
    province_raw         TEXT,
    province_normalized  TEXT,
    post_code            TEXT,
    street               TEXT,
    building_number      TEXT,
    location_247         INTEGER,
    easy_access_zone     INTEGER,
    functions            TEXT,
    image_url            TEXT,
    fetched_at           TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_lockers_country               ON lockers(country);
CREATE INDEX IF NOT EXISTS idx_lockers_province_normalized   ON lockers(province_normalized);
CREATE INDEX IF NOT EXISTS idx_lockers_status                ON lockers(status);
