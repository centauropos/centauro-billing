package com.centauro.billing.web.controller;

import com.centauro.billing.application.dto.ExtendTrialRequest;
import com.centauro.billing.application.dto.ManualPaymentRequest;
import com.centauro.billing.application.dto.ManualPaymentResult;
import com.centauro.billing.application.dto.PlatformCancelRequest;
import com.centauro.billing.application.dto.PlatformChangePlanRequest;
import com.centauro.billing.application.dto.PlatformTrialRequest;
import com.centauro.billing.application.dto.ReactivateRequest;
import com.centauro.billing.application.dto.SubscriptionDetailView;
import com.centauro.billing.application.dto.SubscriptionListItem;
import com.centauro.billing.application.dto.SuspendRequest;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.dto.TrialResult;
import com.centauro.billing.application.usecase.ManualPaymentUseCase;
import com.centauro.billing.application.usecase.PlatformSubscriptionUseCase;
import com.centauro.billing.application.usecase.TrialUseCase;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.PageQuery;
import com.centauro.billing.domain.vo.PageResult;
import com.centauro.billing.web.openapi.OpenApiConfig;
import com.centauro.billing.web.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/** Panel de plataforma. Cada mutación registra un evento con actorType PLATFORM_ADMIN y el sub del JWT. */
@RestController
@RequestMapping("/api/v1/platform/subscriptions")
@PreAuthorize("@billingAuth.isPlatformAdmin()")
@Tag(name = "Platform")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class PlatformSubscriptionController {

    private final PlatformSubscriptionUseCase useCase;
    private final TrialUseCase trialUseCase;
    private final ManualPaymentUseCase manualPaymentUseCase;

    public PlatformSubscriptionController(PlatformSubscriptionUseCase useCase, TrialUseCase trialUseCase,
                                          ManualPaymentUseCase manualPaymentUseCase) {
        this.useCase = useCase;
        this.trialUseCase = trialUseCase;
        this.manualPaymentUseCase = manualPaymentUseCase;
    }

    @GetMapping
    public PageResult<SubscriptionListItem> list(
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) String planCode,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime trialEndsBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        String[] sortParts = sort.split(",");
        boolean ascending = sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1].trim());
        return useCase.list(new PlatformSubscriptionUseCase.SearchFilters(status, planCode, tenantId, q,
                trialEndsBefore), new PageQuery(page, size, sortParts[0].trim(), ascending));
    }

    @GetMapping("/{tenantId}")
    public SubscriptionDetailView detail(@PathVariable String tenantId) {
        return useCase.detail(tenantId);
    }

    @PostMapping("/{tenantId}/trial")
    public ResponseEntity<TrialResult> createTrial(@PathVariable String tenantId,
                                                   @Valid @RequestBody PlatformTrialRequest request) {
        TrialResult result = trialUseCase.createTrial(new TrialUseCase.Command(tenantId, request.companyName(),
                request.billingEmail(), request.planCode(), "PLATFORM", actor(), request.reason()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PostMapping("/{tenantId}/trial/extend")
    public TenantSubscriptionView extendTrial(@PathVariable String tenantId,
                                              @Valid @RequestBody ExtendTrialRequest request) {
        return useCase.extendTrial(tenantId, request, actor());
    }

    @PostMapping("/{tenantId}/change-plan")
    public TenantSubscriptionView changePlan(@PathVariable String tenantId,
                                             @Valid @RequestBody PlatformChangePlanRequest request) {
        return useCase.changePlan(tenantId, request, actor());
    }

    @PostMapping("/{tenantId}/payments")
    public ResponseEntity<ManualPaymentResult> registerPayment(@PathVariable String tenantId,
                                                               @Valid @RequestBody ManualPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(manualPaymentUseCase.register(tenantId, request, actor()));
    }

    @PostMapping("/{tenantId}/suspend")
    public TenantSubscriptionView suspend(@PathVariable String tenantId, @Valid @RequestBody SuspendRequest request) {
        return useCase.suspend(tenantId, request, actor());
    }

    @PostMapping("/{tenantId}/reactivate")
    public TenantSubscriptionView reactivate(@PathVariable String tenantId,
                                             @Valid @RequestBody ReactivateRequest request) {
        return useCase.reactivate(tenantId, request, actor());
    }

    @PostMapping("/{tenantId}/cancel")
    public TenantSubscriptionView cancel(@PathVariable String tenantId,
                                         @Valid @RequestBody PlatformCancelRequest request) {
        return useCase.cancel(tenantId, request, actor());
    }

    private static Actor actor() {
        return Actor.platformAdmin(TenantContextHolder.currentSubject());
    }
}
