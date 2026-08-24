package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.PlatformCandidateResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.ReviewTrayCropRequest;
import com.cretas.aims.dto.labelqc.LabelQcDtos.ReviewPlatformCandidateRequest;
import com.cretas.aims.dto.labelqc.LabelQcDtos.TrayCropResponse;
import com.cretas.aims.entity.enums.LabelQcTrayCropStatus;
import com.cretas.aims.service.LabelQcService;
import com.cretas.aims.service.LabelQcImageProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final LabelQcImageProxyService imageProxyService;

    @GetMapping("/candidates")
    public ApiResponse<PageResponse<PlatformCandidateResponse>> listCandidates(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "false") boolean reviewed,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(
                labelQcService.listPlatformCandidates(factoryId, reviewed, page, size));
    }

    @GetMapping("/photos/{photoId}/image")
    public ResponseEntity<byte[]> photoImage(
            @PathVariable String factoryId,
            @PathVariable String photoId) {
        var image = imageProxyService.load(factoryId, photoId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.bytes());
    }

    @GetMapping("/tray-crops")
    public ApiResponse<PageResponse<TrayCropResponse>> listTrayCrops(
            @PathVariable String factoryId,
            @RequestParam(required = false) LabelQcTrayCropStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(labelQcService.listTrayCrops(factoryId, status, page, size));
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

    @PutMapping("/tray-crops/{cropId}/review")
    public ApiResponse<TrayCropResponse> reviewTrayCrop(
            @PathVariable String factoryId,
            @PathVariable String cropId,
            @Valid @RequestBody ReviewTrayCropRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(
                "单盒标签精修已保存",
                labelQcService.reviewTrayCrop(
                        factoryId, cropId, trustedPlatformUser(servletRequest), request));
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
