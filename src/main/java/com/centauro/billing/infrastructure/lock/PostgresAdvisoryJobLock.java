package com.centauro.billing.infrastructure.lock;

import com.centauro.billing.domain.port.JobLockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Lock de job con {@code pg_try_advisory_lock} de sesión, sobre una conexión dedicada en autocommit: no queda
 * ninguna transacción abierta mientras la tarea llama a la pasarela. Cada paso de la tarea usa sus propias
 * transacciones en otras conexiones del pool.
 *
 * <p>El lock se libera en {@code finally} con {@code pg_advisory_unlock}. Si la liberación falla, la conexión se
 * aborta para que el pool no la reutilice con el lock tomado (cerrar la sesión lo suelta).
 */
@Component
public class PostgresAdvisoryJobLock implements JobLockPort {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryJobLock.class);

    private final DataSource dataSource;

    public PostgresAdvisoryJobLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean runExclusively(long lockKey, Runnable task) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!call(connection, "SELECT pg_try_advisory_lock(?)", lockKey)) {
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                release(connection, lockKey);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo tomar el lock de job " + lockKey, e);
        }
    }

    private static void release(Connection connection, long lockKey) {
        boolean released = false;
        try {
            released = call(connection, "SELECT pg_advisory_unlock(?)", lockKey);
        } catch (SQLException e) {
            log.error("No se pudo liberar el lock de job {}: {}", lockKey, e.getMessage());
        }
        if (!released) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException e) {
                log.error("No se pudo abortar la conexión del lock de job {}: {}", lockKey, e.getMessage());
            }
        }
    }

    private static boolean call(Connection connection, String sql, long lockKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
