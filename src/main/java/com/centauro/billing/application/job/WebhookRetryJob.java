package com.centauro.billing.application.job;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySettings;
import com.centauro.billing.application.usecase.WebhookUseCase;
import com.centauro.billing.domain.port.JobLockPort;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reprocesa los webhooks RECEIVED (el proceso murió antes de procesarlos) o FAILED con
 * {@code next_attempt_at <= now}, hasta {@code billing.jobs.webhook-max-attempts} intentos con backoff
 * exponencial. Sin pasarela configurada no corre (no gasta intentos). Lock {@code 1003}.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookRetryJob {

    public static final long LOCK_KEY = 1003L;
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES = 20;
    static final Actor JOB_ACTOR = Actor.job("WebhookRetryJob");

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryJob.class);

    private final WebhookEventRepositoryPort webhookEvents;
    private final WebhookUseCase webhookUseCase;
    private final GatewayOperations gatewayOperations;
    private final GatewaySettings settings;
    private final JobLockPort jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public WebhookRetryJob(WebhookEventRepositoryPort webhookEvents, WebhookUseCase webhookUseCase,
                           GatewayOperations gatewayOperations, GatewaySettings settings, JobLockPort jobLock,
                           Clock clock) {
        this(webhookEvents, webhookUseCase, gatewayOperations, settings, jobLock, clock, BATCH_SIZE);
    }

    WebhookRetryJob(WebhookEventRepositoryPort webhookEvents, WebhookUseCase webhookUseCase,
                    GatewayOperations gatewayOperations, GatewaySettings settings, JobLockPort jobLock, Clock clock,
                    int batchSize) {
        this.webhookEvents = webhookEvents;
        this.webhookUseCase = webhookUseCase;
        this.gatewayOperations = gatewayOperations;
        this.settings = settings;
        this.jobLock = jobLock;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${billing.jobs.webhook-retry-cron}", zone = "${billing.zone}")
    public void scheduledRun() {
        run();
    }

    /** Devuelve false si no corrió: sin pasarela o sin lock. */
    public boolean run() {
        if (!gatewayOperations.isAvailable()) {
            log.debug("WebhookRetryJob: sin pasarela configurada, se omite");
            return false;
        }
        boolean acquired = jobLock.runExclusively(LOCK_KEY, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int retried = 0;
            UUID cursor = SubscriptionLifecycleJob.CURSOR_START;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<UUID> ids = webhookEvents.findRetryable(now, settings.webhookMaxAttempts(), cursor, batchSize);
                for (UUID id : ids) {
                    try {
                        webhookEvents.findById(id).ifPresent(event -> webhookUseCase.process(event, JOB_ACTOR));
                        retried++;
                    } catch (RuntimeException e) {
                        log.error("WebhookRetryJob: falló el reproceso del evento {}", id, e);
                    }
                }
                if (ids.size() < batchSize) {
                    break;
                }
                cursor = ids.get(ids.size() - 1);
            }
            if (retried > 0) {
                log.info("WebhookRetryJob: reprocesados={}", retried);
            }
        });
        if (!acquired) {
            log.info("WebhookRetryJob: lock {} tomado por otra instancia, se omite", LOCK_KEY);
        }
        return acquired;
    }
}
