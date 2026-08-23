package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.BatchConsumptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.exception.BusinessException;

/**
 * 原材料消耗记录控制器
 *
 * 提供原材料消耗记录的查询功能
 * 路径：/api/mobile/{factoryId}/processing/material-consumptions
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-23
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/processing/material-consumptions")
@RequiredArgsConstructor
@RequirePermission({"procurement:price:view"})
@Tag(name = "原材料消耗记录管理", description = "原材料消耗记录查询API")
public class MaterialConsumptionController {

    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    // T4-D4 (issue #533) fix C1: enrich materialTypeName for frontend display
    // (raw consumption Map only had materialTypeId UUID — frontend showed "-")
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final UserRepository userRepository;
    private final BatchConsumptionService batchConsumptionService;
    private final PriceMaskResolver priceMaskResolver;

    /**
     * 1. 获取消耗记录列表（分页）
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): {@code unitPrice} 和 {@code totalCost}
     * via {@link #enrichConsumptionWithMaps} are gated by
     * {@link PriceMaskResolver#shouldMaskPrice(String)}. Callers without
     * {@code procurement:price:view} get a response with those keys omitted (NOT null).
     * The class-level {@code @RequirePermission("procurement:price:view")} already gates
     * access at module-permission scope; this per-field gate is defense-in-depth — survives
     * any future class-annotation removal. Hand-built {@code Map<String, Object>} responses
     * bypass {@code PriceFieldResponseAdvice} reflective field-strip. Any future
     * price-bearing field added here MUST be gated by {@code maskPrice}.
     */
    @GetMapping
    @Operation(summary = "获取消耗记录列表", description = "分页查询原材料消耗记录")
    public ApiResponse<PageResponse<Map<String, Object>>> getConsumptions(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestParam(required = false) @Parameter(description = "原材料批次ID") String batchId,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页数量") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取消耗记录列表: factoryId={}, productionBatchId={}, batchId={}",
                factoryId, productionBatchId, batchId);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        // 使用数据库层分页+过滤，避免全表加载
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<MaterialConsumption> dbPage;
        if (productionBatchId != null && batchId != null) {
            dbPage = consumptionRepository.findByFactoryIdAndProductionBatchIdAndBatchIdPaged(factoryId, productionBatchId, batchId, pageable);
        } else if (productionBatchId != null) {
            dbPage = consumptionRepository.findByFactoryIdAndProductionBatchIdOrderByConsumptionTimeDesc(factoryId, productionBatchId, pageable);
        } else if (batchId != null) {
            dbPage = consumptionRepository.findByFactoryIdAndBatchIdPaged(factoryId, batchId, pageable);
        } else {
            dbPage = consumptionRepository.findByFactoryIdOrderByConsumptionTimeDesc(factoryId, pageable);
        }

        List<Map<String, Object>> enrichedContent = enrichConsumptions(dbPage.getContent(), maskPrice);

        PageResponse<Map<String, Object>> pageResponse = new PageResponse<>();
        pageResponse.setContent(enrichedContent);
        pageResponse.setTotalElements(dbPage.getTotalElements());
        pageResponse.setTotalPages(dbPage.getTotalPages());
        pageResponse.setSize(size);
        pageResponse.setPage(page);
        pageResponse.setCurrentPage(page);
        pageResponse.setFirst(page == 1);
        pageResponse.setLast(page >= dbPage.getTotalPages());

        return ApiResponse.success(pageResponse);
    }

    /**
     * 2. 获取单条消耗记录详情
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): see {@link #getConsumptions} JavaDoc.
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取消耗记录详情", description = "根据ID获取单条消耗记录")
    public ApiResponse<Map<String, Object>> getConsumptionById(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "记录ID") Integer id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取消耗记录详情: factoryId={}, id={}", factoryId, id);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        Optional<MaterialConsumption> optConsumption = consumptionRepository.findById(id);
        if (optConsumption.isEmpty()) {
            throw new BusinessException(404, "消耗记录不存在");
        }

        MaterialConsumption consumption = optConsumption.get();
        if (!factoryId.equals(consumption.getFactoryId())) {
            throw new BusinessException(403, "无权访问该记录");
        }

        Map<String, Object> enriched = enrichConsumption(consumption, maskPrice);
        return ApiResponse.success(enriched);
    }

    /**
     * 3. 获取生产批次的消耗记录
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): see {@link #getConsumptions} JavaDoc.
     *
     * <p>🔴 2026-08-22: 生产/质检角色也放行。批次消耗**是生产数据**, 而类级
     * {@code procurement:price:view} 把它整体锁给了采购 —— 实测 9 个角色
     * (operator / team_leader / quality_inspector / workshop_supervisor / production_clerk /
     *  group_leader / quality_controller / quality_manager / yield_operator)
     * 在生产批次详情和 BOM 达成页上**看得见入口、点进去必 403**。
     *
     * <p>⛔ 放行的**只有这一个端点和 /summary**, ⛔ 不动类级注解 ——
     * 同控制器的 {@code /batch/{id}/cost} 是纯成本端点, 它该继续只给采购。
     * ⚠️ 金额不随之泄露: {@code maskPrice} 逐字段闸仍在, 无
     * {@code procurement:price:view} 者拿到的响应里 {@code unitPrice}/{@code totalCost}
     * 两个键**根本不出现**(不是 null)。本类 JavaDoc 当初就写明这道逐字段闸是
     * "defense-in-depth — survives any future class-annotation removal" —— 正是此刻。
     * ⚠️ 两个 level 都要列: quality_inspector 在 DB 里是 {@code r} 不是 {@code rw}。
     */
    @RequirePermission({"procurement:price:view", "production:read", "production:read_write"})
    @GetMapping("/batch/{productionBatchId}")
    @Operation(summary = "获取生产批次的消耗记录", description = "根据生产批次ID查询所有消耗记录")
    public ApiResponse<List<Map<String, Object>>> getConsumptionsByBatch(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取生产批次消耗记录: factoryId={}, productionBatchId={}", factoryId, productionBatchId);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        List<MaterialConsumption> consumptions = consumptionRepository.findByProductionBatchId(productionBatchId);

        // 过滤只属于该工厂的记录
        consumptions = consumptions.stream()
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .collect(Collectors.toList());

        List<Map<String, Object>> enriched = enrichConsumptions(consumptions, maskPrice);
        return ApiResponse.success(enriched);
    }

    /**
     * 4. 获取原材料批次的消耗记录
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): see {@link #getConsumptions} JavaDoc.
     */
    @GetMapping("/material-batch/{batchId}")
    @Operation(summary = "获取原材料批次的消耗记录", description = "根据原材料批次ID查询所有消耗记录")
    public ApiResponse<List<Map<String, Object>>> getConsumptionsByMaterialBatch(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "原材料批次ID") String batchId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取原材料批次消耗记录: factoryId={}, batchId={}", factoryId, batchId);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        List<MaterialConsumption> consumptions = consumptionRepository.findByFactoryIdAndBatchId(factoryId, batchId);

        List<Map<String, Object>> enriched = enrichConsumptions(consumptions, maskPrice);
        return ApiResponse.success(enriched);
    }

    /**
     * 5. 获取时间范围内的消耗记录
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): see {@link #getConsumptions} JavaDoc.
     */
    @GetMapping("/time-range")
    @Operation(summary = "获取时间范围消耗记录", description = "根据时间范围查询消耗记录")
    public ApiResponse<List<Map<String, Object>>> getConsumptionsByTimeRange(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "开始日期")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @Parameter(description = "结束日期")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取时间范围消耗记录: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.atTime(LocalTime.MAX);

        List<MaterialConsumption> consumptions = consumptionRepository.findByTimeRange(
                factoryId, startTime, endTime);

        List<Map<String, Object>> enriched = enrichConsumptions(consumptions, maskPrice);
        return ApiResponse.success(enriched);
    }

    /**
     * 6. 获取消耗统计
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): {@code totalCost} 和 byMaterialType
     * 的 {@code cost} 字段 are gated by {@link PriceMaskResolver#shouldMaskPrice(String)}.
     * Callers without {@code procurement:price:view} get a response with those keys omitted.
     */
    @GetMapping("/stats")
    @Operation(summary = "获取消耗统计", description = "获取原材料消耗统计数据")
    public ApiResponse<Map<String, Object>> getConsumptionStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestParam(required = false) @Parameter(description = "开始日期")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @Parameter(description = "结束日期")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取消耗统计: factoryId={}, productionBatchId={}", factoryId, productionBatchId);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        List<MaterialConsumption> consumptions;

        if (startDate != null && endDate != null) {
            LocalDateTime startTime = startDate.atStartOfDay();
            LocalDateTime endTime = endDate.atTime(LocalTime.MAX);
            consumptions = consumptionRepository.findByTimeRange(factoryId, startTime, endTime);
        } else {
            consumptions = consumptionRepository.findByFactoryId(factoryId);
        }

        if (productionBatchId != null) {
            consumptions = consumptions.stream()
                    .filter(c -> productionBatchId.equals(c.getProductionBatchId()))
                    .collect(Collectors.toList());
        }

        // 计算统计
        BigDecimal totalQuantity = consumptions.stream()
                .map(MaterialConsumption::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCost = consumptions.stream()
                .map(MaterialConsumption::getTotalCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 按材料类型分组统计（优化：批量查询避免 N+1）
        Set<String> batchIds = consumptions.stream()
                .map(MaterialConsumption::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, MaterialBatch> batchMap = batchIds.isEmpty() ? Collections.emptyMap() :
                materialBatchRepository.findAllById(batchIds).stream()
                        .collect(Collectors.toMap(MaterialBatch::getId, Function.identity()));

        Map<String, Map<String, BigDecimal>> byMaterialType = new HashMap<>();
        for (MaterialConsumption c : consumptions) {
            String batchId = c.getBatchId();
            if (batchId != null) {
                MaterialBatch batch = batchMap.get(batchId);
                String materialTypeName = (batch != null && batch.getMaterialTypeId() != null)
                        ? batch.getMaterialTypeId() : "未知材料";

                byMaterialType.computeIfAbsent(materialTypeName, k -> {
                    Map<String, BigDecimal> m = new HashMap<>();
                    m.put("quantity", BigDecimal.ZERO);
                    m.put("cost", BigDecimal.ZERO);
                    return m;
                });

                Map<String, BigDecimal> stats = byMaterialType.get(materialTypeName);
                if (c.getQuantity() != null) {
                    stats.put("quantity", stats.get("quantity").add(c.getQuantity()));
                }
                if (c.getTotalCost() != null) {
                    stats.put("cost", stats.get("cost").add(c.getTotalCost()));
                }
            }
        }

        List<Map<String, Object>> byMaterialTypeList = byMaterialType.entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("materialTypeName", e.getKey());
                    item.put("quantity", e.getValue().get("quantity"));
                    if (!maskPrice) {
                        item.put("cost", e.getValue().get("cost"));
                    }
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalQuantity", totalQuantity);
        if (!maskPrice) {
            stats.put("totalCost", totalCost);
        }
        stats.put("consumptionCount", consumptions.size());
        stats.put("byMaterialType", byMaterialTypeList);

        return ApiResponse.success(stats);
    }

    /**
     * 7. 获取生产批次的消耗成本汇总
     *
     * <p>RBAC (PR #488 §F2 sister sweep, 2026-05-12): {@code totalCost} is gated by
     * {@link PriceMaskResolver#shouldMaskPrice(String)}. Callers without
     * {@code procurement:price:view} get a response with the {@code totalCost} key omitted.
     */
    @GetMapping("/batch/{productionBatchId}/cost")
    @Operation(summary = "获取批次消耗成本", description = "获取生产批次的原材料消耗成本汇总")
    public ApiResponse<Map<String, Object>> getBatchConsumptionCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取批次消耗成本: factoryId={}, productionBatchId={}", factoryId, productionBatchId);

        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        List<MaterialConsumption> consumptions = consumptionRepository.findByProductionBatchId(productionBatchId);

        // 过滤只属于该工厂的记录
        consumptions = consumptions.stream()
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .collect(Collectors.toList());

        BigDecimal totalCost = consumptions.stream()
                .map(MaterialConsumption::getTotalCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalQuantity = consumptions.stream()
                .map(MaterialConsumption::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        if (!maskPrice) {
            result.put("totalCost", totalCost);
        }
        result.put("totalQuantity", totalQuantity);
        result.put("recordCount", consumptions.size());

        return ApiResponse.success(result);
    }

    /**
     * 8. 差异调整
     */
    @RequireModule("production_plan")
    @PostMapping("/batch/{productionBatchId}/adjust")
    @Operation(summary = "调整物料消耗", description = "调整生产批次中某种物料的实际消耗量")
    public ApiResponse<Void> adjustConsumption(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestBody Map<String, Object> body) {

        String materialTypeId = (String) body.get("materialTypeId");
        Object rawQty = body.get("actualQuantity");
        String reason = (String) body.getOrDefault("reason", "手工调整");

        if (materialTypeId == null || rawQty == null) {
            // R50 BUG-25 fix: 同 BUG-17 anti-pattern, ApiResponse.error → throw exception
            throw new com.cretas.aims.exception.BusinessException(400,
                    "materialTypeId和actualQuantity必填")
                    .withHint("请填写物料类型 ID 和实际消耗量")
                    .withHintTarget("materialTypeId / actualQuantity");
        }
        BigDecimal actualQuantity = new BigDecimal(rawQty.toString());

        log.info("差异调整: factoryId={}, batchId={}, materialType={}, qty={}",
                factoryId, productionBatchId, materialTypeId, actualQuantity);

        batchConsumptionService.adjustConsumption(factoryId, productionBatchId, materialTypeId, actualQuantity, reason);
        return ApiResponse.successMessage("调整成功");
    }

    /**
     * 9. 获取批次消耗汇总（含计划vs实际、BOM达成率）
     *
     * <p>🔴 2026-08-22: 与 {@link #getConsumptionsByBatch} 同批放行给生产/质检角色。
     *
     * <p>⛔ 但这个端点**原本没有价格掩码** —— {@code getConsumptionSummary} 返回的
     * Map 里带 {@code totalPlannedCost} / {@code totalActualCost}, 每个 item 还带
     * {@code unitPrice} / {@code cost}。类级 {@code procurement:price:view} 一直在替它
     * 挡着, 所以缺掩码这件事**从来没暴露过**。放行的同时不补掩码 = 把物料成本
     * 直接泄给操作员。
     * ▎这正是本类 JavaDoc 那句规矩说的:
     *   "Any future price-bearing field added here MUST be gated by maskPrice"。
     *
     * <p>⚠️ 数量/偏差率/达成率**保留**: 它们是这个页面的全部用途, 且达成率是
     * 成本加权的**比率**, 反推不出绝对单价。
     */
    @RequirePermission({"procurement:price:view", "production:read", "production:read_write"})
    @GetMapping("/batch/{productionBatchId}/summary")
    @Operation(summary = "获取批次消耗汇总", description = "获取生产批次的物料消耗明细，含BOM计划量、实际量和达成率")
    public ApiResponse<Map<String, Object>> getBatchConsumptionSummary(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID") Long productionBatchId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        log.info("获取批次消耗汇总: factoryId={}, batchId={}", factoryId, productionBatchId);

        Map<String, Object> summary = batchConsumptionService.getConsumptionSummary(factoryId, productionBatchId);
        if (priceMaskResolver.shouldMaskPrice(authorization)) {
            summary = maskSummaryPrices(summary);
        }
        return ApiResponse.success(summary);
    }

    /**
     * 从批次消耗汇总里剥掉全部金额键。
     *
     * <p>⛔ 就地改传入的 Map 是不行的 —— service 层的返回值可能被复用/缓存,
     * 就地改会把掩码效果泄漏给下一个**有权限**的调用者。⇒ 复制后再剥。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskSummaryPrices(Map<String, Object> summary) {
        Map<String, Object> masked = new LinkedHashMap<>(summary);
        masked.remove("totalPlannedCost");
        masked.remove("totalActualCost");
        Object items = masked.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> safeItems = new ArrayList<>(list.size());
            for (Object it : list) {
                if (it instanceof Map<?, ?> row) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) row);
                    copy.remove("unitPrice");
                    copy.remove("cost");
                    safeItems.add(copy);
                }
            }
            masked.put("items", safeItems);
        }
        return masked;
    }

    // ========== 辅助方法 ==========

    /**
     * 丰富消耗记录列表（添加关联信息）
     * 优化：使用批量查询避免 N+1 问题
     *
     * @param consumptions 待丰富的消耗记录列表
     * @param maskPrice    {@code true} → 输出 Map 中省略 {@code unitPrice}/{@code totalCost} 键
     *                     (PR #488 §F2 sister sweep, 2026-05-12)
     */
    private List<Map<String, Object>> enrichConsumptions(List<MaterialConsumption> consumptions, boolean maskPrice) {
        if (consumptions.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询原材料批次信息
        Set<String> batchIds = consumptions.stream()
                .map(MaterialConsumption::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, MaterialBatch> batchMap = batchIds.isEmpty() ? Collections.emptyMap() :
                materialBatchRepository.findAllById(batchIds).stream()
                        .collect(Collectors.toMap(MaterialBatch::getId, Function.identity()));

        // T4-D4 (issue #533) fix C1: 批量查询原材料类型 → enrich materialTypeName for display.
        // Collect from consumption directly + fall back to batch's materialTypeId (mirrors
        // enrichConsumptionWithMaps line 514-518 ordering).
        Set<String> materialTypeIds = consumptions.stream()
                .map(c -> c.getMaterialTypeId() != null ? c.getMaterialTypeId()
                        : (c.getBatchId() != null && batchMap.get(c.getBatchId()) != null
                                ? batchMap.get(c.getBatchId()).getMaterialTypeId() : null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, RawMaterialType> materialTypeMap = materialTypeIds.isEmpty() ? Collections.emptyMap() :
                rawMaterialTypeRepository.findAllById(materialTypeIds).stream()
                        .collect(Collectors.toMap(RawMaterialType::getId, Function.identity()));

        // 批量查询记录人信息
        Set<Long> userIds = consumptions.stream()
                .map(MaterialConsumption::getRecordedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap() :
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        // 使用预加载的 Map 进行丰富
        return consumptions.stream()
                .map(c -> enrichConsumptionWithMaps(c, batchMap, materialTypeMap, userMap, maskPrice))
                .collect(Collectors.toList());
    }

    /**
     * 丰富单条消耗记录（添加关联信息）
     * 用于单条记录查询场景
     * 注意：单条记录查询场景下，直接查询是可接受的，因为只有 1-2 次查询
     *
     * @param c         待丰富的消耗记录
     * @param maskPrice {@code true} → 输出 Map 中省略 {@code unitPrice}/{@code totalCost} 键
     *                  (PR #488 §F2 sister sweep, 2026-05-12)
     */
    private Map<String, Object> enrichConsumption(MaterialConsumption c, boolean maskPrice) {
        // 对于单条记录，使用 enrichConsumptions 批量方法以保持一致性
        List<Map<String, Object>> result = enrichConsumptions(Collections.singletonList(c), maskPrice);
        return result.isEmpty() ? new HashMap<>() : result.get(0);
    }

    /**
     * 使用预加载的 Map 丰富消耗记录
     *
     * <p>PR #488 §F2 sister sweep (2026-05-12): when {@code maskPrice} is {@code true},
     * {@code unitPrice} and {@code totalCost} keys are omitted entirely (not put-null).
     * Any future price-bearing field added here MUST be gated by {@code maskPrice}.
     */
    private Map<String, Object> enrichConsumptionWithMaps(
            MaterialConsumption c,
            Map<String, MaterialBatch> batchMap,
            Map<String, RawMaterialType> materialTypeMap,
            Map<Long, User> userMap,
            boolean maskPrice) {

        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("factoryId", c.getFactoryId());
        map.put("productionPlanId", c.getProductionPlanId());
        map.put("productionBatchId", c.getProductionBatchId());
        map.put("batchId", c.getBatchId());
        map.put("quantity", c.getQuantity());
        if (!maskPrice) {
            map.put("unitPrice", c.getUnitPrice());
            map.put("totalCost", c.getTotalCost());
        }
        map.put("consumptionTime", c.getConsumptionTime());
        map.put("consumedAt", c.getConsumedAt());
        map.put("recordedBy", c.getRecordedBy());
        map.put("notes", c.getNotes());
        map.put("plannedQuantity", c.getPlannedQuantity());
        map.put("sourceType", c.getSourceType());
        map.put("createdAt", c.getCreatedAt());
        map.put("updatedAt", c.getUpdatedAt());

        // 添加原材料批次信息 (T4-D4 issue #533 fix: 加 unit 从 batch.quantityUnit)
        String resolvedMaterialTypeId = c.getMaterialTypeId();
        if (c.getBatchId() != null) {
            MaterialBatch batch = batchMap.get(c.getBatchId());
            if (batch != null) {
                map.put("batchNumber", batch.getBatchNumber());
                map.put("unit", batch.getQuantityUnit()); // T4-D4 fix I5: was missing, frontend rendered "-"
                if (resolvedMaterialTypeId == null) {
                    resolvedMaterialTypeId = batch.getMaterialTypeId();
                }
            }
        }
        map.put("materialTypeId", resolvedMaterialTypeId);

        // T4-D4 issue #533 fix C1: enrich materialTypeName for frontend display.
        // Previously emitted only materialTypeId (UUID) — frontend's 原料 column rendered "-" for all rows.
        if (resolvedMaterialTypeId != null) {
            RawMaterialType materialType = materialTypeMap.get(resolvedMaterialTypeId);
            if (materialType != null) {
                map.put("materialTypeName", materialType.getName());
                // Fallback unit if batch lookup didn't populate it (e.g., batch missing or quantityUnit null).
                if (!map.containsKey("unit") && materialType.getUnit() != null) {
                    map.put("unit", materialType.getUnit());
                }
            }
        }

        // 添加记录人名称（从预加载的 Map 中获取）
        if (c.getRecordedBy() != null) {
            User user = userMap.get(c.getRecordedBy());
            if (user != null) {
                map.put("recorderName", user.getFullName() != null ? user.getFullName() : user.getUsername());
            }
        }

        return map;
    }
}
