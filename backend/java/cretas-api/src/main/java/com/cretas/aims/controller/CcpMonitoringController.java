package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.service.foodsafety.ccp.CcpMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 缺口 13 CCP 监控: 报工时录 CCP 实测值 / 偏离处理 / 按批次查看与导出。
 *
 * <p>入口: App 工序报工屏 (本道有 CCP 时出现录入栏)、web 逐道录入「正式报工」前的 CCP 弹窗、
 * web「质量管理 → CCP 监控记录」({@code /quality/ccp})。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/ccp")
@RequiredArgsConstructor
@Tag(name = "CCP 监控", description = "工序报工录入关键控制点实测值、偏离处理、批次导出")
public class CcpMonitoringController {

    private final CcpMonitoringService ccpMonitoringService;
    private final HaccpMonitoringRecordRepository recordRepo;

    @Operation(summary = "本工序要录的关键控制点 (含限值、单位)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/step-checkpoints")
    public ApiResponse<List<CcpMonitoringService.StepCheckpoint>> stepCheckpoints(
            @PathVariable String factoryId,
            @RequestParam(required = false) String workProcessId) {
        return ApiResponse.success(ccpMonitoringService.checkpointsForStep(factoryId, workProcessId));
    }

    @Operation(summary = "录入本次报工的 CCP 实测值 (超限自动生成偏离并扣待检)")
    @RequirePermission({"production:read_write", "quality:read_write"})
    @PostMapping("/records")
    public ApiResponse<CcpMonitoringService.RecordResult> record(
            @PathVariable String factoryId,
            @RequestBody CcpMonitoringService.RecordCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        CcpMonitoringService.RecordResult result = ccpMonitoringService.record(factoryId, body, userId, username);
        return ApiResponse.success(result.message(), result);
    }

    @Operation(summary = "批次的全部 CCP 记录 (批号命中 或 同一生产计划)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/records")
    public ApiResponse<List<CcpMonitoringService.RecordView>> listForBatch(
            @PathVariable String factoryId,
            @RequestParam String batchNumber) {
        return ApiResponse.success(ccpMonitoringService.toViews(
                ccpMonitoringService.listForBatch(factoryId, batchNumber)));
    }

    @Operation(summary = "本厂未处理的偏离 (新的在前)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/deviations/open")
    public ApiResponse<List<CcpMonitoringService.RecordView>> openDeviations(@PathVariable String factoryId) {
        List<HaccpMonitoringRecord> rows = recordRepo.findByFactoryIdAndIsDeviationTrue(factoryId).stream()
                .filter(HaccpMonitoringRecord::isOpenDeviation)
                .sorted(Comparator.comparing(HaccpMonitoringRecord::getMonitoringTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(500)
                .toList();
        return ApiResponse.success(ccpMonitoringService.toViews(rows));
    }

    @Operation(summary = "导出批次 CCP 记录 (CSV, Excel 可直接打开)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/records/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String factoryId,
            @RequestParam String batchNumber) {
        byte[] body = ccpMonitoringService.exportBatchCsv(factoryId, batchNumber);
        String filename = "CCP记录-" + batchNumber.trim().replaceAll("[\\\\/:*?\"<>|\\s]", "_") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "处理偏离: 填纠偏措施, 处理后不再挡放行和出货")
    @RequirePermission({"quality:read_write"})
    @PostMapping("/records/{recordId}/handle")
    public ApiResponse<CcpMonitoringService.RecordView> handle(
            @PathVariable String factoryId,
            @PathVariable Long recordId,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        HaccpMonitoringRecord handled = ccpMonitoringService.handleDeviation(factoryId, recordId,
                body == null ? null : body.get("action"), userId, username);
        return ApiResponse.success("偏离已处理", ccpMonitoringService.toViews(List.of(handled)).get(0));
    }
}
