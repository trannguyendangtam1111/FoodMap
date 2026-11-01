package com.foodmap.ingestion.snapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * PostgreSQL session advisory lock (pg_try_advisory_lock) on one dedicated
 * connection held for the whole run: a second run — in this JVM or any
 * other — cannot take it until the first releases it or its connection
 * closes (a crashed run frees the lock with its session). No table.
 */
@Slf4j
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class PostgresSnapshotRunLock implements SnapshotRunLock {

    /** Arbitrary, FoodMap-specific advisory lock key ("FMSNAP"). */
    static final long LOCK_KEY = 0x464D_534E_4150L;

    private final DataSource dataSource;

    @Override
    public Optional<Handle> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!call(connection, "SELECT pg_try_advisory_lock(?)")) {
                connection.close();
                return Optional.empty();
            }
            Connection held = connection;
            return Optional.of(() -> release(held));
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Could not take the snapshot run lock: " + e.getMessage(), e);
        }
    }

    private void release(Connection connection) {
        try {
            call(connection, "SELECT pg_advisory_unlock(?)");
        } catch (SQLException e) {
            log.warn("Could not release the snapshot run lock explicitly ({}); closing its session frees it", e.getMessage());
        } finally {
            closeQuietly(connection);
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }
}
