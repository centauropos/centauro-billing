package com.centauro.billing.web.controller;

import com.centauro.billing.application.dto.CancelRequest;
import com.centauro.billing.application.dto.ChangePlanRequest;
import com.centauro.billing.application.dto.PublicPlanView;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.usecase.TenantSubscriptionUseCase;
import com.centauro.billing.web.openapi.OpenApiConfig;
import com.centauro.billing.web.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API del tenant. El tenantId sale siempre del JWT. */
@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "Tenant")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class TenantBillingController {

    private final TenantSubscriptionUseCase useCase;

    public TenantBillingController(TenantSubscriptionUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/subscription")
    @PreAuthorize("@billingAuth.hasTenant()")
    public TenantSubscriptionView getSubscription() {
        return useCase.getSubscription(TenantContextHolder.requireTenantId());
    }

    @GetMapping("/plans")
    @PreAuthorize("@billingAuth.hasTenant()")
    public List<PublicPlanView> listPlans() {
        return useCase.listPlans(TenantContextHolder.requireTenantId());
    }

    @PostMapping("/subscription/cancel")
    @PreAuthorize("@billingAuth.canManage()")
    public TenantSubscriptionView cancel(@Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return useCase.cancel(TenantContextHolder.requireTenantId(), reason, TenantContextHolder.tenantUserActor());
    }

    @PostMapping("/subscription/change-plan")
    @PreAuthorize("@billingAuth.canManage()")
    public TenantSubscriptionView changePlan(@Valid @RequestBody ChangePlanRequest request) {
        return useCase.changePlan(TenantContextHolder.requireTenantId(), request.planCode(),
                TenantContextHolder.tenantUserActor());
    }
}
