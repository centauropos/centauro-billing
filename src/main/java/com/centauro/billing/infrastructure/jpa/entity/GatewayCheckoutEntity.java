package com.centauro.billing.infrastructure.jpa.entity;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "gateway_checkouts")
@Getter
@Setter
@NoArgsConstructor
public class GatewayCheckoutEntity {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GatewayName gateway;

    @Column(name = "gateway_preapproval_id", length = 64)
    private String gatewayPreapprovalId;

    @Column(name = "init_point", columnDefinition = "text")
    private String initPoint;

    @Column(name = "payer_email", nullable = false)
    private String payerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckoutStatus status;

    @Column(name = "start_date")
    private OffsetDateTime startDate;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
