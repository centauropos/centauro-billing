package com.centauro.billing.infrastructure.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAdvisoryJobLockTest {

    private static final String LOCK = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK = "SELECT pg_advisory_unlock(?)";

    private DataSource dataSource;
    private Connection connection;
    private PostgresAdvisoryJobLock lock;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        lock = new PostgresAdvisoryJobLock(dataSource);
    }

    private PreparedStatement returning(String sql, boolean value) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(value);
        when(statement.executeQuery()).thenReturn(rs);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        return statement;
    }

    @Test
    void lockNotAcquiredDoesNotRunTheTaskNorUnlock() throws SQLException {
        PreparedStatement acquire = returning(LOCK, false);
        AtomicBoolean ran = new AtomicBoolean();

        assertThat(lock.runExclusively(42L, () -> ran.set(true))).isFalse();

        assertThat(ran).isFalse();
        verify(acquire).setLong(1, 42L);
        verify(connection, never()).prepareStatement(UNLOCK);
        verify(connection).close();
    }

    @Test
    void acquiredLockRunsInAutocommitAndIsReleasedOnTheSameConnection() throws SQLException {
        returning(LOCK, true);
        PreparedStatement release = returning(UNLOCK, true);
        AtomicBoolean ran = new AtomicBoolean();

        assertThat(lock.runExclusively(42L, () -> ran.set(true))).isTrue();

        assertThat(ran).isTrue();
        verify(connection).setAutoCommit(true);
        verify(release).setLong(1, 42L);
        verify(connection, never()).abort(any());
        verify(connection).close();
    }

    @Test
    void taskFailureStillReleasesTheLock() throws SQLException {
        returning(LOCK, true);
        PreparedStatement release = returning(UNLOCK, true);

        assertThatThrownBy(() -> lock.runExclusively(42L, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        verify(release).executeQuery();
        verify(connection).close();
    }

    @Test
    void failedReleaseAbortsTheConnectionSoThePoolDoesNotReuseIt() throws SQLException {
        returning(LOCK, true);
        when(connection.prepareStatement(UNLOCK)).thenThrow(new SQLException("conexión caída"));

        assertThat(lock.runExclusively(42L, () -> { })).isTrue();

        verify(connection).abort(any(Executor.class));
    }

    @Test
    void releaseReturningFalseAlsoAborts() throws SQLException {
        returning(LOCK, true);
        returning(UNLOCK, false);

        lock.runExclusively(42L, () -> { });

        verify(connection).abort(any(Executor.class));
    }
}
