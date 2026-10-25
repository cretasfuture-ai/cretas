package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.util.InventoryExpiry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BatchConsumptionServiceImpl implements BatchConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(BatchConsumptionServiceImpl.class);

    private static final String SOURCE_AUTO_BOM = "AUTO_BOM";
    private static final String SOURCE_ADJUSTMENT = "ADJUSTMENT";

    private final BomExpansionService bomExpansionService;
    private final MaterialBatchService materialBatchService;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final WarehouseResolver warehouseResolver;

    /**
     * RAW_TO_PRODUCTION 血缘边接线点。字段注入 (required=false) 而非构造器参数 ——
     * 本类构造器被单测按位置参数直接 new，加必需依赖会把它们打红；血缘边是审计旁路。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.lineage.LineageEdgeRecorder lineageEdgeRecorder;

    @Override
    // REQUIRES_NEW: 自动扣料失败必须真正"不影响后续流程". 调用方 onBatchCompleted 是 @Transactional,
    // 若本方法用默认 REQUIRED 会加入同一事务, 任一 save 失败(FK/缺料 shortfall 等)就把整个事务标记
    // rollback-only 并污染 Session, 导致调用方后续自动入库 createFinishedGoodsFromBatch flush 失败而
    // 成品静默不创建 (prod E2E 实测). 独立事务隔离扣料失败, 入库在调用方干净事务里照常进行.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoConsumeForBatch(ProductionBatch batch) {
        String factoryId = batch.getFactoryId();
        // recorded_by 是 NOT NULL + FK 到 users; 硬编码 0L 是无效用户, save 会抛 FK 违例并污染
        // Hibernate Session, 导致同事务后续 createFinishedGoodsFromBatch 无法 flush -> 自动入库静默失败
        // (prod E2E 抓到: 凡有 BOM 的成品 plain-complete 都不会自动建成品批次). 用批次主管/创建人兜底.
        Long recordedBy = batch.getSupervisorId() != null ? batch.getSupervisorId()
                : (batch.getCreatedBy() != null ? batch.getCreatedBy() : 1L);
        BigDecimal productionQty = batch.getActualQuantity() != null
                ? batch.getActualQuantity()
                : batch.getPlannedQuantity();

        if (productionQty == null || productionQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("批次无产出数量，跳过自动扣料: batchId={}", batch.getId());
            return;
        }

        // 幂等检查：已有AUTO_BOM记录则跳过
        List<MaterialConsumption> existing = consumptionRepository
                .findByProductionBatchIdAndSourceType(batch.getId(), SOURCE_AUTO_BOM);
        if (!existing.isEmpty()) {
            log.info("批次已执行过自动扣料，跳过: batchId={}, existingRecords={}", batch.getId(), existing.size());
            return;
        }

        // 1. BOM展开
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(
                factoryId, batch.getProductTypeId(), productionQty);
        if (requirements.isEmpty()) {
            log.info("产品无BOM配置，跳过自动扣料: batchId={}, productTypeId={}",
                    batch.getId(), batch.getProductTypeId());
            return;
        }

        // 2. 物料检查 + FEFO分配
        MaterialCheckResult checkResult = bomExpansionService.checkMaterialAvailability(
                factoryId, requirements);

        // 构建物料需求的快速查询map
        Map<String, MaterialRequirement> reqMap = requirements.stream()
                .collect(Collectors.toMap(MaterialRequirement::getMaterialTypeId, r -> r));

        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        // 3. 按分配方案逐批次消耗
        // 按materialTypeId分组allocations
        Map<String, List<MaterialAllocation>> allocationsByType = checkResult.getAllocations().stream()
                .collect(Collectors.groupingBy(MaterialAllocation::getMaterialTypeId));

        for (Map.Entry<String, List<MaterialAllocation>> entry : allocationsByType.entrySet()) {
            String materialTypeId = entry.getKey();
            List<MaterialAllocation> allocations = entry.getValue();
            MaterialRequirement req = reqMap.get(materialTypeId);
            BigDecimal plannedQtyForType = req != null ? req.getRequiredQuantity() : BigDecimal.ZERO;

            for (MaterialAllocation alloc : allocations) {
                try {
                    // 扣减物料批次库存
                    materialBatchService.useBatchQuantity(
                            factoryId, alloc.getMaterialBatchId(), alloc.getAllocatedQuantity());

                    // 获取批次单价
                    BigDecimal unitPrice = getUnitPrice(alloc.getMaterialBatchId(), materialTypeId, factoryId);
                    BigDecimal cost = alloc.getAllocatedQuantity().multiply(unitPrice)
                            .setScale(2, RoundingMode.HALF_UP);

                    // 创建消耗记录
                    MaterialConsumption consumption = new MaterialConsumption();
                    consumption.setFactoryId(factoryId);
                    consumption.setProductionPlanId(batch.getProductionPlanId());
                    consumption.setProductionBatchId(batch.getId());
                    consumption.setBatchId(alloc.getMaterialBatchId());
                    consumption.setMaterialTypeId(materialTypeId);
                    consumption.setQuantity(alloc.getAllocatedQuantity());
                    consumption.setPlannedQuantity(plannedQtyForType);
                    consumption.setUnitPrice(unitPrice);
                    consumption.setTotalCost(cost);
                    consumption.setConsumptionTime(LocalDateTime.now());
                    consumption.setConsumedAt(LocalDateTime.now());
                    consumption.setRecordedBy(recordedBy); // 系统自动 (批次主管/创建人, 不用无效 0L)
                    consumption.setSourceType(SOURCE_AUTO_BOM);
                    consumption.setNotes("BOM自动扣料");

                    consumptionRepository.save(consumption);
                    recordRawLineage(factoryId, alloc.getMaterialBatchId(), batch.getId(),
                            alloc.getAllocatedQuantity(), SOURCE_AUTO_BOM, recordedBy, consumption.getId());
                    totalMaterialCost = totalMaterialCost.add(cost);

                    // 同一物料类型的后续批次，plannedQuantity置0（计划量只在首条记录上）
                    plannedQtyForType = BigDecimal.ZERO;

                } catch (Exception e) {
                    log.error("自动扣料失败: batchId={}, materialBatch={}, error={}",
                            batch.getId(), alloc.getMaterialBatchId(), e.getMessage());
                    // 单个物料批次扣减失败不中断整体流程
                }
            }
        }

        // 4. 库存不足的物料: 只记账不落库 —— 缺料没有可写的 batch_id
        //
        // 🔴 2026-08-19 修复 (prod 实测, 2026-06-26 起 412 次):
        //   原实现写 `consumption.setBatchId("SHORTFALL")` 当缺料标记, 而 material_consumptions.batch_id
        //   是 NOT NULL + FK 到 material_batches(id) (约束 fkpw2b6lau9b8kipm0jrg0mgsvy)。
        //   "SHORTFALL" 不是任何一个批次的 id ⇒ 这条 INSERT **在任何输入下都不可能成功**。
        //
        //   代价不是"少了一条缺料记录", 是**整段自动扣料被回滚**: 本方法是
        //   @Transactional(REQUIRES_NEW), 第 3 步已经 save 成功的那些消耗记录、
        //   useBatchQuantity 扣减的库存、第 5 步的 batch.materialCost 全部随之失效,
        //   而调用方 SupplyChainOrchestrator.onBatchCompleted 把异常吞掉只打一行
        //   "自动扣料失败(不影响后续流程)" ⇒ 用户侧报工/日结显示成功, 静默零扣料。
        //   实测: material_consumptions 表里 source_type='AUTO_BOM' 的行数为 0。
        //
        // ⛔ 为什么不是"把这行改成能写进去"(改 FK / 置 null / 挑个真批次):
        //   缺料分支拿不到任何 allocation (BomExpansionService 是 else-if: 库存不足的物料
        //   **不进 allocations**), 也从不调 useBatchQuantity ⇒ 库存一克没动。
        //   把它落库 = 记一笔**从未发生过的消耗**, 并把这笔钱计进 materialCost ——
        //   那是比丢记录更坏的账。⇒ 缺料只报警, 不记消耗、不计成本。
        //
        //   要保留"计划要多少 / 实际差多少"的台账, 是独立需求, 应该有自己的表
        //   (不复用 material_consumptions 这张"实际消耗"表), 交由 owner 决定。
        for (var shortfall : checkResult.getShortfalls()) {
            log.warn("BOM自动扣料缺料(未记消耗, 未计成本): batchId={}, materialTypeId={}, 名称={}, "
                            + "需={}, 可用={}, 缺={}",
                    batch.getId(), shortfall.getMaterialTypeId(), shortfall.getMaterialTypeName(),
                    shortfall.getRequiredQuantity(), shortfall.getAvailableQuantity(),
                    shortfall.getShortfallQuantity());
        }

        // 5. 更新批次物料成本
        batch.setMaterialCost(totalMaterialCost);
        batch.calculateMetrics();
        productionBatchRepository.save(batch);

        log.info("自动扣料完成: batchId={}, 物料种类={}, 总成本={}",
                batch.getId(), allocationsByType.size() + checkResult.getShortfalls().size(),
                totalMaterialCost);
    }

    @Override
    @Transactional
    public void adjustConsumption(String factoryId, Long batchId, String materialTypeId,
                                  BigDecimal actualQuantity, String reason) {
        // 查找现有消耗记录
        List<MaterialConsumption> existing = consumptionRepository
                .findByProductionBatchIdAndMaterialTypeId(batchId, materialTypeId);

        BigDecimal currentTotal = existing.stream()
                .filter(c -> !"ADJUSTMENT".equals(c.getSourceType()) || c.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(MaterialConsumption::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal delta = actualQuantity.subtract(currentTotal);
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return; // 无差异
        }

        BigDecimal unitPrice = existing.isEmpty()
                ? getMovingAvgPrice(materialTypeId, factoryId)
                : existing.get(0).getUnitPrice();

        ProductionBatch batch = productionBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("生产批次", "id", batchId));
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请切换到该批次所属的工厂后再操作");
        }

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            // D1: warehouse strategy per PR #310 §5 — 报工消耗 WH-WKS fixed (报工只在车间).
            String warehouseId = warehouseResolver.resolveWorkshopId(factoryId);
            List<MaterialBatch> fefoBatches = materialBatchRepository
                    .findAvailableBatchesFEFOByWarehouse(factoryId, materialTypeId, warehouseId);
            BigDecimal remaining = delta;
            LocalDate expiryToday = InventoryExpiry.today();

            for (MaterialBatch mb : fefoBatches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                // 过期批不扣 (到期次日 00:00–02:05 状态还是 AVAILABLE, 且 FEFO 排在最前)
                if (InventoryExpiry.isExpired(mb.getExpireDate(), expiryToday)) continue;

                BigDecimal available = mb.getReceiptQuantity()
                        .subtract(mb.getUsedQuantity() != null ? mb.getUsedQuantity() : BigDecimal.ZERO)
                        .subtract(mb.getReservedQuantity() != null ? mb.getReservedQuantity() : BigDecimal.ZERO);
                BigDecimal alloc = remaining.min(available);

                if (alloc.compareTo(BigDecimal.ZERO) > 0) {
                    materialBatchService.useBatchQuantity(factoryId, mb.getId(), alloc);
                    BigDecimal batchUnitPrice = mb.getUnitPrice() != null ? mb.getUnitPrice() : unitPrice;

                    MaterialConsumption adj = new MaterialConsumption();
                    adj.setFactoryId(factoryId);
                    adj.setProductionPlanId(batch.getProductionPlanId());
                    adj.setProductionBatchId(batchId);
                    adj.setBatchId(mb.getId());
                    adj.setMaterialTypeId(materialTypeId);
                    adj.setQuantity(alloc);
                    adj.setPlannedQuantity(BigDecimal.ZERO);
                    adj.setUnitPrice(batchUnitPrice);
                    adj.setTotalCost(alloc.multiply(batchUnitPrice).setScale(2, RoundingMode.HALF_UP));
                    adj.setConsumptionTime(LocalDateTime.now());
                    adj.setConsumedAt(LocalDateTime.now());
                    adj.setRecordedBy(1L);
                    adj.setSourceType(SOURCE_ADJUSTMENT);
                    adj.setNotes("差异调整(+): " + reason);
                    consumptionRepository.save(adj);
                    // 正向差异调整 = 真的又从某个原料批次扣了料进这个生产批次
                    // (上面刚调过 useBatchQuantity)，所以它是一条真实的 RAW_TO_PRODUCTION 边。
                    // ⚠️ 与之相对，下面那条负数调整是**冲销**，append-only 的边表达不了「减少」，
                    //    recorder 会按 quantity<=0 判 SKIPPED —— 两者都在 PR 描述里登记。
                    recordRawLineage(factoryId, mb.getId(), batchId, alloc, SOURCE_ADJUSTMENT,
                            adj.getRecordedBy(), adj.getId());

                    remaining = remaining.subtract(alloc);
                }
            }
        } else {
            // 退回: 创建负数调整记录
            //
            // 🔴 2026-08-19: 同一个类里 SHORTFALL 那处 (见上方第 4 步) 的**第二处同形**。
            //   原写法是 `existing.isEmpty() ? "ADJUSTMENT" : existing.get(0).getBatchId()` ——
            //   "ADJUSTMENT" 同样不是任何 material_batches.id, 走到就必然 FK 违例。
            //   prod 实测该分支从未触发 (error 流里 `Key (batch_id)=(ADJUSTMENT)` 计数为 0,
            //   同一把尺子量 SHORTFALL 是 412), 因为 existing 为空时 currentTotal=0 ⇒
            //   delta<0 需要调用方传负的 actualQuantity。属**潜伏**同形, 不是在飞事故。
            //
            //   语义上"退回一笔从来没有过的消耗"本身就不成立 ⇒ 显式拒绝, 不再构造不可能落库的行。
            if (existing.isEmpty()) {
                log.warn("差异调整跳过: 没有可退回的既有消耗记录, batchId={}, materialTypeId={}, delta={}, reason={}",
                        batchId, materialTypeId, delta, reason);
                return;
            }
            MaterialConsumption adj = new MaterialConsumption();
            adj.setFactoryId(factoryId);
            adj.setProductionPlanId(batch.getProductionPlanId());
            adj.setProductionBatchId(batchId);
            adj.setBatchId(existing.get(0).getBatchId());
            adj.setMaterialTypeId(materialTypeId);
            adj.setQuantity(delta); // 负数
            adj.setPlannedQuantity(BigDecimal.ZERO);
            adj.setUnitPrice(unitPrice);
            adj.setTotalCost(delta.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
            adj.setConsumptionTime(LocalDateTime.now());
            adj.setConsumedAt(LocalDateTime.now());
            adj.setRecordedBy(1L);
            adj.setSourceType(SOURCE_ADJUSTMENT);
            adj.setNotes("差异调整(-): " + reason);
            consumptionRepository.save(adj);
        }

        // 重算批次成本
        BigDecimal newCost = consumptionRepository.calculateTotalCostByProductionBatch(batchId);
        batch.setMaterialCost(newCost);
        batch.calculateMetrics();
        productionBatchRepository.save(batch);

        log.info("差异调整完成: batchId={}, materialType={}, delta={}, newCost={}",
                batchId, materialTypeId, delta, newCost);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getConsumptionSummary(String factoryId, Long batchId) {
        List<MaterialConsumption> consumptions = consumptionRepository
                .findByProductionBatchIdAndFactoryId(batchId, factoryId);

        // 按物料类型聚合
        Map<String, List<MaterialConsumption>> byType = consumptions.stream()
                .filter(c -> c.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(MaterialConsumption::getMaterialTypeId));

        // 批量查询物料类型名称
        Set<String> typeIds = byType.keySet();
        Map<String, String> typeNameMap = new HashMap<>();
        if (!typeIds.isEmpty()) {
            for (String typeId : typeIds) {
                rawMaterialTypeRepository.findById(typeId)
                        .ifPresent(t -> typeNameMap.put(typeId, t.getName()));
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalPlannedCost = BigDecimal.ZERO;
        BigDecimal totalActualCost = BigDecimal.ZERO;

        for (Map.Entry<String, List<MaterialConsumption>> entry : byType.entrySet()) {
            String typeId = entry.getKey();
            List<MaterialConsumption> typeConsumptions = entry.getValue();

            BigDecimal plannedQty = typeConsumptions.stream()
                    .map(c -> c.getPlannedQuantity() != null ? c.getPlannedQuantity() : BigDecimal.ZERO)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal actualQty = typeConsumptions.stream()
                    .map(MaterialConsumption::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal cost = typeConsumptions.stream()
                    .map(c -> c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Weighted average unit price = totalCost / totalActualQty
            BigDecimal weightedUnitPrice = actualQty.compareTo(BigDecimal.ZERO) > 0
                    ? cost.divide(actualQty, 4, RoundingMode.HALF_UP)
                    : typeConsumptions.stream()
                        .map(MaterialConsumption::getUnitPrice)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(BigDecimal.ZERO);

            // ⚠️ 同一方法里的第二处「算不出来就给个数」: 没有计划量时偏差**没有定义**,
            //   而 0% 会被读成「正好按计划」—— 恰恰是最让人放心的那个值。
            //   ⇒ null。消费端目前只有 RN 的可选类型声明, 没有渲染点(已数)。
            BigDecimal variancePercent = plannedQty.compareTo(BigDecimal.ZERO) > 0
                    ? actualQty.subtract(plannedQty)
                        .divide(plannedQty, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP)
                    : null;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialTypeId", typeId);
            item.put("materialTypeName", typeNameMap.getOrDefault(typeId, typeId));
            item.put("plannedQty", plannedQty);
            item.put("actualQty", actualQty);
            item.put("variancePercent", variancePercent);
            item.put("unitPrice", weightedUnitPrice);
            item.put("cost", cost);
            items.add(item);

            BigDecimal plannedCost = plannedQty.multiply(weightedUnitPrice).setScale(2, RoundingMode.HALF_UP);
            totalPlannedCost = totalPlannedCost.add(plannedCost);
            totalActualCost = totalActualCost.add(cost);
        }

        // 🔴 2026-08-28 逐屏走查: 这里原本在 totalPlannedCost == 0 时返回 100。
        //
        // 实拍(f006 →「生产管理 / BOM达成率分析」): 8 个批次里 **7 个**「计划总量 0 /
        // 实际总量 19.75~152.13」, 达成率一律显示 **100%**, 而顶部四张 KPI 卡
        // (平均达成率 100% / 超耗批次数 0 / 最低达成率 100%)**全部由这些 100 推出来** ——
        // 用户读到的是「8 个批次执行完美、零超耗」, 真相是 7 个批次**根本没有计划数据**。
        //
        // ▎没有计划成本时, 达成率**不是 100%, 是没有定义**。
        // ▎兜底成 100 把「我不知道」翻译成了「满分」, 比翻译成 0 更糟 —— 0 至少会引人去查。
        //
        // ⇒ 返回 null。消费端(web-admin / RN)一律渲染成「—」并说明原因,
        //   参照同仓 SKU毛利率分析那屏的做法:「为避免误导, 明细暂不展示估算数据」。
        BigDecimal achievementRate = null;
        if (totalPlannedCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deviation = totalActualCost.subtract(totalPlannedCost).abs()
                    .divide(totalPlannedCost, 4, RoundingMode.HALF_UP);
            achievementRate = BigDecimal.ONE.subtract(deviation)
                    .max(BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("totalPlannedCost", totalPlannedCost);
        result.put("totalActualCost", totalActualCost);
        result.put("overallAchievementRate", achievementRate);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateBatchMaterialCost(Long batchId) {
        return consumptionRepository.calculateTotalCostByProductionBatch(batchId);
    }

    // ===== 内部方法 =====

    /** BOM 自动扣料 / 正向差异调整的原料→生产批次血缘边写点（本类唯一一处）。 */
    private void recordRawLineage(String factoryId, String materialBatchId, Long productionBatchId,
                                  BigDecimal quantity, String sourceType, Long operatorId,
                                  Integer consumptionId) {
        if (lineageEdgeRecorder == null) {
            return;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("consumptionSourceType", sourceType);
        meta.put("origin", "BATCH_CONSUMPTION");
        lineageEdgeRecorder.recordRawToProduction(factoryId, materialBatchId, productionBatchId,
                quantity, null, operatorId,
                consumptionId == null ? null : "consumption:" + consumptionId, meta);
    }

    private BigDecimal getUnitPrice(String materialBatchId, String materialTypeId, String factoryId) {
        return materialBatchRepository.findById(materialBatchId)
                .filter(b -> b.getUnitPrice() != null)
                .map(MaterialBatch::getUnitPrice)
                .orElseGet(() -> getMovingAvgPrice(materialTypeId, factoryId));
    }

    private BigDecimal getMovingAvgPrice(String materialTypeId, String factoryId) {
        return rawMaterialTypeRepository.findById(materialTypeId)
                .map(t -> {
                    if (t.getMovingAvgPrice() != null) return t.getMovingAvgPrice();
                    if (t.getUnitPrice() != null) return t.getUnitPrice();
                    return BigDecimal.ZERO;
                })
                .orElse(BigDecimal.ZERO);
    }
}
