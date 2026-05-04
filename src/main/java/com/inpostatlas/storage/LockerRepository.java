package com.inpostatlas.storage;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class LockerRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO lockers (
                name, country, type, status, latitude, longitude,
                city, province_raw, province_normalized, post_code,
                street, building_number, location_247, easy_access_zone,
                functions, image_url, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                country=excluded.country,
                type=excluded.type,
                status=excluded.status,
                latitude=excluded.latitude,
                longitude=excluded.longitude,
                city=excluded.city,
                province_raw=excluded.province_raw,
                province_normalized=excluded.province_normalized,
                post_code=excluded.post_code,
                street=excluded.street,
                building_number=excluded.building_number,
                location_247=excluded.location_247,
                easy_access_zone=excluded.easy_access_zone,
                functions=excluded.functions,
                image_url=excluded.image_url,
                fetched_at=excluded.fetched_at
            """;

    private static final RowMapper<LockerRow> ROW_MAPPER = (rs, rowNum) -> new LockerRow(
            rs.getString("name"),
            rs.getString("country"),
            rs.getString("type"),
            rs.getString("status"),
            getNullableDouble(rs, "latitude"),
            getNullableDouble(rs, "longitude"),
            rs.getString("city"),
            rs.getString("province_raw"),
            rs.getString("province_normalized"),
            rs.getString("post_code"),
            rs.getString("street"),
            rs.getString("building_number"),
            getNullableBool(rs, "location_247"),
            getNullableBool(rs, "easy_access_zone"),
            rs.getString("functions"),
            rs.getString("image_url"),
            parseInstant(rs.getString("fetched_at"))
    );

    private final JdbcTemplate jdbc;

    public LockerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertBatch(List<LockerRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[] result = jdbc.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LockerRow r = rows.get(i);
                int idx = 1;
                ps.setString(idx++, r.name());
                ps.setString(idx++, r.country());
                ps.setString(idx++, r.type());
                ps.setString(idx++, r.status());
                setNullableDouble(ps, idx++, r.latitude());
                setNullableDouble(ps, idx++, r.longitude());
                ps.setString(idx++, r.city());
                ps.setString(idx++, r.provinceRaw());
                ps.setString(idx++, r.provinceNormalized());
                ps.setString(idx++, r.postCode());
                ps.setString(idx++, r.street());
                ps.setString(idx++, r.buildingNumber());
                setNullableBool(ps, idx++, r.location247());
                setNullableBool(ps, idx++, r.easyAccessZone());
                ps.setString(idx++, r.functions());
                ps.setString(idx++, r.imageUrl());
                ps.setString(idx, r.fetchedAt() != null ? r.fetchedAt().toString() : Instant.now().toString());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        return result.length;
    }

    public List<LockerRow> findByCountry(String country) {
        return jdbc.query("SELECT * FROM lockers WHERE country = ?", ROW_MAPPER, country);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM lockers", Long.class);
        return c == null ? 0L : c;
    }

    public long countByCountry(String country) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM lockers WHERE country = ?", Long.class, country);
        return c == null ? 0L : c;
    }

    public Map<String, Long> distinctProvinceCountsRaw(String country) {
        return jdbc.queryForList(
                        "SELECT province_raw, COUNT(*) AS c FROM lockers WHERE country = ? GROUP BY province_raw",
                        country)
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("province_raw"),
                        row -> ((Number) row.get("c")).longValue(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    public void deleteByCountry(String country) {
        jdbc.update("DELETE FROM lockers WHERE country = ?", country);
    }

    private static Double getNullableDouble(java.sql.ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean getNullableBool(java.sql.ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v != 0;
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.REAL);
        } else {
            ps.setDouble(idx, v);
        }
    }

    private static void setNullableBool(PreparedStatement ps, int idx, Boolean v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v ? 1 : 0);
        }
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
