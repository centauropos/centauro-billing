package com.centauro.billing.infrastructure.lock;

import com.centauro.billing.domain.port.JobLockPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lock de job con {@code pg_try_advisory_xact_lock}. La transacción externa retiene el lock mientras corre la
 * tarea; los pasos internos abren sus propias transacciones ({@code REQUIRES_NEW}) en otra conexión.
 */
@Component
public class PostgresAdvisoryJobLock implements JobLockPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgresAdvisoryJobLock(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean runExclusively(long lockKey, Runnable task) {
        Boolean acquired = transactionTemplate.execute(status -> {
            Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                    lockKey);
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            task.run();
            return true;
        });
        return Boolean.TRUE.equals(acquired);
    }
}
