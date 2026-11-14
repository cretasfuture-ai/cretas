package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.foodsafety.ProcessTimeMark;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
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
 * 工序间与投料时限 (食品 DAG 缺口 14): 剩余时间、解冻 / 冷藏登记、超时处置。
 * 拦截在投料入口, 不在这里。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/process-time-limits")
@RequiredArgsConstructor
@Tag(name = "工序时限", description = "工序间与投料时限: 状态 / 登记 / 超时处置")
public class ProcessTimeLimitController {

    private final ProcessTimeLimitService service;

    @Operation(summary = "这道工序可选来源的时限状态 (App)")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<List<ProcessTimeLimitService.SourceStatus>> statusForTask(
            @PathVariable String factoryId, @PathVariable Long taskId) {
        return view(service.statusViewForTask(factoryId, taskId));
    }

    /** 归属判不出时仍返回列表 (原料照常), 原因放在 message + severity=WARNING, 页面据此提示。 */
    static ApiResponse<List<ProcessTimeLimitService.SourceStatus>> view(ProcessTimeLimitService.SourceStatusView view) {
        if (view.ownershipNotice() == null) {
            return ApiResponse.success(view.sources());
        }
        ApiResponse<List<ProcessTimeLimitService.SourceStatus>> res =
                ApiResponse.success(view.ownershipNotice(), view.sources());
        res.setSeverity("WARNING");
        return res;
    }

    @Operation(summary = "计划某道工序可选来源的时限状态 (web 逐道录入)")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/plans/{planId}")
    public ApiResponse<List<ProcessTimeLimitService.SourceStatus>> statusForPlan(
            @PathVariable String factoryId, @PathVariable String planId,
            @RequestParam(required = false) Integer processOrder) {
        return view(service.statusViewForPlan(factoryId, planId, processOrder));
    }

    @Operation(summary = "登记开始解冻 / 开始冷藏暂停 (默认取服务端当前时间; 开始解冻可带 startedAt + backfillReason 补登)")
    @RequirePermission({"production:read_write", "quality:read_write"})
    @PostMapping("/marks")
    public ApiResponse<ProcessTimeMark> startMark(
            @PathVariable String factoryId,
            @RequestBody ProcessTimeLimitService.MarkCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        return ApiResponse.success("已登记", service.startMark(factoryId, body, userId, username));
    }

    @Operation(summary = "结束冷藏暂停")
    @RequirePermission({"production:read_write", "quality:read_write"})
    @PostMapping("/marks/{markId}/end")
    public ApiResponse<ProcessTimeMark> endMark(
            @PathVariable String factoryId, @PathVariable Long markId,
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("已结束暂停", service.endMark(factoryId, markId, userId));
    }

    @Operation(summary = "超时待处置清单")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/events")
    public ApiResponse<List<ProcessTimeLimitEvent>> listOpen(@PathVariable String factoryId) {
        return ApiResponse.success(service.listOpen(factoryId));
    }

    @Operation(summary = "超时处置记录 (本厂最近 200 条)")
    @RequirePermission({"quality:read_write", "quality:read", "production:read", "production:read_write"})
    @GetMapping("/events/records")
    public ApiResponse<List<ProcessTimeLimitEvent>> listRecent(@PathVariable String factoryId) {
        return ApiResponse.success(service.listRecent(factoryId));
    }

    @Operation(summary = "复检放行或判不合格 (结论必填)")
    @RequirePermission({"quality:read_write"})
    @PostMapping("/events/{eventId}/decision")
    public ApiResponse<ProcessTimeLimitEvent> decide(
            @PathVariable String factoryId, @PathVariable Long eventId,
            @RequestBody ProcessTimeLimitService.DecisionCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        ProcessTimeLimitEvent saved = service.decide(factoryId, eventId, body, userId, username);
        String msg = ProcessTimeLimitEvent.Status.RELEASED.equals(saved.getStatus()) ? "已复检放行" : "已判定不合格";
        return ApiResponse.success(msg, saved);
    }
}
