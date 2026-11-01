package com.foodmap.ingestion.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** {@link SnapshotDbGuard} over JDBC: counts only (read-only), any database. */
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class JdbcSnapshotDbGuard implements SnapshotDbGuard {

    private final DataSource dataSource;

    @Override
    public String identity() {
        try (Connection c = dataSource.getConnection()) {
            return SnapshotDbGuard.normalise(c.getMetaData().getURL());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the database identity: " + e.getMessage(), e);
        }
    }

    @Override
    public Baseline baseline() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM restaurants", Long.class);
        return new Baseline(tableCounts(), max == null ? 0 : max, Instant.now());
    }

    @Override
    public List<String> verify(Baseline baseline, int created, int rekeyed) {
        List<String> problems = new ArrayList<>();
        Map<String, Long> now = tableCounts();
        for (Map.Entry<String, Long> e : baseline.tableCounts().entrySet()) {
            long expected = e.getValue() + (e.getKey().equals(RESTAURANTS) ? created : 0);
            Long actual = now.get(e.getKey());
            if (!Objects.equals(actual, expected)) {
                problems.add(e.getKey() + " has " + actual + " rows, expected " + expected);
            }
        }
        for (String table : now.keySet()) {
            if (!baseline.tableCounts().containsKey(table)) problems.add("table " + table + " appeared during the run");
        }
        Long updated = new JdbcTemplate(dataSource).queryForObject(
            "SELECT COUNT(*) FROM restaurants WHERE id <= ? AND updated_at >= ?", Long.class,
            baseline.maxRestaurantId(), Timestamp.from(baseline.at()));
        if (updated == null || updated != rekeyed) {
            problems.add(updated + " existing restaurant row(s) changed since the run started, expected " + rekeyed + " (re-keyed)");
        }
        return problems;
    }

    /** Row count of every base table in the connection's schema (lower-case names). */
    private Map<String, Long> tableCounts() {
        Map<String, Long> counts = new TreeMap<>();
        List<String> tables = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getTables(c.getCatalog(), c.getSchema(), "%", null)) {
                while (rs.next()) {
                    String type = rs.getString("TABLE_TYPE");   // PostgreSQL "TABLE", H2 "BASE TABLE"
                    if ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type)) tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot list tables: " + e.getMessage(), e);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : tables) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\"", Long.class);
            counts.put(table.toLowerCase(Locale.ROOT), n == null ? 0 : n);
        }
        return counts;
    }
}
