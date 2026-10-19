package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.TaskMaterialBatchOptionDTO;
import com.cretas.aims.service.production.TaskMaterialBatchPickerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks/{taskId}")
@RequireModule("production_plan")
@Tag(name = "Task material picker")
public class TaskMaterialBatchPickerController {
    private final TaskMaterialBatchPickerService service;

    @GetMapping("/material-options")
    @RequirePermission({"production:read_write", "production:read"})
    @Operation(summary = "获取任务可选原料批次")
    public ApiResponse<List<TaskMaterialBatchOptionDTO>> list(
            @PathVariable @NotBlank String factoryId,
            @PathVariable Long batchId,
            @PathVariable Long taskId) {
        return ApiResponse.success(service.list(factoryId, batchId, taskId));
    }
}
