package com.centauro.billing.web.controller;

import com.centauro.billing.application.dto.AccessView;
import com.centauro.billing.application.dto.TrialRequest;
import com.centauro.billing.application.dto.TrialResult;
import com.centauro.billing.application.usecase.AccessQueryUseCase;
import com.centauro.billing.application.usecase.TrialUseCase;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.web.openapi.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API de servicio a servicio (user-manager, pos-manager, purchase-manager). */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}")
@Tag(name = "Internal")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_API_KEY)
public class InternalBillingController {

    private static final String DEFAULT_SOURCE = "INTERNAL";

    private final TrialUseCase trialUseCase;
    private final AccessQueryUseCase accessQueryUseCase;

    public InternalBillingController(TrialUseCase trialUseCase, AccessQueryUseCase accessQueryUseCase) {
        this.trialUseCase = trialUseCase;
        this.accessQueryUseCase = accessQueryUseCase;
    }

    @PostMapping("/trial")
    public ResponseEntity<TrialResult> createTrial(@PathVariable String tenantId,
                                                   @Valid @RequestBody TrialRequest request) {
        String source = request.source() == null || request.source().isBlank() ? DEFAULT_SOURCE : request.source();
        TrialResult result = trialUseCase.createTrial(new TrialUseCase.Command(tenantId, request.companyName(),
                request.billingEmail(), request.planCode(), source, Actor.internal(source), null));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/access")
    public AccessView access(@PathVariable String tenantId) {
        return accessQueryUseCase.access(tenantId);
    }
}
