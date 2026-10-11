package com.centauro.billing.web.controller;

import com.centauro.billing.application.dto.PlanAdminView;
import com.centauro.billing.application.dto.PlanPriceRequest;
import com.centauro.billing.application.dto.PlanPriceView;
import com.centauro.billing.application.dto.PlanRequest;
import com.centauro.billing.application.dto.PlanUpdateRequest;
import com.centauro.billing.application.usecase.PlanAdminUseCase;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.web.openapi.OpenApiConfig;
import com.centauro.billing.web.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform/plans")
@PreAuthorize("@billingAuth.isPlatformAdmin()")
@Tag(name = "Platform")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class PlatformPlanController {

    private final PlanAdminUseCase useCase;

    public PlatformPlanController(PlanAdminUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    public List<PlanAdminView> listPlans() {
        return useCase.listPlans();
    }

    @PostMapping
    public ResponseEntity<PlanAdminView> createPlan(@Valid @RequestBody PlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.createPlan(request, actor()));
    }

    @PutMapping("/{code}")
    public PlanAdminView updatePlan(@PathVariable String code, @Valid @RequestBody PlanUpdateRequest request) {
        return useCase.updatePlan(code, request, actor());
    }

    @GetMapping("/{code}/prices")
    public List<PlanPriceView> listPrices(@PathVariable String code) {
        return useCase.listPrices(code);
    }

    @PostMapping("/{code}/prices")
    public ResponseEntity<PlanPriceView> addPrice(@PathVariable String code,
                                                  @Valid @RequestBody PlanPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.addPrice(code, request, actor()));
    }

    @DeleteMapping("/{code}/prices/{priceId}")
    public ResponseEntity<Void> deletePrice(@PathVariable String code, @PathVariable UUID priceId) {
        useCase.deletePrice(code, priceId, actor());
        return ResponseEntity.noContent().build();
    }

    private static Actor actor() {
        return Actor.platformAdmin(TenantContextHolder.currentSubject());
    }
}
