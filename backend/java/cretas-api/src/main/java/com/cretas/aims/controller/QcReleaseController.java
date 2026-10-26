package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.service.quality.release.QcReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质检放行 (缺口 11): 待检清单 / 放行或判不合格 / 放行记录。
 *
 * <p>页面: web-admin「质量管理 → 质检放行」({@code /quality/release})。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/qc-release")
@RequiredArgsConstructor
@Tag(name = "质检放行", description = "需检产品产出待检 → 质检放行 / 不合格")
public class QcReleaseController {

    private final QcReleaseService qcReleaseService;

    @Operation(summary = "待检清单 (半成品 / 在制半成品 / 成品)")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/pending")
    public ApiResponse<List<QcReleaseService.PendingItem>> listPending(@PathVariable String factoryId) {
        return ApiResponse.success(qcReleaseService.listPending(factoryId));
    }

    @Operation(summary = "放行或判不合格 (记录结论、放行人、放行时间)")
    @RequirePermission({"quality:read_write"})
    @PostMapping("/decisions")
    public ApiResponse<QcReleaseRecord> decide(
            @PathVariable String factoryId,
            @RequestBody QcReleaseService.DecisionCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        QcReleaseRecord saved = qcReleaseService.decide(factoryId, body, userId, username);
        String msg = QcReleaseRecord.Decision.RELEASED.equals(saved.getDecision()) ? "已放行" : "已判定不合格";
        return ApiResponse.success(msg, saved);
    }

    @Operation(summary = "放行记录 (可按对象过滤; 不带参数 = 本厂最近 200 条)")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/records")
    public ApiResponse<List<QcReleaseRecord>> listRecords(
            @PathVariable String factoryId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId) {
        return ApiResponse.success(qcReleaseService.listRecords(factoryId, targetType, targetId));
    }
}
