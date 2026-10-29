package com.cretas.aims.service.impl;

import com.cretas.aims.dto.batch.TeamBatchReportRequest;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.quality.QualityStandardValueResolver;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.WageRecordTriggerService;
import com.cretas.aims.entity.config.QualityCheckItemBinding;
import com.cretas.aims.repository.QualityCheckItemBindingRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.dto.processing.ProcessingStageRecordDTO;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.util.QuantityFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
/**
 * 生产加工服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class ProcessingServiceImpl implements ProcessingService {
    private static final Logger log = LoggerFactory.getLogger(ProcessingServiceImpl.class);

    /**
     * 统计摘要只需要识别明显的存储单位；它不负责把不同单位换算成同一单位。
     * 未知的非空单位仍按原文分组，并同时暴露 unknownUnitCount。
     */
    private static final Set<String> KNOWN_PRODUCTION_UNITS = Set.of(
            "kg", "g", "mg", "t", "ml", "l", "pcs", "pc", "piece", "box", "case", "bag",
            "bottle", "tray", "份", "个", "件", "盒", "箱", "包", "袋", "瓶", "桶", "只");

    /** 储存位置是受控词表 —— 判据只有一份, 见 WarehouseLocationCatalog。
     *  ⚠️ 用字段注入而不是构造器注入: 这几个类在测试里被直接 new 42 处,
     *  改构造器签名会一次打断全部, 与本次改动无关。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.warehouse.WarehouseLocationCatalog locationCatalog;

    /** RAW_TO_PRODUCTION 血缘边接线点。字段注入理由同上（构造器被大量测试直接 new）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.lineage.LineageEdgeRecorder lineageEdgeRecorder;

    private final ProductionBatchRepository productionBatchRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentAlertRepository equipmentAlertRepository;
    private final BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final SystemLogRepository systemLogRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final BatchWorkSessionRepository batchWorkSessionRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final ProductTypeRepository productTypeRepository;
    private final AIAnalysisService aiAnalysisService;
    private final CacheService cacheService;
    private final ProcessingStageRecordService processingStageRecordService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QualityInspectionService qualityInspectionService;
    private final ProductionAlertRepository productionAlertRepository;
    private final ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    private final ProcessSheetRowRepository processSheetRowRepository;
    @Autowired
    private QualityCheckItemBindingRepository qualityCheckItemBindingRepository;
    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    // 来源工序真名解析 (2026-08-02): 暂用 optional 字段注入 —— 该类的大量既有测试仍按
    // 构造器参数列表装配, 直接加参数会成批打断测试; 查不到时保持 null, 不编造名字。
    // 删除条件: 这些测试迁到共享 builder/@Mock 装配后, 将两项改成 final 构造器依赖并移除
    // required=false/null 分支, 让生产缺 bean 在启动期失败而不是静默少字段。
    @Autowired(required = false)
    private com.cretas.aims.repository.ProductWorkProcessRepository productWorkProcessRepository;
    @Autowired(required = false)
    private com.cretas.aims.repository.WorkProcessRepository workProcessRepository;
    // Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产→工资 自动 trigger
    private final WageRecordTriggerService wageRecordTriggerService;
    // 审计 round2: 班组报工补录时效锁 (六扇门"补录只能 T/T-1, 极限 T-2, T-3 锁死"硬规则)。
    // optional/fail-open: 未配置 bean 时不阻塞 (与 YieldReportServiceImpl 同款)。
    @Autowired(required = false)
    private com.cretas.aims.util.BackdateWindowValidator backdateWindowValidator;
    // 工艺保密: 发给大模型的成本数据不带当前账号无权查看的保密产品 (Steve 10-29)。字段注入, 不打断按构造器装配的既有测试;
    // 生产由 Spring 注入真实实例 (取不到范围时抛出 = 不发), 单测直接 new / 构造器装配时用「没有保密产品」的默认实例。
    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope llmCostScope =
            com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();

    /** 生产由 Spring 注入 (必需); 部分模拟 (CALLS_REAL_METHODS) 的单测里字段初始化不执行, 退回同一个默认实例。 */
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope secretScope() {
        return llmCostScope != null ? llmCostScope : com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();
    }

    /**
     * Canvas-Thresholds resolver (Phase A P0-3) — overlays FALLBACK_* with per-factory config.
     * Optional injection so Mockito @InjectMocks tests without resolver still construct.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    /**
     * 将字符串 batchId 安全转换为 Long。
     * production_batches.id 是 bigint，前端传入的 batchId 应为数字字符串。
     * 如果传入了非数字（如 UUID "MB-F001-001" 或 "4b6728e3-..."），
     * 返回友好的错误信息而非原始 NumberFormatException 堆栈。
     */
    private Long parseBatchId(String batchId) {
        try {
            return Long.parseLong(batchId);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无效的生产批次ID: \"" + batchId + "\"，生产批次ID应为数字")
                    .withHint("请刷新批次列表后重新选择").withHintTarget("batchId");
        }
    }

    // ========== 批次管理 ==========
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductionBatch createBatch(String factoryId, ProductionBatch batch) {
        log.info("创建生产批次: factoryId={}, batchNumber={}", factoryId, batch.getBatchNumber());
        // 验证批次号唯一性
        if (productionBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber())) {
            throw new BusinessException(409, "批次号已存在: " + batch.getBatchNumber())
                    .withHint("请使用不同的批次号或刷新列表");
        }

        // ID is auto-generated by database (IDENTITY strategy), no need to set manually
        batch.setFactoryId(factoryId);
        batch.setStatus(ProductionBatchStatus.PLANNED);
        batch.setCreatedAt(LocalDateTime.now());

        // P0-1 产品名绑定 (F006 报工审计修复):
        //   若传了 productTypeId, 从 product_types 解析真实品名设为 productName,
        //   消除"待设置产品名称"占位符 (生产批次/报工界面显示真实品名)。
        //   防呆 (fool-proof 快速失败): productTypeId 传了但查不到 → 抛 400 业务异常,
        //   不静默落占位符 (避免用户错填一个无效的 productTypeId 而无感知)。
        //   仅当用户未显式提供 productName 时才用 productType 的 name 覆盖
        //   (尊重用户传入的自定义品名, 向后兼容)。
        boolean productNameMissing = batch.getProductName() == null || batch.getProductName().isEmpty();
        if (batch.getProductTypeId() != null && !batch.getProductTypeId().isEmpty()) {
            ProductType productType = productTypeRepository
                    .findByIdAndFactoryId(batch.getProductTypeId(), factoryId)
                    .orElseThrow(() -> new BusinessException(400,
                            "产品类型不存在: " + batch.getProductTypeId())
                            .withHint("请刷新产品列表后重新选择产品").withHintTarget("productTypeId"));
            if (productNameMissing) {
                batch.setProductName(productType.getName());
            }
        } else if (productNameMissing) {
            // productTypeId 和 productName 都没传 → 保留原占位逻辑 (向后兼容, 不硬断)
            batch.setProductName("待设置产品名称");
        }
        if (batch.getQuantity() == null) {
            batch.setQuantity(batch.getPlannedQuantity() != null ? batch.getPlannedQuantity() : BigDecimal.ZERO);
        }
        if (batch.getUnit() == null || batch.getUnit().isEmpty()) {
            batch.setUnit("kg");
        }

        return productionBatchRepository.save(batch);
    }
    public ProductionBatch startProduction(String factoryId, String batchId, Integer supervisorId) {
        log.info("开始生产: factoryId={}, batchId={}, supervisorId={}", factoryId, batchId, supervisorId);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.PLANNED != batch.getStatus()) {
            throw new BusinessException(409, "批次状态不允许开始生产: " + batch.getStatus())
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setStartTime(LocalDateTime.now());
        batch.setSupervisorId(supervisorId != null ? supervisorId.longValue() : null);
        ProductionBatch saved = productionBatchRepository.save(batch);
        transitionLinkedMaterialBatchesToWip(factoryId, saved.getProductionPlanId(), saved.getId());
        return saved;
    }
    public ProductionBatch pauseProduction(String factoryId, String batchId, String reason) {
        log.info("暂停生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.IN_PROGRESS != batch.getStatus()) {
            throw new BusinessException(409, "只有进行中的批次可以暂停")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.PAUSED);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n暂停原因: " + reason : "暂停原因: " + reason);
        return productionBatchRepository.save(batch);
    }
    @Override
    public ProductionBatch resumeProduction(String factoryId, String batchId) {
        log.info("恢复生产: factoryId={}, batchId={}", factoryId, batchId);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.PAUSED != batch.getStatus()) {
            throw new BusinessException(409, "只有暂停中的批次可以恢复生产")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n恢复生产: " + LocalDateTime.now() : "恢复生产: " + LocalDateTime.now());
        return productionBatchRepository.save(batch);
    }
    @Transactional(rollbackFor = Exception.class)
    public ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                             BigDecimal goodQuantity, BigDecimal defectQuantity) {
        // 默认重载: 不改批次成品单位 (沿用 batch.unit)
        return completeProduction(factoryId, batchId, actualQuantity, goodQuantity, defectQuantity, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                             BigDecimal goodQuantity, BigDecimal defectQuantity, String finishedUnit) {
        log.info("完成生产: factoryId={}, batchId={}, actualQuantity={}, finishedUnit={}",
                factoryId, batchId, actualQuantity, finishedUnit);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryIdForUpdate(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.IN_PROGRESS != batch.getStatus() &&
            ProductionBatchStatus.PAUSED != batch.getStatus()) {
            throw new BusinessException(409, "批次状态不允许完成生产: " + batch.getStatus())
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        batch.setEndTime(LocalDateTime.now());
        batch.setActualQuantity(actualQuantity);
        batch.setGoodQuantity(goodQuantity);
        batch.setDefectQuantity(defectQuantity);
        // P0-2: 末道产出单位非空时, 成品按"末道产出单位"(如 份/盒)入库, 而非批次原单位 (kg)
        // → createFinishedGoodsFromBatch 的 fg.setUnit(batch.getUnit()) 自动拿到正确单位.
        // 覆盖 unit 前先把原计划单位记到瞬态 plannedUnit, 供 calculateMetrics 检测跨单位:
        // 跨单位 (产出"份" ≠ 原计划"kg") 时 efficiency/unitCost 会被置 null 诚实留空,
        // 不算出 actualQuantity(份)/plannedQuantity(kg) 这类无意义值 (镜像 yield cumulative=null).
        if (finishedUnit != null && !finishedUnit.isBlank()) {
            batch.setPlannedUnit(batch.getUnit());  // 记录原单位 (kg), 须在 setUnit 之前
            batch.setUnit(finishedUnit);
        }
        // 计算指标
        batch.calculateMetrics();
        ProductionBatch saved = productionBatchRepository.save(batch);

        // 释放 WIP 状态 (M-WIP-1): PRODUCING_RESERVED → 按剩余量回到 AVAILABLE / DEPLETED / USED_UP
        releaseLinkedMaterialBatchesFromWip(factoryId, saved.getProductionPlanId(), saved.getId());

        // 发布批次完成事件 → 触发供应链联动（自动创建成品、更新PP、通知SO）
        try {
            applicationEventPublisher.publishEvent(new BatchCompletedEvent(this, saved));
            log.info("已发布BatchCompletedEvent: batchId={}, goodQuantity={}", saved.getId(), saved.getGoodQuantity());
        } catch (Exception e) {
            log.error("发布BatchCompletedEvent失败(不影响主流程): batchId={}", saved.getId(), e);
        }

        return saved;
    }
    public ProductionBatch cancelProduction(String factoryId, String batchId, String reason) {
        log.info("取消生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.COMPLETED == batch.getStatus()) {
            throw new BusinessException(409, "已完成的批次不能取消")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.CANCELLED);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n取消原因: " + reason : "取消原因: " + reason);
        ProductionBatch saved = productionBatchRepository.save(batch);
        // 释放 WIP 状态 (M-WIP-1): PRODUCING_RESERVED → RESERVED (取消时按预留量恢复)
        releaseLinkedMaterialBatchesFromWip(factoryId, saved.getProductionPlanId(), saved.getId());
        return saved;
    }

    /**
     * Sprint 4 Wave 2 M-WIP-1: 进入 WIP 时, 将关联物料批次状态 RESERVED → PRODUCING_RESERVED.
     *
     * <p>关联路径: ProductionBatch.productionPlanId → ProductionPlanBatchUsage.materialBatchId → MaterialBatch.id</p>
     *
     * <p>失败时记 WARN 但不抛 — 不阻塞 startProduction 主流程 (与 BatchCompletedEvent 相同的防御模式).</p>
     */
    private void transitionLinkedMaterialBatchesToWip(String factoryId, String productionPlanId, Long productionBatchId) {
        if (productionPlanId == null || productionPlanId.isBlank()) {
            return;  // 无生产计划关联 (standalone batch), 无 WIP 物料可标记
        }
        try {
            List<ProductionPlanBatchUsage> usages = productionPlanBatchUsageRepository.findByProductionPlanId(productionPlanId);
            int flipped = 0;
            for (ProductionPlanBatchUsage usage : usages) {
                String materialBatchId = usage.getMaterialBatchId();
                if (materialBatchId == null) continue;
                MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(materialBatchId, factoryId).orElse(null);
                if (mb == null) continue;
                if (mb.getStatus() == MaterialBatchStatus.RESERVED || mb.getStatus() == MaterialBatchStatus.AVAILABLE) {
                    mb.setStatus(MaterialBatchStatus.PRODUCING_RESERVED);
                    materialBatchRepository.save(mb);
                    flipped++;
                }
            }
            log.info("[M-WIP-1] WIP 转换完成: productionBatchId={}, productionPlanId={}, flipped={}/{} 物料批次",
                    productionBatchId, productionPlanId, flipped, usages.size());
        } catch (Exception e) {
            log.warn("[M-WIP-1] WIP 转换失败 (不影响主流程): productionBatchId={}, productionPlanId={}, err={}",
                    productionBatchId, productionPlanId, e.getMessage());
        }
    }

    /**
     * Sprint 4 Wave 2 M-WIP-1: 离开 WIP (完成 / 取消) 时, 将物料批次状态从 PRODUCING_RESERVED 释放回正常状态.
     *
     * <p>恢复规则:</p>
     * <ul>
     *   <li>currentQuantity = 0 → DEPLETED (耗尽)</li>
     *   <li>currentQuantity &gt; 0 + reservedQuantity &gt; 0 → RESERVED (仍有预留)</li>
     *   <li>currentQuantity &gt; 0 + reservedQuantity = 0 → AVAILABLE (无预留可用)</li>
     * </ul>
     */
    private void releaseLinkedMaterialBatchesFromWip(String factoryId, String productionPlanId, Long productionBatchId) {
        if (productionPlanId == null || productionPlanId.isBlank()) {
            return;
        }
        try {
            List<ProductionPlanBatchUsage> usages = productionPlanBatchUsageRepository.findByProductionPlanId(productionPlanId);
            int flipped = 0;
            for (ProductionPlanBatchUsage usage : usages) {
                String materialBatchId = usage.getMaterialBatchId();
                if (materialBatchId == null) continue;
                MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(materialBatchId, factoryId).orElse(null);
                if (mb == null) continue;
                if (mb.getStatus() != MaterialBatchStatus.PRODUCING_RESERVED) continue;

                MaterialBatchStatus next;
                BigDecimal current = mb.getCurrentQuantity();
                BigDecimal reserved = mb.getReservedQuantity() != null ? mb.getReservedQuantity() : BigDecimal.ZERO;
                if (current.compareTo(BigDecimal.ZERO) <= 0) {
                    next = MaterialBatchStatus.DEPLETED;
                } else if (reserved.compareTo(BigDecimal.ZERO) > 0) {
                    next = MaterialBatchStatus.RESERVED;
                } else {
                    next = MaterialBatchStatus.AVAILABLE;
                }
                mb.setStatus(next);
                materialBatchRepository.save(mb);
                flipped++;
            }
            log.info("[M-WIP-1] WIP 释放完成: productionBatchId={}, productionPlanId={}, flipped={}/{} 物料批次",
                    productionBatchId, productionPlanId, flipped, usages.size());
        } catch (Exception e) {
            log.warn("[M-WIP-1] WIP 释放失败 (不影响主流程): productionBatchId={}, productionPlanId={}, err={}",
                    productionBatchId, productionPlanId, e.getMessage());
        }
    }

    public ProductionBatch getBatchById(String factoryId, String batchId) {
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        // BUG-2 修复: 填充 batchSourceType (@Transient) 从关联计划的 planSourceType。
        // null-safe: productionPlanId 为 null 或计划已删除时 batchSourceType 保持 null。
        if (batch.getProductionPlanId() != null) {
            productionPlanRepository.findById(batch.getProductionPlanId())
                    .ifPresent(plan -> batch.setBatchSourceType(plan.getPlanSourceType()));
        }
        enrichBatchDisplayFields(batch);
        return batch;
    }

    @Override
    public ProductionBatch getBatchByNumber(String factoryId, String batchNumber) {
        String exactBatchNumber = batchNumber == null ? "" : batchNumber.trim();
        if (exactBatchNumber.isEmpty()) {
            throw new BusinessException(400, "批次号不能为空")
                    .withHint("请输入完整批次号后再查询")
                    .withHintTarget("批次号");
        }
        ProductionBatch batch = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, exactBatchNumber)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + exactBatchNumber)
                        .withHint("请核对批次号，或从待检批次列表选择")
                        .withHintTarget("批次号"));
        enrichBatchDisplayFields(batch);
        return batch;
    }

    /**
     * headed-audit 修复 (2026-07-03): 生产批次列表显示 GUID + 空数量列.
     *
     * <p>根因: 部分批次创建路径 (文员逐道录入 {@code ClerkProcessEntryServiceImpl},
     * AI dataop 工具 {@code ProcessingBatchCreateToolDataOp}) 没有在创建时写入
     * {@code productName}/{@code plannedQuantity}, 导致这些历史行落库即为 null。
     * 与生产计划列表同根因 (sibling GUID-resolution bug, 见 ProductionPlanMapper.toDTO
     * 71-76 行 "Bug #1b" 修复) — 这里同样在读时兜底解析, 不需要数据回填迁移:
     * <ul>
     *   <li>productName 为空 → 按 productTypeId 查 product_types 表回填 (只读, 不落库)</li>
     *   <li>plannedQuantity 为空 → 退回 quantity (NOT NULL, 创建时必填的"初始数量")</li>
     * </ul>
     * 批次对象此时已 detached (repository 方法各自独立事务、已提交关闭), 内存赋值不会
     * 被 Hibernate 脏检查回写数据库, 与 {@link #getBatchById} 已有的
     * {@code batchSourceType} 回填模式一致。
     */
    private void enrichBatchDisplayFields(ProductionBatch batch) {
        if (batch == null) {
            return;
        }
        if ((batch.getProductName() == null || batch.getProductName().isBlank())
                && batch.getProductTypeId() != null) {
            productTypeRepository.findById(batch.getProductTypeId())
                    .ifPresent(pt -> batch.setProductName(pt.getName()));
        }
        if (batch.getPlannedQuantity() == null) {
            batch.setPlannedQuantity(batch.getQuantity());
        }
    }

    private void enrichClerkWipSourceFields(String factoryId, List<ProductionBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return;
        }
        List<Long> clerkWipIds = batches.stream()
                .filter(batch -> batch != null && "CLERK_WIP".equals(batch.getBatchType()))
                .map(ProductionBatch::getId)
                .filter(Objects::nonNull)
                .toList();
        if (clerkWipIds.isEmpty()) {
            return;
        }

        Map<Long, ProcessSheetRow> rowByBatchId = processSheetRowRepository
                .findByFactoryIdAndBatchIdIn(factoryId, clerkWipIds)
                .stream()
                .filter(row -> row.getBatchId() != null)
                .collect(Collectors.toMap(
                        ProcessSheetRow::getBatchId,
                        row -> row,
                        (left, right) -> left.getId() <= right.getId() ? left : right
                ));

        Set<String> planIds = rowByBatchId.values().stream()
                .map(ProcessSheetRow::getPlanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ProductionPlan> plans = planIds.isEmpty()
                ? Collections.emptyList()
                : productionPlanRepository.findAllById(planIds).stream()
                        .filter(plan -> factoryId.equals(plan.getFactoryId()))
                        .toList();
        Map<String, String> planNumberById = plans.stream()
                .collect(Collectors.toMap(ProductionPlan::getId, ProductionPlan::getPlanNumber));
        Map<String, String> productTypeIdByPlanId = plans.stream()
                .filter(plan -> plan.getProductTypeId() != null)
                .collect(Collectors.toMap(ProductionPlan::getId, ProductionPlan::getProductTypeId));

        for (ProductionBatch batch : batches) {
            if (batch == null || !"CLERK_WIP".equals(batch.getBatchType())) {
                continue;
            }
            ProcessSheetRow row = rowByBatchId.get(batch.getId());
            if (row == null) {
                continue;
            }
            batch.setSourcePlanId(row.getPlanId());
            batch.setSourcePlanNumber(planNumberById.get(row.getPlanId()));
            batch.setSourceProcessOrder(row.getProcessOrder());
            batch.setSourceProcessCode(row.getProcessCode());
            batch.setSourceProcessName(resolveSourceProcessName(
                    factoryId, productTypeIdByPlanId.get(row.getPlanId()), row.getProcessOrder()));
        }
    }

    /**
     * 按 (产品, 工序序号) 查这道工序的真实名称。
     *
     * <p>🔴 为什么不直接用 {@code row.getProcessCode()}: 那是内部 archetype 码
     * （xiuyou / chaoshui / shuzhi / qidiao …），只决定录入表格显示哪几列，且是<b>多对一</b>的 ——
     * 前端把匹配不上关键词的工序一律归到 {@code 'chaoshui'}，所以界面上显示的 {@code chaoshui}
     * 多数时候并不是「焯水」，只是「没归到别的类」。2026-08-02 六膳门 prod 实测：批次列表
     * 「来源工序」列显示 {@code 第2道 / chaoshui}，而该工厂 20 个工序名里<b>没有一个</b>叫这个。
     *
     * <p>archetype 映射是单向的（工序名 → 码），反查不出来；真名只能按 processOrder
     * （产品工序链内唯一，见 {@code ProcessSheetRow.processOrder} 的注释）去查。
     *
     * <p>⛔ 查不到返回 null，让前端只显示「第N道」—— 宁可少说，不编一个名字。
     */
    private String resolveSourceProcessName(String factoryId, String productTypeId, Integer processOrder) {
        if (productWorkProcessRepository == null || workProcessRepository == null
                || productTypeId == null || processOrder == null) {
            return null;
        }
        return productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndProcessOrder(factoryId, productTypeId, processOrder)
                .map(com.cretas.aims.entity.ProductWorkProcess::getWorkProcessId)
                .flatMap(workProcessRepository::findById)
                .map(com.cretas.aims.entity.WorkProcess::getProcessName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }

    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, PageRequest pageRequest) {
        if (pageRequest != null) {
            return getBatchPage(factoryId, status, null, false, null, pageRequest);
        }
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            // 将字符串状态转换为枚举
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndStatus(factoryId, statusEnum, pageable);
        } else {
            page = productionBatchRepository.findByFactoryId(factoryId, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId,
                                                    Boolean isTrial, Boolean includeClerkWip,
                                                    PageRequest pageRequest) {
        if (isTrial == null) {
            return getBatchPage(factoryId, status, supervisorId, Boolean.TRUE.equals(includeClerkWip), null, pageRequest);
        }
        return getBatches(factoryId, status, supervisorId, isTrial, pageRequest);
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId,
                                                    Boolean isTrial, Boolean includeClerkWip, String batchNumber,
                                                    PageRequest pageRequest) {
        if (isTrial == null) {
            return getBatchPage(factoryId, status, supervisorId, Boolean.TRUE.equals(includeClerkWip), batchNumber, pageRequest);
        }
        // isTrial (SP10 中报价试制批次下拉) 从没和批次号搜索一起用过, 不新增组合 —— 与旧 6 参
        // 版本一样直接落到不支持 batchNumber 的 isTrial 分支。
        return getBatches(factoryId, status, supervisorId, isTrial, pageRequest);
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, Boolean isTrial, PageRequest pageRequest) {
        // 无试制过滤 → 走原有逻辑 (主管过滤 / 全量)
        if (isTrial == null) {
            return getBatches(factoryId, status, supervisorId, pageRequest);
        }
        // SP10: 试制批次过滤 (中报价下拉). 与主管过滤不叠加 (试制场景无主管语义).
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndStatusAndIsTrial(factoryId, statusEnum, isTrial, pageable);
        } else {
            page = productionBatchRepository.findByFactoryIdAndIsTrial(factoryId, isTrial, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, PageRequest pageRequest) {
        if (supervisorId == null) {
            return getBatches(factoryId, status, pageRequest);
        }
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndSupervisorIdAndStatus(factoryId, supervisorId, statusEnum, pageable);
        } else {
            page = productionBatchRepository.findByFactoryIdAndSupervisorId(factoryId, supervisorId, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    /**
     * 缺陷 50 第二步 (2026-09-02): 加了 batchNumber 的过滤。
     *
     * <p>⚠️ {@code batchNumber} 为空/空白时逐字保留原来 6 个分支的既有 derived-query 调用
     * (下面 else 分支跟改动前一模一样, 零改动) —— 只有真的带了搜索词才会切到
     * {@link ProductionBatchRepository#searchByFactoryIdAndBatchNumber} 那条通用查询,
     * 不影响 /processing/batches (非 clerk-wip) 等既有调用方的行为。
     */
    private PageResponse<ProductionBatch> getBatchPage(String factoryId, String status, Long supervisorId,
                                                       boolean includeClerkWip, String batchNumber,
                                                       PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        String trimmedBatchNumber = (batchNumber != null && !batchNumber.isBlank()) ? batchNumber.trim() : null;
        Page<ProductionBatch> page;
        if (trimmedBatchNumber != null) {
            ProductionBatchStatus statusEnum = (status != null && !status.isEmpty())
                    ? ProductionBatchStatus.valueOf(status.toUpperCase())
                    : null;
            page = productionBatchRepository.searchByFactoryIdAndBatchNumber(
                    factoryId, statusEnum, supervisorId, includeClerkWip, trimmedBatchNumber, pageable);
        } else if (status != null && !status.isEmpty()) {
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            if (supervisorId == null) {
                page = includeClerkWip
                        ? productionBatchRepository.findByFactoryIdAndStatusIncludingClerkWip(factoryId, statusEnum, pageable)
                        : productionBatchRepository.findByFactoryIdAndStatus(factoryId, statusEnum, pageable);
            } else {
                page = includeClerkWip
                        ? productionBatchRepository.findByFactoryIdAndSupervisorIdAndStatusIncludingClerkWip(factoryId, supervisorId, statusEnum, pageable)
                        : productionBatchRepository.findByFactoryIdAndSupervisorIdAndStatus(factoryId, supervisorId, statusEnum, pageable);
            }
        } else if (supervisorId == null) {
            page = includeClerkWip
                    ? productionBatchRepository.findByFactoryIdIncludingClerkWip(factoryId, pageable)
                    : productionBatchRepository.findByFactoryId(factoryId, pageable);
        } else {
            page = includeClerkWip
                    ? productionBatchRepository.findByFactoryIdAndSupervisorIdIncludingClerkWip(factoryId, supervisorId, pageable)
                    : productionBatchRepository.findByFactoryIdAndSupervisorId(factoryId, supervisorId, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        if (includeClerkWip) {
            enrichClerkWipSourceFields(factoryId, page.getContent());
        }
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitTeamBatchReport(String factoryId, TeamBatchReportRequest request) {
        // 验证批次存在且属于该工厂
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(request.getBatchId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在或不属于该工厂"));

        // A-F1/F2 同族: 班组报工同样须批次已开工 (开工→报工 是六扇门明确顺序; 对齐
        // YieldReportServiceImpl.submitReport 守卫)。放行 IN_PROGRESS/PRODUCING/PAUSED;
        // 拦 PLANNED/PLANNING(未开工) + COMPLETED/CANCELLED(终态, 防累加产出到已结算/取消批次)。
        ProductionBatchStatus st = batch.getStatus();
        if (st == ProductionBatchStatus.PLANNED || st == ProductionBatchStatus.PLANNING) {
            throw new BusinessException(409, "批次尚未开始生产，无法报工")
                    .withHint("请先由主管在该批次点击「开始生产」后再报工");
        }
        if (st == ProductionBatchStatus.COMPLETED || st == ProductionBatchStatus.CANCELLED) {
            throw new BusinessException(409, "批次已" + st.getDescription() + "，不可再报工")
                    .withHint("如需调整请走撤回/重开流程");
        }

        // 团队总产出作为主数据源
        int teamOutput = request.getTotalOutput();
        int teamGood = request.getTotalGoodQuantity() != null ? request.getTotalGoodQuantity() : teamOutput;
        int teamDefect = request.getTotalDefectQuantity() != null ? request.getTotalDefectQuantity() : 0;

        // 解析报工时间
        LocalDateTime reportTime = LocalDateTime.now();
        boolean reportTimeProvided = false;
        if (request.getReportTime() != null && !request.getReportTime().isEmpty()) {
            try {
                reportTime = LocalDateTime.parse(request.getReportTime().replace("Z", ""));
                reportTimeProvided = true;
            } catch (Exception e) {
                log.warn("无法解析reportTime: {}, 使用当前时间", request.getReportTime());
            }
        }
        // 审计 round2: 补录时效锁 — 班组报工业务日期不得早于 T-maxDays (对齐 submitReport/recordMaterialInput)。
        // 仅当显式传 reportTime 时校验 (默认 now() 必在窗口内)。
        if (reportTimeProvided && backdateWindowValidator != null) {
            backdateWindowValidator.assertWithinWindow(reportTime.toLocalDate(), "班组报工");
        }

        // 处理可选的个人明细
        int recordedMembers = 0;
        if (request.getMembers() != null && !request.getMembers().isEmpty()) {
            for (TeamBatchReportRequest.MemberReport member : request.getMembers()) {
                if (member.getUserId() == null || member.getUserId() <= 0) {
                    continue;
                }
                int output = member.getOutputQuantity() != null ? member.getOutputQuantity() : 0;
                int good = member.getGoodQuantity() != null ? member.getGoodQuantity() : output;
                int defect = member.getDefectQuantity() != null ? member.getDefectQuantity() : 0;

                BatchWorkSession session = new BatchWorkSession();
                session.setBatchId(request.getBatchId());
                session.setEmployeeId(member.getUserId());
                session.setCheckInTime(reportTime);
                session.setCheckOutTime(reportTime);
                session.setStatus("completed");
                session.setNotes(member.getNotes() != null ? member.getNotes()
                        : String.format("班组报工: 产出%d, 良品%d, 不良品%d", output, good, defect));
                batchWorkSessionRepository.save(session);
                recordedMembers++;

                // Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产报工 → 计件工资自动 trigger
                // 仅当个人产量 > 0 时 trigger (本次班组成员有报产, 才计入计件 efficiency).
                // 失败不阻塞主流程 (per WageRecordTriggerService 内部已 catch-all).
                if (output > 0) {
                    wageRecordTriggerService.recordPieceWage(factoryId, batch, member.getUserId(), output, good);
                }
            }
        }

        // 累加团队总产出到批次（不再用 sum(个人)）
        BigDecimal currentActual = batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO;
        BigDecimal currentGood = batch.getGoodQuantity() != null ? batch.getGoodQuantity() : BigDecimal.ZERO;
        BigDecimal currentDefect = batch.getDefectQuantity() != null ? batch.getDefectQuantity() : BigDecimal.ZERO;

        batch.setActualQuantity(currentActual.add(BigDecimal.valueOf(teamOutput)));
        batch.setGoodQuantity(currentGood.add(BigDecimal.valueOf(teamGood)));
        batch.setDefectQuantity(currentDefect.add(BigDecimal.valueOf(teamDefect)));
        productionBatchRepository.save(batch);

        Map<String, Object> result = new HashMap<>();
        result.put("recordedMembers", recordedMembers);
        result.put("totalOutput", teamOutput);
        result.put("totalGood", teamGood);
        result.put("totalDefect", teamDefect);
        result.put("batchId", request.getBatchId());
        result.put("reportTime", reportTime.toString());
        if (request.getNotes() != null) {
            result.put("notes", request.getNotes());
        }
        return result;
    }

    public List<Map<String, Object>> getBatchTimeline(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        List<Map<String, Object>> timeline = new ArrayList<>();
        // 创建时间点
        Map<String, Object> created = new HashMap<>();
        created.put("time", batch.getCreatedAt());
        created.put("event", "批次创建");
        created.put("status", "PLANNED");
        timeline.add(created);
        // 开始生产时间点
        if (batch.getStartTime() != null) {
            Map<String, Object> started = new HashMap<>();
            started.put("time", batch.getStartTime());
            started.put("event", "开始生产");
            started.put("status", "IN_PROGRESS");
            timeline.add(started);
        }
        // 结束时间点
        if (batch.getEndTime() != null) {
            Map<String, Object> ended = new HashMap<>();
            ended.put("time", batch.getEndTime());
            // 🔴 2026-08-28 视觉验收抓到: 这里原本是 `"COMPLETED".equals(batch.getStatus())`,
            //    而 status 是 @Enumerated 的 ProductionBatchStatus【枚举】, 不是 String
            //    ⇒ String.equals(Enum) 【恒为 false】⇒ **每一个批次的时间线最后一条都写「生产取消」**,
            //      包括已完成的。生产实测: 批次 10823 状态 COMPLETED, 页面时间线写着「生产取消」。
            //    ⚠️ javac 不报警 —— equals(Object) 收任何类型, 所以它能活到今天。
            ended.put("event", ProductionBatchStatus.COMPLETED == batch.getStatus() ? "生产完成" : "生产取消");
            ended.put("status", batch.getStatus());
            timeline.add(ended);
        }
        return timeline;
    }
    public PageResponse<MaterialBatch> getMaterialReceipts(String factoryId, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "purchaseDate")
        );
        Page<MaterialBatch> page = materialBatchRepository.findByFactoryId(factoryId, pageable);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }
    public MaterialBatch updateMaterialReceipt(String factoryId, String batchId, MaterialBatch updates) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次不存在"));
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作此批次")
                    .withHint("当前批次不属于该工厂, 无法操作");
        }
        // 更新允许修改的字段
        if (updates.getStorageLocation() != null) {
            // 受控词表 —— 判据见 WarehouseLocationCatalog。
            batch.setStorageLocation(
                    locationCatalog.requireConfiguredLocation(factoryId, updates.getStorageLocation()));
        }
        if (updates.getQualityCertificate() != null) {
            batch.setQualityCertificate(updates.getQualityCertificate());
        }
        if (updates.getNotes() != null) {
            batch.setNotes(updates.getNotes());
        }
        return materialBatchRepository.save(batch);
    }
    /**
     * 记录原材料消耗 (向后兼容，使用supervisorId或默认值)
     */
    public void recordMaterialConsumption(String factoryId, String productionBatchId,
                                         List<Map<String, Object>> consumptions) {
        recordMaterialConsumption(factoryId, productionBatchId, consumptions, null);
    }

    /**
     * 记录原材料消耗 (带用户ID参数)
     * N+1 修复：合并两个循环，使用缓存 Map 避免重复查询
     */
    public void recordMaterialConsumption(String factoryId, String productionBatchId,
                                         List<Map<String, Object>> consumptions, Long userId) {
        log.info("记录原材料消耗: factoryId={}, productionBatchId={}, userId={}", factoryId, productionBatchId, userId);
        ProductionBatch productionBatch = getBatchById(factoryId, productionBatchId);

        // N+1 修复：使用缓存 Map 避免重复查询
        Map<String, MaterialBatch> batchCache = new HashMap<>();
        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        for (Map<String, Object> consumption : consumptions) {
            String materialBatchId = (String) consumption.get("materialBatchId");
            BigDecimal quantity = new BigDecimal(consumption.get("quantity").toString());

            // 使用 computeIfAbsent 确保每个 materialBatchId 只查询一次
            MaterialBatch materialBatch = batchCache.computeIfAbsent(materialBatchId,
                    id -> materialBatchRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("原材料批次不存在: " + id)));

            // 检查库存
            if (materialBatch.getRemainingQuantity().compareTo(quantity) < 0) {
                throw new BusinessException(409, "原材料库存不足: " + materialBatch.getBatchNumber())
                        .withHint("请减少消耗数量, 或选择其他批次");
            }
            // 更新库存
            // 注意: remainingQuantity 和 currentQuantity 是计算属性，会自动重新计算
            materialBatch.setUsedQuantity(materialBatch.getUsedQuantity().add(quantity));
            materialBatch.setLastUsedAt(LocalDateTime.now());
            // 创建消耗记录
            MaterialConsumption consumptionRecord = new MaterialConsumption();
            // ID使用自动生成策略，不需要手动设置
            consumptionRecord.setFactoryId(factoryId);  // 设置工厂ID (必填字段)
            consumptionRecord.setBatch(materialBatch);
            consumptionRecord.setBatchId(materialBatchId);  // 设置原料批次ID
            // 设置生产计划ID (从生产批次获取，如果没有则保持null，避免FK约束违反)
            if (productionBatch.getProductionPlanId() != null) {
                consumptionRecord.setProductionPlanId(productionBatch.getProductionPlanId());
            }
            // 如果productionPlanId为null，则不设置 (字段允许null)
            consumptionRecord.setProductionBatchId(parseBatchId(productionBatchId));
            consumptionRecord.setQuantity(quantity);
            // 安全获取单价，避免 NPE (如果没有设置单价，默认为 0)
            BigDecimal unitPrice = materialBatch.getUnitPrice() != null ? materialBatch.getUnitPrice() : BigDecimal.ZERO;
            consumptionRecord.setUnitPrice(unitPrice);  // 从原料批次获取单价 (必填字段)
            consumptionRecord.setTotalCost(quantity.multiply(unitPrice));  // 计算总成本 (必填字段)
            consumptionRecord.setConsumptionTime(LocalDateTime.now());  // 设置消耗时间 (必填字段)
            consumptionRecord.setConsumedAt(LocalDateTime.now());
            // 设置记录人: 优先使用传入的userId，其次使用supervisorId，最后使用批次创建者
            Long recordedBy = userId != null ? userId :
                (productionBatch.getSupervisorId() != null ? productionBatch.getSupervisorId() :
                 productionBatch.getCreatedBy());
            consumptionRecord.setRecordedBy(recordedBy);
            // 🔴 2026-08-27: 此前**从不设 sourceType** ⇒ VoucherConsumptionSourcePolicy 对 null
            //    返回 false ⇒ 这条领用不进财务凭证, 成本悄悄少算(静默, 无信号)。
            //
            // ⛔ 这里**不能**照 :1665 那样「无 planId 就记 MANUAL_ISSUE」: 那个三元判据的前提是
            //    「没有计划 ⇒ 没有可归集的产出」, 而本方法**始终**挂在一个 productionBatch 上 ——
            //    产出是这个批次, 归集得上。记成 MANUAL_ISSUE 会把它错误地排除出凭证。
            //    (planId 可空只是因为批次不一定挂计划, 与「是不是生产领用」无关。)
            consumptionRecord.setSourceType("PRODUCTION_ISSUE");
            // 🔒 全局兜底: 增加 usedQuantity 后断言 used+reserved≤receipt, 防超扣 (误伤-proof 后置校验)
            materialBatch.assertConsumptionInvariant();
            materialBatchRepository.save(materialBatch);
            inventoryLowStockEventPublisher.publishIfLowStock(factoryId, materialBatch, "OUT");
            materialConsumptionRepository.save(consumptionRecord);
            recordRawLineage(factoryId, materialBatchId, consumptionRecord.getProductionBatchId(),
                    quantity, materialBatch.getQuantityUnit(), recordedBy, consumptionRecord.getId());

            // 同时计算成本（合并原第二个循环的逻辑）
            BigDecimal cost = quantity.multiply(unitPrice);
            totalMaterialCost = totalMaterialCost.add(cost);
        }

        // 更新生产批次的原材料成本：从本批次、本工厂已持久化的消耗金额重算，
        // 避免重复领料请求或 AUTO_BOM 先后写入时覆盖既有成本。
        productionBatch.setMaterialCost(MaterialConsumptionCostTotals.forBatch(
                materialConsumptionRepository, productionBatch.getId(), factoryId));
        productionBatchRepository.save(productionBatch);
    }

    /** 生产领料的原料→生产批次血缘边写点（本类唯一一处）。 */
    private void recordRawLineage(String factoryId, String materialBatchId, Long productionBatchId,
                                  BigDecimal quantity, String unit, Long operatorId,
                                  Integer consumptionId) {
        if (lineageEdgeRecorder == null) {
            return;
        }
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("consumptionSourceType", "PRODUCTION_ISSUE");
        meta.put("origin", "PROCESSING_SERVICE");
        lineageEdgeRecorder.recordRawToProduction(factoryId, materialBatchId, productionBatchId,
                quantity, unit, operatorId,
                consumptionId == null ? null : "consumption:" + consumptionId, meta);
    }
    // ========== 质量检验 ==========
    /**
     * PASS 阈值 fallback (合格率 >=) — 与 {@link QualityInspection#getQualityGrade()} A 级阈值对齐.
     * Issue #813 (2026-05-17). Per-factory override via ThresholdKeys.PROCESSING_PASS_THRESHOLD.
     */
    private static final BigDecimal FALLBACK_PASS_THRESHOLD = new BigDecimal("95");
    /**
     * FAIL 阈值 fallback (合格率 <) — 与 QualityInspection.getQualityGrade() C/D 分界对齐.
     * Issue #813 (2026-05-17). Per-factory override via ThresholdKeys.PROCESSING_FAIL_THRESHOLD.
     */
    private static final BigDecimal FALLBACK_FAIL_THRESHOLD = new BigDecimal("70");

    private BigDecimal resolvePassThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PASS_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PROCESSING_PASS_THRESHOLD, FALLBACK_PASS_THRESHOLD);
    }

    private BigDecimal resolveFailThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_FAIL_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PROCESSING_FAIL_THRESHOLD, FALLBACK_FAIL_THRESHOLD);
    }

    @Override
    public Map<String, Object> getDynamicQualityForm(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        List<QualityCheckItemBinding> bindings = dynamicBindings(factoryId, batch);
        if (bindings.isEmpty()) {
            throw new BusinessException(409, "该产品未配置启用的质检标准")
                    .withHint("请先为产品配置并启用质检标准").withHintTarget("质检标准");
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("batchId", batch.getId().toString());
        form.put("batchNumber", batch.getBatchNumber());
        form.put("productTypeId", batch.getProductTypeId());
        form.put("productName", batch.getProductName());
        form.put("standardRevision", dynamicRevision(factoryId, batch.getId().toString(), bindings));
        List<Map<String, Object>> items = new ArrayList<>();
        for (QualityCheckItemBinding binding : bindings) {
            var item = binding.getQualityCheckItem();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bindingId", binding.getId());
            row.put("itemId", binding.getQualityCheckItemId());
            row.put("itemVersion", item.getVersion());
            row.put("label", item.getItemName());
            row.put("valueType", item.getValueType());
            row.put("unit", item.getUnit());
            row.put("effectiveStandardValue", QualityStandardValueResolver.inheritBlank(
                    binding.getOverrideStandardValue(), item.getStandardValue()));
            row.put("effectiveMinValue", binding.getOverrideMinValue() != null ? binding.getOverrideMinValue() : item.getMinValue());
            row.put("effectiveMaxValue", binding.getOverrideMaxValue() != null ? binding.getOverrideMaxValue() : item.getMaxValue());
            row.put("effectiveIsRequired", binding.getOverrideIsRequired() != null ? binding.getOverrideIsRequired() : item.getIsRequired());
            items.add(row);
        }
        form.put("items", items);
        return form;
    }

    @Override
    @Transactional
    public Map<String, Object> submitDynamicInspection(String factoryId, String batchId,
            com.cretas.aims.dto.quality.DynamicQualityInspectionRequest request, Long inspectorId) {
        if (inspectorId == null) throw new BusinessException(401, "无法确认当前质检员身份");
        ProductionBatch batch = getBatchById(factoryId, batchId);
        List<QualityCheckItemBinding> bindings = dynamicBindings(factoryId, batch);
        if (bindings.isEmpty()) throw new BusinessException(409, "该产品未配置启用的质检标准")
                .withHint("请先为产品配置并启用质检标准").withHintTarget("质检标准");
        String revision = dynamicRevision(factoryId, batch.getId().toString(), bindings);
        if (request == null || request.getStandardRevision() == null || !revision.equals(request.getStandardRevision())) {
            throw new BusinessException(409, "质检标准已更新，请刷新后重新确认")
                    .withCode("QUALITY_STANDARD_STALE").withHint("刷新质检标准后保留已填写数据").withHintTarget("刷新标准");
        }
        List<com.cretas.aims.dto.quality.DynamicQualityInspectionRequest.Answer> answers = request.getAnswers();
        if (answers == null) throw new BusinessException(400, "质检项目不能为空");
        BigDecimal sampleSize = request.getSampleSize() == null ? BigDecimal.ONE : request.getSampleSize();
        if (sampleSize.compareTo(BigDecimal.ONE) != 0) {
            throw new BusinessException(400, "当前质检表只支持单组抽样，抽样数量必须为 1")
                    .withHint("按一组样本填写全部质检项目").withHintTarget("抽样数量");
        }
        Map<String, QualityCheckItemBinding> byBinding = new HashMap<>();
        for (QualityCheckItemBinding b : bindings) byBinding.put(b.getId(), b);
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> snapshotItems = new ArrayList<>();
        boolean sampleFailed = false;
        for (var answer : answers) {
            if (answer == null || answer.getBindingId() == null || answer.getItemId() == null) {
                throw new BusinessException(400, "质检项目答案不能为空且必须包含项目标识");
            }
            QualityCheckItemBinding b = byBinding.get(answer.getBindingId());
            if (b == null || !b.getQualityCheckItemId().equals(answer.getItemId()))
                throw new BusinessException(400, "质检项目不属于当前产品标准");
            if (!seen.add(b.getId())) throw new BusinessException(400, "质检项目重复提交");
            var item = b.getQualityCheckItem();
            if (answer.getItemVersion() == null || !answer.getItemVersion().equals(item.getVersion()))
                throw new BusinessException(409, "质检项目已更新，请刷新标准").withCode("QUALITY_STANDARD_STALE")
                        .withHint("刷新质检标准后保留已填写数据").withHintTarget("刷新标准");
            String valueType = item.getValueType() == null ? "" : item.getValueType().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("NUMERIC", "RANGE", "TEXT", "BOOLEAN").contains(valueType)) {
                throw new BusinessException(409, "质检项目类型未配置，无法提交")
                        .withCode("QUALITY_VALUE_TYPE_UNKNOWN").withHint("先补齐质检项目类型").withHintTarget("质检标准");
            }
            BigDecimal min = b.getOverrideMinValue() != null ? b.getOverrideMinValue() : item.getMinValue();
            BigDecimal max = b.getOverrideMaxValue() != null ? b.getOverrideMaxValue() : item.getMaxValue();
            String standardValue = QualityStandardValueResolver.inheritBlank(
                    b.getOverrideStandardValue(), item.getStandardValue());
            boolean answered = answer.getValue() != null && !answer.getValue().toString().isBlank();
            boolean itemFailed = false;
            if (!answered) {
                if (Boolean.TRUE.equals(b.getOverrideIsRequired() != null ? b.getOverrideIsRequired() : item.getIsRequired()))
                    throw new BusinessException(400, "必检项目未填写");
            } else if ("NUMERIC".equals(valueType) || "RANGE".equals(valueType)) {
                BigDecimal value;
                try { value = new BigDecimal(answer.getValue().toString().trim()); }
                catch (NumberFormatException e) { throw new BusinessException(400, "数值型质检项目格式不正确"); }
                if (min != null || max != null) {
                    itemFailed = (min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0);
                } else if (standardValue == null || standardValue.isBlank()) {
                    throw new BusinessException(409, "数值型质检项目缺少有效标准")
                            .withCode("QUALITY_STANDARD_INVALID").withHint("补齐范围或标准值").withHintTarget("质检标准");
                } else {
                    try { itemFailed = value.compareTo(new BigDecimal(standardValue.trim())) != 0; }
                    catch (NumberFormatException e) { throw new BusinessException(409, "数值型质检标准格式不正确").withCode("QUALITY_STANDARD_INVALID")
                            .withHint("补齐可解析的数值标准").withHintTarget("质检标准"); }
                }
            } else if ("BOOLEAN".equals(valueType)) {
                if (standardValue == null || standardValue.isBlank()) throw new BusinessException(409, "布尔型质检项目缺少标准值").withCode("QUALITY_STANDARD_INVALID")
                        .withHint("补齐 true 或 false 标准值").withHintTarget("质检标准");
                String actual = answer.getValue().toString().trim().toLowerCase(Locale.ROOT);
                String expected = standardValue.trim().toLowerCase(Locale.ROOT);
                if (!(actual.equals("true") || actual.equals("false")) || !(expected.equals("true") || expected.equals("false")))
                    throw new BusinessException(400, "布尔型质检项目只能填写 true 或 false");
                itemFailed = !actual.equals(expected);
            } else {
                if (standardValue == null || standardValue.isBlank()) throw new BusinessException(409, "文本型质检项目缺少标准值").withCode("QUALITY_STANDARD_INVALID")
                        .withHint("补齐文本标准值").withHintTarget("质检标准");
                itemFailed = !answer.getValue().toString().trim().equals(standardValue.trim());
            }
            sampleFailed |= itemFailed;
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("bindingId", b.getId()); saved.put("itemId", item.getId()); saved.put("itemVersion", item.getVersion());
            saved.put("label", item.getItemName()); saved.put("valueType", valueType); saved.put("unit", item.getUnit());
            saved.put("effectiveStandardValue", standardValue); saved.put("value", answered ? answer.getValue() : null);
            saved.put("minValue", min); saved.put("maxValue", max); saved.put("isRequired", b.getOverrideIsRequired() != null ? b.getOverrideIsRequired() : item.getIsRequired());
            saved.put("status", answered ? (itemFailed ? "FAIL" : "PASS") : "NOT_ANSWERED");
            snapshotItems.add(saved);
        }
        for (QualityCheckItemBinding b : bindings) {
            boolean required = Boolean.TRUE.equals(b.getOverrideIsRequired() != null ? b.getOverrideIsRequired() : b.getQualityCheckItem().getIsRequired());
            if (!seen.contains(b.getId())) {
                var item = b.getQualityCheckItem();
                Map<String, Object> saved = new LinkedHashMap<>();
                saved.put("bindingId", b.getId()); saved.put("itemId", item.getId()); saved.put("itemVersion", item.getVersion());
                saved.put("label", item.getItemName()); saved.put("valueType", item.getValueType()); saved.put("unit", item.getUnit());
                saved.put("effectiveStandardValue", QualityStandardValueResolver.inheritBlank(
                        b.getOverrideStandardValue(), item.getStandardValue()));
                saved.put("value", null); saved.put("minValue", b.getOverrideMinValue() != null ? b.getOverrideMinValue() : item.getMinValue());
                saved.put("maxValue", b.getOverrideMaxValue() != null ? b.getOverrideMaxValue() : item.getMaxValue());
                saved.put("isRequired", required); saved.put("status", "NOT_ANSWERED"); snapshotItems.add(saved);
            }
            if (required && !seen.contains(b.getId())) throw new BusinessException(400, "存在未填写的必检项目");
        }
        BigDecimal failures = sampleFailed ? BigDecimal.ONE : BigDecimal.ZERO;
        BigDecimal passes = sampleSize.subtract(failures);
        QualityInspection inspection = new QualityInspection();
        inspection.setFactoryId(factoryId); inspection.setProductionBatchId(batch.getId()); inspection.setInspectorId(inspectorId);
        inspection.setInspectionDate(LocalDate.now()); inspection.setSampleSize(sampleSize); inspection.setFailCount(failures);
        inspection.setPassCount(passes); inspection.setPassRate(passes.multiply(BigDecimal.valueOf(100)).divide(passes.add(failures).max(BigDecimal.ONE), 2, RoundingMode.HALF_UP));
        inspection.setResult(sampleFailed ? "FAIL" : "PASS"); inspection.setNotes(request.getNotes());
        Map<String, Object> customFields = new LinkedHashMap<>();
        customFields.put("standardBasis", "DYNAMIC_PRODUCT_BINDING"); customFields.put("standardRevision", revision); customFields.put("items", snapshotItems);
        inspection.setCustomFields(customFields);
        QualityInspection savedInspection = qualityInspectionService.createInspection(factoryId, inspection);
        return Map.of("inspection", savedInspection, "standardRevision", revision);
    }

    private List<QualityCheckItemBinding> dynamicBindings(String factoryId, ProductionBatch batch) {
        if (batch == null) return List.of();
        if (qualityCheckItemBindingRepository == null) {
            throw new BusinessException(503, "动态质检标准服务未就绪").withCode("QUALITY_STANDARD_SERVICE_UNAVAILABLE");
        }
        return qualityCheckItemBindingRepository.findByProductTypeIdWithItems(batch.getProductTypeId()).stream()
                .filter(b -> factoryId.equals(b.getFactoryId()) && Boolean.TRUE.equals(b.getEnabled())).toList();
    }

    private String dynamicRevision(String factoryId, String batchId, List<QualityCheckItemBinding> bindings) {
        return factoryId + ":" + batchId + ":" + bindings.stream().map(b -> b.getId() + ":" + b.getQualityCheckItem().getVersion() + ":" + b.getQualityCheckItem().getValueType() + ":" + b.getOverrideMinValue() + ":" + b.getOverrideMaxValue() + ":" + b.getOverrideStandardValue() + ":" + b.getOverrideIsRequired() + ":" + b.getQualityCheckItem().getUnit() + ":" + b.getQualityCheckItem().getStandardValue())
                .sorted().collect(Collectors.joining("|"));
    }

    public Map<String, Object> submitInspection(String factoryId, String batchId, Map<String, Object> inspection) {
        log.info("提交质检记录: factoryId={}, batchId={}", factoryId, batchId);
        BigDecimal sampleSize = new BigDecimal(inspection.get("sampleSize").toString());
        BigDecimal passCount = new BigDecimal(inspection.get("passCount").toString());
        BigDecimal failCount = new BigDecimal(inspection.get("failCount").toString());
        if (sampleSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.cretas.aims.exception.BusinessException("抽样数量必须大于 0");
        }
        if (passCount.compareTo(BigDecimal.ZERO) < 0 || failCount.compareTo(BigDecimal.ZERO) < 0) {
            throw new com.cretas.aims.exception.BusinessException("合格数/不合格数不能为负");
        }
        if (passCount.add(failCount).compareTo(sampleSize) > 0) {
            throw new com.cretas.aims.exception.BusinessException("合格数+不合格数不能超过抽样数量");
        }
        if (passCount.add(failCount).compareTo(sampleSize) < 0) {
            throw new com.cretas.aims.exception.BusinessException("合格数+不合格数必须等于抽样数量，不能留下未分类样本");
        }
        // Issue #813 fix A (2026-05-17): reject result=null. FE form already requires
        // it; this guards API-direct callers (curl, dev console, future mobile app).
        Object resultRaw = inspection.get("result");
        String resultStr = resultRaw == null ? null : resultRaw.toString().trim();
        if (resultStr == null || resultStr.isEmpty()) {
            throw new com.cretas.aims.exception.BusinessException("检验结果 (result) 不能为空, 需为 PASS / FAIL / CONDITIONAL / PENDING 之一");
        }
        // Issue #813 fix B (2026-05-17): reject user-supplied result that contradicts
        // computed passRate. Auditor's nightmare avoided: a 43% defective batch
        // can no longer be marked PASS.
        //   PASS         requires passRate >= 95   (A grade)
        //   FAIL         requires passRate <  70   (D grade)
        //   CONDITIONAL  requires 70 <= passRate < 95 (B/C grade)
        //   PENDING      always allowed (待复检, no verdict yet)
        BigDecimal passRate = passCount
                .multiply(new BigDecimal(100))
                .divide(sampleSize, 2, RoundingMode.HALF_UP);
        validateResultVsPassRate(factoryId, resultStr, passRate);
        // Compatibility is retained for products with no dynamic binding. Once a
        // product has an enabled binding, the old fixed-score endpoint cannot bypass
        // required items. Keep this lookup non-invasive for legacy test callers that
        // predate the batch guard and do not provide a persisted batch fixture.
        try {
            Optional<ProductionBatch> legacyBatch = productionBatchRepository.findById(parseBatchId(batchId))
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()));
            if (legacyBatch.isPresent()) {
                if (qualityCheckItemBindingRepository == null) {
                    throw new BusinessException(503, "动态质检标准服务未就绪").withCode("QUALITY_STANDARD_SERVICE_UNAVAILABLE");
                }
                if (!dynamicBindings(factoryId, legacyBatch.get()).isEmpty()) {
                    throw new BusinessException(409, "该产品已绑定动态质检标准，请使用标准质检表提交")
                            .withCode("QUALITY_STANDARD_REQUIRED").withHint("打开动态质检表后按当前标准填写").withHintTarget("质检表");
                }
            }
        } catch (NumberFormatException ignored) {
            // Existing validation below remains the compatibility boundary.
        }
        QualityInspection qualityInspection = new QualityInspection();
        qualityInspection.setFactoryId(factoryId);
        qualityInspection.setProductionBatchId(parseBatchId(batchId));
        qualityInspection.setInspectorId(inspection.get("inspectorId") != null ?
                Long.valueOf(inspection.get("inspectorId").toString()) : null);
        qualityInspection.setInspectionDate(LocalDate.now());
        qualityInspection.setSampleSize(sampleSize);
        qualityInspection.setPassCount(passCount);
        qualityInspection.setFailCount(failCount);
        qualityInspection.setPassRate(passRate);
        qualityInspection.setResult(resultStr);
        qualityInspection.setNotes((String) inspection.get("notes"));
        // Round 10 Fix Task 4 (R8-α Gap #3 per-module template): forward customFields
        // from the incoming payload to the entity so QualityInspectionServiceImpl's
        // dynamicFieldService hook can persist them into the cf_* columns on
        // quality_inspections. Without this the factory's configured dynamic fields
        // (农残二次检验结果, 温度偏差记录, etc.) would silently drop at the controller
        // boundary even though the frontend sent them.
        @SuppressWarnings("unchecked")
        Map<String, Object> customFieldsFromPayload = (Map<String, Object>) inspection.get("customFields");
        if (customFieldsFromPayload != null && !customFieldsFromPayload.isEmpty()) {
            qualityInspection.setCustomFields(customFieldsFromPayload);
        }
        // 通过 QualityInspectionService 保存，FAIL 时自动创建告警 + 发布事件
        QualityInspection saved = qualityInspectionService.createInspection(factoryId, qualityInspection);
        Map<String, Object> result = new HashMap<>();
        result.put("inspection", saved);
        result.put("passRate", passRate);
        return result;
    }

    /**
     * Issue #813 fix B (2026-05-17) — 校验用户填的 result 与计算 passRate 不冲突.
     *
     * 阈值 (与 {@link QualityInspection#getQualityGrade()} 对齐):
     *   PASS         需 passRate >= 95   (A 级)
     *   FAIL         需 passRate <  70   (D 级)
     *   CONDITIONAL  需 70 <= passRate < 95 (B/C 级)
     *   PENDING      永远允许 (待复检, 无最终结论)
     *
     * 不在白名单的字符串 (大小写不敏感) 抛 BusinessException —
     * 避免诡异值如 result="UNKNOWN" 静默落库.
     */
    private void validateResultVsPassRate(String factoryId, String result, BigDecimal passRate) {
        if (result == null) return;  // 上游已 null-check
        String r = result.toUpperCase(Locale.ROOT);
        // 允许 "PENDING" (待复检) 无视 passRate
        if ("PENDING".equals(r)) return;
        // PASSED / FAILED 是旧式 alias, 与 PASS / FAIL 等价处理
        if ("PASSED".equals(r)) r = "PASS";
        if ("FAILED".equals(r)) r = "FAIL";
        if (!"PASS".equals(r) && !"FAIL".equals(r) && !"CONDITIONAL".equals(r)) {
            throw new com.cretas.aims.exception.BusinessException(
                    "无效的检验结果 [" + result + "], 需为 PASS / FAIL / CONDITIONAL / PENDING 之一");
        }
        BigDecimal passThreshold = resolvePassThreshold(factoryId);
        BigDecimal failThreshold = resolveFailThreshold(factoryId);
        if ("PASS".equals(r) && passRate.compareTo(passThreshold) < 0) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% < " + passThreshold + "% 不能判为 PASS, 请选择 CONDITIONAL 或 FAIL");
        }
        if ("FAIL".equals(r) && passRate.compareTo(failThreshold) >= 0) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% >= " + failThreshold + "% 不能判为 FAIL, 请选择 CONDITIONAL 或 PASS");
        }
        if ("CONDITIONAL".equals(r)
                && (passRate.compareTo(failThreshold) < 0
                    || passRate.compareTo(passThreshold) >= 0)) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% 不在 CONDITIONAL 区间 [" + failThreshold + "%, " + passThreshold + "%), 请选择 PASS 或 FAIL");
        }
    }

    public PageResponse<Map<String, Object>> getInspections(String factoryId, String batchId, PageRequest pageRequest) {
        return getInspections(factoryId, batchId, null, pageRequest);
    }

    /**
     * 🔴 2026-08-28: 加 {@code results} 过滤。
     *
     * <p>此前「不良品处置」屏在传 {@code result=FAIL} / {@code result=CONDITIONAL},
     * 而 controller **没声明**这个参数 —— Spring 静默忽略 ⇒ 两次调用返回同一份全量,
     * 前端 {@code [...failList, ...condList]} 拼接后<b>每条渲染两遍</b>,
     * 且本不该出现的 PASS 行也混了进来。质检主管看到的「待处置」数量因此严重偏大。</p>
     *
     * <p>⛔ 不在前端本地过滤: 那样仍要把全量拉过来, 而且分页会失真
     * (后端按全量分页, 前端过滤后每页条数不定)。</p>
     */
    public PageResponse<Map<String, Object>> getInspections(
            String factoryId, String batchId, java.util.List<String> results, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "inspectionDate")
        );
        boolean filtered = results != null && !results.isEmpty();
        Page<QualityInspection> page;
        if (batchId != null) {
            page = filtered
                    ? qualityInspectionRepository.findByFactoryIdAndProductionBatchIdAndResultIn(
                            factoryId, parseBatchId(batchId), results, pageable)
                    : qualityInspectionRepository.findByFactoryIdAndProductionBatchId(factoryId, parseBatchId(batchId), pageable);
        } else {
            page = filtered
                    ? qualityInspectionRepository.findByFactoryIdAndResultIn(factoryId, results, pageable)
                    : qualityInspectionRepository.findByFactoryId(factoryId, pageable);
        }
        Map<Long, ProductionBatch> batchesById = productionBatchRepository.findAllById(
                        page.getContent().stream()
                                .map(QualityInspection::getProductionBatchId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductionBatch::getId, Function.identity()));
        List<Map<String, Object>> inspectionMaps = page.getContent().stream()
                .map(inspection -> convertInspectionToMap(
                        inspection,
                        batchesById.get(inspection.getProductionBatchId())))
                .collect(Collectors.toList());
        return PageResponse.of(
                inspectionMaps,
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    /**
     * 获取质检详情
     */
    public Map<String, Object> getInspectionById(String factoryId, String inspectionId) {
        QualityInspection inspection = qualityInspectionRepository.findById(inspectionId)
                .filter(i -> factoryId.equals(i.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("QualityInspection", inspectionId));
        ProductionBatch batch = inspection.getProductionBatchId() == null
                ? null
                : productionBatchRepository.findById(inspection.getProductionBatchId()).orElse(null);
        return convertInspectionToMap(inspection, batch);
    }

    public Map<String, Object> getQualityStatistics(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndDateRange(
                        factoryId, startDate, endDate).stream()
                .filter(Objects::nonNull)
                .filter(inspection -> factoryId.equals(inspection.getFactoryId()))
                .filter(inspection -> inspection.getInspectionDate() != null)
                .filter(inspection -> !inspection.getInspectionDate().isBefore(startDate)
                        && !inspection.getInspectionDate().isAfter(endDate))
                .toList();
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalInspections", inspections.size());
        if (!inspections.isEmpty()) {
            // 过滤掉 passRate 为 null 的记录（如 pending 状态）
            List<BigDecimal> validPassRates = inspections.stream()
                    .map(QualityInspection::getPassRate)
                    .filter(rate -> rate != null)
                    .collect(Collectors.toList());

            BigDecimal averagePassRate = BigDecimal.ZERO;
            if (!validPassRates.isEmpty()) {
                averagePassRate = validPassRates.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(validPassRates.size()), 2, RoundingMode.HALF_UP);
            }
            statistics.put("averagePassRate", averagePassRate);
            long passedCount = inspections.stream()
                    .filter(i -> "PASS".equalsIgnoreCase(i.getResult()))
                    .count();
            statistics.put("passedBatches", passedCount);
            statistics.put("failedBatches", inspections.size() - passedCount);
        }
        // APP-CONTRACT-004 (2026-08-02): 上面的 failedBatches 语义是"非 PASS", 把待检和
        // CONDITIONAL 都算进了"未通过", 移动端质检首页要分开显示"待检/通过/未通过", 拿它算不出来。
        // 既有键的语义不动 (web-admin DashboardAdmin.vue / QualityRateWidget 已按老口径读),
        // 另加一个语义明确的 breakdown, 且**无论有没有记录都输出** —— 之前空结果只回 totalInspections,
        // 客户端读到 undefined 与"0 条"分不开。
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("pass", inspections.stream().filter(i -> "PASS".equalsIgnoreCase(i.getResult())).count());
        breakdown.put("fail", inspections.stream().filter(i -> "FAIL".equalsIgnoreCase(i.getResult())).count());
        breakdown.put("conditional",
                inspections.stream().filter(i -> "CONDITIONAL".equalsIgnoreCase(i.getResult())).count());
        breakdown.put("pending", inspections.stream()
                .filter(i -> i.getResult() == null || i.getResult().isBlank())
                .count());
        statistics.put("breakdown", breakdown);
        statistics.put("qualitySummary", buildQualitySummary(inspections, startDate, endDate));
        return statistics;
    }

    /**
     * 质量报表移动端摘要。
     *
     * <p>保留上面的 legacy 字段及其「非 PASS」failedBatches 口径，避免影响 web-admin
     * 消费者；新摘要按完整查询周期统计每一种真实结果，并把空结果、未知结果、缺失数值
     * 与合法的零值分开表达。分页样本不参与这里的统计。
     */
    private Map<String, Object> buildQualitySummary(List<QualityInspection> inspections,
                                                     LocalDate startDate,
                                                     LocalDate endDate) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("PASS", 0L);
        statusCounts.put("FAIL", 0L);
        statusCounts.put("CONDITIONAL", 0L);
        statusCounts.put("PENDING", 0L);
        statusCounts.put("UNKNOWN", 0L);
        statusCounts.put("EMPTY", 0L);

        BigDecimal totalSampleSize = BigDecimal.ZERO;
        BigDecimal totalPassCount = BigDecimal.ZERO;
        BigDecimal totalFailCount = BigDecimal.ZERO;
        long missingSampleSizeCount = 0L;
        long missingPassCount = 0L;
        long missingFailCount = 0L;
        long missingPassRateCount = 0L;
        BigDecimal passRateSum = BigDecimal.ZERO;
        long reportedPassRateCount = 0L;

        for (QualityInspection inspection : inspections) {
            String result = inspection.getResult();
            if (result == null || result.isBlank()) {
                statusCounts.computeIfPresent("EMPTY", (key, value) -> value + 1L);
            } else {
                String normalizedResult = result.trim().toUpperCase(Locale.ROOT);
                String statusKey = switch (normalizedResult) {
                    case "PASS" -> "PASS";
                    case "FAIL" -> "FAIL";
                    case "CONDITIONAL" -> "CONDITIONAL";
                    case "PENDING" -> "PENDING";
                    default -> "UNKNOWN";
                };
                statusCounts.computeIfPresent(statusKey, (key, value) -> value + 1L);
            }

            if (inspection.getSampleSize() == null) {
                missingSampleSizeCount++;
            } else {
                totalSampleSize = totalSampleSize.add(inspection.getSampleSize());
            }
            if (inspection.getPassCount() == null) {
                missingPassCount++;
            } else {
                totalPassCount = totalPassCount.add(inspection.getPassCount());
            }
            if (inspection.getFailCount() == null) {
                missingFailCount++;
            } else {
                totalFailCount = totalFailCount.add(inspection.getFailCount());
            }
            if (inspection.getPassRate() == null) {
                missingPassRateCount++;
            } else {
                passRateSum = passRateSum.add(inspection.getPassRate());
                reportedPassRateCount++;
            }
        }

        long passCount = statusCounts.get("PASS");
        long failCount = statusCounts.get("FAIL");
        long conditionalCount = statusCounts.get("CONDITIONAL");
        long decidedCount = passCount + failCount + conditionalCount;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("startDate", startDate);
        summary.put("endDate", endDate);
        summary.put("totalInspections", (long) inspections.size());
        summary.put("statusCounts", statusCounts);
        summary.put("passedInspections", passCount);
        summary.put("failedInspections", failCount);
        summary.put("conditionalInspections", conditionalCount);
        summary.put("pendingInspections", statusCounts.get("PENDING"));
        summary.put("unknownInspections", statusCounts.get("UNKNOWN"));
        summary.put("emptyResultInspections", statusCounts.get("EMPTY"));
        summary.put("averagePassRate", reportedPassRateCount == 0 ? null
                : passRateSum.divide(BigDecimal.valueOf(reportedPassRateCount), 2, RoundingMode.HALF_UP));
        summary.put("reportedPassRateCount", reportedPassRateCount);
        summary.put("missingPassRateCount", missingPassRateCount);
        summary.put("decisionPassRate", decidedCount == 0 ? null
                : BigDecimal.valueOf(passCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(decidedCount), 2, RoundingMode.HALF_UP));
        summary.put("decidedInspectionCount", decidedCount);
        summary.put("totalSampleSize", totalSampleSize);
        summary.put("totalPassCount", totalPassCount);
        summary.put("totalFailCount", totalFailCount);
        summary.put("missingSampleSizeCount", missingSampleSizeCount);
        summary.put("missingPassCount", missingPassCount);
        summary.put("missingFailCount", missingFailCount);
        return summary;
    }

    /**
     * 按单条质检记录的合格率归入 A/B/C/D 四档, 返回各档数量。
     *
     * <p>APP-CONTRACT-004: 移动端质检分析页一直期望 {@code gradeDistribution}, 但后端从未产出过,
     * 该页因此恒显示"数据格式异常"。质检表里没有等级字段, 唯一的真实依据就是每条记录的
     * {@code pass_rate}; 分档阈值沿用 {@link #getQualityDashboard} 里已在用的同一套
     * (>=95 A / >=90 B / >=80 C / 其余 D), 保证工厂级 qualityGrade 与本分布口径一致。
     * {@code pass_rate} 为空的记录 (待检) 不计入任何一档。
     */
    private Map<String, Object> buildGradeDistribution(List<QualityInspection> inspections) {
        Map<String, Object> distribution = new LinkedHashMap<>();
        long a = 0;
        long b = 0;
        long c = 0;
        long d = 0;
        for (QualityInspection inspection : inspections) {
            BigDecimal rate = inspection.getPassRate();
            if (rate == null) {
                continue;
            }
            if (rate.compareTo(BigDecimal.valueOf(95)) >= 0) {
                a++;
            } else if (rate.compareTo(BigDecimal.valueOf(90)) >= 0) {
                b++;
            } else if (rate.compareTo(BigDecimal.valueOf(80)) >= 0) {
                c++;
            } else {
                d++;
            }
        }
        distribution.put("A", a);
        distribution.put("B", b);
        distribution.put("C", c);
        distribution.put("D", d);
        return distribution;
    }
    public List<Map<String, Object>> getQualityTrends(String factoryId, Integer days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndDateRange(
                factoryId, startDate, endDate);
        Map<LocalDate, List<QualityInspection>> groupedByDate = inspections.stream()
                .collect(Collectors.groupingBy(QualityInspection::getInspectionDate));
        List<Map<String, Object>> trends = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> dayTrend = new HashMap<>();
            dayTrend.put("date", date);
            List<QualityInspection> dayInspections = groupedByDate.getOrDefault(date, new ArrayList<>());
            if (!dayInspections.isEmpty()) {
                // 过滤掉 passRate 为 null 的记录
                List<BigDecimal> validRates = dayInspections.stream()
                        .map(QualityInspection::getPassRate)
                        .filter(rate -> rate != null)
                        .collect(Collectors.toList());

                BigDecimal avgPassRate = BigDecimal.ZERO;
                if (!validRates.isEmpty()) {
                    avgPassRate = validRates.stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(new BigDecimal(validRates.size()), 2, RoundingMode.HALF_UP);
                }
                dayTrend.put("passRate", avgPassRate);
                dayTrend.put("inspectionCount", dayInspections.size());
            } else {
                dayTrend.put("passRate", BigDecimal.ZERO);
                dayTrend.put("inspectionCount", 0);
            }
            trends.add(dayTrend);
        }
        return trends;
    }
    // ========== 设备监控 ==========
    public void recordEquipmentUsage(String factoryId, String batchId, Long equipmentId,
                                    LocalDate startTime, LocalDate endTime) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage
        BatchEquipmentUsage usage = new BatchEquipmentUsage();
        usage.setEquipmentId(equipmentId);
        usage.setBatchId(parseBatchId(batchId));  // 转换为Long类型
        usage.setStartTime(startTime.atStartOfDay());
        usage.setEndTime(endTime.atTime(23, 59, 59));
        long hours = ChronoUnit.HOURS.between(usage.getStartTime(), usage.getEndTime());
        usage.setUsageHours(new BigDecimal(hours));
        // 使用设备的实际小时成本，如果没有则使用默认值50元/小时
        BigDecimal hourlyCost = equipment.getHourlyCost() != null ? equipment.getHourlyCost() : new BigDecimal("50");
        usage.setEquipmentCost(new BigDecimal(hours).multiply(hourlyCost));
        batchEquipmentUsageRepository.save(usage);
        // 更新设备使用时长
        equipment.setTotalRunningHours(
                (equipment.getTotalRunningHours() != null ? equipment.getTotalRunningHours() : 0) + (int) hours
        );
        equipment.setLastMaintenanceDate(LocalDate.now());
        equipmentRepository.save(equipment);
    }
    public List<Map<String, Object>> getEquipmentMonitoring(String factoryId) {
        List<FactoryEquipment> equipments = equipmentRepository.findByFactoryId(factoryId,
                org.springframework.data.domain.PageRequest.of(0, 1000)).getContent();
        return equipments.stream().map(equipment -> {
            Map<String, Object> monitoring = new HashMap<>();
            monitoring.put("equipmentId", equipment.getId());
            monitoring.put("name", equipment.getEquipmentName());
            monitoring.put("status", equipment.getStatus());
            monitoring.put("totalOperatingHours", equipment.getTotalRunningHours());
            monitoring.put("lastMaintenanceDate", equipment.getLastMaintenanceDate());
            // 计算利用率 - 使用 BatchEquipmentUsage
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            List<BatchEquipmentUsage> recentUsages = batchEquipmentUsageRepository.findByEquipmentIdAndStartTimeAfter(
                    equipment.getId(), weekAgo);
            BigDecimal totalUsageHours = recentUsages.stream()
                    .map(BatchEquipmentUsage::getUsageHours)
                    .filter(h -> h != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double utilizationRate = (totalUsageHours.doubleValue() / 168.0) * 100; // 168 = 7天 * 24小时
            monitoring.put("weeklyUtilizationRate", utilizationRate);
            return monitoring;
        }).collect(Collectors.toList());
    }
    public Map<String, Object> getEquipmentMetrics(String factoryId, Long equipmentId, Integer days) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByEquipmentIdAndStartTimeAfter(
                equipmentId, startDate);
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("equipment", equipment);
        BigDecimal totalUsageHours = usages.stream()
                .map(BatchEquipmentUsage::getUsageHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        metrics.put("totalUsageHours", totalUsageHours);
        metrics.put("usageCount", usages.size());
        BigDecimal avgUsageHours = usages.isEmpty() ? BigDecimal.ZERO :
                totalUsageHours.divide(new BigDecimal(usages.size()), 2, RoundingMode.HALF_UP);
        metrics.put("averageUsageHours", avgUsageHours);
        return metrics;
    }
    public void recordEquipmentMaintenance(String factoryId, Long equipmentId, Map<String, Object> maintenance) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        equipment.setLastMaintenanceDate(LocalDate.now());
        equipment.setStatus("running");
        equipment.setNotes((String) maintenance.get("notes"));
        equipmentRepository.save(equipment);
    }
    // ========== 成本分析 ==========
    public Map<String, Object> getBatchCostAnalysis(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> analysis = new HashMap<>();

        // 添加batch基本信息（作为Map而不是对象）
        Map<String, Object> batchInfo = new HashMap<>();
        batchInfo.put("batchNumber", batch.getBatchNumber());
        batchInfo.put("productName", batch.getProductName());
        batchInfo.put("actualQuantity", batch.getActualQuantity());
        batchInfo.put("goodQuantity", batch.getGoodQuantity());
        batchInfo.put("defectQuantity", batch.getDefectQuantity());
        batchInfo.put("yieldRate", batch.getYieldRate());
        batchInfo.put("startTime", batch.getStartTime());
        batchInfo.put("endTime", batch.getEndTime());
        analysis.put("batch", batchInfo);

        analysis.put("materialCost", batch.getMaterialCost());
        analysis.put("laborCost", batch.getLaborCost());
        analysis.put("equipmentCost", batch.getEquipmentCost());
        analysis.put("otherCost", batch.getOtherCost());
        analysis.put("totalCost", batch.getTotalCost());
        analysis.put("unitCost", batch.getUnitCost());
        // 成本构成比例 (null 安全处理)
        if (batch.getTotalCost() != null && batch.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalCost = batch.getTotalCost();
            BigDecimal materialCost = batch.getMaterialCost() != null ? batch.getMaterialCost() : BigDecimal.ZERO;
            BigDecimal laborCost = batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO;
            BigDecimal equipmentCost = batch.getEquipmentCost() != null ? batch.getEquipmentCost() : BigDecimal.ZERO;
            BigDecimal otherCost = batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO;

            analysis.put("materialCostRatio",
                    materialCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("laborCostRatio",
                    laborCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("equipmentCostRatio",
                    equipmentCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("otherCostRatio",
                    otherCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
        }
        return analysis;
    }

    /**
     * 获取增强的批次成本分析（包含完整业务链数据）
     * 整合原材料、设备、人工、质检等全维度信息
     */
    public Map<String, Object> getEnhancedBatchCostAnalysis(String factoryId, String batchId) {
        log.info("获取增强的批次成本分析: factoryId={}, batchId={}", factoryId, batchId);

        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> analysis = new HashMap<>();

        // ========== 1. 基本信息 ==========
        Map<String, Object> batchInfo = new HashMap<>();
        batchInfo.put("batchNumber", batch.getBatchNumber());
        batchInfo.put("productName", batch.getProductName());
        batchInfo.put("plannedQuantity", batch.getPlannedQuantity());
        batchInfo.put("actualQuantity", batch.getActualQuantity());
        batchInfo.put("goodQuantity", batch.getGoodQuantity());
        batchInfo.put("defectQuantity", batch.getDefectQuantity());
        batchInfo.put("yieldRate", batch.getYieldRate());
        batchInfo.put("efficiency", batch.getEfficiency());
        batchInfo.put("status", batch.getStatus());
        batchInfo.put("startTime", batch.getStartTime());
        batchInfo.put("endTime", batch.getEndTime());

        // 计算生产时长
        if (batch.getStartTime() != null && batch.getEndTime() != null) {
            long hours = ChronoUnit.HOURS.between(batch.getStartTime(), batch.getEndTime());
            batchInfo.put("productionHours", hours);
        }
        analysis.put("batchInfo", batchInfo);

        // ========== 2. 生产计划对比 ==========
        if (batch.getProductionPlanId() != null) {
            productionPlanRepository.findById(batch.getProductionPlanId()).ifPresent(plan -> {
                Map<String, Object> planComparison = new HashMap<>();
                planComparison.put("planId", plan.getId());
                planComparison.put("planNumber", plan.getPlanNumber());
                // planComparison.put("plannedDate", plan.getPlannedDate());  // 暂时注释 - 数据库表中没有此字段
                planComparison.put("plannedQuantity", plan.getPlannedQuantity());
                planComparison.put("actualQuantity", batch.getActualQuantity());

                if (plan.getPlannedQuantity() != null
                        && plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) > 0
                        && batch.getActualQuantity() != null) {
                    BigDecimal completionRate = batch.getActualQuantity()
                        .divide(plan.getPlannedQuantity(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                    planComparison.put("completionRate", completionRate);
                }

                planComparison.put("planStartTime", plan.getStartTime());
                planComparison.put("planEndTime", plan.getEndTime());
                planComparison.put("actualStartTime", batch.getStartTime());
                planComparison.put("actualEndTime", batch.getEndTime());
                planComparison.put("status", plan.getStatus());

                analysis.put("productionPlanComparison", planComparison);
            });
        }

        // ========== 3. 原材料消耗详情 ==========
        Long batchIdLong = parseBatchId(batchId);
        List<MaterialConsumption> consumptions = materialConsumptionRepository.findByProductionBatchId(batchIdLong);
        List<Map<String, Object>> materialDetails = new ArrayList<>();
        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        for (MaterialConsumption consumption : consumptions) {
            Map<String, Object> materialDetail = new HashMap<>();
            MaterialBatch materialBatch = consumption.getBatch();
            BigDecimal quantity = consumption.getQuantity() != null ? consumption.getQuantity() : BigDecimal.ZERO;
            BigDecimal unitPrice = consumption.getUnitPrice();
            if (unitPrice == null && materialBatch != null) {
                unitPrice = materialBatch.getUnitPrice();
            }
            BigDecimal cost = consumption.getTotalCost();
            if (cost == null && unitPrice != null) {
                cost = quantity.multiply(unitPrice);
            }
            if (cost == null) {
                cost = BigDecimal.ZERO;
            }
            if (materialBatch == null) {
                materialBatch = new MaterialBatch();
                materialBatch.setBatchNumber(consumption.getBatchId());
                materialBatch.setUnitPrice(unitPrice);
            }

            materialDetail.put("consumptionId", consumption.getId());
            materialDetail.put("batchNumber", materialBatch != null ? materialBatch.getBatchNumber() : consumption.getBatchId());

            // 获取原材料名称（通过materialType关联）
            String materialName = materialBatch.getMaterialType() != null ?
                materialBatch.getMaterialType().getName() : "未知原材料";
            materialDetail.put("materialName", materialName);

            materialDetail.put("quantity", quantity);
            // 报工自动带出 / BOM 自动扣料: 数量 = 配比 × 投入 (独立审查 V2.1 M4)
            materialDetail.put("automatic", com.cretas.aims.service.processsecret.ProcessSecretRecipeDerived.isAutomaticConsumption(consumption.getSourceType()));
            materialDetail.put("unit", materialBatch != null ? materialBatch.getQuantityUnit() : null);
            materialDetail.put("unitPrice", unitPrice);

            materialDetail.put("cost", cost);
            totalMaterialCost = totalMaterialCost.add(cost);

            // 供应商信息
            if (materialBatch.getSupplier() != null) {
                Map<String, Object> supplierInfo = new HashMap<>();
                supplierInfo.put("id", materialBatch.getSupplier().getId());
                supplierInfo.put("name", materialBatch.getSupplier().getName());
                supplierInfo.put("contactPerson", materialBatch.getSupplier().getContactPerson());
                materialDetail.put("supplier", supplierInfo);
            }

            // FIFO信息
            materialDetail.put("receiptDate", materialBatch.getReceiptDate());
            materialDetail.put("expireDate", materialBatch.getExpireDate());

            // 库存状态
            materialDetail.put("initialQuantity", materialBatch.getInitialQuantity());
            materialDetail.put("remainingQuantity", materialBatch.getRemainingQuantity());

            materialDetail.put("consumedAt", consumption.getConsumedAt());

            materialDetails.add(materialDetail);
        }

        if (materialDetails.isEmpty() && batch.getMaterialCost() != null) {
            totalMaterialCost = batch.getMaterialCost();
            Map<String, Object> materialSummary = new HashMap<>();
            materialSummary.put("cost", totalMaterialCost);
            materialSummary.put("note", "from batch summary cost");
            materialDetails.add(materialSummary);
        }

        analysis.put("materialConsumptions", materialDetails);
        analysis.put("materialConsumptionCount", materialDetails.size());
        analysis.put("totalMaterialCost", totalMaterialCost);

        // ========== 4. 设备使用详情 ==========
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage (batchId 是 Long 类型)
        // 注: batchIdLong 已在上面定义
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByBatchId(batchIdLong);
        List<Map<String, Object>> equipmentDetails = new ArrayList<>();
        BigDecimal totalEquipmentCost = BigDecimal.ZERO;
        BigDecimal totalEquipmentHours = BigDecimal.ZERO;

        // N+1 修复：预先批量查询所有设备，使用 Map 缓存
        Set<Long> equipmentIds = usages.stream()
                .map(BatchEquipmentUsage::getEquipmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, FactoryEquipment> equipmentCache = equipmentRepository.findByIdIn(equipmentIds)
                .stream()
                .collect(Collectors.toMap(FactoryEquipment::getId, e -> e));

        for (BatchEquipmentUsage usage : usages) {
            Map<String, Object> equipmentDetail = new HashMap<>();

            // N+1 修复：使用预加载的 Map 进行查找
            Long equipmentIdLong = usage.getEquipmentId();
            BigDecimal hourlyCost = new BigDecimal("50"); // 默认值
            FactoryEquipment equipment = equipmentCache.get(equipmentIdLong);
            if (equipment != null) {
                equipmentDetail.put("equipmentId", equipment.getId());
                equipmentDetail.put("equipmentName", equipment.getEquipmentName());
                equipmentDetail.put("equipmentCode", equipment.getEquipmentCode());
                equipmentDetail.put("model", equipment.getModel());
                equipmentDetail.put("status", equipment.getStatus());
                equipmentDetail.put("hourlyCost", equipment.getHourlyCost());
                // 获取设备的实际小时成本
                if (equipment.getHourlyCost() != null) {
                    hourlyCost = equipment.getHourlyCost();
                }
            }

            equipmentDetail.put("usageId", usage.getId());
            equipmentDetail.put("startTime", usage.getStartTime());
            equipmentDetail.put("endTime", usage.getEndTime());
            equipmentDetail.put("usageHours", usage.getUsageHours());

            // 使用记录中已存储的设备成本，如果没有则按设备实际小时成本计算
            BigDecimal equipmentCost = usage.getEquipmentCost() != null ? usage.getEquipmentCost() :
                    (usage.getUsageHours() != null ? usage.getUsageHours().multiply(hourlyCost) : BigDecimal.ZERO);
            equipmentDetail.put("cost", equipmentCost);

            totalEquipmentCost = totalEquipmentCost.add(equipmentCost);
            if (usage.getUsageHours() != null) {
                totalEquipmentHours = totalEquipmentHours.add(usage.getUsageHours());
            }

            equipmentDetails.add(equipmentDetail);
        }

        if (equipmentDetails.isEmpty() && batch.getEquipmentCost() != null) {
            totalEquipmentCost = batch.getEquipmentCost();
            Map<String, Object> equipmentSummary = new HashMap<>();
            equipmentSummary.put("cost", totalEquipmentCost);
            equipmentSummary.put("note", "from batch summary cost");
            equipmentDetails.add(equipmentSummary);
        }

        analysis.put("equipmentUsages", equipmentDetails);
        analysis.put("equipmentUsageCount", equipmentDetails.size());
        analysis.put("totalEquipmentHours", totalEquipmentHours);
        analysis.put("totalEquipmentCost", totalEquipmentCost);

        // ========== 5. 人工工时详情 ==========
        // 查询 BatchWorkSession 获取详细的工时数据
        List<BatchWorkSession> workSessions = batchWorkSessionRepository.findByBatchId(batchIdLong);
        List<Map<String, Object>> laborDetails = new ArrayList<>();
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        int totalWorkMinutes = 0;
        Set<Long> uniqueEmployeeIds = new HashSet<>();

        for (BatchWorkSession session : workSessions) {
            Map<String, Object> laborDetail = new HashMap<>();
            laborDetail.put("sessionId", session.getId());
            laborDetail.put("workMinutes", session.getWorkMinutes());
            laborDetail.put("laborCost", session.getLaborCost());
            laborDetail.put("employeeId", session.getEmployeeId());

            // 获取员工信息
            if (session.getEmployeeId() != null) {
                userRepository.findById(session.getEmployeeId()).ifPresent(employee -> {
                    laborDetail.put("employeeName", employee.getFullName());
                    laborDetail.put("employeeRole", employee.getRole());
                });
                uniqueEmployeeIds.add(session.getEmployeeId());
            }

            // 获取工作会话详情
            if (session.getWorkSession() != null) {
                EmployeeWorkSession ws = session.getWorkSession();
                // 从startTime提取日期
                laborDetail.put("workDate", ws.getStartTime() != null ? ws.getStartTime().toLocalDate() : null);
                laborDetail.put("startTime", ws.getStartTime());
                laborDetail.put("endTime", ws.getEndTime());
            }

            laborDetails.add(laborDetail);

            // 累加总计
            if (session.getLaborCost() != null) {
                totalLaborCost = totalLaborCost.add(session.getLaborCost());
            }
            if (session.getWorkMinutes() != null) {
                totalWorkMinutes += session.getWorkMinutes();
            }
        }

        // 如果没有 BatchWorkSession 数据，使用 ProductionBatch 中的汇总数据作为兜底
        if (workSessions.isEmpty()) {
            totalLaborCost = batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO;
            totalWorkMinutes = batch.getWorkDurationMinutes() != null ? batch.getWorkDurationMinutes() : 0;

            if (totalLaborCost.compareTo(BigDecimal.ZERO) > 0 || totalWorkMinutes > 0) {
                Map<String, Object> laborSummary = new HashMap<>();
                laborSummary.put("workMinutes", totalWorkMinutes);
                laborSummary.put("laborCost", totalLaborCost);
                laborSummary.put("workerCount", batch.getWorkerCount());
                laborSummary.put("note", "来自批次汇总数据");
                laborDetails.add(laborSummary);
            }
        }

        analysis.put("laborSessions", laborDetails);
        analysis.put("laborSessionCount", laborDetails.size());
        analysis.put("totalWorkMinutes", totalWorkMinutes);
        analysis.put("totalWorkHours", totalWorkMinutes / 60.0);
        analysis.put("totalLaborCost", totalLaborCost);
        analysis.put("uniqueWorkerCount", workSessions.isEmpty() ?
            (batch.getWorkerCount() != null ? batch.getWorkerCount() : 0) : uniqueEmployeeIds.size());

        // ========== 6. 质量检验详情 ==========
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndProductionBatchId(
            factoryId, batchIdLong, org.springframework.data.domain.PageRequest.of(0, 100)).getContent();

        List<Map<String, Object>> qualityDetails = new ArrayList<>();

        for (QualityInspection inspection : inspections) {
            Map<String, Object> qualityDetail = new HashMap<>();

            qualityDetail.put("inspectionId", inspection.getId());
            qualityDetail.put("inspectionDate", inspection.getInspectionDate());
            qualityDetail.put("sampleSize", inspection.getSampleSize());
            qualityDetail.put("passCount", inspection.getPassCount());
            qualityDetail.put("failCount", inspection.getFailCount());
            qualityDetail.put("passRate", inspection.getPassRate());
            qualityDetail.put("result", inspection.getResult());
            qualityDetail.put("notes", inspection.getNotes());

            // 检验员信息
            if (inspection.getInspectorId() != null) {
                userRepository.findById(inspection.getInspectorId()).ifPresent(inspector -> {
                    Map<String, Object> inspectorInfo = new HashMap<>();
                    inspectorInfo.put("id", inspector.getId());
                    inspectorInfo.put("fullName", inspector.getFullName());
                    qualityDetail.put("inspector", inspectorInfo);
                });
            }

            qualityDetails.add(qualityDetail);
        }

        analysis.put("qualityInspections", qualityDetails);
        analysis.put("qualityInspectionCount", qualityDetails.size());

        // 计算平均合格率
        List<BigDecimal> validPassRates = inspections.stream()
            .map(QualityInspection::getPassRate)
            .filter(Objects::nonNull)
            .toList();
        if (!validPassRates.isEmpty()) {
            BigDecimal avgPassRate = validPassRates.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(validPassRates.size()), 2, RoundingMode.HALF_UP);
            analysis.put("averagePassRate", avgPassRate);
        }

        // ========== 7. 加工环节详情 ==========
        try {
            List<ProcessingStageRecordDTO> stageRecords = processingStageRecordService
                .getByBatchIdWithComparison(factoryId, batchIdLong);

            List<Map<String, Object>> stageDetails = new ArrayList<>();

            for (ProcessingStageRecordDTO stage : stageRecords) {
                Map<String, Object> stageDetail = new HashMap<>();

                // 基本信息
                stageDetail.put("id", stage.getId());
                stageDetail.put("stageType", stage.getStageType());
                stageDetail.put("stageName", stage.getStageName());
                stageDetail.put("stageOrder", stage.getStageOrder());

                // 时间数据
                stageDetail.put("startTime", stage.getStartTime());
                stageDetail.put("endTime", stage.getEndTime());
                stageDetail.put("durationMinutes", stage.getDurationMinutes());
                stageDetail.put("avgDuration", stage.getAvgDuration());

                // 重量/数量数据
                stageDetail.put("inputWeight", stage.getInputWeight());
                stageDetail.put("outputWeight", stage.getOutputWeight());
                stageDetail.put("lossWeight", stage.getLossWeight());
                stageDetail.put("lossRate", stage.getLossRate());
                stageDetail.put("avgLossRate", stage.getAvgLossRate());

                // 温度数据
                stageDetail.put("ambientTemperature", stage.getAmbientTemperature());
                stageDetail.put("productTemperature", stage.getProductTemperature());
                stageDetail.put("targetTemperature", stage.getTargetTemperature());

                // 质量数据
                stageDetail.put("passCount", stage.getPassCount());
                stageDetail.put("failCount", stage.getFailCount());
                stageDetail.put("passRate", stage.getPassRate());
                stageDetail.put("avgPassRate", stage.getAvgPassRate());
                stageDetail.put("reworkCount", stage.getReworkCount());

                // 设备数据
                stageDetail.put("equipmentId", stage.getEquipmentId());
                stageDetail.put("equipmentName", stage.getEquipmentName());
                stageDetail.put("equipmentRunTime", stage.getEquipmentRunTime());
                stageDetail.put("equipmentDowntime", stage.getEquipmentDowntime());
                stageDetail.put("equipmentOee", stage.getEquipmentOee());

                // 资源消耗数据
                stageDetail.put("waterUsage", stage.getWaterUsage());
                stageDetail.put("powerUsage", stage.getPowerUsage());
                stageDetail.put("auxiliaryUsage", stage.getAuxiliaryUsage());
                stageDetail.put("auxiliaryName", stage.getAuxiliaryName());

                // 特殊环节数据
                stageDetail.put("dripLoss", stage.getDripLoss());
                stageDetail.put("thicknessSd", stage.getThicknessSd());
                stageDetail.put("marinadeAbsorption", stage.getMarinadeAbsorption());
                stageDetail.put("phValue", stage.getPhValue());
                stageDetail.put("salinity", stage.getSalinity());
                stageDetail.put("atpResult", stage.getAtpResult());
                stageDetail.put("microPassRate", stage.getMicroPassRate());
                stageDetail.put("ccpPassRate", stage.getCcpPassRate());

                // 操作员信息
                stageDetail.put("operatorId", stage.getOperatorId());
                stageDetail.put("operatorName", stage.getOperatorName());
                stageDetail.put("workerCount", stage.getWorkerCount());

                // 其他
                stageDetail.put("notes", stage.getNotes());

                stageDetails.add(stageDetail);
            }

            analysis.put("processingStages", stageDetails);
            analysis.put("processingStageCount", stageDetails.size());

            // 添加AI分析格式化的环节数据
            if (!stageRecords.isEmpty()) {
                Map<String, String> aiFormatData = processingStageRecordService
                    .formatForAIAnalysis(factoryId, batchIdLong);
                analysis.put("processingStageAIFormat", aiFormatData);
            }
        } catch (Exception e) {
            log.warn("获取加工环节数据失败: {}", e.getMessage());
            analysis.put("processingStages", new ArrayList<>());
            analysis.put("processingStageCount", 0);
        }

        // ========== 8. 成本汇总 ==========
        Map<String, Object> costSummary = new HashMap<>();
        costSummary.put("materialCost", totalMaterialCost);
        costSummary.put("laborCost", totalLaborCost);
        costSummary.put("equipmentCost", totalEquipmentCost);
        costSummary.put("otherCost", batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);

        BigDecimal totalCost = totalMaterialCost.add(totalLaborCost).add(totalEquipmentCost)
            .add(batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);
        costSummary.put("totalCost", totalCost);

        // 单位成本 - 优先使用良品数量
        BigDecimal quantityForUnitCost = batch.getGoodQuantity() != null && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0
                ? batch.getGoodQuantity() : batch.getActualQuantity();
        if (quantityForUnitCost != null && quantityForUnitCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unitCost = totalCost.divide(quantityForUnitCost, 4, RoundingMode.HALF_UP);
            costSummary.put("unitCost", unitCost);
            costSummary.put("unitCostBasis", batch.getGoodQuantity() != null && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? "goodQuantity" : "actualQuantity");
        }

        // 成本占比
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            costSummary.put("materialCostRatio",
                totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            costSummary.put("laborCostRatio",
                totalLaborCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            costSummary.put("equipmentCostRatio",
                totalEquipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
        }

        analysis.put("costSummary", costSummary);

        // ========== 8.1 前端兼容的costBreakdown结构（CostAnalysisDashboard使用） ==========
        Map<String, Object> costBreakdown = new HashMap<>();
        costBreakdown.put("rawMaterialCost", totalMaterialCost);
        costBreakdown.put("laborCost", totalLaborCost);
        costBreakdown.put("equipmentCost", totalEquipmentCost);
        costBreakdown.put("totalCost", totalCost);

        // 百分比计算
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rawMaterialPct = totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            BigDecimal laborPct = totalLaborCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            BigDecimal equipmentPct = totalEquipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            costBreakdown.put("rawMaterialPercentage", rawMaterialPct.setScale(1, RoundingMode.HALF_UP));
            costBreakdown.put("laborPercentage", laborPct.setScale(1, RoundingMode.HALF_UP));
            costBreakdown.put("equipmentPercentage", equipmentPct.setScale(1, RoundingMode.HALF_UP));
        } else {
            costBreakdown.put("rawMaterialPercentage", 0);
            costBreakdown.put("laborPercentage", 0);
            costBreakdown.put("equipmentPercentage", 0);
        }
        analysis.put("costBreakdown", costBreakdown);

        // ========== 9. 风险预警 ==========
        List<String> risks = new ArrayList<>();

        // 原材料过期风险
        for (MaterialConsumption consumption : consumptions) {
            MaterialBatch mb = consumption.getBatch();
            if (mb != null && mb.getExpireDate() != null && mb.getExpireDate().isBefore(LocalDate.now().plusDays(7))) {
                String matName = mb.getMaterialType() != null ?
                    mb.getMaterialType().getName() : "未知原材料";
                risks.add("原材料 " + matName + " 批次 " + mb.getBatchNumber() + " 即将过期");
            }
        }

        // 良品率风险
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("90")) < 0) {
            risks.add("良品率偏低（" + batch.getYieldRate() + "%），建议加强质量控制");
        }

        // 成本占比风险
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal materialRatio = totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP);
            if (materialRatio.compareTo(new BigDecimal("0.65")) > 0) {
                risks.add("原材料成本占比过高（" + materialRatio.multiply(new BigDecimal(100)) + "%），建议优化采购");
            }
        }

        analysis.put("risks", risks);
        analysis.put("riskCount", risks.size());

        log.info("增强的批次成本分析完成: batchId={}, 原材料{}种, 设备{}台, 人工{}人次, 质检{}次, 加工环节{}个",
                 batchId, materialDetails.size(), equipmentDetails.size(), laborDetails.size(), qualityDetails.size(),
                 analysis.get("processingStageCount"));

        return analysis;
    }
    public ProductionBatch recalculateBatchCost(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Long batchIdLong = parseBatchId(batchId);

        // 重新计算原材料成本
        BigDecimal materialCost = MaterialConsumptionCostTotals.forBatch(
                materialConsumptionRepository, batchIdLong, factoryId);
        batch.setMaterialCost(materialCost);

        // 重新计算设备成本 - 使用 BatchEquipmentUsage
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByBatchId(batchIdLong);
        // 优先使用记录中已存储的成本，如果没有则按设备实际小时成本计算
        BigDecimal equipmentCost = BigDecimal.ZERO;
        for (BatchEquipmentUsage usage : usages) {
            if (usage.getEquipmentCost() != null) {
                equipmentCost = equipmentCost.add(usage.getEquipmentCost());
            } else if (usage.getUsageHours() != null) {
                // 获取设备的实际小时成本
                BigDecimal hourlyCost = new BigDecimal("50"); // 默认值
                Long equipmentId = usage.getEquipmentId();
                if (equipmentId != null) {
                    FactoryEquipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
                    if (equipment != null && equipment.getHourlyCost() != null) {
                        hourlyCost = equipment.getHourlyCost();
                    }
                }
                equipmentCost = equipmentCost.add(usage.getUsageHours().multiply(hourlyCost));
            }
        }
        batch.setEquipmentCost(equipmentCost);

        // 重新计算人工成本 - 使用 BatchWorkSession
        List<BatchWorkSession> workSessions = batchWorkSessionRepository.findByBatchId(batchIdLong);
        BigDecimal laborCost = workSessions.stream()
                .map(s -> s.getLaborCost() != null ? s.getLaborCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (laborCost.compareTo(BigDecimal.ZERO) > 0) {
            batch.setLaborCost(laborCost);
        }

        // 重新计算总成本
        batch.calculateMetrics();
        return productionBatchRepository.save(batch);
    }
    public Map<String, Object> getAICostAnalysis(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> aiAnalysis = new HashMap<>();
        aiAnalysis.put("batch", batch);
        List<String> suggestions = new ArrayList<>();
        // 基于成本分析提供建议 (null 和零值安全处理)
        if (batch.getMaterialCost() != null && batch.getTotalCost() != null
                && batch.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal materialRatio = batch.getMaterialCost().divide(batch.getTotalCost(), 2, RoundingMode.HALF_UP);
            if (materialRatio.compareTo(new BigDecimal("0.6")) > 0) {
                suggestions.add("原材料成本占比过高（" + materialRatio.multiply(new BigDecimal(100)) + "%），建议优化采购策略或寻找替代供应商");
            }
        }
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("90")) < 0) {
            suggestions.add("良品率偏低（" + batch.getYieldRate() + "%），建议加强质量控制和工艺优化");
        }
        if (batch.getEfficiency() != null && batch.getEfficiency().compareTo(new BigDecimal("80")) < 0) {
            suggestions.add("生产效率偏低（" + batch.getEfficiency() + "%），建议优化生产流程和人员配置");
        }
        aiAnalysis.put("suggestions", suggestions);
        aiAnalysis.put("potentialSavings", calculatePotentialSavings(batch));
        return aiAnalysis;
    }
    // ========== 仪表盘 ==========
    public Map<String, Object> getDashboardOverview(String factoryId) {
        log.info("===== getDashboardOverview 开始: factoryId={} =====", factoryId);
        long startTime = System.currentTimeMillis();
        long lastQueryTime = startTime;

        Map<String, Object> overview = new HashMap<>();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // ===== summary (概览) =====
        Map<String, Object> summary = new HashMap<>();

        // 查询1: 今日批次总数
        log.info("[查询1] countByFactoryIdAndCreatedAtAfter 开始");
        long todayBatches = productionBatchRepository.countByFactoryIdAndCreatedAtAfter(factoryId, todayStart);
        log.info("[查询1] 完成: todayBatches={}, 耗时={}ms", todayBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("totalBatches", todayBatches);

        // 查询2: 进行中批次数
        log.info("[查询2] countByFactoryIdAndStatus 开始");
        long activeBatches = productionBatchRepository.countByFactoryIdAndStatus(
                factoryId, ProductionBatchStatus.IN_PROGRESS);
        log.info("[查询2] 完成: activeBatches={}, 耗时={}ms", activeBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("activeBatches", activeBatches);

        // APP-DATA-005 (2026-08-02): activeBatches 是**全量历史**的进行中批次, 而 totalBatches
        // 只数今天新建的 —— 移动端"今日概览"把两者当成分子/分母显示成 "13 / 1", 分子大于分母。
        // 字段名不带时间语义是根因, 这里补两个语义明确的键, 既有键保持不动 (web-admin 在用)。
        long todayInProgressBatches = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtAfter(
                factoryId, ProductionBatchStatus.IN_PROGRESS, todayStart);
        summary.put("todayInProgressBatches", todayInProgressBatches);
        summary.put("allActiveBatches", activeBatches);
        log.info("[查询2b] 完成: todayInProgressBatches={}, 耗时={}ms",
                todayInProgressBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // 查询3: 今日完成的批次数
        // 🔴 2026-08-19: 原来用 countByFactoryIdAndStatusAndCreatedAtAfter —— 那是
        //    「今天**创建**的批次里有多少已完成」, 而卡片写的是「完成批次」, 用户读成
        //    「今天完成了几批」。F006 实测: 批次 10766 昨天创建、今天完成(良品 75kg),
        //    被整张卡漏掉。形态 A: 想知道的 X ≠ 实际在量的 Y。
        log.info("[查询3] countByFactoryIdAndStatusAndEndTimeAfter 开始");
        long completedBatches = productionBatchRepository.countByFactoryIdAndStatusAndEndTimeAfter(
                factoryId, ProductionBatchStatus.COMPLETED, todayStart);
        log.info("[查询3] 完成: completedBatches={}, 耗时={}ms", completedBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("completedBatches", completedBatches);

        // 查询4: 今日质检数
        log.info("[查询4] countByFactoryIdAndInspectionDateAfter 开始");
        long qualityInspections = qualityInspectionRepository.countByFactoryIdAndInspectionDateAfter(
                factoryId, todayStart.toLocalDate());
        log.info("[查询4] 完成: qualityInspections={}, 耗时={}ms", qualityInspections, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("qualityInspections", qualityInspections);

        // 查询5: 活跃告警数
        log.info("[查询5] findByFactoryIdAndStatusOrderByTriggeredAtDesc 开始");
        List<EquipmentAlert> activeAlerts = equipmentAlertRepository.findByFactoryIdAndStatusOrderByTriggeredAtDesc(
                factoryId, AlertStatus.ACTIVE);
        log.info("[查询5] 完成: activeAlerts.size={}, 耗时={}ms", activeAlerts.size(), System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("activeAlerts", activeAlerts.size());

        // 查询6: 工人数量统计
        log.info("[查询6] countByFactoryId(user) 开始");
        long totalWorkers = userRepository.countByFactoryId(factoryId);
        log.info("[查询6] 完成: totalWorkers={}, 耗时={}ms", totalWorkers, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("totalWorkers", totalWorkers);
        summary.put("onDutyWorkers", totalWorkers);
        overview.put("summary", summary);

        // ===== todayStats (今日统计) =====
        Map<String, Object> todayStats = new HashMap<>();
        todayStats.put("productionCount", todayBatches);
        todayStats.put("qualityCheckCount", qualityInspections);

        // 查询7: 今日入库原材料数（批次数, 即"N 单"）
        log.info("[查询7] countByFactoryIdAndReceiptDateAfter 开始");
        long materialReceived = materialBatchRepository.countByFactoryIdAndReceiptDateAfter(factoryId, todayStart);
        log.info("[查询7] 完成: materialReceived={}, 耗时={}ms", materialReceived, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("materialReceived", materialReceived);

        // 查询7b (M-11, 2026-08-20): 今日入库数量总和（kg, 即"N kg"）—— 与查询7
        // 同一个时间窗口, 聚合函数从 COUNT 换成 SUM(receiptQuantity)。App 首页之前
        // 把 materialReceived (COUNT=批次数) 当成 kg 数直接显示, 数字和单位来自
        // 两个不同的量。这里新增一个字段, 不改 materialReceived 的既有语义/调用方。
        log.info("[查询7b] sumReceiptQuantityByFactoryIdAndReceiptDateAfter 开始");
        BigDecimal materialReceivedQuantity = materialBatchRepository.sumReceiptQuantityByFactoryIdAndReceiptDateAfter(factoryId, todayStart);
        log.info("[查询7b] 完成: materialReceivedQuantity={}, 耗时={}ms", materialReceivedQuantity, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("materialReceivedQuantity", materialReceivedQuantity != null ? materialReceivedQuantity : BigDecimal.ZERO);

        // 查询8: 总原材料批次数
        log.info("[查询8] countByFactoryId(material) 开始");
        long totalMaterialBatches = materialBatchRepository.countByFactoryId(factoryId);
        log.info("[查询8] 完成: totalMaterialBatches={}, 耗时={}ms", totalMaterialBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("totalMaterialBatches", totalMaterialBatches);
        todayStats.put("ordersCompleted", completedBatches);

        // 查询9: 生产效率
        log.info("[查询9] calculateAverageEfficiency 开始");
        BigDecimal avgEfficiency = productionBatchRepository.calculateAverageEfficiency(factoryId, todayStart);
        log.info("[查询9] 完成: avgEfficiency={}, 耗时={}ms", avgEfficiency, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        // 🔴 比率类兜 0 是编的: 今天没有批次时 calculateAverageEfficiency 返 null,
        //    兜成 0 之后屏上是「完成率 0.0%」—— 读起来是**一件没做完**, 而真相是今天还没开工。
        //    ⚠️ 与产量类(todayOutputKg / monthlyOutput)不同: 产量的 0 是合法读数
        //    (没生产就是产 0), 比率的 0 也是合法读数(效率确实是 0), 所以**必须**用 null
        //    才能把「我不知道」和这两者分开。(2026-08-29 真机走查实测)
        todayStats.put("productionEfficiency", avgEfficiency);
        todayStats.put("activeWorkers", totalWorkers);

        // 查询10: 今日产量
        // 🔴 2026-08-19: 原来用 calculateTotalOutputAfter —— 它按 createdAt 筛**且不过滤状态**,
        //    于是「今天建但没做完」的算进产量, 「昨天建、今天做完」的不算。
        //    F006 实测当天真实完成 115.00, 那个口径只给 40.00。改用按完成时间的口径。
        log.info("[查询10] calculateTotalOutputCompletedAfter 开始");
        BigDecimal todayOutput = productionBatchRepository.calculateTotalOutputCompletedAfter(factoryId, todayStart);
        log.info("[查询10] 完成: todayOutput={}, 耗时={}ms", todayOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("todayOutputKg", todayOutput != null ? todayOutput : BigDecimal.ZERO);
        todayStats.put("totalBatches", todayBatches);
        todayStats.put("totalWorkers", totalWorkers);

        // 查询11: 设备总数
        log.info("[查询11] countByFactoryId(equipment) 开始");
        long totalEquipment = equipmentRepository.countByFactoryId(factoryId);
        log.info("[查询11] 完成: totalEquipment={}, 耗时={}ms", totalEquipment, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // 查询12: 活跃设备数
        log.info("[查询12] countByFactoryIdAndStatus(equipment) 开始");
        long activeEquipment = equipmentRepository.countByFactoryIdAndStatus(factoryId, "active");
        log.info("[查询12] 完成: activeEquipment={}, 耗时={}ms", activeEquipment, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("totalEquipment", totalEquipment);
        todayStats.put("activeEquipment", activeEquipment);
        overview.put("todayStats", todayStats);

        // ===== yesterdayStats (昨日统计，用于计算涨幅) =====
        LocalDateTime yesterdayStart = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime yesterdayEnd = LocalDate.now().atStartOfDay();
        Map<String, Object> yesterdayStats = new HashMap<>();

        // 查询13: 昨日批次总数
        log.info("[查询13] countByFactoryIdAndCreatedAtBetween 开始");
        long yesterdayBatches = productionBatchRepository.countByFactoryIdAndCreatedAtBetween(
                factoryId, yesterdayStart, yesterdayEnd);
        log.info("[查询13] 完成: yesterdayBatches={}, 耗时={}ms", yesterdayBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("totalBatches", yesterdayBatches);

        // 查询14: 昨日产量
        log.info("[查询14] calculateTotalOutputBetween 开始");
        BigDecimal yesterdayOutput = productionBatchRepository.calculateTotalOutputBetween(
                factoryId, yesterdayStart, yesterdayEnd);
        log.info("[查询14] 完成: yesterdayOutput={}, 耗时={}ms", yesterdayOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("outputKg", yesterdayOutput != null ? yesterdayOutput : BigDecimal.ZERO);

        // 查询15: 昨日完成批次
        log.info("[查询15] countByFactoryIdAndStatusAndCreatedAtBetween 开始");
        long yesterdayCompleted = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtBetween(
                factoryId, ProductionBatchStatus.COMPLETED, yesterdayStart, yesterdayEnd);
        log.info("[查询15] 完成: yesterdayCompleted={}, 耗时={}ms", yesterdayCompleted, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("completedBatches", yesterdayCompleted);
        overview.put("yesterdayStats", yesterdayStats);

        // ===== kpi (关键绩效指标) =====
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("productionEfficiency", avgEfficiency);   // ⛔ 同上, 不兜 0

        // 查询16: 质量合格率
        log.info("[查询16] calculateAverageYieldRate 开始");
        BigDecimal monthlyYieldRate = productionBatchRepository.calculateAverageYieldRate(factoryId, monthStart);
        log.info("[查询16] 完成: monthlyYieldRate={}, 耗时={}ms", monthlyYieldRate, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        kpi.put("qualityPassRate", monthlyYieldRate);   // ⛔ 本月没有批次 != 合格率 0%

        // 设备利用率（计算，无查询）
        BigDecimal equipmentUtilization = totalEquipment > 0 ?
                BigDecimal.valueOf(activeEquipment * 100.0 / totalEquipment).setScale(2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;
        kpi.put("equipmentUtilization", equipmentUtilization);

        // 查询17: 单位成本 - 本月成本总和
        log.info("[查询17] calculateTotalCostAfter 开始");
        BigDecimal monthlyTotalCost = productionBatchRepository.calculateTotalCostAfter(factoryId, monthStart);
        log.info("[查询17] 完成: monthlyTotalCost={}, 耗时={}ms", monthlyTotalCost, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // 查询18: 本月产量总和
        log.info("[查询18] calculateTotalOutputAfter(month) 开始");
        BigDecimal monthlyTotalOutput = productionBatchRepository.calculateTotalOutputAfter(factoryId, monthStart);
        log.info("[查询18] 完成: monthlyTotalOutput={}, 耗时={}ms", monthlyTotalOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // ⛔ 无产量时单位成本**算不出来**, 不是 ¥0 ——「单位成本 ¥0」读起来是不要钱。
        BigDecimal unitCost = null;
        if (monthlyTotalOutput != null && monthlyTotalOutput.compareTo(BigDecimal.ZERO) > 0
                && monthlyTotalCost != null) {
            unitCost = monthlyTotalCost.divide(monthlyTotalOutput, 2, RoundingMode.HALF_UP);
        }
        // 工艺保密: 调用者对本厂某个保密产品无权时, 单位成本 = 他能看的批次的成本 / 同一批批次的产量 (带说明)
        com.cretas.aims.service.processsecret.ProcessSecretBatchCosts visibleMonth = visibleBatchCosts(factoryId, monthStart);
        if (visibleMonth != null) {
            unitCost = visibleMonth.unitCost();
            if (visibleMonth.partial()) kpi.put("processSecretNote", visibleMonth.note());
        }
        kpi.put("unitCost", unitCost);

        // 查询19: 平均生产周期
        log.info("[查询19] findCompletedBatchesWithTimes 开始");
        // ⛔ 本月没有完工批次时平均周期算不出来, 不是 0 小时。
        BigDecimal avgCycleHours = null;
        java.util.List<ProductionBatch> batchesWithTimes = productionBatchRepository.findCompletedBatchesWithTimes(factoryId, monthStart);
        log.info("[查询19] 完成: batchesWithTimes.size={}, 耗时={}ms", batchesWithTimes != null ? batchesWithTimes.size() : 0, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        if (batchesWithTimes != null && !batchesWithTimes.isEmpty()) {
            long totalHours = 0;
            int cycleCount = 0;
            for (ProductionBatch batch : batchesWithTimes) {
                if (batch.getStartTime() != null && batch.getEndTime() != null) {
                    long hours = java.time.Duration.between(batch.getStartTime(), batch.getEndTime()).toHours();
                    totalHours += hours;
                    cycleCount++;
                }
            }
            if (cycleCount > 0) {
                avgCycleHours = BigDecimal.valueOf(totalHours).divide(BigDecimal.valueOf(cycleCount), 1, RoundingMode.HALF_UP);
            }
        }
        kpi.put("avgCycleHours", avgCycleHours);

        overview.put("kpi", kpi);

        // ===== alerts (告警状态) =====
        Map<String, Object> alerts = new HashMap<>();
        alerts.put("active", activeAlerts.size());
        alerts.put("status", activeAlerts.isEmpty() ? "normal" : "warning");
        overview.put("alerts", alerts);

        // ===== period =====
        overview.put("period", "today");

        // ===== 顶层投影：首页四张 KPI 卡读的是**顶层**，不是嵌套 =====
        // 🔴 2026-08-19 F006 客户截图: 「今日产量 0 kg」「完成批次 0 个」, 而库里当天
        //    完成 2 个批次 / 115.00。根因不是取数, 是**投影丢失**:
        //    completedBatches 只放进了 summary、todayOutputKg 只放进了 todayStats,
        //    而 web-admin 的 DashboardOverview 类型声明的是顶层 todayOutput /
        //    completedBatches → `?? 0` → 这两张卡**恒为 0, 与数据无关**。
        //
        // ⚠️ 同一屏第四张卡「设备告警」得过一模一样的病并且修过(R42 BUG-14),
        //    当时的修法是在前端加 fallback 链, 但**同屏另外两张卡没跟着改**。
        //    ⇒ 「改共享结构前先数」: 那次该数的是「这一屏有几张卡读顶层」。
        //
        // ⛔ 是**新增**不是搬家: summary / todayStats 里的原键保持不动, 它们另有消费方。
        overview.put("completedBatches", completedBatches);
        overview.put("todayOutput", todayOutput != null ? todayOutput : BigDecimal.ZERO);

        // ===== 保留旧字段（向后兼容） =====
        overview.put("todayBatches", todayBatches);
        overview.put("inProgressBatches", activeBatches);
        // APP-DATA-005: 带时间语义的新键 (顶层扁平结构的消费方同样需要)
        overview.put("todayInProgressBatches", todayInProgressBatches);
        overview.put("allActiveBatches", activeBatches);

        // 查询20: 月产量
        log.info("[查询20] calculateMonthlyOutput 开始");
        BigDecimal monthlyOutput = productionBatchRepository.calculateMonthlyOutput(factoryId, monthStart);
        log.info("[查询20] 完成: monthlyOutput={}, 耗时={}ms", monthlyOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        overview.put("monthlyOutput", monthlyOutput != null ? monthlyOutput : BigDecimal.ZERO);
        overview.put("monthlyYieldRate", monthlyYieldRate);   // ⛔ 比率不兜 0

        // 查询21: 低库存原材料
        log.info("[查询21] countLowStockMaterials 开始");
        Long lowStockMaterials = materialBatchRepository.countLowStockMaterials(factoryId);
        log.info("[查询21] 完成: lowStockMaterials={}, 耗时={}ms", lowStockMaterials, System.currentTimeMillis() - lastQueryTime);

        overview.put("lowStockMaterials", lowStockMaterials != null ? lowStockMaterials : 0L);

        log.info("===== getDashboardOverview 完成: 总耗时={}ms =====", System.currentTimeMillis() - startTime);
        return overview;
    }
    public Map<String, Object> getProductionStatistics(String factoryId, String period) {
        Map<String, Object> statistics = new HashMap<>();
        LocalDateTime startDate;
        switch (period.toLowerCase()) {
            case "today":
                startDate = LocalDate.now().atStartOfDay();
                break;
            case "week":
                startDate = LocalDate.now().minusDays(7).atStartOfDay();
                break;
            case "month":
                startDate = LocalDate.now().withDayOfMonth(1).atStartOfDay();
                break;
            default:
                startDate = LocalDate.now().minusDays(30).atStartOfDay();
        }
        // 批次统计
        long totalBatches = productionBatchRepository.countByFactoryIdAndCreatedAtAfter(factoryId, startDate);
        statistics.put("totalBatches", totalBatches);
        // 已完成批次统计
        long completedBatches = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtAfter(
                factoryId,
                ProductionBatchStatus.COMPLETED,
                startDate);
        statistics.put("completedBatches", completedBatches);
        // 产量统计
        BigDecimal totalOutput = productionBatchRepository.calculateTotalOutputAfter(factoryId, startDate);
        statistics.put("totalOutput", totalOutput != null ? totalOutput : BigDecimal.ZERO);
        // 成本统计 (工艺保密: 调用者对本厂某个保密产品无权时只算他能看的批次, 带说明)
        BigDecimal totalCost = productionBatchRepository.calculateTotalCostAfter(factoryId, startDate);
        com.cretas.aims.service.processsecret.ProcessSecretBatchCosts visibleStats = visibleBatchCosts(factoryId, startDate);
        if (visibleStats != null) {
            totalCost = visibleStats.totalCost();
            if (visibleStats.partial()) statistics.put("processSecretNote", visibleStats.note());
        }
        statistics.put("totalCost", totalCost != null ? totalCost : BigDecimal.ZERO);
        // 效率统计
        BigDecimal avgEfficiency = productionBatchRepository.calculateAverageEfficiency(factoryId, startDate);
        statistics.put("averageEfficiency", avgEfficiency != null ? avgEfficiency : BigDecimal.ZERO);

        // ===== 添加批次状态分布 (batchStatusDistribution) =====
        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtAfter(factoryId, startDate);
        List<Map<String, Object>> batchStatusDistribution = new ArrayList<>();
        Map<ProductionBatchStatus, List<ProductionBatch>> byStatus = batches.stream()
                .collect(Collectors.groupingBy(ProductionBatch::getStatus));
        for (Map.Entry<ProductionBatchStatus, List<ProductionBatch>> entry : byStatus.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("status", entry.getKey().name());
            item.put("count", entry.getValue().size());
            double totalQuantity = entry.getValue().stream()
                    .mapToDouble(b -> b.getActualQuantity() != null ? b.getActualQuantity().doubleValue() : 0)
                    .sum();
            item.put("totalQuantity", totalQuantity);
            batchStatusDistribution.add(item);
        }
        statistics.put("batchStatusDistribution", batchStatusDistribution);

        // ===== 添加产品类型统计 (productTypeStats) =====
        List<Map<String, Object>> productTypeStats = new ArrayList<>();
        Map<String, List<ProductionBatch>> byProductType = batches.stream()
                .filter(b -> b.getProductTypeId() != null)
                .collect(Collectors.groupingBy(ProductionBatch::getProductTypeId));

        // N+1 修复：使用批量查询预先加载所有产品类型名称
        Set<String> productTypeIds = byProductType.keySet();
        Map<String, String> productTypeNames = productTypeRepository.findByIdIn(productTypeIds)
                .stream()
                .collect(Collectors.toMap(ProductType::getId, ProductType::getName));

        for (Map.Entry<String, List<ProductionBatch>> entry : byProductType.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            String productTypeId = entry.getKey();
            item.put("productTypeId", productTypeId);
            // 添加产品名称，如果没找到则使用ID
            String productName = productTypeNames.getOrDefault(productTypeId, productTypeId);
            item.put("productTypeName", productName);
            item.put("productType", productName);  // 兼容前端字段
            item.put("count", entry.getValue().size());
            double quantity = entry.getValue().stream()
                    .mapToDouble(b -> b.getActualQuantity() != null ? b.getActualQuantity().doubleValue() : 0)
                    .sum();
            item.put("totalQuantity", quantity);
            productTypeStats.add(item);
        }
        statistics.put("productTypeStats", productTypeStats);

        // 新摘要使用明确的自然日窗口，独立于上面的 legacy 查询口径。
        statistics.put("productionSummary", buildProductionSummary(factoryId, period));

        return statistics;
    }

    /**
     * 生产报表手机端使用的全量摘要。
     *
     * <p>Repository 查询只是第一道租户/窗口筛选；二次筛选是为了让 mock、历史脏数据和
     * 边界值也不会进入摘要。actualQuantity 为 null 时保持「缺失」，0 是合法已上报读数。</p>
     */
    private Map<String, Object> buildProductionSummary(String factoryId, String period) {
        LocalDate today = LocalDate.now();
        String normalizedPeriod = period == null ? "today" : period.toLowerCase(Locale.ROOT);
        LocalDate startDay = switch (normalizedPeriod) {
            case "week" -> today.minusDays(6);
            case "month" -> today.withDayOfMonth(1);
            default -> today;
        };
        LocalDate endDay = today;
        LocalDateTime startDate = startDay.atStartOfDay();
        LocalDateTime endExclusive = endDay.plusDays(1).atStartOfDay();

        List<ProductionBatch> queried = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startDate, endExclusive);
        List<ProductionBatch> batches = queried == null ? Collections.emptyList() : queried.stream()
                .filter(Objects::nonNull)
                .filter(batch -> Objects.equals(factoryId, batch.getFactoryId()))
                .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType()))
                .filter(batch -> batch.getCreatedAt() != null
                        && !batch.getCreatedAt().isBefore(startDate)
                        && batch.getCreatedAt().isBefore(endExclusive))
                .toList();

        long completedBatches = batches.stream()
                .filter(batch -> batch.getStatus() == ProductionBatchStatus.COMPLETED)
                .count();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (ProductionBatch batch : batches) {
            String status = batch.getStatus() == null ? "UNKNOWN" : batch.getStatus().name();
            statusCounts.merge(status, 1L, Long::sum);
        }

        long missingUnitCount = 0;
        long missingActualQuantityCount = 0;
        long unknownUnitCount = 0;
        Map<String, Map<String, Object>> unitStats = new LinkedHashMap<>();
        for (ProductionBatch batch : batches) {
            String unit = batch.getUnit() == null ? null : batch.getUnit().trim();
            if (unit == null || unit.isEmpty()) {
                missingUnitCount++;
            } else {
                if (!KNOWN_PRODUCTION_UNITS.contains(unit.toLowerCase(Locale.ROOT))) {
                    unknownUnitCount++;
                }
                Map<String, Object> unitSummary = unitStats.computeIfAbsent(unit, key -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("unit", key);
                    value.put("totalBatches", 0L);
                    value.put("reportedBatches", 0L);
                    value.put("missingActualQuantityBatches", 0L);
                    value.put("reportedQuantity", BigDecimal.ZERO);
                    value.put("totalQuantity", BigDecimal.ZERO);
                    return value;
                });
                unitSummary.put("totalBatches", ((Long) unitSummary.get("totalBatches")) + 1L);
                if (batch.getActualQuantity() == null) {
                    missingActualQuantityCount++;
                    unitSummary.put("missingActualQuantityBatches",
                            ((Long) unitSummary.get("missingActualQuantityBatches")) + 1L);
                } else {
                    unitSummary.put("reportedBatches", ((Long) unitSummary.get("reportedBatches")) + 1L);
                    BigDecimal reportedQuantity = ((BigDecimal) unitSummary.get("reportedQuantity"))
                            .add(batch.getActualQuantity());
                    unitSummary.put("reportedQuantity", reportedQuantity);
                    unitSummary.put("totalQuantity", reportedQuantity);
                }
            }
            if (batch.getActualQuantity() == null && (unit == null || unit.isEmpty())) {
                missingActualQuantityCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("period", normalizedPeriod.equals("week") || normalizedPeriod.equals("month")
                ? normalizedPeriod : "today");
        summary.put("startDate", startDay);
        summary.put("endDate", endDay);
        summary.put("totalBatches", (long) batches.size());
        summary.put("completedBatches", completedBatches);
        summary.put("completionRate", batches.isEmpty()
                ? null : BigDecimal.valueOf(completedBatches)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(batches.size()), 2, RoundingMode.HALF_UP));
        summary.put("statusCounts", statusCounts);
        summary.put("missingUnitCount", missingUnitCount);
        summary.put("missingActualQuantityCount", missingActualQuantityCount);
        summary.put("unknownUnitCount", unknownUnitCount);
        summary.put("unitBreakdown", new ArrayList<>(unitStats.values()));
        return summary;
    }
    public Map<String, Object> getQualityDashboard(String factoryId) {
        Map<String, Object> dashboard = new HashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        // 本月质检统计
        Map<String, Object> monthlyStats = getQualityStatistics(factoryId, monthStart, today);
        dashboard.put("monthlyStatistics", monthlyStats);

        // 将关键统计字段提升到顶层（用于E2E测试兼容性）
        dashboard.put("totalInspections", monthlyStats.getOrDefault("totalInspections", 0));
        dashboard.put("passedInspections", monthlyStats.getOrDefault("passedBatches", 0));
        dashboard.put("failedInspections", monthlyStats.getOrDefault("failedBatches", 0));
        dashboard.put("avgPassRate", monthlyStats.getOrDefault("averagePassRate", BigDecimal.ZERO));

        // B3-fix (2026-07-06): web-admin 首页质量统计卡片 (DashboardAdmin.vue) 读取
        // qualityStats.todayInspections / qualityStats.failedBatches, 但本方法此前从未
        // 输出这两个字段名(只有月度的 totalInspections/failedInspections) → 前端恒 "-"。
        // failedBatches: 与已建立的 failedInspections/passRate 同口径(本月), 复用同一份
        // monthlyStats, 不新增查询, 也不改动既有字段。
        // todayInspections: 卡片语义是"今日检验批次数", 单独按"今天"这一天查询
        // (与 monthlyStatistics 的月度范围区分, 是其子集)。
        Map<String, Object> todayStats = getQualityStatistics(factoryId, today, today);
        dashboard.put("todayInspections", todayStats.getOrDefault("totalInspections", 0));
        dashboard.put("failedBatches", monthlyStats.getOrDefault("failedBatches", 0));

        // 计算缺陷率 (defectRate = 100 - passRate)
        BigDecimal passRate = (BigDecimal) monthlyStats.getOrDefault("averagePassRate", BigDecimal.ZERO);
        BigDecimal defectRate = BigDecimal.valueOf(100).subtract(passRate);
        dashboard.put("defectRate", defectRate);
        dashboard.put("passRate", passRate);

        // 添加质量等级 (基于 passRate)
        String qualityGrade;
        if (passRate.compareTo(BigDecimal.valueOf(95)) >= 0) {
            qualityGrade = "A";
        } else if (passRate.compareTo(BigDecimal.valueOf(90)) >= 0) {
            qualityGrade = "B";
        } else if (passRate.compareTo(BigDecimal.valueOf(80)) >= 0) {
            qualityGrade = "C";
        } else {
            qualityGrade = "D";
        }
        dashboard.put("qualityGrade", qualityGrade);

        // APP-CONTRACT-004 (2026-08-02): 移动端质检分析页要的 gradeDistribution。
        // 与 monthlyStatistics 同一时间范围 (本月), 按每条记录的 pass_rate 分档。
        dashboard.put("gradeDistribution", buildGradeDistribution(
                qualityInspectionRepository.findByFactoryIdAndDateRange(factoryId, monthStart, today)));

        // 质量趋势
        List<Map<String, Object>> trends = getQualityTrends(factoryId, 30);
        dashboard.put("trends", trends);
        // 最新质检记录
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(1);
        pageRequest.setSize(10);
        PageResponse<Map<String, Object>> recentInspections = getInspections(factoryId, null, pageRequest);
        dashboard.put("recentInspections", recentInspections.getContent());
        return dashboard;
    }
    public Map<String, Object> getEquipmentDashboard(String factoryId) {
        Map<String, Object> dashboard = new HashMap<>();
        // 设备监控
        List<Map<String, Object>> monitoring = getEquipmentMonitoring(factoryId);
        dashboard.put("monitoring", monitoring);
        // 设备统计
        long totalEquipments = equipmentRepository.countByFactoryId(factoryId);
        long runningEquipments = equipmentRepository.countByFactoryIdAndStatus(factoryId, "running");
        long maintenanceEquipments = equipmentRepository.countByFactoryIdAndStatus(factoryId, "maintenance");
        dashboard.put("totalEquipments", totalEquipments);
        dashboard.put("runningEquipments", runningEquipments);
        dashboard.put("maintenanceEquipments", maintenanceEquipments);
        // 平均利用率
        double avgUtilization = monitoring.stream()
                .mapToDouble(m -> (double) m.get("weeklyUtilizationRate"))
                .average()
                .orElse(0);
        dashboard.put("averageUtilization", avgUtilization);
        return dashboard;
    }

    /** 去掉尾随的 0：库存量给人看，⛔ 不要打出 3.200000。 */
    public Map<String, Object> getAlertsDashboard(String factoryId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> alertList = new ArrayList<>();

        // 低库存预警
        //
        // 🔴 2026-08-29 逐屏走查：这里原来对每一种低库存原料都放同一句「原材料库存低」，
        //    主体只以 id 的形式躺在 data 里(mrmt_1f41b247166d8e01)。
        //    实测告警中心三条**完全一模一样**，仓管看不出是哪种料、还剩多少、低于多少 ——
        //    一条无法据以行动的告警，和没有告警的区别只在于它占了一行。
        //
        // ⇒ 与紧挨着的过期预警对齐(那一条本来就会写「原材料即将过期: 批次号」)，把主体说出来。
        //    ⛔ 没有改 findLowStockMaterials 本身：另外 3 个调用点只需要「有几种」这个计数。
        for (Object[] row : materialBatchRepository.findLowStockMaterialsDetailed(factoryId)) {
            String materialTypeId = row[0] == null ? null : String.valueOf(row[0]);
            String name = row[1] == null ? null : String.valueOf(row[1]);
            BigDecimal available = row[2] instanceof BigDecimal ? (BigDecimal) row[2]
                    : row[2] == null ? null : new BigDecimal(String.valueOf(row[2]));
            BigDecimal minStock = row[3] instanceof BigDecimal ? (BigDecimal) row[3]
                    : row[3] == null ? null : new BigDecimal(String.valueOf(row[3]));
            String unit = row[4] == null ? "" : String.valueOf(row[4]);

            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "LOW_STOCK");
            alert.put("level", "WARNING");
            alert.put("status", "ACTIVE");
            // 名称取不到时退回 id —— ⛔ 不要退回一句放之四海皆准的「原材料库存低」,
            //   那样又变回三条一模一样。退回 id 至少还能区分是哪一条。
            String subject = (name != null && !name.isBlank()) ? name : String.valueOf(materialTypeId);
            alert.put("message", available != null && minStock != null
                    ? String.format("%s 可用 %s%s，低于安全库存 %s%s",
                            subject, QuantityFormat.orDash(available), unit, QuantityFormat.orDash(minStock), unit)
                    : subject + " 库存低于安全水平");
            alert.put("materialTypeId", materialTypeId);
            alert.put("materialName", name);
            alert.put("availableQuantity", available);
            alert.put("minStock", minStock);
            alert.put("unit", unit);
            alert.put("data", materialTypeId);
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 过期预警
        List<MaterialBatch> expiringSoon = materialBatchRepository.findExpiringSoon(factoryId, LocalDate.now().plusDays(7));
        for (MaterialBatch batch : expiringSoon) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "EXPIRING");
            alert.put("level", "CRITICAL");
            alert.put("status", "ACTIVE");
            alert.put("message", "原材料即将过期: " + batch.getBatchNumber());
            alert.put("data", batch);
            alert.put("expireDate", batch.getExpireDate());
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 设备维护提醒
        List<FactoryEquipment> maintenanceDue = equipmentRepository.findMaintenanceDue(factoryId, LocalDate.now().minusDays(30));
        for (FactoryEquipment equipment : maintenanceDue) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "MAINTENANCE");
            alert.put("level", "INFO");
            alert.put("status", "ACTIVE");
            alert.put("message", "设备需要维护: " + equipment.getEquipmentName());
            alert.put("data", equipment);
            alert.put("lastMaintenance", equipment.getLastMaintenanceDate());
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 质检不合格告警（来自 ProductionAlert 表）
        List<ProductionAlert> qualityAlerts = productionAlertRepository.findByFactoryIdAndStatus(factoryId, "ACTIVE");
        for (ProductionAlert qa : qualityAlerts) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", qa.getAlertType());
            alert.put("level", qa.getLevel());
            alert.put("status", qa.getStatus());
            alert.put("message", qa.getDescription());
            alert.put("data", qa);
            alert.put("createdAt", qa.getCreatedAt());
            alertList.add(alert);
        }

        // ===== 构建 summary 对象 =====
        int totalAlerts = alertList.size();
        int activeAlerts = (int) alertList.stream()
                .filter(a -> "ACTIVE".equals(a.get("status")))
                .count();
        int resolvedAlerts = totalAlerts - activeAlerts;
        int criticalAlerts = (int) alertList.stream()
                .filter(a -> "CRITICAL".equals(a.get("level")))
                .count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalAlerts", totalAlerts);
        summary.put("activeAlerts", activeAlerts);
        summary.put("resolvedAlerts", resolvedAlerts);
        summary.put("criticalAlerts", criticalAlerts);
        result.put("summary", summary);

        // ===== 构建 byType 数组 =====
        Map<String, Long> typeCount = alertList.stream()
                .collect(Collectors.groupingBy(
                        a -> (String) a.get("type"),
                        Collectors.counting()
                ));
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map.Entry<String, Long> entry : typeCount.entrySet()) {
            Map<String, Object> typeItem = new HashMap<>();
            typeItem.put("type", entry.getKey());
            typeItem.put("count", entry.getValue().intValue());
            byType.add(typeItem);
        }
        result.put("byType", byType);

        // ===== 其他字段 =====
        result.put("period", "week");
        result.put("recent", alertList);  // 重命名 alerts → recent
        // 保留旧字段以便向后兼容
        result.put("alerts", alertList);
        result.put("totalAlerts", totalAlerts);

        return result;
    }
    /** 趋势分析最多回看的天数 (逐日查询, 防止一次请求打出成千上万条查询)。 */
    static final int MAX_TREND_DAYS = 366;

    public Map<String, Object> getTrendAnalysis(String factoryId, String metric, Integer days) {
        // Apr 21 2026: return a MULTI-metric bundle, not a single metric.
        // FE /analytics/trends destructures
        // {productionTrend, qualityTrend, costTrend, materialTrend, equipmentTrend}.
        // Old shape {metric, period, data:[]} left FE with undefined on every
        // chart (empty skeleton page for 100% of users).
        Map<String, Object> analysis = new HashMap<>();
        if (days == null || days <= 0) days = 30;   // 天数没给: 按 30 天, 不再空指针
        days = Math.min(days, MAX_TREND_DAYS);       // 每天 3 次查询: 上限一年
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        // 工艺保密: 调用者对本厂某个保密产品无权时, 每日产量 / 良品率 / 成本都只算他能看的批次
        // (跨批次合计不给无权的人看保密产品; 一次取全区间, 按天分, 带说明)
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded hidden = secretScope().forCurrentCaller(factoryId);
        com.cretas.aims.service.processsecret.ProcessSecretBatchCosts visibleCosts = hidden.isEmpty() ? null : com.cretas.aims.service.processsecret.ProcessSecretReadTracker.untracked(() -> com.cretas.aims.service.processsecret.ProcessSecretBatchCosts.of(hidden,
                productionBatchRepository.findByFactoryIdAndCreatedAtAfter(factoryId, startDate.atStartOfDay().minusSeconds(1)),
                startDate.atStartOfDay(), endDate.atTime(23, 59, 59)));
        if (visibleCosts != null && visibleCosts.partial()) analysis.put("processSecretNote", visibleCosts.note());
        List<Map<String, Object>> productionTrend = new ArrayList<>();
        List<Map<String, Object>> qualityTrend = new ArrayList<>();
        List<Map<String, Object>> costTrend = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.atTime(23, 59, 59);
            BigDecimal dayOutput = visibleCosts != null ? visibleCosts.outputByDay().get(date)
                    : productionBatchRepository.calculateDailyOutput(factoryId, dayStart, dayEnd);
            BigDecimal dayYieldRate = visibleCosts != null ? visibleCosts.yieldRateByDay().get(date)
                    : productionBatchRepository.calculateDailyYieldRate(factoryId, dayStart, dayEnd);
            BigDecimal dayCost = visibleCosts != null ? visibleCosts.costByDay().get(date)
                    : productionBatchRepository.calculateDailyCost(factoryId, dayStart, dayEnd);
            productionTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayOutput != null ? dayOutput : BigDecimal.ZERO,
                    "output", dayOutput != null ? dayOutput : BigDecimal.ZERO
            ));
            qualityTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayYieldRate != null ? dayYieldRate : BigDecimal.ZERO,
                    "passRate", dayYieldRate != null ? dayYieldRate : BigDecimal.ZERO
            ));
            costTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayCost != null ? dayCost : BigDecimal.ZERO,
                    "totalCost", dayCost != null ? dayCost : BigDecimal.ZERO
            ));
        }
        analysis.put("productionTrend", productionTrend);
        analysis.put("qualityTrend", qualityTrend);
        analysis.put("costTrend", costTrend);
        // Reserved for future material / equipment metrics; empty now so FE
        // doesn't crash on `.map()` over undefined.
        analysis.put("materialTrend", new ArrayList<>());
        analysis.put("equipmentTrend", new ArrayList<>());
        // Keep legacy keys for any old callers
        analysis.put("metric", metric);
        analysis.put("period", days);
        return analysis;
    }
    // 辅助方法
    /**
     * 质检员显示名。
     *
     * <p>解析不出(用户已删/ID 为空)时返回 {@code null} —— <b>不编造</b>「未知」之类的假名字,
     * 由前端回落显示 ID。「诚实-null」比「假装有名字」好: 前者用户知道要去查 ID,
     * 后者会让人以为系统真的记录了这么一个人。
     */
    private String resolveInspectorName(Long inspectorId) {
        if (inspectorId == null) {
            return null;
        }
        return userRepository.findById(inspectorId)
                .map(com.cretas.aims.entity.User::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }

    private Map<String, Object> convertInspectionToMap(
            QualityInspection inspection,
            ProductionBatch productionBatch) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", inspection.getId());
        map.put("productionBatchId", inspection.getProductionBatchId());
        map.put("inspectorId", inspection.getInspectorId());
        // 质检记录列表把 inspectorId 这个**数字主键**直接摆给用户看(列名就叫「质检员ID」)。
        // 同一个文件里 buildQualityDetails 早就有解析姓名的写法(userRepository.findById →
        // getFullName), 只是列表这处没用 —— 又是「一处做对、另一处漏了」。
        // 解析不出时**不编造**: 留 null, 由前端回落显示 ID(总比显示空白强, 也不假装有名字)。
        map.put("inspectorName", resolveInspectorName(inspection.getInspectorId()));
        map.put("inspectionDate", inspection.getInspectionDate());
        map.put("sampleSize", inspection.getSampleSize());
        map.put("passCount", inspection.getPassCount());
        map.put("failCount", inspection.getFailCount());
        map.put("passRate", inspection.getPassRate());
        map.put("result", inspection.getResult());
        map.put("notes", inspection.getNotes());
        map.put("customFields", inspection.getCustomFields());
        if (productionBatch != null) {
            // The mobile quality detail must show the real business identity. Falling back to
            // "B-{id}" or reusing inspection notes as a product name fabricates evidence.
            map.put("batchNumber", productionBatch.getBatchNumber());
            map.put("productName", productionBatch.getProductName());
        }
        // 添加计算字段
        map.put("qualityGrade", inspection.getQualityGrade());
        map.put("defectRate", inspection.getDefectRate());
        return map;
    }
    private BigDecimal calculatePotentialSavings(ProductionBatch batch) {
        BigDecimal savings = BigDecimal.ZERO;
        // 如果良品率提升到95%能节省的成本
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("95")) < 0) {
            BigDecimal currentWaste = batch.getDefectQuantity().multiply(batch.getUnitCost());
            BigDecimal targetWaste = batch.getActualQuantity().multiply(new BigDecimal("0.05")).multiply(batch.getUnitCost());
            savings = savings.add(currentWaste.subtract(targetWaste));
        }
        return savings;
    }

    /**
     * 工艺保密: 调用者对本厂保密产品都能完整查看 (或本厂没有保密产品) 时返回 null, 调用方沿用原查询;
     * 否则只在他能看的批次上累加成本与产量。读全厂批次本身不打标 (结果已经按调用者裁剪)。
     */
    private com.cretas.aims.service.processsecret.ProcessSecretBatchCosts visibleBatchCosts(String factoryId, java.time.LocalDateTime from) {
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded hidden = secretScope().forCurrentCaller(factoryId);
        if (hidden.isEmpty()) return null;
        return com.cretas.aims.service.processsecret.ProcessSecretReadTracker.untracked(() -> com.cretas.aims.service.processsecret.ProcessSecretBatchCosts.of(hidden,
                productionBatchRepository.findByFactoryIdAndCreatedAtAfter(factoryId, from.minusSeconds(1)), from));
    }

    // ========== AI智能成本分析（新版本） ==========

    /**
     * AI智能成本分析（带缓存优化 + 完整业务链数据）
     */
    @Override
    public Map<String, Object> analyzeWithAI(String factoryId, String batchId,
                                             String sessionId, String customMessage) {
        log.info("AI成本分析(增强版): factoryId={}, batchId={}, sessionId={}, customMessage={}",
                 factoryId, batchId, sessionId, customMessage != null ? "有追问" : "初次分析");

        // 0. 工艺保密: 当前账号无权查看的保密产品批次, 成本不发给 AI, 也不回放以前缓存的分析 (判定在读缓存之前;
        //    负责人 / 有效授权照常分析)
        ProductionBatch secretCheck = getBatchById(factoryId, batchId);
        if (secretScope().isProtectedBatch(factoryId, secretCheck.getProductTypeId(), secretCheck.getProductionPlanId())) {
            Map<String, Object> withheld = new HashMap<>();
            withheld.put("batchId", batchId);
            withheld.put("batchNumber", secretCheck.getBatchNumber());
            withheld.put("success", false);
            withheld.put("processSecret", true);
            withheld.put("error", com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.WITHHELD_REASON);
            withheld.put("fromCache", false);
            return withheld;
        }
        // 共享的分析缓存按调用者保密档位分开: 有保密产品的工厂键里带档位 (同档复用, 跨档不复用, 不带档位的旧键不再命中)
        String secrecyTier = secretScope().accessTier(factoryId);
        String cacheKey = secrecyTier.isEmpty() ? batchId : batchId + "|" + secrecyTier;

        // 1. 检查缓存（仅对初次分析且无自定义消息的情况使用缓存）
        if (sessionId == null && customMessage == null) {
            Map<String, Object> cachedResult = cacheService.getAIAnalysisCache(factoryId, cacheKey);
            if (cachedResult != null) {
                log.info("返回缓存的AI分析结果: factoryId={}, batchId={}", factoryId, batchId);
                cachedResult.put("fromCache", true);
                return cachedResult;
            }
        }

        // 2. 获取增强的批次成本数据（包含完整业务链数据）
        Map<String, Object> enhancedCostData = getEnhancedBatchCostAnalysis(factoryId, batchId);

        // 3. 调用AI服务
        Map<String, Object> aiResult = aiAnalysisService.analyzeCost(
            factoryId, batchId, enhancedCostData, sessionId, customMessage);

        // 4. 组合结果
        Map<String, Object> result = new HashMap<>();
        ProductionBatch batch = getBatchById(factoryId, batchId);

        result.put("batchId", batchId);
        result.put("batchNumber", batch.getBatchNumber());
        result.put("productName", batch.getProductName());
        result.put("enhancedData", enhancedCostData); // 使用增强数据
        result.put("aiAnalysis", aiResult.get("aiAnalysis"));
        result.put("sessionId", aiResult.get("sessionId"));
        result.put("messageCount", aiResult.get("messageCount"));
        result.put("success", aiResult.get("success"));
        result.put("fromCache", false);
        result.put("dataVersion", "enhanced_v2"); // 标记使用增强版数据

        // 如果AI调用失败，添加错误信息
        if (aiResult.containsKey("error")) {
            result.put("error", aiResult.get("error"));
            result.put("errorDetail", aiResult.get("errorDetail"));
        }

        // 5. 保存到缓存（仅对成功的初次分析）
        if (sessionId == null && customMessage == null && Boolean.TRUE.equals(aiResult.get("success"))) {
            cacheService.setAIAnalysisCache(factoryId, cacheKey, result);
        }

        log.info("AI成本分析完成(增强版): batchId={}, 包含原材料{}种, 设备{}台, 人工{}人次, 质检{}次",
                 batchId,
                 enhancedCostData.get("materialConsumptionCount"),
                 enhancedCostData.get("equipmentUsageCount"),
                 enhancedCostData.get("laborSessionCount"),
                 enhancedCostData.get("qualityInspectionCount"));

        return result;
    }

    /**
     * 获取时间范围内的批次成本分析数据
     */
    @Override
    public List<Map<String, Object>> getTimeRangeBatchesCostAnalysis(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate) {

        log.info("获取时间范围批次成本数据: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        // 1. 查询时间范围内的所有批次
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startDate, endDate);

        log.info("查询到{}个批次", batches.size());

        // 2. 限制最大批次数量（避免Token消耗过大）
        final int MAX_BATCHES = 50;
        if (batches.size() > MAX_BATCHES) {
            log.warn("批次数量过多({})，限制为前{}个", batches.size(), MAX_BATCHES);
            batches = batches.subList(0, MAX_BATCHES);
        }

        // 3. 为每个批次获取增强的成本数据
        List<Map<String, Object>> batchesCostData = batches.stream()
                .map(batch -> {
                    try {
                        Map<String, Object> costData = getEnhancedBatchCostAnalysis(factoryId, batch.getId().toString());
                        // 添加批次基本信息
                        costData.put("batchNumber", batch.getBatchNumber());
                        costData.put("productName", batch.getProductName());
                        // 工艺保密: 给 AI 前按产品 / 计划去掉保密批次 (ProcessSecretLlmCostScope)
                        costData.put("productTypeId", batch.getProductTypeId());
                        costData.put("productionPlanId", batch.getProductionPlanId());
                        costData.put("status", batch.getStatus());
                        costData.put("createdAt", batch.getCreatedAt());
                        return costData;
                    } catch (Exception e) {
                        log.error("获取批次成本数据失败: batchId={}, error={}",
                                batch.getId(), e.getMessage());
                        return null;
                    }
                })
                .filter(data -> data != null)
                .collect(java.util.stream.Collectors.toList());

        log.info("成功获取{}个批次的成本数据", batchesCostData.size());

        return batchesCostData;
    }

    /**
     * 获取多个批次的对比分析数据
     */
    @Override
    public List<Map<String, Object>> getComparativeBatchesCostAnalysis(
            String factoryId,
            List<String> batchIds) {

        log.info("获取批次对比分析数据: factoryId={}, batchIds={}", factoryId, batchIds);

        // 1. 参数校验：确保批次数量在2-5之间
        if (batchIds == null || batchIds.size() < 2) {
            throw new IllegalArgumentException("至少需要2个批次进行对比分析");
        }
        if (batchIds.size() > 5) {
            throw new IllegalArgumentException("最多支持5个批次进行对比分析");
        }

        // 2. 为每个批次获取增强的成本数据
        List<Map<String, Object>> comparativeBatchesData = batchIds.stream()
                .map(batchId -> {
                    try {
                        // 获取批次基本信息
                        Long id = parseBatchId(batchId);
                        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                                .orElseThrow(() -> new EntityNotFoundException("ProductionBatch", batchId));

                        // 获取增强的成本数据
                        Map<String, Object> costData = getEnhancedBatchCostAnalysis(factoryId, batchId);

                        // 添加批次基本信息
                        costData.put("batchId", batch.getId());
                        costData.put("batchNumber", batch.getBatchNumber());
                        costData.put("productName", batch.getProductName());
                        costData.put("productTypeId", batch.getProductTypeId());
                        costData.put("productionPlanId", batch.getProductionPlanId());
                        costData.put("status", batch.getStatus());
                        costData.put("createdAt", batch.getCreatedAt());

                        log.info("成功获取批次{}的成本数据", batchId);
                        return costData;

                    } catch (Exception e) {
                        log.error("获取批次成本数据失败: batchId={}, error={}", batchId, e.getMessage());
                        return null;
                    }
                })
                .filter(data -> data != null)
                .collect(java.util.stream.Collectors.toList());

        // 3. 校验：确保所有批次数据都获取成功
        if (comparativeBatchesData.size() < 2) {
            throw new RuntimeException("至少需要2个有效批次数据才能进行对比分析");
        }

        log.info("成功获取{}个批次的对比数据", comparativeBatchesData.size());

        return comparativeBatchesData;
    }

    /**
     * 获取指定时间范围内的批次成本摘要数据（用于周报告/月报告）
     * 返回轻量级的批次成本摘要，不包含详细的业务链数据
     */
    @Override
    public List<Map<String, Object>> getWeeklyBatchesCost(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate) {

        log.info("获取周报告批次成本数据: factoryId={}, startDate={}, endDate={}",
                 factoryId, startDate, endDate);

        // 1. 查询时间范围内的所有批次
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startDate, endDate);

        log.info("查询到{}个批次", batches.size());

        // 2. 为每个批次构建成本摘要（轻量级，不包含详细业务链数据）
        return batches.stream()
                .map(batch -> {
                    Map<String, Object> batchCostSummary = new HashMap<>();

                    // 基本信息
                    batchCostSummary.put("batchId", batch.getId());
                    batchCostSummary.put("batchNumber", batch.getBatchNumber());
                    batchCostSummary.put("productName", batch.getProductName());
                    batchCostSummary.put("productTypeId", batch.getProductTypeId());
                    batchCostSummary.put("productionPlanId", batch.getProductionPlanId());
                    batchCostSummary.put("status", batch.getStatus());
                    batchCostSummary.put("createdAt", batch.getCreatedAt());

                    // 成本汇总
                    Map<String, Object> costSummary = new HashMap<>();
                    costSummary.put("materialCost", batch.getMaterialCost() != null ? batch.getMaterialCost() : BigDecimal.ZERO);
                    costSummary.put("laborCost", batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO);
                    costSummary.put("equipmentCost", batch.getEquipmentCost() != null ? batch.getEquipmentCost() : BigDecimal.ZERO);
                    costSummary.put("otherCost", batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);
                    costSummary.put("totalCost", batch.getTotalCost() != null ? batch.getTotalCost() : BigDecimal.ZERO);
                    costSummary.put("unitCost", batch.getUnitCost() != null ? batch.getUnitCost() : BigDecimal.ZERO);
                    batchCostSummary.put("costSummary", costSummary);

                    // 生产指标
                    batchCostSummary.put("actualQuantity", batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO);
                    // ⛔ 良品率不兜底成 0 —— 0% 良品率是一个【灾难性的真实读数】,
                    //    与「还没算出来」含义完全相反。原样透传 null, 让前端说「—」。
                    //
                    // 🔴 不改这一处, 本轮的数据清洗会让事情更糟: 那 40 个批次会从
                    //    「假的 100%(完美)」变成「假的 0%(全废)」。
                    //
                    // ⚠️ 同一块里 efficiency / actualQuantity / 各项成本还有同形的 ZERO 兜底,
                    //    【本次故意不改】: 它们与本次改动无因果, 且各自需要自己的生产实测
                    //    (efficiency 当前 38 个批次为 NULL ⇒ 这个接口现在就在把它们报成 0% 效率)。
                    //    已在 PR 描述里单独登记, ⛔ 不在这里顺手改。
                    batchCostSummary.put("yieldRate", batch.getYieldRate());
                    batchCostSummary.put("efficiency", batch.getEfficiency() != null ? batch.getEfficiency() : BigDecimal.ZERO);

                    return batchCostSummary;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * AI服务健康检查
     */
    @Override
    public Map<String, Object> checkAIServiceHealth() {
        log.info("检查AI服务健康状态");
        return aiAnalysisService.healthCheck();
    }

    // ========== 批次员工分配 ==========

    /**
     * 分配员工到批次
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> assignWorkersToBatch(String factoryId, Long batchId,
                                                           List<Long> workerIds, Long assignedBy, String notes) {
        log.info("分配员工到批次: factoryId={}, batchId={}, workerIds={}, assignedBy={}",
                factoryId, batchId, workerIds, assignedBy);

        // 1. 验证批次存在且状态正确
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 兼容 PRODUCING 和 IN_PROGRESS，以及 PLANNING 和 PLANNED
        ProductionBatchStatus status = batch.getStatus();
        if (status != ProductionBatchStatus.IN_PROGRESS &&
            status != ProductionBatchStatus.PRODUCING &&
            status != ProductionBatchStatus.PLANNED &&
            status != ProductionBatchStatus.PLANNING) {
            throw new BusinessException(409, "只有计划中或进行中的批次可以分配员工")
                    .withHint("已完成或已取消的批次无法分配员工, 请刷新批次列表查看最新状态");
        }

        // 2. 验证分配人存在
        User assigner = userRepository.findById(assignedBy)
                .orElseThrow(() -> new ResourceNotFoundException("分配人不存在: " + assignedBy));

        List<Map<String, Object>> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // N+1 修复：预先批量查询所有员工，使用 Map 缓存
        Map<Long, User> workerCache = userRepository.findByIdIn(workerIds)
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        for (Long workerId : workerIds) {
            try {
                // 3. N+1 修复：使用预加载的 Map 进行员工查找
                User worker = workerCache.get(workerId);
                if (worker == null) {
                    throw new ResourceNotFoundException("员工不存在: " + workerId);
                }

                if (!factoryId.equals(worker.getFactoryId())) {
                    throw new BusinessException(403, "员工不属于该工厂: " + workerId)
                            .withHint("分配人员中包含其他工厂的员工, 请重新选择").withHintTarget("workerIds");
                }

                // 4. 检查员工是否已分配到该批次
                Optional<BatchWorkSession> existing = batchWorkSessionRepository
                        .findActiveByBatchIdAndEmployeeId(batchId, workerId);

                if (existing.isPresent()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("workerId", workerId);
                    result.put("workerName", worker.getFullName() != null ? worker.getFullName() : worker.getUsername());
                    result.put("success", false);
                    result.put("message", "员工已分配到该批次");
                    results.add(result);
                    continue;
                }

                // 5. 创建 BatchWorkSession 记录
                BatchWorkSession session = new BatchWorkSession();
                session.setBatchId(batchId);
                session.setEmployeeId(workerId);
                session.setCheckInTime(now);
                session.setAssignedBy(assignedBy);
                session.setStatus(BatchWorkSession.Status.ASSIGNED);
                session.setNotes(notes);
                session.setCreatedAt(now);

                batchWorkSessionRepository.save(session);

                Map<String, Object> result = new HashMap<>();
                result.put("workerId", workerId);
                result.put("workerName", worker.getFullName() != null ? worker.getFullName() : worker.getUsername());
                result.put("sessionId", session.getId());
                result.put("checkInTime", now);
                result.put("success", true);
                result.put("message", "分配成功");
                results.add(result);

                log.info("成功分配员工 {} 到批次 {}", workerId, batchId);

            } catch (Exception e) {
                Map<String, Object> result = new HashMap<>();
                result.put("workerId", workerId);
                result.put("success", false);
                result.put("message", e.getMessage());
                results.add(result);
                log.error("分配员工失败: workerId={}, error={}", workerId, e.getMessage());
            }
        }

        return results;
    }

    /**
     * 员工完成批次工作（签出）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> workerCheckout(String factoryId, Long batchId, Long workerId,
                                               Integer workMinutes, String notes) {
        log.info("员工签出: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);

        // 1. 查找活跃的工作会话
        BatchWorkSession session = batchWorkSessionRepository
                .findActiveByBatchIdAndEmployeeId(batchId, workerId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该员工在该批次的活跃工作记录"));

        LocalDateTime now = LocalDateTime.now();

        // 2. 计算工作时长
        int actualWorkMinutes;
        if (workMinutes != null && workMinutes > 0) {
            actualWorkMinutes = workMinutes;
        } else if (session.getCheckInTime() != null) {
            actualWorkMinutes = (int) ChronoUnit.MINUTES.between(session.getCheckInTime(), now);
        } else {
            actualWorkMinutes = 0;
        }

        // 3. 计算人工成本（假设每小时50元）
        BigDecimal hourlyRate = new BigDecimal("50.00");
        BigDecimal laborCost = hourlyRate
                .multiply(BigDecimal.valueOf(actualWorkMinutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        // 4. 更新工作会话
        session.setCheckOutTime(now);
        session.setWorkMinutes(actualWorkMinutes);
        session.setLaborCost(laborCost);
        session.setStatus(BatchWorkSession.Status.COMPLETED);
        if (notes != null && !notes.isEmpty()) {
            session.setNotes(session.getNotes() != null ?
                    session.getNotes() + "\n" + notes : notes);
        }
        session.setUpdatedAt(now);

        batchWorkSessionRepository.save(session);

        // 5. 获取员工信息
        User worker = userRepository.findById(workerId).orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("workerId", workerId);
        result.put("workerName", worker != null ? (worker.getFullName() != null ? worker.getFullName() : worker.getUsername()) : "未知");
        result.put("checkInTime", session.getCheckInTime());
        result.put("checkOutTime", now);
        result.put("workMinutes", actualWorkMinutes);
        result.put("laborCost", laborCost);
        result.put("status", session.getStatus());
        result.put("success", true);
        result.put("message", "签出成功");

        log.info("员工签出成功: workerId={}, workMinutes={}, laborCost={}",
                workerId, actualWorkMinutes, laborCost);

        return result;
    }

    /**
     * 获取批次的员工列表
     */
    @Override
    public List<Map<String, Object>> getBatchWorkers(String factoryId, Long batchId) {
        log.info("获取批次员工列表: factoryId={}, batchId={}", factoryId, batchId);

        // 验证批次存在
        productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 获取批次的所有工作会话
        List<BatchWorkSession> sessions = batchWorkSessionRepository.findByBatchIdWithEmployees(batchId);

        return sessions.stream().map(session -> {
            Map<String, Object> workerInfo = new HashMap<>();
            workerInfo.put("sessionId", session.getId());
            workerInfo.put("workerId", session.getEmployeeId());

            User employee = session.getEmployee();
            if (employee != null) {
                workerInfo.put("workerName", employee.getFullName() != null ? employee.getFullName() : employee.getUsername());
                workerInfo.put("employeeNumber", employee.getUsername());
                workerInfo.put("departmentName", employee.getDepartment());
            } else {
                workerInfo.put("workerName", "未知");
            }

            workerInfo.put("checkInTime", session.getCheckInTime());
            workerInfo.put("checkOutTime", session.getCheckOutTime());
            workerInfo.put("workMinutes", session.getWorkMinutes());
            workerInfo.put("laborCost", session.getLaborCost());
            workerInfo.put("status", session.getStatus());

            // 状态显示文字
            String statusText = "未知";
            if (session.getStatus() != null) {
                switch (session.getStatus()) {
                    case BatchWorkSession.Status.ASSIGNED: statusText = "已分配"; break;
                    case BatchWorkSession.Status.WORKING: statusText = "工作中"; break;
                    case BatchWorkSession.Status.COMPLETED: statusText = "已完成"; break;
                    case BatchWorkSession.Status.CANCELLED: statusText = "已取消"; break;
                }
            }
            workerInfo.put("statusText", statusText);

            User assigner = session.getAssigner();
            workerInfo.put("assignedBy", session.getAssignedBy());
            workerInfo.put("assignerName", assigner != null ? (assigner.getFullName() != null ? assigner.getFullName() : assigner.getUsername()) : null);
            workerInfo.put("assignedAt", session.getCreatedAt());
            workerInfo.put("notes", session.getNotes());

            return workerInfo;
        }).collect(Collectors.toList());
    }

    /**
     * 取消员工批次分配
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelWorkerAssignment(String factoryId, Long batchId, Long workerId) {
        log.info("取消员工分配: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);

        // 与查询/分配路径保持一致：先验证工厂归属，再访问或修改工作会话。
        productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 查找活跃的工作会话
        BatchWorkSession session = batchWorkSessionRepository
                .findActiveByBatchIdAndEmployeeId(batchId, workerId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该员工在该批次的活跃工作记录"));

        // 更新状态为取消
        session.setStatus(BatchWorkSession.Status.CANCELLED);
        session.setUpdatedAt(LocalDateTime.now());
        batchWorkSessionRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("workerId", workerId);
        result.put("success", true);
        result.put("message", "取消分配成功");

        log.info("取消员工分配成功: workerId={}", workerId);

        return result;
    }
}
