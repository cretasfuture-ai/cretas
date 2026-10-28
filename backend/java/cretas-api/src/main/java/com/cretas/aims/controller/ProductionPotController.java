package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.pot.PotTraceService;
import com.cretas.aims.service.pot.ProductionPotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * S3-16 锅次: 录入 / 更正 / 确认 / 作废 / 按生产批次查看 / 按锅追溯。
 *
 * <p>入口: web「生产批次详情 → 锅次」、web「溯源查询 → 按锅追溯」、逐工序报工随行提交 (见 ProcessSheetPotHook)。
 * 全部按路径 factoryId 过滤 (路径工厂与登录工厂一致由拦截器保证), 别厂的批次 / 锅一律 404。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-pots")
@RequiredArgsConstructor
@Tag(name = "锅次", description = "生产批次内的逐锅记录 (下锅/出锅、出锅量、工艺参数、用料) 与按锅追溯")
public class ProductionPotController {

    private final ProductionPotService potService;
    private final PotTraceService potTraceService;

    @Operation(summary = "某生产批次的全部锅次 + 投入/产出分摊进度")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping
    public ApiResponse<ProductionPotService.BatchPotsView> listForBatch(
            @PathVariable String factoryId,
            @RequestParam Long productionBatchId) {
        return ApiResponse.success(potService.listForBatch(factoryId, productionBatchId));
    }

    @Operation(summary = "多个生产批次的锅次 (溯源页: 一条链上各道工序的锅)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/by-batches")
    public ApiResponse<List<ProductionPotService.PotView>> listForBatches(
            @PathVariable String factoryId,
            @RequestParam String productionBatchIds) {
        List<Long> ids;
        try {
            ids = Arrays.stream(productionBatchIds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::valueOf).distinct().limit(200).toList();
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "生产批次 ID 必须为数字").withHintTarget("productionBatchIds");
        }
        return ApiResponse.success(potService.listForBatches(factoryId, ids));
    }

    @Operation(summary = "录入一锅")
    @RequirePermission({"production:read_write"})
    @PostMapping
    public ApiResponse<ProductionPotService.PotView> record(
            @PathVariable String factoryId,
            @RequestBody ProductionPotService.PotCommand body,
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("锅次已记录", potService.record(factoryId, body, userId));
    }

    @Operation(summary = "更正一锅 (必须填原因, 原值保留在更正记录里)")
    @RequirePermission({"production:read_write"})
    @PutMapping("/{potId}")
    public ApiResponse<ProductionPotService.PotView> correct(
            @PathVariable String factoryId,
            @PathVariable Long potId,
            @RequestBody ProductionPotService.CorrectionCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        return ApiResponse.success("锅次已更正", potService.correct(factoryId, potId, body, userId, username));
    }

    @Operation(summary = "主管确认 (关联不明 / 摄像候选时间 → 确认)")
    @RequirePermission({"production:read_write", "quality:read_write"})
    @PostMapping("/{potId}/confirm")
    public ApiResponse<ProductionPotService.PotView> confirm(
            @PathVariable String factoryId,
            @PathVariable Long potId,
            @RequestBody ProductionPotService.ConfirmCommand body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        return ApiResponse.success("锅次已确认", potService.confirm(factoryId, potId, body, userId, username));
    }

    @Operation(summary = "作废一锅 (不删除, 必须填原因)")
    @RequirePermission({"production:read_write"})
    @PostMapping("/{potId}/void")
    public ApiResponse<ProductionPotService.PotView> voidPot(
            @PathVariable String factoryId,
            @PathVariable Long potId,
            @RequestBody Map<String, String> body,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username) {
        return ApiResponse.success("锅次已作废",
                potService.voidPot(factoryId, potId, body == null ? null : body.get("reason"), userId, username));
    }

    @Operation(summary = "按锅追溯: 哪些锅用了这个原料/在制批, 出到了哪些成品 (只在能证明时按锅收窄)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/trace/consumers/{nodeType}/{nodeId}")
    public ApiResponse<PotTraceService.MaterialPotTrace> consumers(
            @PathVariable String factoryId,
            @PathVariable String nodeType,
            @PathVariable String nodeId) {
        return ApiResponse.success(potTraceService.potsConsuming(factoryId, nodeType, nodeId));
    }

    @Operation(summary = "按锅追溯: 这个成品/在制批来自哪几锅")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/trace/producers/{nodeType}/{nodeId}")
    public ApiResponse<PotTraceService.OutputPotTrace> producers(
            @PathVariable String factoryId,
            @PathVariable String nodeType,
            @PathVariable String nodeId) {
        return ApiResponse.success(potTraceService.potsProducing(factoryId, nodeType, nodeId));
    }

    @Operation(summary = "按锅追溯: 这一锅出到了哪些批次、最终哪些成品")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @GetMapping("/{potId}/trace")
    public ApiResponse<PotTraceService.PotForwardTrace> forward(
            @PathVariable String factoryId,
            @PathVariable Long potId) {
        return ApiResponse.success(potTraceService.forward(factoryId, potId));
    }
}
