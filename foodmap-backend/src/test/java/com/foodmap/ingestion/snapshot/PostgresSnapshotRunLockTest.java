package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** pg_try_advisory_lock on one held connection; a held lock means no run. (JDBC mocked.) */
class PostgresSnapshotRunLockTest {

    final DataSource dataSource = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement statement = mock(PreparedStatement.class);
    final ResultSet result = mock(ResultSet.class);

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
    }

    @Test
    void takesTheAdvisoryLockAndReleasesItOnTheSameConnection() throws Exception {
        when(result.getBoolean(1)).thenReturn(true);

        Optional<SnapshotRunLock.Handle> handle = new PostgresSnapshotRunLock(dataSource).tryAcquire();

        assertThat(handle).isPresent();
        verify(connection).prepareStatement("SELECT pg_try_advisory_lock(?)");
        verify(statement).setLong(1, PostgresSnapshotRunLock.LOCK_KEY);
        verify(connection, never()).close();   // held for the whole run

        handle.get().close();
        verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
        verify(connection).close();
    }

    @Test
    void anotherRunHoldingTheLockMeansNoHandle() throws Exception {
        when(result.getBoolean(1)).thenReturn(false);

        assertThat(new PostgresSnapshotRunLock(dataSource).tryAcquire()).isEmpty();
        verify(connection).close();
    }
}
