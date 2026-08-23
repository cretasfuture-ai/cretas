package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.PlatformCandidateResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.ReviewPlatformCandidateRequest;
import com.cretas.aims.service.LabelQcService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Platform-only Label QC diagnostics, deliberately outside factory module gates. */
@RestController
@RequestMapping("/api/platform/label-qc/{factoryId}")
@RequireRole({"platform_admin", "super_admin", "developer", "platform_super_admin"})
@RequiredArgsConstructor
public class LabelQcPlatformController {

    private final LabelQcService labelQcService;

    @GetMapping("/candidates")
    public ApiResponse<PageResponse<PlatformCandidateResponse>> listCandidates(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "false") boolean reviewed,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(
                labelQcService.listPlatformCandidates(factoryId, reviewed, page, size));
    }

    @PutMapping("/candidates/{candidateId}/review")
    public ApiResponse<PlatformCandidateResponse> reviewCandidate(
            @PathVariable String factoryId,
            @PathVariable String candidateId,
            @Valid @RequestBody ReviewPlatformCandidateRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(
                "平台诊断结果已保存",
                labelQcService.reviewPlatformCandidate(
                        factoryId, candidateId, trustedPlatformUser(servletRequest), request));
    }

    private Long trustedPlatformUser(HttpServletRequest request) {
        Object trustedUser = request.getAttribute("userId");
        if (trustedUser instanceof Number number) {
            return number.longValue();
        }
        if (trustedUser != null) {
            try {
                return Long.parseLong(String.valueOf(trustedUser));
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid platform identity");
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing platform identity");
    }
}
