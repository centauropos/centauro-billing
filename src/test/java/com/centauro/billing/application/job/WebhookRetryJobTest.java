package com.centauro.billing.application.job;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.vo.WebhookNotification;
import com.centauro.billing.support.GatewayHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookRetryJobTest {

    private GatewayHarness h;
    private TestJobLock lock;

    @BeforeEach
    void setUp() {
        h = new GatewayHarness();
        lock = new TestJobLock();
    }

    private WebhookRetryJob jobAt(GatewayHarness harness, OffsetDateTime now) {
        return new WebhookRetryJob(harness.webhookEvents, harness.webhookUseCase, harness.gatewayOperations,
                harness.settings, lock, clockAt(now), 1);
    }

    private WebhookEvent stored(String notificationId, String resourceId) {
        WebhookEvent event = WebhookEvent.received(new WebhookNotification(GatewayName.MERCADOPAGO, notificationId,
                "req-" + notificationId, "subscription_preapproval", "updated", resourceId, false, Map.of()), NOW);
        h.webhookEvents.put(event);
        return event;
    }

    @Test
    void reprocessesReceivedAndFailedEventsThatAreDue() {
        h.store(subscription().gatewaySubscriptionId("pre-9").gatewayStatus("authorized").build());
        h.store(subscription().tenantId("t-8").gatewaySubscriptionId("pre-8").gatewayStatus("authorized").build());
        h.preapproval("pre-9", GatewaySubscriptionStatus.PAUSED, null, new BigDecimal("25000"), NOW);
        h.preapproval("pre-8", GatewaySubscriptionStatus.PAUSED, null, new BigDecimal("25000"), NOW);
        WebhookEvent received = stored("n-1", "pre-9");
        WebhookEvent failed = stored("n-2", "pre-8");
        failed.markFailed("MP caído", NOW);
        h.webhookEvents.put(failed);

        assertThat(jobAt(h, NOW.plusMinutes(10)).run()).isTrue();

        assertThat(received.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(failed.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(lock.keys).containsExactly(WebhookRetryJob.LOCK_KEY);
    }

    @Test
    void eventsNotYetDueOrOutOfAttemptsAreLeftAlone() {
        WebhookEvent notDue = stored("n-1", "pre-9");
        WebhookEvent exhausted = stored("n-2", "pre-9");
        for (int i = 0; i < GatewayHarness.MAX_ATTEMPTS; i++) {
            exhausted.markFailed("MP caído", NOW.minusDays(1));
        }

        jobAt(h, NOW.plusMinutes(1)).run();

        assertThat(notDue.getStatus()).isEqualTo(WebhookStatus.RECEIVED);
        assertThat(exhausted.getAttempts()).isEqualTo(GatewayHarness.MAX_ATTEMPTS);
        assertThat(h.gateway.subscriptionLookups).isEmpty();
    }

    @Test
    void failingAgainIncrementsAttempts() {
        WebhookEvent event = stored("n-1", "pre-9");
        h.gateway.readFailure = new IllegalStateException("MP caído");

        jobAt(h, NOW.plusMinutes(10)).run();

        assertThat(event.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void withoutGatewayDoesNotRunNorSpendAttempts() {
        GatewayHarness noGateway = new GatewayHarness(false, GatewayHarness.BACK_URL);
        WebhookEvent event = WebhookEvent.received(new WebhookNotification(GatewayName.MERCADOPAGO, "n-1", "req-1",
                "subscription_preapproval", null, "pre-9", false, Map.of()), NOW);
        noGateway.webhookEvents.put(event);

        assertThat(jobAt(noGateway, NOW.plusMinutes(10)).run()).isFalse();

        assertThat(event.getStatus()).isEqualTo(WebhookStatus.RECEIVED);
        assertThat(lock.keys).isEmpty();
    }
}
