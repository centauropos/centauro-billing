package com.centauro.billing.domain.port;

public interface JobLockPort {

    /** Ejecuta la tarea si obtiene el lock; devuelve false si otra instancia lo tiene. */
    boolean runExclusively(long lockKey, Runnable task);
}