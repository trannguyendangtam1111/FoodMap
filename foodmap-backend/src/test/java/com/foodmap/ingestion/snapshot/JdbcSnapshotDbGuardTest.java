package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The JDBC database guard on an embedded database: identity, and the per-wave write check. */
class JdbcSnapshotDbGuardTest {

    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    JdbcSnapshotDbGuard guard;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE restaurants (id BIGINT PRIMARY KEY, name VARCHAR(100), updated_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE place_cells (cell_key VARCHAR(6) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO restaurants VALUES (1, 'old', ?)", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        guard = new JdbcSnapshotDbGuard(db);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    void insert(long id) {
        jdbc.update("INSERT INTO restaurants VALUES (?, 'new', ?)", id, Timestamp.from(Instant.now()));
    }

    @Test
    void identityIsTheNormalisedJdbcUrl() {
        assertThat(guard.identity()).startsWith("h2:mem:");
        assertThat(SnapshotDbGuard.normalise("jdbc:postgresql://localhost:5434/foodmap_dev")).isEqualTo("localhost:5434/foodmap_dev");
        assertThat(SnapshotDbGuard.normalise("jdbc:postgresql://127.0.0.1:5434/foodmap_dev?sslmode=disable"))
            .isEqualTo("localhost:5434/foodmap_dev");
        assertThat(SnapshotDbGuard.normalise("jdbc:postgresql://db.example.com/foodmap")).isEqualTo("db.example.com:5432/foodmap");
        assertThat(SnapshotDbGuard.normalise("localhost:5434/foodmap_dev")).isEqualTo("localhost:5434/foodmap_dev");
        assertThat(SnapshotDbGuard.normalise("jdbc:h2:mem:testdb;MODE=PostgreSQL")).isEqualTo("h2:mem:testdb");
    }

    @Test
    void exactlyTheExpectedInsertsPass() {
        SnapshotDbGuard.Baseline b = guard.baseline();
        assertThat(b.tableCounts()).containsEntry("restaurants", 1L).containsEntry("place_cells", 0L).containsEntry("users", 0L);
        assertThat(b.maxRestaurantId()).isEqualTo(1);
        insert(2);
        insert(3);
        assertThat(guard.verify(b, 2, 0)).isEmpty();
    }

    @Test
    void anUnexpectedInsertUpdateOrOtherTableChangeIsReported() {
        SnapshotDbGuard.Baseline b = guard.baseline();
        insert(2);
        assertThat(guard.verify(b, 0, 0)).singleElement().asString().contains("restaurants has 2 rows, expected 1");

        jdbc.update("UPDATE restaurants SET updated_at = ? WHERE id = 1", Timestamp.from(Instant.now().plusSeconds(1)));
        assertThat(guard.verify(b, 1, 0)).singleElement().asString().contains("existing restaurant row(s) changed");
        assertThat(guard.verify(b, 1, 1)).isEmpty();   // a re-key is an expected update

        jdbc.update("INSERT INTO place_cells VALUES ('w3gvkq')");
        assertThat(guard.verify(b, 1, 1)).singleElement().asString().contains("place_cells has 1 rows, expected 0");
    }
}
