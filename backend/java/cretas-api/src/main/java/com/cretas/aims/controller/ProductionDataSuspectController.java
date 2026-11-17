package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.datasuspect.DataSuspectService;
import com.cretas.aims.service.yield.WeighingEntryRules;
import com.cretas.aims.service.yield.WeighingPendingTaskService;
import com.cretas.aims.service.yield.YieldReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 迁移 P5 数据疑点 (R30 / R34) 与称重逐笔列表 —— 只读, 不改报工原值, 不进异常。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production")
@Tag(name = "数据疑点", description = "迁移 P5: 称重报工的数据疑点 (只标不改) 与逐笔称重列表")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProductionDataSuspectController {

    private final DataSuspectService dataSuspectService;
    private final WeighingPendingTaskService pendingTaskService;
    private final YieldReportService yieldReportService;

    @RequirePermission({"production:read"})
    @GetMapping("/data-suspects")
    @Operation(summary = "最近 N 天称重报工里的数据疑点 (出成率 ≥100% / 起止相同 / 单位明显不符)")
    public ApiResponse<DataSuspectService.SuspectList> listSuspects(
            @PathVariable String factoryId,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.success(dataSuspectService.listSuspects(
                factoryId, days, LocalDate.now(WeighingEntryRules.FACTORY_ZONE)));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/work-process-tasks/{taskId}/weighings")
    @Operation(summary = "一道工序任务的逐笔称重 (含皮重, 每笔带数据疑点)")
    public ApiResponse<List<DataSuspectService.WeighingEntry>> listTaskWeighings(
            @PathVariable String factoryId,
            @PathVariable Long taskId) {
        return ApiResponse.success(dataSuspectService.listTaskWeighings(factoryId, taskId));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/weigh/pending-tasks")
    @Operation(summary = "待称重任务总览 (执行中且还没有任何称重, 今日计划排前面)")
    public ApiResponse<List<WeighingPendingTaskService.PendingTask>> pendingTasks(@PathVariable String factoryId) {
        return ApiResponse.success(pendingTaskService.list(factoryId, LocalDate.now(WeighingEntryRules.FACTORY_ZONE)));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/weighings/{reportId}/void")
    @Operation(summary = "撤销单笔称重 (原因必填并留痕, 退回半成品过账, 重算合计; 不是整批冲销)")
    public ApiResponse<Map<String, Object>> voidWeighing(
            @PathVariable String factoryId,
            @PathVariable Long reportId,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long operatorId) {
        return ApiResponse.success(yieldReportService.voidWeighing(
                factoryId, reportId, operatorId, body == null ? null : body.get("reason")));
    }
}
