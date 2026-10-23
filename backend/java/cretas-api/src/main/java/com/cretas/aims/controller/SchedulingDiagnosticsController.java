package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.scheduling.SchedulingReadinessResponse;
import com.cretas.aims.service.scheduling.SchedulingReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/mobile/{factoryId}/scheduling")
@RequiredArgsConstructor
public class SchedulingDiagnosticsController {
    private final SchedulingReadinessService readinessService;

    @GetMapping("/readiness")
    @RequirePermission({"scheduling:read", "production:read"})
    @RequireModule("scheduling")
    public ApiResponse<SchedulingReadinessResponse> readiness(
            @PathVariable String factoryId,
            @RequestParam(required = false) String productionLineId,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String scheduleId) {
        return ApiResponse.success("排程输入检查完成", readinessService.check(factoryId, productionLineId, batchId, startTime, endTime, scheduleId));
    }
}
