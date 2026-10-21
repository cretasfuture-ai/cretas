package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.cretas.aims.service.intent.PurchaseGrantRefusalAuditService;
import com.cretas.aims.service.intent.PurchaseTaskGrantService;
import com.cretas.aims.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import java.time.DateTimeException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Explicit deferred authorization/execution; the original atomic confirmation stays unchanged. */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/mobile/{factoryId}/ai-purchase-task-grants")
public class AiPurchaseTaskGrantController {
    private final ControlledPurchaseTaskExecutionService service;
    @Autowired
    private PurchaseGrantRefusalAuditService refusalAudit;
    @GetMapping("/{grantId}")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> status(@PathVariable String factoryId, @PathVariable String grantId) {
        return ApiResponse.success(service.status(factoryId, grantId));
    }
    @PostMapping("/authorize-later")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> authorizeLater(@PathVariable String factoryId,
            @Valid @RequestBody AuthorizeLaterRequest request) {
        try {
            return ApiResponse.success(service.authorizeForLater(factoryId,
                    new ConfirmationProof(request.proofToken(), request.commandDigest(), Instant.parse(request.expiresAt()))));
        } catch (DateTimeException invalidExpiresAt) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "expiresAt格式无效", invalidExpiresAt);
        }
    }
    @PostMapping("/{grantId}/execute")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> executeLater(@PathVariable String factoryId,
            @PathVariable String grantId, @Valid @RequestBody ExecuteLaterRequest request) {
        try {
            return ApiResponse.success(service.executeLater(factoryId, grantId, request.expectedVersion()));
        } catch (BusinessException | PurchaseTaskGrantService.GrantRejectedException refused) {
            // The transactional service proxy has already rolled back. Do not retry its operation.
            // Unknown infrastructure/commit failures are deliberately not labeled confirmed refusals.
            BusinessException response = refused instanceof BusinessException business ? business
                    : new BusinessException(403, refused.getMessage(), refused).withCode("PURCHASE_GRANT_REJECTED");
            try {
                refusalAudit.record(factoryId, grantId, refused).ifPresent(correlation -> {
                    String previous = response.getActionHint();
                    response.withHint((previous == null || previous.isBlank() ? "" : previous + "；")
                            + "拒绝记录: " + correlation);
                });
            } catch (RuntimeException auditUnavailable) {
                log.error("采购授权执行已拒绝，但拒绝审计不可写: {}", auditUnavailable.getClass().getSimpleName());
                var failure = new BusinessException(503, "执行已拒绝，但拒绝审计暂不可写；请先核对记录，勿重试业务执行")
                        .withCode("GRANT_REFUSAL_AUDIT_UNAVAILABLE");
                failure.addSuppressed(refused);
                throw failure;
            }
            throw response;
        }
    }
    @PostMapping("/{grantId}/revoke")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> revoke(@PathVariable String factoryId, @PathVariable String grantId,
            @Valid @RequestBody RevokeRequest request) {
        return ApiResponse.success(service.revoke(factoryId, grantId, request.expectedVersion(), request.reason()));
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RevokeRequest(Long expectedVersion, @NotBlank @Size(max=500) String reason) {
        @JsonAnySetter public void rejectUnknown(String field, Object ignored) { throw new IllegalArgumentException("未知字段: " + field); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthorizeLaterRequest(@NotBlank @Size(max=128) String proofToken,
                                        @NotBlank @Size(max=128) String commandDigest,
                                        @NotBlank @Size(max=64) String expiresAt) {
        @JsonAnySetter public void rejectUnknown(String field, Object ignored) { throw new IllegalArgumentException("未知字段: " + field); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExecuteLaterRequest(@NotNull @Positive Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String field, Object ignored) { throw new IllegalArgumentException("未知字段: " + field); }
    }
}
