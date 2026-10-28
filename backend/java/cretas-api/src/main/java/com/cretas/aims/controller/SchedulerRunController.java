package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.config.SchedulerRunService;
import com.cretas.aims.service.config.SchedulerRunService.RunView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Run history of a factory's scheduled configs, and the ruling on runs whose outcome is unknown. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/config/v2/scheduler")
@RequiredArgsConstructor
@Tag(name = "定时任务运行记录")
public class SchedulerRunController {

    private final SchedulerRunService service;

    public record ResolveRequest(String resolution, String note) { }

    @GetMapping("/{taskCode}/runs")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "某个定时任务的运行历史")
    public ApiResponse<PageResponse<RunView>> history(@PathVariable String factoryId,
            @PathVariable String taskCode,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.history(factoryId, taskCode, page, size));
    }

    @GetMapping("/{taskCode}/runs/latest")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "某个定时任务最近一次运行结果（含数据快照）；没有运行记录时 data 为空")
    public ApiResponse<RunView> latest(@PathVariable String factoryId, @PathVariable String taskCode) {
        return ApiResponse.success(service.latest(factoryId, taskCode).orElse(null));
    }

    @GetMapping("/runs/{runId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "一次运行的完整结果")
    public ApiResponse<RunView> detail(@PathVariable String factoryId, @PathVariable String runId) {
        return ApiResponse.success(service.detail(factoryId, runId));
    }

    @PostMapping("/runs/{runId}/resolve")
    @Operation(summary = "把结果未知的运行裁定为成功或失败（须填说明，留审计）")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<RunView> resolve(@PathVariable String factoryId, @PathVariable String runId,
            @RequestBody ResolveRequest body) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest()
                .orElseThrow(() -> new BusinessException(401, "无法确认是谁在裁定，请重新登录"));
        return ApiResponse.success("已记录裁定", service.resolve(factoryId, runId,
                body == null ? null : body.resolution(), body == null ? null : body.note(),
                actor.id(), actor.role()));
    }
}
