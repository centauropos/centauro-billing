package com.centauro.billing.application.job;

import com.centauro.billing.domain.port.JobLockPort;

import java.util.ArrayList;
import java.util.List;

final class TestJobLock implements JobLockPort {

    boolean available = true;
    final List<Long> keys = new ArrayList<>();

    @Override
    public boolean runExclusively(long lockKey, Runnable task) {
        keys.add(lockKey);
        if (!available) {
            return false;
        }
        task.run();
        return true;
    }
}
