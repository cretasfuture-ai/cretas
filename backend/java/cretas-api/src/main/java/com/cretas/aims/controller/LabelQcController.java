package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.client.LabelQcAnalysisClient;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.labelqc.LabelQcDtos.*;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import com.cretas.aims.entity.enums.LabelQcTrayCropStatus;
import com.cretas.aims.service.LabelQcService;
import com.cretas.aims.service.LabelQcImageProxyService;
import com.cretas.aims.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/label-qc")
@RequireModule("quality_inspection")
@RequiredArgsConstructor
public class LabelQcController {

    private final LabelQcService labelQcService;
    private final LabelQcImageProxyService imageProxyService;
    private final LabelQcAnalysisClient analysisClient;

    @PostMapping(value = "/capture-quality", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission({"quality:read_write"})
    public ApiResponse<LabelQcAnalysisClient.CaptureQualityResult> captureQuality(
            @PathVariable String factoryId,
            @RequestPart("image") MultipartFile image,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片内容为空，请重新拍摄");
        }
        if (image.getSize() > 10L * 1024L * 1024L) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "照片超过 10 MB，请重新拍摄");
        }
        try {
            return ApiResponse.success(
                    "拍摄质量检查完成",
                    analysisClient.analyzeCaptureQuality(
                            image.getBytes(), image.getOriginalFilename(), factoryId));
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "照片读取失败，请重新拍摄",
                    ex);
        } catch (LabelQcAnalysisClient.LabelQcClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "拍摄质量检查暂时不可用，请重试",
                    ex);
        }
    }

    @PostMapping("/tasks")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateTaskRequest request,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "拍检任务已创建",
                labelQcService.createTask(factoryId, userId, request));
    }

    @PostMapping("/tasks/{taskId}/photos")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<PhotoResponse> addPhoto(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody AddPhotoRequest request,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "照片已登记",
                labelQcService.addPhoto(factoryId, taskId, request));
    }

    @PostMapping("/tasks/{taskId}/submit")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> submit(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "已进入 AI 初筛和人工审核队列",
                labelQcService.submit(factoryId, taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<TaskDetailResponse> retry(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "已重新进入 AI 初筛队列",
                labelQcService.retryAnalysis(factoryId, taskId));
    }

    @GetMapping("/tasks")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<PageResponse<TaskSummaryResponse>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) Collection<LabelQcTaskStatus> statuses,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate productionDateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate productionDateTo,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.list(
                factoryId, statuses, archived, productionDateFrom, productionDateTo, page, size));
    }

    @GetMapping("/tasks/status-counts")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<StatusCountsResponse> statusCounts(
            @PathVariable String factoryId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.statusCounts(factoryId));
    }

    @GetMapping("/tasks/{taskId}")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ApiResponse<TaskDetailResponse> detail(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.detail(factoryId, taskId));
    }

    @GetMapping("/photos/{photoId}/image")
    @RequirePermission({"quality:read_write", "quality:read"})
    public ResponseEntity<byte[]> photoImage(
            @PathVariable String factoryId,
            @PathVariable String photoId,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        var image = imageProxyService.load(factoryId, photoId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.bytes());
    }

    @PutMapping("/tasks/{taskId}/review")
    @RequirePermission({"quality:read_write"})
    @RequireRole({"quality_manager", "quality_controller", "factory_super_admin", "platform_admin"})
    public ApiResponse<TaskDetailResponse> review(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody ReviewTaskRequest request,
            HttpServletRequest servletRequest) {
        Long reviewerId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "人工审核已完成",
                labelQcService.review(factoryId, taskId, reviewerId, request));
    }

    @PostMapping("/tasks/{taskId}/archive")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskDetailResponse> archive(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "任务已归档，可随时恢复",
                labelQcService.archive(factoryId, taskId, userId));
    }

    @PostMapping("/tasks/{taskId}/restore")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskDetailResponse> restore(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "任务已恢复",
                labelQcService.restore(factoryId, taskId, userId));
    }

    @PostMapping("/tasks/{taskId}/backup")
    @RequirePermission({"quality:read_write", "system:read_write"})
    public ApiResponse<TaskBackupResponse> backup(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            HttpServletRequest servletRequest) {
        Long userId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "备份数据已生成",
                labelQcService.exportBackup(factoryId, taskId, userId));
    }

    @PutMapping("/tasks/{taskId}/training-decision")
    @RequirePermission({"system:read_write"})
    public ApiResponse<TaskDetailResponse> decideTraining(
            @PathVariable String factoryId,
            @PathVariable String taskId,
            @Valid @RequestBody TrainingDecisionRequest request,
            HttpServletRequest servletRequest) {
        Long technicalAdminId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                Boolean.TRUE.equals(request.approved())
                        ? "已确认进入训练集"
                        : "已拒绝进入训练集",
                labelQcService.decideTraining(
                        factoryId, taskId, technicalAdminId, request));
    }

    @GetMapping("/training-export")
    @RequirePermission({"system:read_write"})
    public ApiResponse<List<TrainingPhoto>> exportTrainingData(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        if (!from.isBefore(to) || from.isBefore(to.minusDays(31))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "导出时间范围必须为 1 到 31 天");
        }
        return ApiResponse.success(
                labelQcService.exportTrainingData(factoryId, from, to, limit));
    }

    @GetMapping("/tray-crops")
    @RequirePermission({"system:read_write"})
    @RequireRole({"platform_admin", "super_admin", "developer", "platform_super_admin"})
    public ApiResponse<PageResponse<TrayCropResponse>> listTrayCrops(
            @PathVariable String factoryId,
            @RequestParam(required = false) LabelQcTrayCropStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(labelQcService.listTrayCrops(factoryId, status, page, size));
    }

    @GetMapping("/platform-candidates")
    @RequirePermission({"system:read_write"})
    @RequireRole({"platform_admin", "super_admin", "developer", "platform_super_admin"})
    public ApiResponse<PageResponse<PlatformCandidateResponse>> listPlatformCandidates(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "false") boolean reviewed,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest servletRequest) {
        trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                labelQcService.listPlatformCandidates(factoryId, reviewed, page, size));
    }

    @PutMapping("/platform-candidates/{candidateId}/review")
    @RequirePermission({"system:read_write"})
    @RequireRole({"platform_admin", "super_admin", "developer", "platform_super_admin"})
    public ApiResponse<PlatformCandidateResponse> reviewPlatformCandidate(
            @PathVariable String factoryId,
            @PathVariable String candidateId,
            @Valid @RequestBody ReviewPlatformCandidateRequest request,
            HttpServletRequest servletRequest) {
        Long reviewerId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "平台诊断结果已保存",
                labelQcService.reviewPlatformCandidate(
                        factoryId, candidateId, reviewerId, request));
    }

    @PutMapping("/tray-crops/{cropId}/review")
    @RequirePermission({"system:read_write"})
    @RequireRole({"platform_admin", "super_admin", "developer", "platform_super_admin"})
    public ApiResponse<TrayCropResponse> reviewTrayCrop(
            @PathVariable String factoryId,
            @PathVariable String cropId,
            @Valid @RequestBody ReviewTrayCropRequest request,
            HttpServletRequest servletRequest) {
        Long reviewerId = trustedUser(factoryId, servletRequest);
        return ApiResponse.success(
                "单盒标签精修已保存",
                labelQcService.reviewTrayCrop(factoryId, cropId, reviewerId, request));
    }

    private Long trustedUser(String pathFactoryId, HttpServletRequest request) {
        Object trustedFactory = request.getAttribute("factoryId");
        if (trustedFactory != null && !pathFactoryId.equals(String.valueOf(trustedFactory))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "factory scope mismatch");
        }
        Object trustedUser = request.getAttribute("userId");
        if (trustedUser instanceof Number number) {
            return number.longValue();
        }
        if (trustedUser != null) {
            try {
                return Long.parseLong(String.valueOf(trustedUser));
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid user identity");
            }
        }
        Long securityUser = SecurityUtils.currentUserId();
        if (securityUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user identity");
        }
        return securityUser;
    }
}
