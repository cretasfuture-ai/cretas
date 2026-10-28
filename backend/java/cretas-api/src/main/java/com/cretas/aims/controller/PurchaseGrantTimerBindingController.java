package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.intent.PurchaseGrantTimerExecutionService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/ai-purchase-task-grants/{grantId}/timer")
public class PurchaseGrantTimerBindingController {
    private final PurchaseGrantTimerExecutionService timers;
    @PostMapping public ApiResponse<PurchaseGrantTimerExecutionService.View> bind(@PathVariable String factoryId,
            @PathVariable String grantId,@Valid @RequestBody BindRequest request){
        return ApiResponse.success(timers.bind(factoryId,grantId,request.expectedVersion(),LocalDateTime.ofInstant(request.executeAt(),ZoneId.systemDefault())));
    }
    @GetMapping public ApiResponse<PurchaseGrantTimerExecutionService.View> status(@PathVariable String factoryId,@PathVariable String grantId){
        return ApiResponse.success(timers.status(factoryId,grantId));
    }
    @PostMapping("/reconcile") public ApiResponse<PurchaseGrantTimerExecutionService.View> reconcile(@PathVariable String factoryId,@PathVariable String grantId,
            @Valid @RequestBody ReconcileRequest ignored){return ApiResponse.success(timers.reconcileStatus(factoryId,grantId));}
    public record ReconcileRequest(){
        @JsonAnySetter public void rejectUnknown(String name,Object ignored){throw new IllegalArgumentException("未知字段: "+name);}
    }
    public record BindRequest(@NotNull @Positive Long expectedVersion,@NotNull @Future Instant executeAt){
        @JsonAnySetter public void rejectUnknown(String name,Object ignored){throw new IllegalArgumentException("未知字段: "+name);}
    }
}
