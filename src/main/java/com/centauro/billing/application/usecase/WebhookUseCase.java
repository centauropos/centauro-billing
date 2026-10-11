package com.centauro.billing.application.usecase;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySettings;
import com.centauro.billing.application.service.GatewaySyncService;
import com.centauro.billing.domain.enums.ActorType;
import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.WebhookNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Webhook de Mercado Pago (la firma ya se verificó). Guarda primero (deduplicado por
 * {@code (provider, dedupe_key)}), después reconsulta el recurso en MP fuera de toda transacción y aplica el
 * estado real. Un error de procesamiento deja el evento FAILED con backoff y no se propaga: el webhook igual
 * responde 200 y lo retoma {@code WebhookRetryJob}.
 */
@Service
public class WebhookUseCase {

    public static final Actor WEBHOOK_ACTOR = new Actor(ActorType.WEBHOOK, "mercadopago");
    static final String TOPIC_PREAPPROVAL = "subscription_preapproval";
    static final String TOPIC_AUTHORIZED_PAYMENT = "subscription_authorized_payment";

    private static final Logger log = LoggerFactory.getLogger(WebhookUseCase.class);

    private final WebhookEventRepositoryPort webhookEvents;
    private final GatewaySyncService sync;
    private final GatewayOperations gatewayOperations;
    private final GatewaySettings settings;
    private final Clock clock;

    public WebhookUseCase(WebhookEventRepositoryPort webhookEvents, GatewaySyncService sync,
                          GatewayOperations gatewayOperations, GatewaySettings settings, Clock clock) {
        this.webhookEvents = webhookEvents;
        this.sync = sync;
        this.gatewayOperations = gatewayOperations;
        this.settings = settings;
        this.clock = clock;
    }

    /** Devuelve false si la notificación ya se había recibido. Solo falla si no se pudo guardar. */
    public boolean receive(WebhookNotification notification) {
        String dedupeKey = notification.dedupeKey();
        if (dedupeKey == null) {
            throw new InvalidBillingRequestException("Notificación sin data.id o sin x-request-id");
        }
        if (dedupeKey.length() > WebhookNotification.MAX_DEDUPE_KEY_LENGTH
                || notification.requestId().length() > WebhookNotification.MAX_REQUEST_ID_LENGTH
                || notification.resourceId().length() > WebhookNotification.MAX_RESOURCE_ID_LENGTH) {
            throw new InvalidBillingRequestException("data.id o x-request-id demasiado largos");
        }
        WebhookEvent event = WebhookEvent.received(notification, OffsetDateTime.now(clock));
        if (!webhookEvents.insertIfAbsent(event)) {
            log.info("Webhook duplicado {} ({}), se ignora", event.getDedupeKey(), event.getTopic());
            return false;
        }
        process(event, WEBHOOK_ACTOR);
        return true;
    }

    /** Procesa (o reprocesa) un evento guardado. Idempotente: el mismo estado aplicado dos veces no cambia nada. */
    public WebhookStatus process(WebhookEvent event, Actor actor) {
        WebhookStatus readStatus = event.getStatus();
        int readAttempts = event.getAttempts();
        try {
            GatewaySyncService.Outcome outcome = handle(event, actor);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (outcome.processed()) {
                event.markProcessed(outcome.detail(), now);
            } else {
                event.markIgnored(outcome.detail(), now);
            }
            log.info("Webhook {} {} {}: {} ({})", event.getId(), event.getTopic(), event.getResourceId(),
                    event.getStatus(), outcome.detail());
        } catch (RuntimeException e) {
            event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), OffsetDateTime.now(clock));
            if (event.getAttempts() >= settings.webhookMaxAttempts()) {
                log.error("Webhook {} {} {} agotó {} intentos: {}", event.getId(), event.getTopic(),
                        event.getResourceId(), event.getAttempts(), event.getLastError());
            } else {
                log.warn("Webhook {} {} {} falló (intento {}), próximo {}: {}", event.getId(), event.getTopic(),
                        event.getResourceId(), event.getAttempts(), event.getNextAttemptAt(), event.getLastError());
            }
        }
        if (!webhookEvents.update(event, readStatus, readAttempts)) {
            log.info("Webhook {}: otro proceso lo actualizó mientras tanto; se conserva su resultado", event.getId());
        }
        return event.getStatus();
    }

    private GatewaySyncService.Outcome handle(WebhookEvent event, Actor actor) {
        String topic = event.getTopic() == null ? "" : event.getTopic().trim().toLowerCase(Locale.ROOT);
        if (!TOPIC_PREAPPROVAL.equals(topic) && !TOPIC_AUTHORIZED_PAYMENT.equals(topic)) {
            return new GatewaySyncService.Outcome(false, "tópico no procesado: " + event.getTopic());
        }
        String resourceId = event.getResourceId();
        if (resourceId == null || resourceId.isBlank()) {
            return new GatewaySyncService.Outcome(false, "notificación sin data.id");
        }
        PaymentGateway gateway = gatewayOperations.requireGateway();
        if (TOPIC_PREAPPROVAL.equals(topic)) {
            GatewaySubscriptionSnapshot preapproval = gateway.getSubscription(resourceId);
            return sync.applyPreapproval(preapproval, actor, OffsetDateTime.now(clock));
        }
        GatewayChargeSnapshot charge = gateway.getCharge(resourceId);
        GatewaySubscriptionSnapshot preapproval = null;
        String preapprovalId = charge.gatewaySubscriptionId();
        if (preapprovalId != null && !preapprovalId.isBlank() && !sync.isLinked(preapprovalId)) {
            preapproval = gateway.getSubscription(preapprovalId);
        }
        return sync.applyCharge(charge, preapproval, actor, OffsetDateTime.now(clock));
    }
}
