package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.scheduling.*;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.InsertSlotPreview;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.cretas.aims.service.ImpactAnalysisService.*;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.UrgentInsertService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 紧急插单服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrgentInsertServiceImpl implements UrgentInsertService {

    private static final int MAX_ACTIVE_PREVIEWS_PER_FACTORY = 100;
    private static final long PREVIEW_VALID_MINUTES = 15L;

    private final InsertSlotRepository insertSlotRepository;
    private final InsertSlotPreviewRepository insertSlotPreviewRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductEquipmentRequirementRepository equipmentRequirements;
    private final EquipmentRepository equipmentRepository;
    private final ProductionLineRepository productionLineRepository;
    private final ProductionLineCapacityConfigRepository capacityConfigRepository;
    private final UserRepository userRepository;
    private final ProductionPlanMapper productionPlanMapper;
    private final ObjectMapper objectMapper;
    private final DecisionAuditService decisionAuditService;
    private final ImpactAnalysisService impactAnalysisService;
    private final LineCapacityEquipmentCheck lineChecks;
    private final WorkerShiftAvailabilityCheck workerShifts;
    private final SchedulingResourceWriteLock resourceWriteLock;
    private final ApprovalChainService approvalChainService;
    private final PushNotificationService pushNotificationService;
    /** 缺口 17: 按生效配方版本 + 库存 + 在途评估原料, 并产出确认时比对用的指纹。 */
    private final UrgentInsertMaterialAssessor materialAssessor;
    /** 缺口 17 第 2 项: 调约束排程引擎, 预览算排程、确认后真正排入并顺延被挤计划。 */
    private final UrgentInsertScheduleService scheduleService;
    private final PlatformTransactionManager transactionManager;
    /** A2: 缺料插单读和开工缺料同一个工厂开关 (block_start_on_open_shortage)。 */
    private final com.cretas.aims.repository.FactorySettingsRepository settingsRepository;
    /** 1551-11: 强制插单审批画布运行时 (实例创建 + 节点推进)。 */
    private final WorkflowEngineService workflowEngine;
    /** #1580: 工厂「允许本人审批」开关。 */
    private final FactorySelfApprovalGate selfApprovalGate;
    /** 1551-11: 读本厂有没有启用的强制插单审批画布。 */
    private final com.cretas.aims.service.ApprovalWorkflowService approvalWorkflowService;
    /** 1551-11: 工厂开关「插单一律走审批」(production.urgent_insert.always_approval)。 */
    private final com.cretas.aims.service.discipline.FactoryDisciplinePolicy disciplinePolicy;

    /** 画布审批实例的 moduleCode, 与 DecisionTypeMetadataRegistry 里 FORCE_INSERT 的登记一致。 */
    static final String FORCE_INSERT_MODULE = "FORCE_INSERT";

    /** Sprint 3 Track-F (C-LINKARRAY-1): unified link service for urgent-insert path. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LinkArrayService linkArrayService;

    @Override
    @Transactional
    public List<InsertSlotDTO> getAvailableSlots(String factoryId, GetInsertSlotsRequest request) {
        log.info("获取可用插单时段: factoryId={}, productTypeId={}, quantity={}",
                factoryId, request.getProductTypeId(), request.getRequiredQuantity());

        // 先清理过期时段
        cleanupExpiredSlots(factoryId);

        List<InsertSlot> slots;

        if (Boolean.TRUE.equals(request.getNoImpactOnly())) {
            // 只获取无影响的时段
            slots = insertSlotRepository.findNoImpactSlots(factoryId);
        } else if (request.getMinRecommendScore() != null && request.getMinRecommendScore() > 0) {
            // 按最低推荐分数筛选
            slots = insertSlotRepository.findHighScoreSlots(factoryId, request.getMinRecommendScore());
        } else {
            // 获取所有可用时段
            slots = insertSlotRepository.findByFactoryIdAndStatus(factoryId, "available");
        }

        // S4: 时段上的 availableCapacity 是按工厂默认时产能 (kg) 估的, 不是配置读数, 不再拿它筛掉时段;
        // 产能够不够在影响分析里按产线配置、同单位核对。

        // 如果指定了产线，优先返回该产线的时段
        if (request.getPreferredProductionLineId() != null) {
            slots.sort((a, b) -> {
                boolean aMatch = request.getPreferredProductionLineId().equals(a.getProductionLineId());
                boolean bMatch = request.getPreferredProductionLineId().equals(b.getProductionLineId());
                if (aMatch && !bMatch) return -1;
                if (!aMatch && bMatch) return 1;
                return Integer.compare(b.getRecommendScore(), a.getRecommendScore());
            });
        }

        // 转换为DTO
        return slots.stream()
                .map(this::toSlotDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> analyzeInsertImpact(String factoryId, String slotId, GetInsertSlotsRequest request) {
        return analyzeInsertImpactInternal(factoryId, slotId, request, false, null);
    }

    /**
     * 创建确认预览。排程预览要建一条临时计划再回滚 (引擎按计划 id 读计划), 它必须在<b>外层事务和行锁之外</b>先算:
     * 外层 {@code factories} / {@code product_types} 行被 FOR UPDATE 锁住后, 内层插入 production_plans
     * 的外键检查 (FOR KEY SHARE) 会一直等外层释放, 外层又在 Java 里等内层返回 ⇒ 应用层互等, 数据库死锁检测看不到。
     * 所以这里不加 {@code @Transactional}: 先算排程, 再用事务模板跑原来的预览事务。预览只是建议,
     * 确认时会在锁内重算并比对指纹, 两步之间数据变了只会让确认被拒、要求重新预览。
     */
    @Override
    public Map<String, Object> createInsertPreview(String factoryId, Long operatorId, String slotId,
                                                   GetInsertSlotsRequest request) {
        UrgentInsertScheduleService.Proposal precomputed = precomputeSchedule(factoryId, operatorId, slotId, request);
        return new TransactionTemplate(transactionManager).execute(
                status -> analyzeInsertImpactInternal(factoryId, slotId, request, true, precomputed));
    }

    /**
     * 不持锁地读时段 / 产品, 条件齐了才算排程; 任何一项没核对通过返回 null (预览里记为「排程未评估」,
     * 原因已在原料 / 产能 / 单位里说了)。真正的校验错误由随后的预览事务照原样抛出。
     */
    private UrgentInsertScheduleService.Proposal precomputeSchedule(
            String factoryId, Long operatorId, String slotId, GetInsertSlotsRequest request) {
        // 临时计划的 created_by 非空且外键到 users: 必须是真实操作人; 没有 (如平台账号) 就不算排程
        if (operatorId == null || operatorId <= 0) {
            return null;
        }
        try {
            InsertSlot slot = insertSlotRepository.findByIdAndFactoryId(slotId, factoryId).orElse(null);
            ProductType product = request.getProductTypeId() == null ? null
                    : productTypeRepository.findByIdAndFactoryId(request.getProductTypeId(), factoryId).orElse(null);
            if (slot == null || product == null || product.getUnit() == null || product.getUnit().isBlank()
                    || slot.getProductionLineId() == null || slot.getProductionLineId().isBlank()
                    || slot.getStartTime() == null || slot.getEndTime() == null) {
                return null;
            }
            String quantityUnit = tryNormalizeQuantityUnit(request.getQuantityUnit());
            String productCapacityUnit = capacityUnitForProduct(product.getUnit());
            if (quantityUnit == null || productCapacityUnit == null
                    || !sameCapacityDimension(productCapacityUnit, quantityUnit)) {
                return null;
            }
            validatePreviewQuantity(request.getRequiredQuantity(), false);
            UrgentInsertMaterialAssessor.Assessment material = materialAssessor.assess(
                    factoryId, request.getProductTypeId(), request.getRequiredQuantity());
            if (!material.ok()) {
                return null;
            }
            return scheduleService.dryRun(factoryId, slot, createProductionPlanFromSlot(factoryId, operatorId, slot,
                    ConfirmUrgentInsertRequest.builder().slotId(slot.getId())
                            .productTypeId(request.getProductTypeId())
                            .plannedQuantity(request.getRequiredQuantity()).build(),
                    product.getUnit(), material));
        } catch (RuntimeException e) {
            log.warn("插单排程预览前置失败, 按未评估处理: factoryId={}, slotId={}, reason={}", factoryId, slotId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> analyzeInsertImpactInternal(
            String factoryId, String slotId, GetInsertSlotsRequest request, boolean createPreview,
            UrgentInsertScheduleService.Proposal precomputedSchedule) {
        log.info("分析插单影响: factoryId={}, slotId={}, productTypeId={}, quantity={}",
                factoryId, slotId, request.getProductTypeId(), request.getRequiredQuantity());

        // Only the explicit POST preview path is a write. GET impact remains
        // retry-safe and read-only for users who only have scheduling access.
        if (createPreview) {
            resourceWriteLock.lockFactory(factoryId);
        }
        String quantityUnit = tryNormalizeQuantityUnit(request.getQuantityUnit());
        ProductType product = (createPreview
                ? productTypeRepository.findByIdAndFactoryIdForUpdate(request.getProductTypeId(), factoryId)
                : productTypeRepository.findByIdAndFactoryId(request.getProductTypeId(), factoryId))
                .orElseThrow(() -> new EntityNotFoundException("ProductType", request.getProductTypeId()));
        String plannedUnit = product.getUnit();
        String productCapacityUnit = capacityUnitForProduct(plannedUnit);
        if (createPreview) {
            validatePreviewQuantity(request.getRequiredQuantity(), false);
        }

        // 只按本厂查: 他厂时段 404, 不泄露他厂排产信息
        InsertSlot slot = insertSlotRepository.findByIdAndFactoryId(slotId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("InsertSlot", slotId));
        boolean physicalLineBound = slot.getProductionLineId() != null
                && !slot.getProductionLineId().isBlank()
                && slot.getStartTime() != null && slot.getEndTime() != null;
        if (createPreview && !physicalLineBound) {
            throw new BusinessException(409, "时段没有绑定实际产线或完整时间窗，不能生成确认预览")
                    .withHint("请刷新时段列表，选择已绑定实际 active 产线的时段")
                    .withCode("INSERT_SLOT_RESOURCE_UNVERIFIED");
        }

        // 使用 ImpactAnalysisService 进行详细链式影响分析
        ChainImpactResult chainImpact = impactAnalysisService.calculateChainImpact(
                factoryId,
                slot.getStartTime(),
                slot.getEndTime(),
                request.getProductTypeId(),
                request.getRequiredQuantity()
        );
        if (createPreview && !isCompleteChainImpact(chainImpact)) {
            throw new BusinessException(409, "影响分析未完成，不能创建确认预览")
                    .withCode("INSERT_IMPACT_PREVIEW_UNVERIFIED")
                    .withHint("刷新影响分析，取得完整影响等级和受影响计划统计后重试");
        }

        // 计算多维度推荐评分
        RecommendScoreResult scoreResult = impactAnalysisService.calculateRecommendScore(
                factoryId,
                slot.getStartTime(),
                slot.getEndTime(),
                request.getRequiredQuantity(),
                request.getProductTypeId(),
                request.getDeadline()
        );

        // 资源检查
        UrgentInsertMaterialAssessor.Assessment materialPlan = materialAssessor.assess(
                factoryId, request.getProductTypeId(), request.getRequiredQuantity());
        ResourceCheckResult materialCheck = toMaterialCheck(materialPlan, request.getRequiredQuantity());
        // S4: 产能与设备只读对话建厂 / 工厂配置写下的配置 (同单位、显式设备), 缺配置 = 未验证
        ResourceCheckResult equipmentCheck = lineChecks.checkEquipment(factoryId, request.getProductTypeId());
        boolean productSpecificCapacityRequired = "个".equals(
                plannedUnit == null ? null : plannedUnit.trim());
        boolean productSpecificCapacity = !productSpecificCapacityRequired
                || !physicalLineBound
                || hasProductSpecificCapacity(factoryId, request.getProductTypeId(),
                slot.getProductionLineId(), quantityUnit, slot.getStartTime());
        boolean unitCompatible = quantityUnit != null
                && productCapacityUnit != null
                && sameCapacityDimension(productCapacityUnit, quantityUnit)
                && productSpecificCapacity;
        ResourceCheckResult capacityCheck = unitCompatible && physicalLineBound
                ? lineChecks.checkCapacity(factoryId, request.getProductTypeId(),
                        request.getRequiredQuantity(), quantityUnit, slot.getProductionLineId(),
                        slot.getStartTime(), slot.getEndTime())
                : ResourceCheckResult.unverified("CAPACITY",
                        productCapacityUnit == null
                                ? "产能未验证: 产品实际单位无法映射到 PIECE / KG / BATCH"
                                : !productSpecificCapacity
                                ? "产能未验证: 个单位必须有该产品在实际产线上的专用 PIECE 产能配置"
                                : "产能未验证: 产品实际单位与请求数量单位不一致",
                        request.getRequiredQuantity());
        WorkerShiftAvailabilityCheck.Result workerCheck = checkWorkers(factoryId, slot);

        // 缺口 17 第 2 项: 排程预览 (引擎算, 临时计划算完回滚)。原料不满足 / 单位或产线没验证时不算,
        // 状态 NOT_EVALUATED, 原因已在前面的原料 / 产能里说了。
        // 只读的 GET 影响分析不算排程 (排程预览要建临时计划再回滚, 那是写事务): 只在创建确认预览时算
        boolean scheduleAttempted = createPreview && materialPlan.ok() && unitCompatible && physicalLineBound
                && plannedUnit != null && !plannedUnit.isBlank();
        UrgentInsertScheduleService.Proposal schedulePlan = scheduleAttempted && precomputedSchedule != null
                ? precomputedSchedule
                : UrgentInsertScheduleService.Proposal.notEvaluated(createPreview
                        ? "排程未评估: 原料、产能或产线信息还没核对通过"
                        : "排程只在创建确认预览时计算");

        Map<String, Object> result = new HashMap<>();

        // 基本信息
        result.put("slotId", slotId);
        result.put("startTime", slot.getStartTime());
        result.put("endTime", slot.getEndTime());
        result.put("availableCapacity", slot.getAvailableCapacity());
        result.put("requiredCapacity", request.getRequiredQuantity());

        // 影响等级 (使用链式分析结果)
        result.put("impactLevel", chainImpact.getImpactLevel());
        result.put("impactLevelDisplayName", chainImpact.getImpactLevelDisplayName());
        result.put("impactScore", chainImpact.getImpactScore());
        result.put("affectedPlanCount", chainImpact.getTotalAffectedPlans());

        // 链式影响详情
        Map<String, Object> chainDetails = new HashMap<>();
        chainDetails.put("directConflicts", chainImpact.getDirectConflicts());
        chainDetails.put("cascadeDelays", chainImpact.getCascadeDelays());
        chainDetails.put("totalAffectedPlans", chainImpact.getTotalAffectedPlans());
        chainDetails.put("maxDelayMinutes", chainImpact.getMaxDelayMinutes());
        chainDetails.put("averageDelayMinutes", chainImpact.getAverageDelayMinutes());
        chainDetails.put("totalDelayMinutes", chainImpact.getTotalDelayMinutes());
        chainDetails.put("affectedVipCustomers", chainImpact.getAffectedVipCustomers());
        chainDetails.put("criticalCrPlans", chainImpact.getCriticalCrPlans());
        chainDetails.put("exceedingDeadlinePlans", chainImpact.getExceedingDeadlinePlans());
        result.put("chainImpactDetails", chainDetails);

        // 受影响计划列表
        result.put("impactedPlans", chainImpact.getAffectedPlans());
        result.put("impactedPlanCount", chainImpact.getTotalAffectedPlans());

        // 推荐评分详情
        Map<String, Object> scoreDetails = new HashMap<>();
        scoreDetails.put("totalScore", scoreResult.getTotalScore());
        scoreDetails.put("capacityFactor", scoreResult.getCapacityFactor());
        scoreDetails.put("workerFactor", scoreResult.getWorkerFactor());
        scoreDetails.put("deadlineFactor", scoreResult.getDeadlineFactor());
        scoreDetails.put("impactFactor", scoreResult.getImpactFactor());
        scoreDetails.put("switchCostFactor", scoreResult.getSwitchCostFactor());
        result.put("recommendScore", scoreResult.getTotalScore());
        result.put("recommendationReason", scoreResult.getRecommendationReason());
        result.put("scoreBreakdown", scoreDetails);

        // 资源状态
        Map<String, Object> resourceStatus = new HashMap<>();
        resourceStatus.put("materialAvailable", materialCheck.isAvailable());
        resourceStatus.put("materialVerified", materialCheck.isVerified());
        resourceStatus.put("materialMessage", materialCheck.getMessage());
        resourceStatus.put("equipmentAvailable", equipmentCheck.isAvailable());
        resourceStatus.put("equipmentVerified", equipmentCheck.isVerified());
        resourceStatus.put("equipmentMessage", equipmentCheck.getMessage());
        resourceStatus.put("equipmentStatus", LineCapacityEquipmentCheck.status(equipmentCheck));
        resourceStatus.put("capacityAvailable", capacityCheck.isAvailable());
        resourceStatus.put("capacityVerified", capacityCheck.isVerified());
        resourceStatus.put("capacityMessage", capacityCheck.getMessage());
        resourceStatus.put("capacityStatus", LineCapacityEquipmentCheck.status(capacityCheck));
        resourceStatus.put("capacityUnit", unitCompatible ? quantityUnit : null);
        resourceStatus.put("hasEnoughWorkers", workerCheck.available());
        resourceStatus.put("workerVerified", workerCheck.verified());
        resourceStatus.put("workerMessage", workerCheck.message());
        // 没有排班数据 (不是数据显示人不够): 判「未核对」, 不阻断; 其余没核对到 / 数据读不全仍记 MISSING
        resourceStatus.put("workerStatus", workerCheck.noScheduleData() ? "UNVERIFIED"
                : !workerCheck.verified() ? "MISSING"
                : workerCheck.available() ? "READY" : "SHORTAGE");
        resourceStatus.put("workerNoScheduleData", workerCheck.noScheduleData());
        // 定员沿用时段要求；可用人数来自实际班次/请假/任务，不再信生成时段时的估算快照。
        resourceStatus.put("workerRequirementSource", "SLOT_REQUIREMENT");
        resourceStatus.put("workerAvailabilitySource", "HR_SHIFT_AND_ASSIGNMENT");
        resourceStatus.put("workerReservationStatus", "NOT_RESERVED");
        resourceStatus.put("requiredWorkers", slot.getRequiredWorkers());
        // 没有排班数据时不报「0 人可用」: 不知道不等于 0
        resourceStatus.put("availableWorkers", workerCheck.noScheduleData() ? null : workerCheck.availableWorkers());
        result.put("resourceStatus", resourceStatus);
        // 缺口 17: 按生效配方版本算的原料需求、缺口 (缺什么/缺多少)、在途; 确认时按同一口径重算比对
        result.put("materialPlan", materialPlan.toMap());
        // 缺口 17 第 2 项: 插单排进去之后插单自己和每个被挤计划的新完成日、交期是否被突破
        result.put("schedulePlan", schedulePlan.toMap());

        // 风险评估
        String riskLevel = determineRiskLevel(chainImpact, materialCheck, equipmentCheck, capacityCheck, workerCheck);
        result.put("riskLevel", riskLevel);
        result.put("riskWarnings", chainImpact.getRiskWarnings());

        // 审批要求
        result.put("requiresApproval", chainImpact.getRequiresApproval());
        result.put("approvalLevel", chainImpact.getApprovalLevel());

        // 可行性 —— 2026-10-24 止血: 任何一项没用真实数据核对过, isFeasible 都不得为 true。
        // 三态 feasibilityStatus: FEASIBLE / INFEASIBLE (核对过且确定不够) / UNVERIFIED (有项没核对)
        List<String> unverifiedReasons = new ArrayList<>();
        if (!materialCheck.isVerified()) {
            unverifiedReasons.add(materialCheck.getMessage());
        }
        if (!equipmentCheck.isVerified()) {
            unverifiedReasons.add(equipmentCheck.getMessage());
        }
        if (!workerCheck.verified()) {
            unverifiedReasons.add(workerCheck.message());
        }
        if (!capacityCheck.isVerified()) {
            unverifiedReasons.add(capacityCheck.getMessage());
        }
        boolean scheduleBlocked = UrgentInsertScheduleService.BLOCKED.equals(schedulePlan.status());
        // A18: 工厂配了「照插并提示」的排不进原因 (UNSCHEDULED) 不算没核对, 它作为提示随结果返回
        boolean scheduleUnscheduled = UrgentInsertScheduleService.UNSCHEDULED.equals(schedulePlan.status());
        if (scheduleAttempted && !schedulePlan.ok() && !scheduleBlocked && !scheduleUnscheduled) {
            unverifiedReasons.add(schedulePlan.message());
        }
        boolean resourcesVerified = unverifiedReasons.isEmpty();
        boolean definitelyShort = (materialCheck.isVerified() && !materialCheck.isAvailable())
                || (equipmentCheck.isVerified() && !equipmentCheck.isAvailable())
                || (capacityCheck.isVerified() && !capacityCheck.isAvailable())
                || (workerCheck.verified() && !workerCheck.available())
                || scheduleBlocked;
        boolean isFeasible = resourcesVerified && !definitelyShort
                && materialCheck.isAvailable() && equipmentCheck.isAvailable()
                && workerCheck.available() && capacityCheck.isAvailable();
        result.put("isFeasible", isFeasible);
        List<String> previewWarnings = new ArrayList<>();
        if (workerCheck.noScheduleData()) {
            previewWarnings.add(WorkerShiftAvailabilityCheck.NO_SCHEDULE_DATA);
        }
        if (scheduleUnscheduled) {
            previewWarnings.add(schedulePlan.message());
        }
        result.put("uncheckedWarnings", previewWarnings);
        result.put("resourcesVerified", resourcesVerified);
        result.put("unverifiedReasons", unverifiedReasons);
        result.put("feasibilityStatus", isFeasible ? "FEASIBLE" : (definitelyShort ? "INFEASIBLE" : "UNVERIFIED"));
        if (!isFeasible) {
            result.put("infeasibleReason", buildInfeasibleReason(materialCheck, equipmentCheck, capacityCheck, workerCheck)
                    + (scheduleBlocked ? "排程排不进去: " + schedulePlan.message() + "; " : ""));
        }

        // Only a product/unit-compatible analysis may issue a confirmation
        // preview.  Unknown package units remain UNVERIFIED and never get a
        // guessed PIECE/KG/BATCH snapshot.
        // 原料无法核对 (无指纹) 时不签发预览: 确认本来就会拒绝, 而无指纹的预览永远复用不上, 会一条条占满额度
        // 设备 / 产能没核对过或不够时同样不签发: 确认时 (INSERT_RESOURCE_RECHECK_FAILED) 一定会拒绝, 预览放行、确认再拦是
        // 口径不一致 (验收: 产品没配设备, 预览照给 previewId, 确认 409; 配上设备同样的流程就是 200)。理由写在
        // unverifiedReasons / infeasibleReason 里, 用户看得到该补什么。单位对不上 / 个单位缺专用产能本来就不签发, 这是同一条规则。
        boolean confirmCanPassResourceRecheck = equipmentCheck.isVerified() && equipmentCheck.isAvailable()
                && capacityCheck.isVerified() && capacityCheck.isAvailable();
        if (createPreview && unitCompatible && plannedUnit != null && !plannedUnit.isBlank()
                && confirmCanPassResourceRecheck
                && materialPlan.fingerprint() != null && schedulePlan.fingerprint() != null) {
            LocalDateTime validAfter = LocalDateTime.now().minusMinutes(PREVIEW_VALID_MINUTES);
            List<InsertSlotPreview> recent = insertSlotPreviewRepository
                    .findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(factoryId, validAfter);
            InsertSlotPreview preview = recent.stream()
                    .filter(candidate -> previewMatches(candidate, slot, product, request,
                            quantityUnit, plannedUnit, chainImpact, materialPlan, schedulePlan))
                    .findFirst()
                    .orElse(null);
            if (preview == null) {
                if (recent.size() >= MAX_ACTIVE_PREVIEWS_PER_FACTORY) {
                    throw new BusinessException(409, "当前工厂有效插单预览已达上限，未创建新预览")
                            .withCode("INSERT_PREVIEW_LIMIT")
                            .withHint("请使用现有预览或等待过期后重新分析");
                }
                preview = insertSlotPreviewRepository.save(InsertSlotPreview.builder()
                        .id(UUID.randomUUID().toString())
                        .factoryId(factoryId)
                        .slotId(slot.getId())
                        .productTypeId(product.getId())
                        .previewQuantity(request.getRequiredQuantity())
                        .quantityUnit(quantityUnit)
                        .plannedUnit(plannedUnit)
                        .productionLineId(slot.getProductionLineId())
                        .windowStart(slot.getStartTime())
                        .windowEnd(slot.getEndTime())
                        .slotUpdatedAt(slot.getUpdatedAt())
                        .impactLevel(chainImpact.getImpactLevel())
                        .requiresApproval(Boolean.TRUE.equals(chainImpact.getRequiresApproval()))
                        .affectedPlanCount(chainImpact.getTotalAffectedPlans() == null
                                ? 0 : chainImpact.getTotalAffectedPlans())
                        .candidateNumber(Math.toIntExact(
                                insertSlotPreviewRepository.countByFactoryIdAndSlotId(factoryId, slot.getId()) + 1))
                        .bomRecipeId(materialPlan.bomRecipeId())
                        .bomVersion(materialPlan.bomVersion())
                        .materialFingerprint(materialPlan.fingerprint())
                        .impactFingerprint(impactFingerprint(chainImpact))
                        .scheduleFingerprint(schedulePlan.fingerprint())
                        .build());
            }
            result.put("previewId", preview.getId());
            result.put("quantityUnit", preview.getQuantityUnit());
            result.put("plannedUnit", preview.getPlannedUnit());
            result.put("previewQuantity", preview.getPreviewQuantity());
            result.put("expiresAt", preview.getCreatedAt() == null
                    ? null : preview.getCreatedAt().plusMinutes(PREVIEW_VALID_MINUTES));
        } else {
            result.put("previewId", null);
            result.put("quantityUnit", unitCompatible ? quantityUnit : null);
            result.put("plannedUnit", plannedUnit);
            result.put("previewQuantity", request.getRequiredQuantity());
            result.put("expiresAt", null);
        }

        return result;
    }

    /** 无真实核验结果时的失败说明；不得退回生成时段时的估算人数。 */
    static final String WORKER_UNVERIFIED_REASON =
            "人员未验证: 未取得完整的HR班次和人员占用核验结果";

    private WorkerShiftAvailabilityCheck.Result checkWorkers(String factoryId, InsertSlot slot) {
        // A1: 产线没配标准人数 => 不比较、不提示 (不拿编出来的人数去比, 也不去读班次)
        if (slot.getRequiredWorkers() == null || slot.getRequiredWorkers() <= 0) {
            return WorkerShiftAvailabilityCheck.Result.notConfigured();
        }
        WorkerShiftAvailabilityCheck.Result checked = workerShifts.check(
                factoryId, slot.getStartTime(), slot.getEndTime(), slot.getRequiredWorkers());
        // 缺失结果绝不能退回 cached availableWorkers 或把未知当成可派。
        return checked != null ? checked : WorkerShiftAvailabilityCheck.Result.unverified(
                slot.getRequiredWorkers(), WORKER_UNVERIFIED_REASON);
    }

    /** 确认 / 批准时没能核对的项, 随结果返回; 现在只有「没有排班数据」一种。 */
    private static List<String> uncheckedWarnings(WorkerShiftAvailabilityCheck.Result workerCheck) {
        List<String> warnings = new ArrayList<>();
        if (workerCheck.noScheduleData()) {
            warnings.add(WorkerShiftAvailabilityCheck.NO_SCHEDULE_DATA);
        }
        // A1: 配了标准人数、已核对、人不够 => 只提示, 不拦
        if (workerCheck.verified() && !workerCheck.isNotConfigured() && !workerCheck.available()) {
            warnings.add("人员提示：本产线标准人数 " + workerCheck.requiredWorkers() + " 人，该时段已核对可用 "
                    + workerCheck.availableWorkers() + " 人，人数不足（仅提示，不拦截）");
        }
        return warnings;
    }

    /** A18: 排不进但工厂配了照插: 把「没排入及原因」随结果提示出来。 */
    private static List<String> withScheduleWarning(List<String> warnings, UrgentInsertScheduleService.Proposal applied) {
        if (applied == null || !UrgentInsertScheduleService.UNSCHEDULED.equals(applied.status())) {
            return warnings;
        }
        List<String> all = new ArrayList<>(warnings);
        all.add(applied.message());
        return all;
    }

    /** A2: 缺料但允许插 (开关关, 或开关开且填了原因) 时, 把缺料随结果提示出来。 */
    private static List<String> withMaterialWarning(List<String> warnings,
                                                    UrgentInsertMaterialAssessor.Assessment material) {
        if (material == null || material.status() != UrgentInsertMaterialAssessor.Status.SHORT) {
            return warnings;
        }
        List<String> all = new ArrayList<>(warnings);
        all.add("缺料提示：" + material.message());
        return all;
    }

    /**
     * 只有明确提交人员名单的请求才核验名单。空名单仍走既有建待排计划、后续分配路径；
     * 这里不写 worker_assignments，也不把只读资格核验声称为并发预留或实际到岗。
     */
    /** 名单格式 (重复 / 空 / 非正数) 先于任何人员读取就拒绝: 无效名单不该触发排班读取。 */
    private void validateWorkerListShape(ConfirmUrgentInsertRequest request) {
        List<Long> selected = request.getAssignedWorkerIds();
        if (selected == null || selected.isEmpty()) return;
        if (selected.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(selected).size() != selected.size()) {
            throw new BusinessException(409, "指定工人名单无效，请重新选择，未创建计划")
                    .withCode("INSERT_WORKER_SELECTION_INVALID")
                    .withHint("重新打开人员选择，去掉重复或无效项，仅选择本厂可派工人");
        }
    }

    private void validateSelectedWorkers(WorkerShiftAvailabilityCheck.Result checked, ConfirmUrgentInsertRequest request) {
        List<Long> selected = request.getAssignedWorkerIds();
        if (selected == null || selected.isEmpty()) return;
        if (checked.noScheduleData() || checked.isNotConfigured()) {
            // 没有排班数据 / 产线没配标准人数: 名单无从核对, 照插 (不编人数); 有数据但读不全仍按原规则拦
            return;
        }
        if (!checked.verified()) {
            throw new BusinessException(409, checked.message() + "，未创建计划")
                    .withCode("INSERT_WORKERS_UNVERIFIED")
                    .withHint("请先确认该时段的HR班次、请假和任务安排，再刷新人员列表；当前没有预留工人或创建计划");
        }
        // A1: 人数不够只提示 (见 uncheckedWarnings), 不再拦; 只拦「点名的人本身不可派」
        if (!checked.availableWorkerIds().containsAll(selected)) {
            throw new BusinessException(409, "指定工人不足或在该时段不可派，请刷新班次和人员，未创建计划")
                    .withCode("INSERT_WORKERS_UNAVAILABLE")
                    .withHint("请重新选择覆盖整个时段、人数满足要求的工人；不可用人员需先确认班次或任务安排");
        }
    }

    /**
     * An explicit equipment list is a claim about the configured product
     * equipment, not a free-form list of factory IDs.  Keep it tenant scoped
     * and require the complete configured set before confirmation.
     */
    private void validateSelectedEquipment(String factoryId, ConfirmUrgentInsertRequest request) {
        List<String> selected = request.getAssignedEquipmentIds();
        if (selected == null || selected.isEmpty()) return;
        if (selected.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(selected).size() != selected.size()) {
            throw new BusinessException(409, "指定设备名单无效，未创建计划")
                    .withCode("INSERT_EQUIPMENT_SELECTION_INVALID")
                    .withHint("重新选择本厂设备，去掉重复或空设备ID");
        }

        Set<Long> selectedIds = new HashSet<>();
        try {
            for (String id : selected) {
                long parsed = Long.parseLong(id.trim());
                if (parsed <= 0 || !selectedIds.add(parsed)) {
                    throw new NumberFormatException(id);
                }
            }
        } catch (NumberFormatException ex) {
            throw new BusinessException(409, "指定设备名单无效，未创建计划")
                    .withCode("INSERT_EQUIPMENT_SELECTION_INVALID")
                    .withHint("重新选择本厂设备，设备ID必须为正整数");
        }

        Set<Long> requiredIds = equipmentRequirements
                .findByFactoryIdAndProductTypeId(factoryId, request.getProductTypeId())
                .stream()
                .map(com.cretas.aims.entity.ProductEquipmentRequirement::getEquipmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (requiredIds.isEmpty() || !selectedIds.equals(requiredIds)) {
            throw new BusinessException(409, "指定设备与产品要求不一致，未创建计划")
                    .withCode("INSERT_EQUIPMENT_SELECTION_MISMATCH")
                    .withHint("按当前产品的设备要求重新选择完整设备名单");
        }
        for (Long equipmentId : selectedIds) {
            equipmentRepository.findByIdAndFactoryId(equipmentId, factoryId)
                    .orElseThrow(() -> new BusinessException(409, "指定设备不属于本厂或已删除，未创建计划")
                            .withCode("INSERT_EQUIPMENT_SELECTION_MISMATCH")
                            .withHint("仅选择当前工厂仍有效的设备"));
        }
    }

    private String normalizeQuantityUnit(String unit) {
        String normalized = tryNormalizeQuantityUnit(unit);
        if (normalized == null) {
            throw new BusinessException(400, "数量单位只能是 PIECE / KG / BATCH")
                    .withHint("请选择与产品实际单位一致的 PIECE、KG 或 BATCH");
        }
        return normalized;
    }

    private String tryNormalizeQuantityUnit(String unit) {
        com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit parsed =
                LineCapacityEquipmentCheck.parseUnit(unit);
        return parsed == null ? null : parsed.name();
    }

    private String capacityUnitForProduct(String productUnit) {
        if (productUnit == null) return null;
        return switch (productUnit.trim().toLowerCase(Locale.ROOT)) {
            case "件", "个", "piece" -> "PIECE";
            case "kg", "公斤", "千克" -> "KG";
            case "批", "batch" -> "BATCH";
            default -> null;
        };
    }

    /** Capacity compatibility is a closed enum contract, never label equality. */
    private boolean sameCapacityDimension(String left, String right) {
        var leftDimension = LineCapacityEquipmentCheck.parseUnit(left);
        var rightDimension = LineCapacityEquipmentCheck.parseUnit(right);
        return leftDimension != null && leftDimension == rightDimension;
    }

    /**
     * Identity of an unchanged product snapshot, not physical unit equivalence.
     * Even equivalent spellings are a changed stored label and require a new
     * preview. Never normalize aliases or merge 个/件 when checking this identity.
     */
    private record ProductUnitIdentity(String productTypeId, String exactStoredLabel) {
    }

    private boolean hasProductSpecificCapacity(String factoryId, String productTypeId,
                                               String productionLineId, String quantityUnit,
                                               LocalDateTime at) {
        com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit unit =
                LineCapacityEquipmentCheck.parseUnit(quantityUnit);
        return unit != null && capacityConfigRepository.findEffectiveForProduct(
                        factoryId, productTypeId, at).stream()
                .anyMatch(config -> productTypeId.equals(config.getProductTypeId())
                        && productionLineId.equals(config.getProductionLineId())
                        && unit == config.getCapacityUnit());
    }

    private void validatePreviewQuantity(BigDecimal quantity, boolean confirmation) {
        if (quantity == null || quantity.signum() <= 0
                || quantity.scale() > 2
                || quantity.precision() - quantity.scale() > 8) {
            throw new BusinessException(409,
                    confirmation ? "确认数量必须与预览一致且最多8位整数、2位小数"
                            : "预览数量必须最多8位整数、2位小数")
                    .withCode("INSERT_QUANTITY_PRECISION_INVALID")
                    .withHint("调整数量后重新分析并创建预览");
        }
    }

    private boolean previewMatches(InsertSlotPreview preview, InsertSlot slot, ProductType product,
                                   GetInsertSlotsRequest request, String quantityUnit, String plannedUnit,
                                   ChainImpactResult chainImpact,
                                   UrgentInsertMaterialAssessor.Assessment materialPlan,
                                   UrgentInsertScheduleService.Proposal schedulePlan) {
        return preview.getMaterialFingerprint() != null
                && preview.getScheduleFingerprint() != null
                && Objects.equals(preview.getScheduleFingerprint(), schedulePlan.fingerprint())
                && Objects.equals(preview.getMaterialFingerprint(), materialPlan.fingerprint())
                && Objects.equals(preview.getImpactFingerprint(), impactFingerprint(chainImpact))
                && Objects.equals(preview.getSlotId(), slot.getId())
                && Objects.equals(preview.getProductTypeId(), product.getId())
                && preview.getPreviewQuantity() != null && request.getRequiredQuantity() != null
                && preview.getPreviewQuantity().compareTo(request.getRequiredQuantity()) == 0
                && sameCapacityDimension(preview.getQuantityUnit(), quantityUnit)
                && new ProductUnitIdentity(preview.getProductTypeId(), preview.getPlannedUnit())
                        .equals(new ProductUnitIdentity(product.getId(), plannedUnit))
                && Objects.equals(preview.getProductionLineId(), slot.getProductionLineId())
                && Objects.equals(preview.getWindowStart(), slot.getStartTime())
                && Objects.equals(preview.getWindowEnd(), slot.getEndTime())
                && Objects.equals(preview.getSlotUpdatedAt(), slot.getUpdatedAt())
                && Objects.equals(preview.getImpactLevel(), chainImpact.getImpactLevel())
                && Objects.equals(preview.getRequiresApproval(),
                Boolean.TRUE.equals(chainImpact.getRequiresApproval()))
                && Objects.equals(preview.getAffectedPlanCount(),
                chainImpact.getTotalAffectedPlans() == null ? 0 : chainImpact.getTotalAffectedPlans());
    }

    private String requireProductUnit(ProductType product) {
        if (product.getUnit() == null || product.getUnit().isBlank()) {
            throw new BusinessException(409, "产品实际单位未配置，不能创建插单预览或计划")
                    .withCode("INSERT_PRODUCT_UNIT_UNVERIFIED")
                    .withHint("先补齐产品实际单位后重新分析");
        }
        return product.getUnit();
    }

    private PreviewValidation validatePreviewAndFreshResources(
            String factoryId, ConfirmUrgentInsertRequest request) {
        resourceWriteLock.lockFactory(factoryId);
        if (request.getPreviewId() == null || request.getPreviewId().isBlank()) {
            throw new BusinessException(400, "预览ID不能为空，不能确认插单")
                    .withCode("INSERT_PREVIEW_REQUIRED");
        }
        validatePreviewQuantity(request.getPlannedQuantity(), true);

        InsertSlotPreview preview = insertSlotPreviewRepository
                .findByIdAndFactoryId(request.getPreviewId(), factoryId)
                .orElseThrow(() -> new EntityNotFoundException("InsertSlotPreview", request.getPreviewId()));
        if (preview.getCreatedAt() == null
                || preview.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(PREVIEW_VALID_MINUTES))) {
            throw new BusinessException(409, "插单预览已过期，请重新分析，未创建计划")
                    .withCode("INSERT_PREVIEW_EXPIRED")
                    .withHint("返回时段影响分析并创建新的确认预览");
        }
        if (!Objects.equals(request.getSlotId(), preview.getSlotId())
                || !Objects.equals(request.getProductTypeId(), preview.getProductTypeId())) {
            throw new BusinessException(409, "预览快照与当前插单参数不一致，未创建计划")
                    .withCode("INSERT_PREVIEW_MISMATCH")
                    .withHint("使用同一份最新预览的时段和产品参数");
        }

        String quantityUnit = normalizeQuantityUnit(request.getQuantityUnit());
        if (!sameCapacityDimension(quantityUnit, preview.getQuantityUnit())
                || request.getPlannedQuantity() == null
                || request.getPlannedQuantity().compareTo(preview.getPreviewQuantity()) != 0) {
            throw new BusinessException(409, "预览快照的数量或单位已变化，请重新分析，未创建计划")
                    .withCode("INSERT_PREVIEW_MISMATCH")
                    .withHint("返回影响分析并创建新的确认预览");
        }

        ProductType product = productTypeRepository.findByIdAndFactoryIdForUpdate(
                        request.getProductTypeId(), factoryId)
                .orElseThrow(() -> new EntityNotFoundException("ProductType", request.getProductTypeId()));
        String plannedUnit = requireProductUnit(product);
        if (!new ProductUnitIdentity(product.getId(), plannedUnit)
                .equals(new ProductUnitIdentity(preview.getProductTypeId(), preview.getPlannedUnit()))) {
            throw new BusinessException(409, "产品实际单位已变化，请重新分析，未创建计划")
                    .withCode("INSERT_PREVIEW_PRODUCT_CHANGED")
                    .withHint("重新分析当前产品后再确认");
        }

        // The slot is loaded only after factory and product locks, and all
        // physical-line/window bindings are checked against the snapshot.
        InsertSlot slot = loadOwnSlot(factoryId, request.getSlotId());
        if (!Objects.equals(slot.getProductionLineId(), preview.getProductionLineId())
                || !Objects.equals(slot.getStartTime(), preview.getWindowStart())
                || !Objects.equals(slot.getEndTime(), preview.getWindowEnd())
                || preview.getSlotUpdatedAt() == null
                || slot.getUpdatedAt() == null
                || !Objects.equals(slot.getUpdatedAt(), preview.getSlotUpdatedAt())) {
            throw new BusinessException(409, "时段产线或时间窗已变化，请重新分析，未创建计划")
                    .withCode("INSERT_PREVIEW_SLOT_CHANGED")
                    .withHint("刷新时段列表并重新创建确认预览");
        }

        ResourceCheckResult equipmentCheck = lineChecks.checkEquipment(factoryId, request.getProductTypeId());
        String productCapacityUnit = capacityUnitForProduct(plannedUnit);
        boolean productSpecificCapacity = !"个".equals(plannedUnit.trim())
                || hasProductSpecificCapacity(factoryId, request.getProductTypeId(),
                slot.getProductionLineId(), quantityUnit, slot.getStartTime());
        ResourceCheckResult capacityCheck = lineChecks.checkCapacity(
                factoryId, request.getProductTypeId(), request.getPlannedQuantity(), quantityUnit,
                slot.getProductionLineId(), slot.getStartTime(), slot.getEndTime());
        // 说清是哪一项没过 (设备检修 / 没配设备 / 没配产能 / 产能配置还没生效 / 单位对不上 …), 不再只说「复核未通过」
        List<String> recheckFailures = new ArrayList<>();
        if (!sameCapacityDimension(quantityUnit, productCapacityUnit)) {
            recheckFailures.add("产能未验证: 产品实际单位与请求数量单位不一致");
        }
        if (!productSpecificCapacity) {
            recheckFailures.add("产能未验证: 个单位必须有该产品在实际产线上的专用 PIECE 产能配置");
        }
        if (!equipmentCheck.isVerified() || !equipmentCheck.isAvailable()) {
            recheckFailures.add(equipmentCheck.getMessage());
        }
        if (!capacityCheck.isVerified() || !capacityCheck.isAvailable()) {
            recheckFailures.add(capacityCheck.getMessage());
        }
        if (!recheckFailures.isEmpty()) {
            throw new BusinessException(409, "确认前产能或设备复核未通过（" + String.join("；", recheckFailures)
                    + "），未创建计划")
                    .withCode("INSERT_RESOURCE_RECHECK_FAILED")
                    .withHint("刷新并重新分析当前产线、时段和资源配置");
        }

        ChainImpactResult freshChainImpact = impactAnalysisService.calculateChainImpact(
                factoryId,
                slot.getStartTime(),
                slot.getEndTime(),
                request.getProductTypeId(),
                request.getPlannedQuantity());
        if (!isCompleteChainImpact(freshChainImpact)) {
            throw new BusinessException(409, "确认前影响分析未完成，未创建计划")
                    .withCode("INSERT_IMPACT_RECHECK_FAILED")
                    .withHint("刷新影响分析并使用完整的最新预览后重试");
        }
        if (!previewImpactMatches(preview, freshChainImpact)
                || preview.getImpactFingerprint() == null
                || !preview.getImpactFingerprint().equals(impactFingerprint(freshChainImpact))) {
            throw new BusinessException(409, "预览影响快照已变化（被挤的计划或顺延结果与预览不一致），请重新预览，未创建计划")
                    .withCode("INSERT_PREVIEW_IMPACT_CHANGED")
                    .withHint("刷新影响分析并创建新的确认预览");
        }

        // 缺口 17: 原料按生效配方版本 + 当前库存重算。缺料 / 无法核对一律拦在建计划之前;
        // 再比对预览时的指纹, 预览之后配方或库存变了就拒绝并要求重新预览。
        UrgentInsertMaterialAssessor.Assessment material = materialAssessor.assess(
                factoryId, request.getProductTypeId(), request.getPlannedQuantity());
        if (material.status() == UrgentInsertMaterialAssessor.Status.SHORT
                && Boolean.TRUE.equals(settingsRepository.findBlockStartOnOpenShortageByFactoryId(factoryId))
                && (request.getShortageForceReason() == null || request.getShortageForceReason().isBlank())) {
            // A2: 和开工缺料同一个工厂开关; 开着才拦, 填了原因可强制插; 关着照插并提示 (见 withMaterialWarning)
            throw new BusinessException(409, "原料不足，未创建计划: " + material.message())
                    .withCode("INSERT_MATERIAL_SHORT")
                    .withHint("先补足缺口原料（或等在途到货）再插单；也可减少插单数量后重新预览；确需先插，请填写缺料强制插单原因");
        }
        if (material.status() == UrgentInsertMaterialAssessor.Status.UNVERIFIED) {
            throw new BusinessException(409, material.message() + "，未创建计划")
                    .withCode("INSERT_MATERIAL_UNVERIFIED")
                    .withHint("补齐该产品的生效配方和原料库存数据后重新预览");
        }
        if (preview.getMaterialFingerprint() == null
                || !preview.getMaterialFingerprint().equals(material.fingerprint())) {
            throw new BusinessException(409, "预览之后配方或库存已变化，请重新预览，未创建计划")
                    .withCode("INSERT_PREVIEW_MATERIAL_CHANGED")
                    .withHint("重新创建确认预览，按最新配方和库存确认");
        }

        return new PreviewValidation(slot, plannedUnit, preview, freshChainImpact, material);
    }

    /** 评估结果转成既有的资源核对结构 (feasibility / 风险等级沿用它)。 */
    private ResourceCheckResult toMaterialCheck(UrgentInsertMaterialAssessor.Assessment a, BigDecimal required) {
        return switch (a.status()) {
            case OK -> ResourceCheckResult.available("MATERIAL", null, required);
            case SHORT -> ResourceCheckResult.unavailable("MATERIAL", a.message(), null, required);
            case UNVERIFIED -> ResourceCheckResult.unverified("MATERIAL", a.message(), required);
        };
    }

    /**
     * 受影响计划指纹: 每个被挤计划的 id / 原完成 / 顺延后完成 / 顺延分钟。
     * 只比数量 (affectedPlanCount) 时, 同数量但换了一批计划或顺延幅度变了也会放行。
     */
    static String impactFingerprint(ChainImpactResult chainImpact) {
        List<AffectedPlanDTO> plans = chainImpact == null || chainImpact.getAffectedPlans() == null
                ? List.of() : chainImpact.getAffectedPlans();
        String canon = plans.stream()
                .sorted(Comparator.comparing(p -> String.valueOf(p.getPlanId())))
                .map(p -> p.getPlanId() + "|" + p.getOriginalEndTime() + "|" + p.getDelayedEndTime()
                        + "|" + p.getDelayMinutes())
                .collect(Collectors.joining(";"));
        return UrgentInsertMaterialAssessor.sha256(canon);
    }

    private boolean isCompleteChainImpact(ChainImpactResult chainImpact) {
        return chainImpact != null
                && chainImpact.getImpactLevel() != null
                && !chainImpact.getImpactLevel().isBlank()
                && chainImpact.getRequiresApproval() != null
                && chainImpact.getTotalAffectedPlans() != null
                && chainImpact.getDirectConflicts() != null
                && chainImpact.getCascadeDelays() != null
                && chainImpact.getMaxDelayMinutes() != null
                && chainImpact.getTotalDelayMinutes() != null
                && chainImpact.getAffectedVipCustomers() != null
                && chainImpact.getCriticalCrPlans() != null
                && chainImpact.getExceedingDeadlinePlans() != null
                && chainImpact.getImpactScore() != null;
    }

    private boolean previewImpactMatches(InsertSlotPreview preview, ChainImpactResult chainImpact) {
        return Objects.equals(preview.getImpactLevel(), chainImpact.getImpactLevel())
                && Objects.equals(preview.getRequiresApproval(), chainImpact.getRequiresApproval())
                && Objects.equals(preview.getAffectedPlanCount(), chainImpact.getTotalAffectedPlans());
    }

    private record PreviewValidation(InsertSlot slot, String plannedUnit, InsertSlotPreview preview,
                                     ChainImpactResult chainImpact,
                                     UrgentInsertMaterialAssessor.Assessment material) {
    }

    /**
     * 写路径取时段: 只认本厂 (他厂 / 不存在 ⇒ 404, 不改任何数据), 且必须仍是 available。
     * 这里的 status 检查只是提前失败; 真正防并发的是 {@link #claimSlotOrThrow} 的条件更新。
     */
    private InsertSlot loadOwnSlot(String factoryId, String slotId) {
        InsertSlot slot = insertSlotRepository.findByIdAndFactoryId(slotId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("InsertSlot", slotId));
        if (!"available".equals(slot.getStatus())) {
            throw new BusinessException(409, "时段已被占用或不可用 (当前状态: " + slot.getStatus()
                    + ")，请刷新后选择其他时段")
                    .withCode("INSERT_SLOT_NOT_AVAILABLE")
                    .withHint("刷新插单时段列表，改选一个状态为「可用」的时段");
        }
        return slot;
    }

    /**
     * 原子占用: UPDATE … SET status='selected' WHERE id AND factory_id AND status='available'。
     * 影响 0 行 = 并发确认 / 重复提交已经占了它 ⇒ 409, 调用方还没建计划, 事务回滚后什么都不留。
     */
    private void claimSlotOrThrow(String factoryId, InsertSlot slot) {
        int claimed = insertSlotRepository.claimAvailableSlot(slot.getId(), factoryId, LocalDateTime.now());
        if (claimed != 1) {
            log.warn("插单时段占用冲突: factoryId={}, slotId={}, updatedRows={}", factoryId, slot.getId(), claimed);
            throw new BusinessException(409, "该时段刚刚已被占用（可能是重复提交或他人同时确认），未创建新计划，请刷新后重试")
                    .withCode("INSERT_SLOT_CLAIM_CONFLICT")
                    .withHint("刷新后查看该时段的最新状态；如果刚才已经提交过，先到生产计划里确认，不要重复提交");
        }
    }

    /**
     * 确定风险等级
     */
    private String determineRiskLevel(ChainImpactResult chainImpact,
                                      ResourceCheckResult materialCheck,
                                      ResourceCheckResult equipmentCheck,
                                      ResourceCheckResult capacityCheck,
                                      WorkerShiftAvailabilityCheck.Result workerCheck) {
        // 核对过且确定不够 = 高风险 (未验证不等于不够, 它体现在 feasibilityStatus=UNVERIFIED)
        if ((materialCheck.isVerified() && !materialCheck.isAvailable())
                || (equipmentCheck.isVerified() && !equipmentCheck.isAvailable())
                || (capacityCheck.isVerified() && !capacityCheck.isAvailable())
                || (workerCheck.verified() && !workerCheck.available())) {
            return "critical";
        }
        // 使用链式影响的风险等级
        String impactLevel = chainImpact.getImpactLevel();
        if ("critical".equals(impactLevel)) return "critical";
        if ("high".equals(impactLevel)) return "high";
        if ("medium".equals(impactLevel)) return "medium";
        return "low";
    }

    private boolean requiresForceApproval(ChainImpactResult chainImpact) {
        return Boolean.TRUE.equals(chainImpact.getRequiresApproval())
                || "high".equalsIgnoreCase(chainImpact.getImpactLevel())
                || "critical".equalsIgnoreCase(chainImpact.getImpactLevel());
    }

    /**
     * 构建不可行原因
     */
    private String buildInfeasibleReason(ResourceCheckResult materialCheck,
                                         ResourceCheckResult equipmentCheck,
                                         ResourceCheckResult capacityCheck,
                                         WorkerShiftAvailabilityCheck.Result workerCheck) {
        StringBuilder reasons = new StringBuilder();
        if (!materialCheck.isAvailable()) {
            // 未验证的 message 自带「原料未验证: …」, 不冠「原料不足」
            reasons.append(materialCheck.isVerified() ? "原料不足: " : "")
                    .append(materialCheck.getMessage()).append("; ");
        }
        if (!equipmentCheck.isAvailable()) {
            reasons.append(equipmentCheck.isVerified() ? "设备不可用: " : "")
                    .append(equipmentCheck.getMessage()).append("; ");
        }
        if (!workerCheck.verified() || !workerCheck.available()) {
            reasons.append(workerCheck.message()).append("; ");
        }
        if (!capacityCheck.isAvailable()) {
            // message 自带「产能不足: …」或「产能未验证: …」, 单位取自配置
            reasons.append(capacityCheck.getMessage()).append("; ");
        }
        return reasons.toString();
    }

    @Override
    @Transactional
    public ProductionPlanDTO confirmInsert(String factoryId, Long userId, ConfirmUrgentInsertRequest request) {
        log.info("确认紧急插单: factoryId={}, slotId={}, quantity={}",
                factoryId, request.getSlotId(), request.getPlannedQuantity());

        // 1551-11 (Steve 2026-11-11): 工厂开了「插单一律走审批」且有启用的强制插单画布 ⇒ 普通插单也先进审批,
        // 走与强制插单同一条路 (建画布实例, 批准后才排入并顺延)。开关默认关 = 原行为; 没有画布时开了也不生效。
        if (alwaysRequiresCanvasApproval(factoryId)) {
            log.info("插单一律走审批: 普通插单转入画布审批 factoryId={}, slotId={}", factoryId, request.getSlotId());
            return doForceInsert(factoryId, userId, request, ROUTED_BY_ALWAYS_APPROVAL);
        }

        PreviewValidation validation = validatePreviewAndFreshResources(factoryId, request);
        InsertSlot slot = validation.slot();

        // 人员只读一次, 在任何写入之前: 既用来校验名单, 也决定结果里要不要提示「人员未核对」
        validateWorkerListShape(request);
        WorkerShiftAvailabilityCheck.Result workerCheck = checkWorkers(factoryId, slot);
        validateSelectedWorkers(workerCheck, request);
        validateSelectedEquipment(factoryId, request);

        // Normal confirm must never turn the request's force flag into an
        // approval bypass.  High/critical or an explicit approval requirement
        // is only handled by the audited force endpoint.
        if (requiresForceApproval(validation.chainImpact())) {
            throw new BusinessException(409, "该时段需要强制插入审批，请使用强制插入流程")
                    .withCode("INSERT_APPROVAL_REQUIRED")
                    .withHint("由有审批权限的人使用「强制插入」并提交审批原因");
        }

        // 原子占用时段 (条件更新, 0 行 = 被并发请求 / 重复提交抢先) —— 必须在建计划之前
        claimSlotOrThrow(factoryId, slot);

        // 创建生产计划
        ProductionPlan plan = createProductionPlanFromSlot(
                factoryId, userId, slot, request, validation.plannedUnit(), validation.material());

        // 保存计划
        ProductionPlan savedPlan = productionPlanRepository.save(plan);

        // 缺口 17 第 2 项: 把插单真正排进去并顺延被挤计划。与预览的排程指纹比对, 不一致整个确认回滚。
        UrgentInsertScheduleService.Proposal applied = scheduleService.apply(
                factoryId, slot, savedPlan, validation.preview().getScheduleFingerprint(), userId);

        log.info("紧急插单创建成功: planId={}, planNumber={}", savedPlan.getId(), savedPlan.getPlanNumber());

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink for urgent-insert path.
        // Note: sourceOrderId here is customer-provided order number (not necessarily
        // an internal SO UUID), so the link may be a dangling reference if customer's
        // string doesn't match a real SalesOrder. Still useful for traceability.
        linkUrgentInsertPlan(factoryId, savedPlan, userId);

        // 发送紧急插单推送通知
        try {
            pushNotificationService.sendUrgentInsertNotification(
                    userId,
                    // 计划 id 是 UUID; 原来 Long.valueOf 恒抛异常, 通知从没发出去过
                    savedPlan.getId(),
                    String.format("紧急插单已创建: %s，计划数量: %s",
                            savedPlan.getPlanNumber(), request.getPlannedQuantity())
            );
            log.info("紧急插单推送通知已发送: planId={}", savedPlan.getId());
        } catch (Exception e) {
            log.error("发送紧急插单推送通知失败: planId={}", savedPlan.getId(), e);
            // 不阻塞主流程
        }

        ProductionPlanDTO dto = productionPlanMapper.toDTO(savedPlan);
        dto.setScheduleOutcome(applied.toMap());
        dto.setUncheckedWarnings(withScheduleWarning(
                withMaterialWarning(uncheckedWarnings(workerCheck), validation.material()), applied));
        return dto;
    }

    @Override
    @Transactional
    public ProductionPlanDTO forceInsert(String factoryId, Long userId, ConfirmUrgentInsertRequest request) {
        return doForceInsert(factoryId, userId, request, null);
    }

    /** 审计里标明「这张是普通插单, 因工厂开关『插单一律走审批』转入审批」, 不与真正的强制插单混算。 */
    static final String ROUTED_BY_ALWAYS_APPROVAL = "ALWAYS_APPROVAL_SWITCH";

    /**
     * @param routedBy 非空 = 普通插单被转入这条路 (目前只有 {@link #ROUTED_BY_ALWAYS_APPROVAL}); 记进审计上下文
     */
    private ProductionPlanDTO doForceInsert(String factoryId, Long userId, ConfirmUrgentInsertRequest request,
                                            String routedBy) {
        log.info("强制紧急插单: factoryId={}, slotId={}, routedBy={}", factoryId, request.getSlotId(), routedBy);

        PreviewValidation validation = validatePreviewAndFreshResources(factoryId, request);
        InsertSlot slot = validation.slot();

        validateWorkerListShape(request);
        WorkerShiftAvailabilityCheck.Result workerCheck = checkWorkers(factoryId, slot);
        validateSelectedWorkers(workerCheck, request);
        validateSelectedEquipment(factoryId, request);

        // 获取操作用户信息
        User operator = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        // 构建审批上下文，包含影响分析结果
        Map<String, Object> approvalContext = buildForceInsertApprovalContext(
                slot, request, validation.preview(), validation.chainImpact());

        // 使用配置化的审批链判断是否需要审批
        boolean configuredRequiresApproval = approvalChainService.requiresApproval(
                factoryId, DecisionType.FORCE_INSERT, approvalContext);
        boolean needsApproval = requiresForceApproval(validation.chainImpact())
                || configuredRequiresApproval;

        log.debug("强制插单审批判断: factoryId={}, impactLevel={}, affectedPlans={}, needsApproval={}",
                factoryId, validation.preview().getImpactLevel(),
                approvalContext.get("affectedPlanCount"), needsApproval);

        // 创建生产计划
        ProductionPlan plan = createProductionPlanFromSlot(
                factoryId, userId, slot, request, validation.plannedUnit(), validation.material());

        // 标记为强制插单
        plan.markAsForceInsert(userId, request.getNotes(), needsApproval);

        // 如果需要审批，状态设置为待审批
        if (needsApproval) {
            plan.setStatus(ProductionPlanStatus.PENDING);
            log.info("强制插单需要审批: planId={}, impactLevel={}",
                    plan.getId(), validation.preview().getImpactLevel());
        }

        // 原子占用时段 (条件更新, 0 行 = 被并发请求 / 重复提交抢先) —— 必须在保存计划之前
        claimSlotOrThrow(factoryId, slot);

        // 保存计划
        ProductionPlan savedPlan = productionPlanRepository.save(plan);

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink for force-insert path.
        linkUrgentInsertPlan(factoryId, savedPlan, userId);

        // 1551-11: 本厂启用了强制插单审批画布 ⇒ 建画布审批实例, 谁来批、几级由画布定;
        // 批准/驳回只走 OA 审批中心 (applyWorkflowAction), 直接审批入口对这类计划拒绝。
        // 没有画布 (requiresApproval=false) 时维持原状: 高/严重影响走直接审批, 其余直接排入。
        ApprovalWorkflowInstance workflowInstance = configuredRequiresApproval
                ? startForceInsertWorkflow(factoryId, savedPlan, approvalContext, request, userId)
                : null;
        boolean pendingApproval = needsApproval;
        if (workflowInstance != null
                && workflowInstance.getStatus() == ApprovalWorkflowInstance.InstanceStatus.APPROVED) {
            // 画布在创建时就走到了「通过」(工厂自己配的条件分支), 没有人要审 ⇒ 现在排入。
            savedPlan.approve(null, "审批流程自动通过", "审批画布在提交时按条件直接通过");
            savedPlan = productionPlanRepository.save(savedPlan);
            pendingApproval = false;
            decisionAuditService.logApproval(factoryId, "ProductionPlan", savedPlan.getId(),
                    com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus.APPROVED,
                    "审批画布在提交时按条件直接通过 (实例 " + workflowInstance.getId() + ")",
                    null, "审批流程自动通过");
        }

        // 不需要审批的强制插入立即排入; 需要审批的等批准那一刻再排 (approveForceInsert / applyWorkflowAction)
        UrgentInsertScheduleService.Proposal applied = pendingApproval ? null : scheduleService.apply(
                factoryId, slot, savedPlan, validation.preview().getScheduleFingerprint(), userId);

        // 准备审计日志上下文
        Map<String, Object> inputContext = new HashMap<>();
        inputContext.put("slotId", request.getSlotId());
        inputContext.put("productTypeId", request.getProductTypeId());
        inputContext.put("plannedQuantity", request.getPlannedQuantity());
        inputContext.put("priority", request.getPriority());
        inputContext.put("impactLevel", validation.chainImpact().getImpactLevel());
        inputContext.put("affectedPlanCount", validation.chainImpact().getTotalAffectedPlans());
        inputContext.put("previewId", validation.preview().getId());
        inputContext.put("impactSource", "FRESH_CHAIN_MATCHED_PREVIEW");
        inputContext.put("impactedPlans", parseImpactedPlans(slot.getImpactedPlans()));
        inputContext.put("customerOrderNumber", request.getCustomerOrderNumber());
        inputContext.put("forceInsertReason", request.getNotes());
        if (routedBy != null) {
            inputContext.put("routedBy", routedBy);
        }
        if (workflowInstance != null) {
            inputContext.put("workflowInstanceId", workflowInstance.getId());
            inputContext.put("workflowStatus", String.valueOf(workflowInstance.getStatus()));
        }

        // 创建决策审计记录（含规则版本追踪）。走画布的不再挂旧版审批链: 决定权在画布, 挂旧规则会误导审计
        Optional<ApprovalChainConfig> matchedConfig = workflowInstance != null ? Optional.empty()
                : approvalChainService.findMatchingConfig(factoryId, DecisionType.FORCE_INSERT, approvalContext);

        if (matchedConfig.isPresent()) {
            ApprovalChainConfig config = matchedConfig.get();
            decisionAuditService.logForceInsertWithRuleConfig(
                    factoryId,
                    "ProductionPlan",
                    savedPlan.getId(),
                    inputContext,
                    request.getNotes(),
                    needsApproval,
                    userId,
                    operator.getFullName(),
                    operator.getRole() != null ? operator.getRole() : "UNKNOWN",
                    config.getId(),
                    config.getVersion(),
                    config.getName()
            );
            log.debug("审计日志记录匹配规则: configId={}, version={}, name={}",
                    config.getId(), config.getVersion(), config.getName());
        } else {
            // 无匹配规则时使用基础日志方法
            decisionAuditService.logForceInsert(
                    factoryId,
                    "ProductionPlan",
                    savedPlan.getId(),
                    inputContext,
                    request.getNotes(),
                    needsApproval,
                    userId,
                    operator.getFullName(),
                    operator.getRole() != null ? operator.getRole() : "UNKNOWN"
            );
        }

        log.info("强制插单创建成功: planId={}, planNumber={}, needsApproval={}",
                savedPlan.getId(), savedPlan.getPlanNumber(), needsApproval);

        // 发送强制插单推送通知
        try {
            String message = pendingApproval
                    ? String.format("强制插单已提交审批: %s，计划数量: %s", savedPlan.getPlanNumber(), request.getPlannedQuantity())
                    : String.format("强制插单已创建: %s，计划数量: %s", savedPlan.getPlanNumber(), request.getPlannedQuantity());

            pushNotificationService.sendUrgentInsertNotification(
                    userId,
                    // 计划 id 是 UUID; 原来 Long.valueOf 恒抛异常, 通知从没发出去过
                    savedPlan.getId(),
                    message
            );

            // 如果需要审批，同时发送到工厂通知审批人
            if (pendingApproval) {
                Map<String, Object> pushData = new HashMap<>();
                pushData.put("type", "force_insert_approval");
                pushData.put("planId", savedPlan.getId());
                pushData.put("planNumber", savedPlan.getPlanNumber());
                pushData.put("impactLevel", validation.preview().getImpactLevel());
                if (workflowInstance != null) {
                    // 画布审批: 在 OA 审批中心处理, 不是直接审批页
                    pushData.put("workflowInstanceId", workflowInstance.getId());
                    pushData.put("moduleCode", FORCE_INSERT_MODULE);
                } else {
                    pushData.put("screen", "ForceInsertApprovalScreen");
                }
                pushNotificationService.sendToFactory(
                        factoryId,
                        "强制插单待审批",
                        String.format("强制插单 %s 需要审批，影响等级: %s",
                                savedPlan.getPlanNumber(), validation.preview().getImpactLevel()),
                        pushData
                );
            }
            log.info("强制插单推送通知已发送: planId={}, needsApproval={}", savedPlan.getId(), needsApproval);
        } catch (Exception e) {
            log.error("发送强制插单推送通知失败: planId={}", savedPlan.getId(), e);
            // 不阻塞主流程
        }

        ProductionPlanDTO dto = productionPlanMapper.toDTO(savedPlan);
        if (applied != null) {
            dto.setScheduleOutcome(applied.toMap());
        }
        dto.setUncheckedWarnings(withScheduleWarning(
                withMaterialWarning(uncheckedWarnings(workerCheck), validation.material()), applied));
        return dto;
    }

    /**
     * 审批强制插单
     *
     * @param factoryId   工厂ID
     * @param planId      计划ID
     * @param approverId  审批人ID
     * @param approved    是否批准
     * @param comment     审批备注
     * @return 更新后的计划
     */
    @Override
    @Transactional
    public ProductionPlanDTO approveForceInsert(
            String factoryId, String planId, Long approverId, boolean approved, String comment) {

        log.info("审批强制插单: planId={}, approved={}", planId, approved);
        resourceWriteLock.lockFactory(factoryId);

        ProductionPlan plan = loadPendingForceInsert(factoryId, planId);

        // 1551-11: 走画布审批的计划只能在 OA 审批中心处理 —— 否则这条直接入口就绕过了
        // 画布里配的审批人、级数和「允许本人审批」开关。
        Optional<ApprovalWorkflowInstance> canvasInstance =
                workflowEngine.getLatestInstance(factoryId, FORCE_INSERT_MODULE, planId);
        if (canvasInstance != null && canvasInstance.isPresent()) {
            throw new BusinessException(409, "这张强制插单走的是审批流程，请到审批中心处理")
                    .withCode("FORCE_INSERT_APPROVAL_VIA_OA")
                    .withHint("在「审批中心 / 我的待办」里找到这张单子批准或驳回")
                    .withHintTarget("审批中心");
        }

        User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new EntityNotFoundException("User", approverId));
        // 本人不能批自己提交的插单 (与画布路径同一个工厂开关): 开关关 ⇒ 拦; 开 ⇒ 必须写原因, 审批意见带留痕前缀。
        // 只管批准; 驳回自己的插单 = 撤回, 不拦。
        boolean actorIsMaker = approved
                && (Objects.equals(plan.getCreatedBy(), approverId) || Objects.equals(plan.getForceInsertBy(), approverId));
        String trimmedComment = comment == null ? null : comment.trim();
        com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision selfDecision = decideSelfApproval(factoryId, actorIsMaker, trimmedComment);
        if (selfDecision.forbidden()) {
            throw new BusinessException(409, "提交强制插单的人不能审批自己的插单")
                    .withCode("FORCE_INSERT_SELF_APPROVAL_FORBIDDEN")
                    .withHint(com.cretas.aims.service.workflow.FactorySelfApprovalGate.SWITCH_HINT);
        }
        String auditComment = selfDecision.selfApproved() ? SELF_APPROVAL_MARK + selfDecision.reason() : comment;
        InsertSlot approvedSlot = approved ? findClaimedSlotOrThrow(factoryId, plan) : null;
        return applyDecision(factoryId, plan, approverId, approver, approved, auditComment, approvedSlot);
    }

    /**
     * 1551-11: OA 审批中心对强制插单画布实例的批准/驳回 (由统一 OA 动作入口调用)。
     *
     * <p>审批人是谁由画布节点定 (入口已用 {@code canTransition} 校验); 提交人本人审批遵守
     * 工厂「允许本人审批」开关 ({@link com.cretas.aims.service.workflow.FactorySelfApprovalGate}),
     * 开着时 OA 审批意见就是本人审批原因, 记进审批意见留痕。画布走完「通过」才插入并顺延排程;
     * 走到「驳回」就作废计划、释放占用的时段。多级画布中间节点只推进流程, 计划保持待审批。
     */
    @Override
    @Transactional
    public ProductionPlanDTO applyWorkflowAction(
            String factoryId, String planId, String workflowInstanceId, Long actorId, String actorRole,
            String expectedNodeId, ApprovalHistory.HistoryAction action, String notes) {
        log.info("OA 审批强制插单: planId={}, instanceId={}, action={}", planId, workflowInstanceId, action);
        if (action != ApprovalHistory.HistoryAction.APPROVE && action != ApprovalHistory.HistoryAction.REJECT) {
            throw new BusinessException(400, "强制插单审批只支持批准或驳回").withHintTarget("action");
        }
        // 与直接审批、确认插单、排程写入用同一把工厂锁: 两个审批人同时点、审批与取消同时发生都串行
        resourceWriteLock.lockFactory(factoryId);

        ProductionPlan plan = productionPlanRepository.findById(planId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));
        // 锁内重读实例: 入口在锁外读到的状态可能已经被并发的另一次审批推进了
        ApprovalWorkflowInstance instance = workflowEngine.getInstance(factoryId, workflowInstanceId)
                .orElseThrow(() -> new BusinessException(404, "OA 审批实例不存在"));
        if (!FORCE_INSERT_MODULE.equals(instance.getModuleCode())
                || !planId.equals(instance.getBusinessEntityId())) {
            throw new BusinessException(409, "审批实例与强制插单计划不匹配")
                    .withCode("FORCE_INSERT_WORKFLOW_MISMATCH")
                    .withHint("请刷新审批中心待办, 从这张强制插单自己的审批记录进入后再处理");
        }
        if (instance.getStatus() != ApprovalWorkflowInstance.InstanceStatus.RUNNING) {
            // 已被并发的另一次审批走完: 不重复插入/释放, 也不能对后来者报「成功」(驳回的人会以为驳回了)。
            // 同一个幂等键的重试由 OaActionIdempotencyService 返回首次结果, 到不了这里。
            throw new BusinessException(409, "这条强制插单审批已被处理（结果：" + instance.getStatus() + "），请刷新待办")
                    .withCode("FORCE_INSERT_ALREADY_DECIDED")
                    .withHint("别人已经处理了这条审批; 刷新待办查看结果, 不用再操作");
        }
        // 入口的节点 / 处理人校验是在锁外读的实例上做的: 多级画布里并发的另一次审批可能已把实例推到下一个节点,
        // 那个节点不一定还是这个人能批 ⇒ 锁内按最新实例再判一次。
        if (expectedNodeId != null && (instance.getCurrentNodeIds() == null
                || !instance.getCurrentNodeIds().contains(expectedNodeId))) {
            throw new BusinessException(409, "审批节点已变化，请刷新待办后重试")
                    .withCode("OA_TASK_NODE_CHANGED")
                    .withHint("这条审批已经走到下一步; 刷新待办, 如果下一步仍是你处理再重新操作");
        }
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new EntityNotFoundException("User", actorId));
        if (!workflowEngine.canTransition(instance, actor)) {
            throw new BusinessException(403, "您不属于当前审批节点的处理角色")
                    .withCode("OA_TASK_FORBIDDEN");
        }

        boolean planStillPending = plan.isPendingApproval() && plan.getStatus() != ProductionPlanStatus.CANCELLED;
        if (action == ApprovalHistory.HistoryAction.APPROVE && !planStillPending) {
            throw new BusinessException(409, "该计划已不在待审批状态（可能已被取消），不能批准")
                    .withCode("FORCE_INSERT_NOT_PENDING")
                    .withHint("如需结束这条审批，请驳回");
        }
        String trimmedNotes = notes == null ? null : notes.trim();
        if (action == ApprovalHistory.HistoryAction.REJECT && (trimmedNotes == null || trimmedNotes.isEmpty())) {
            throw new BusinessException(422, "驳回强制插单必须填写原因")
                    .withCode("FORCE_INSERT_REJECT_REASON_REQUIRED")
                    .withHintTarget("审批意见");
        }

        // 纪律排查 B1–B3 / #1580: 提交人本人批准 —— 开关关 ⇒ 拦; 开 ⇒ 必须写原因并留痕。
        boolean actorIsMaker = action == ApprovalHistory.HistoryAction.APPROVE
                && (Objects.equals(plan.getCreatedBy(), actorId) || Objects.equals(plan.getForceInsertBy(), actorId));
        com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision selfDecision = decideSelfApproval(factoryId, actorIsMaker, trimmedNotes);
        if (selfDecision.forbidden()) {
            throw new BusinessException(409, "提交强制插单的人不能审批自己的插单")
                    .withCode("FORCE_INSERT_SELF_APPROVAL_FORBIDDEN")
                    .withHint(com.cretas.aims.service.workflow.FactorySelfApprovalGate.SWITCH_HINT);
        }
        String auditNotes = selfDecision.selfApproved()
                ? SELF_APPROVAL_MARK + selfDecision.reason()
                : trimmedNotes;

        // 批准前先确认时段还在 (读在任何写入之前); 中间节点也查, 免得一路批到最后才发现排不进去
        InsertSlot approvedSlot = action == ApprovalHistory.HistoryAction.APPROVE
                ? findClaimedSlotOrThrow(factoryId, plan) : null;

        // 并行网关: 推进入口校验过的那个节点 (expectedNodeId), 不是 currentNodeIds[0]
        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(
                workflowInstanceId, actorId, actorRole, action, auditNotes, expectedNodeId);

        if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.APPROVED) {
            return applyDecision(factoryId, plan, actorId, actor, true, auditNotes, approvedSlot);
        }
        if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.REJECTED) {
            if (!planStillPending) {
                // 计划早已取消 (时段已经释放, 可能已被别的计划占用): 只结束审批, 不再动计划和时段
                return productionPlanMapper.toDTO(plan);
            }
            return applyDecision(factoryId, plan, actorId, actor, false, auditNotes, null);
        }
        if (selfDecision.selfApproved()) {
            // 多级画布的中间节点由本人批: 也要留痕 (不只最后一个节点)
            plan.setApprovalComment(auditNotes);
            plan = productionPlanRepository.save(plan);
        }
        return productionPlanMapper.toDTO(plan);
    }

    /**
     * 1551-11: 「按当前审批流重新发起」(缺陷 27 恢复入口) 对强制插单走这里: 新实例在创建时就走到终态时,
     * 通过 ⇒ 当场排入; 驳回 / 取消 / 超时 ⇒ 整体回滚 (原实例保持不变), 不留下「流程结束、计划永远待审批」。
     */
    @Override
    @Transactional
    public ApprovalWorkflowInstance restartWorkflowBinding(
            String factoryId, String workflowInstanceId, Long operatorId, String reason) {
        resourceWriteLock.lockFactory(factoryId);
        ApprovalWorkflowInstance current = workflowEngine.getInstance(factoryId, workflowInstanceId)
                .orElseThrow(() -> new BusinessException(404, "OA 审批实例不存在"));
        if (!FORCE_INSERT_MODULE.equals(current.getModuleCode())) {
            throw new BusinessException(400, "不是强制插单审批实例").withCode("FORCE_INSERT_WORKFLOW_MISMATCH");
        }
        String planId = current.getBusinessEntityId();
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));
        ApprovalWorkflowInstance restarted = workflowEngine.restartWithCurrentDefinition(
                factoryId, workflowInstanceId, operatorId, reason);
        ApprovalWorkflowInstance.InstanceStatus status = restarted.getStatus();
        if (status == ApprovalWorkflowInstance.InstanceStatus.APPROVED) {
            if (!plan.isPendingApproval() || plan.getStatus() == ProductionPlanStatus.CANCELLED) {
                return restarted;
            }
            InsertSlot slot = findClaimedSlotOrThrow(factoryId, plan);
            plan.approve(null, "审批流程自动通过", "按当前审批流重新发起后按条件直接通过");
            plan.setStatus(ProductionPlanStatus.PENDING);
            checkWorkers(factoryId, slot);
            scheduleService.apply(factoryId, slot, plan, null, operatorId);
            productionPlanRepository.save(plan);
            decisionAuditService.logApproval(factoryId, "ProductionPlan", planId,
                    com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus.APPROVED,
                    "按当前审批流重新发起后按条件直接通过 (实例 " + restarted.getId() + ")",
                    null, "审批流程自动通过");
        } else if (status == ApprovalWorkflowInstance.InstanceStatus.REJECTED
                || status == ApprovalWorkflowInstance.InstanceStatus.CANCELLED
                || status == ApprovalWorkflowInstance.InstanceStatus.TIMEOUT) {
            throw new BusinessException(409, "当前强制插单审批流程在发起时就进入了 " + status + " 态，未重新发起")
                    .withCode("FORCE_INSERT_WORKFLOW_TERMINAL_ON_START")
                    .withHint("请工厂管理员检查强制插单审批画布; 原审批保持不变")
                    .withHintTarget("OA 工作流配置");
        }
        return restarted;
    }

    /** 开关开 (显式 1) 且本厂有启用的 FORCE_INSERT 画布。读开关失败按关 (= 原行为, 不凭空拦单)。 */
    private boolean alwaysRequiresCanvasApproval(String factoryId) {
        if (disciplinePolicy == null || approvalWorkflowService == null) {
            return false;
        }
        boolean switchOn;
        try {
            switchOn = disciplinePolicy.urgentInsertAlwaysApproval(factoryId);
        } catch (RuntimeException e) {
            log.warn("插单一律走审批 开关读取失败, 按关处理: factoryId={}, err={}", factoryId, e.getMessage());
            return false;
        }
        return switchOn && approvalWorkflowService.getActiveByDecisionType(factoryId, DecisionType.FORCE_INSERT).isPresent();
    }

    /**
     * 本人审批判定。fail-closed: 判定组件没给出结论 (返回 null) 时抛 500 拒绝, 既不 NPE, 也绝不当成放行。
     */
    private com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision decideSelfApproval(
            String factoryId, boolean actorIsMaker, String reason) {
        com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision decision = com.cretas.aims.service.workflow.FactorySelfApprovalGate.decide(
                selfApprovalGate, factoryId, actorIsMaker, reason);
        if (decision == null) {
            throw new BusinessException(500, "无法判定是否本人审批, 为安全起见已拒绝本次审批")
                    .withCode("FORCE_INSERT_SELF_APPROVAL_UNDECIDABLE")
                    .withHint("请稍后重试; 持续出现请联系技术支持");
        }
        return decision;
    }

    /** 本人审批留痕: 写在计划审批意见与 OA 审批记录的前缀。 */
    static final String SELF_APPROVAL_MARK = "【本人审批】";

    private ProductionPlan loadPendingForceInsert(String factoryId, String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));
        if (!factoryId.equals(plan.getFactoryId())) {
            throw new RuntimeException("无权审批该计划");
        }
        if (!plan.isPendingApproval()) {
            throw new RuntimeException("该计划不在待审批状态");
        }
        return plan;
    }

    /** 这张待审批计划占着的那个时段 (同一开始时间 + 同一产线); 找不到 = 排不进去。 */
    private InsertSlot findClaimedSlotOrThrow(String factoryId, ProductionPlan plan) {
        return insertSlotRepository.findByFactoryIdAndStatus(factoryId, "selected").stream()
                .filter(s -> Objects.equals(s.getStartTime(), plan.getStartTime())
                        && Objects.equals(s.getProductionLineId(), plan.getSuggestedProductionLineId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409, "找不到这张计划占用的时段，无法排入")
                        .withCode("INSERT_APPROVAL_SLOT_MISSING")
                        .withHint("到插单时段列表确认该时段状态后重试"));
    }

    /**
     * 批准 = 按最新数据重算并排入 (顺延被挤计划); 驳回 = 作废计划并释放时段。直接审批与画布审批共用。
     */
    private ProductionPlanDTO applyDecision(String factoryId, ProductionPlan plan, Long approverId, User approver,
                                            boolean approved, String comment, InsertSlot approvedSlot) {
        String planId = plan.getId();
        UrgentInsertScheduleService.Proposal approvalSchedule = null;
        List<String> approvalWarnings = List.of();

        if (approved) {
            plan.approve(approverId, approver.getFullName(), comment);
            // 批准后可以开始执行
            plan.setStatus(ProductionPlanStatus.PENDING);
            log.info("强制插单已批准: planId={}", planId);
            // 缺口 17 第 2 项: 批准那一刻按最新数据重算并排入 (审批可能隔了很久, 没有预览指纹可比)。
            // 排不进去则批准整体失败回滚, 计划仍待审批, 不会出现「批准了但没排进去」。
            // 人员读在排程写入之前, 读失败就在写任何东西之前失败
            approvalWarnings = uncheckedWarnings(checkWorkers(factoryId, approvedSlot));
            approvalSchedule = scheduleService.apply(factoryId, approvedSlot, plan, null, approverId);
        } else {
            plan.reject(approverId, approver.getFullName(), comment);
            plan.setStatus(ProductionPlanStatus.CANCELLED);

            // 释放这张计划占的时段 (同一开始时间 + 同一产线; 原来只比开始时间, 会放掉别的产线同一时刻的时段)。
            // 计划没记产线 (旧数据) 时退回只比开始时间, 与改动前一致。
            insertSlotRepository.findByFactoryIdAndStatus(factoryId, "selected").stream()
                    .filter(s -> Objects.equals(s.getStartTime(), plan.getStartTime())
                            && (plan.getSuggestedProductionLineId() == null
                                || Objects.equals(s.getProductionLineId(), plan.getSuggestedProductionLineId())))
                    .findFirst()
                    .ifPresent(slot -> {
                        slot.setStatus("available");
                        insertSlotRepository.save(slot);
                    });

            log.info("强制插单已拒绝: planId={}", planId);
        }

        // 保存更新
        ProductionPlan updatedPlan = productionPlanRepository.save(plan);

        // 记录审批决策
        decisionAuditService.logApproval(
                factoryId,
                "ProductionPlan",
                planId,
                approved ?
                        com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus.APPROVED :
                        com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus.REJECTED,
                comment,
                approverId,
                approver.getFullName()
        );

        // 发送审批结果推送通知给申请人
        try {
            Long applicantId = plan.getCreatedBy();
            if (applicantId != null) {
                String resultText = approved ? "已批准" : "已拒绝";
                pushNotificationService.sendApprovalNotification(
                        applicantId,
                        "FORCE_INSERT",
                        // ⛔ 原来 Long.valueOf(planId): 计划 id 是 UUID, 恒抛 NumberFormatException,
                        //    被下面的 catch 吞掉 ⇒ 审批结果通知从没发出去过。
                        planId,
                        String.format("强制插单 %s %s: %s",
                                plan.getPlanNumber(), resultText,
                                comment != null ? comment : (approved ? "审批通过" : "审批拒绝"))
                );
                log.info("强制插单审批结果推送通知已发送: planId={}, applicantId={}, approved={}",
                        planId, applicantId, approved);
            }
        } catch (Exception e) {
            log.error("发送强制插单审批结果推送通知失败: planId={}", planId, e);
            // 不阻塞主流程
        }

        ProductionPlanDTO approvalDto = productionPlanMapper.toDTO(updatedPlan);
        if (approvalSchedule != null) {
            approvalDto.setScheduleOutcome(approvalSchedule.toMap());
        }
        approvalDto.setUncheckedWarnings(approvalWarnings);
        return approvalDto;
    }

    /**
     * 1551-11: 为待审批的强制插单建画布审批实例。
     *
     * <p>画布在提交时就走到「通过」(工厂配的条件分支) ⇒ 返回 APPROVED, 调用方当场排入;
     * 走到驳回 / 取消 / 超时 ⇒ 只可能是画布配错了, 整个提交回滚 (时段不占、计划不建)。
     * 画布在判断之后、建实例之前被停用 ⇒ 409, 同样回滚, 让提交人重试。
     */
    private ApprovalWorkflowInstance startForceInsertWorkflow(
            String factoryId, ProductionPlan plan, Map<String, Object> approvalContext,
            ConfirmUrgentInsertRequest request, Long userId) {
        Map<String, Object> context = new HashMap<>(approvalContext);
        context.put("planId", plan.getId());
        context.put("planNumber", plan.getPlanNumber());
        context.put("productTypeId", request.getProductTypeId());
        context.put("plannedQuantity", request.getPlannedQuantity() == null
                ? null : request.getPlannedQuantity().toPlainString());
        context.put("plannedUnit", plan.getPlannedUnit());
        context.put("productionLineId", plan.getSuggestedProductionLineId());
        context.put("startTime", plan.getStartTime() == null ? null : plan.getStartTime().toString());
        context.put("forceInsertReason", request.getNotes());
        ApprovalWorkflowInstance instance = workflowEngine.startWorkflowIfConfigured(
                        factoryId, FORCE_INSERT_MODULE, plan.getId(), context, userId)
                .orElseThrow(() -> new BusinessException(409, "强制插单审批流程刚被停用，请重新提交")
                        .withCode("FORCE_INSERT_WORKFLOW_NOT_CONFIGURED")
                        .withHint("请重新提交这张强制插单; 如果本厂不再需要审批, 会直接排入")
                        .withHintTarget("OA 工作流配置"));
        ApprovalWorkflowInstance.InstanceStatus status = instance.getStatus();
        if (status == ApprovalWorkflowInstance.InstanceStatus.REJECTED
                || status == ApprovalWorkflowInstance.InstanceStatus.CANCELLED
                || status == ApprovalWorkflowInstance.InstanceStatus.TIMEOUT) {
            throw new BusinessException(409, "强制插单审批流程配置有问题：提交时就进入了 " + status + " 态")
                    .withCode("FORCE_INSERT_WORKFLOW_TERMINAL_ON_START")
                    .withHint("请工厂管理员检查强制插单审批画布, 确认提交后会进入审批节点或直接通过")
                    .withHintTarget("OA 工作流配置");
        }
        log.info("强制插单进入画布审批: planId={}, instanceId={}, status={}",
                plan.getId(), instance.getId(), status);
        return instance;
    }

    /**
     * 获取待审批的强制插单列表
     *
     * @param factoryId 工厂ID
     * @return 待审批计划列表
     */
    @Override
    public List<ProductionPlanDTO> getPendingForceInsertApprovals(String factoryId) {
        // 使用 Repository 条件查询替代 findAll() 全表扫描，避免内存溢出
        return productionPlanRepository
                .findByFactoryIdAndIsForceInsertedAndRequiresApprovalAndApprovalStatus(
                        factoryId,
                        true,
                        true,
                        ProductionPlan.ApprovalStatus.PENDING)
                .stream()
                // 已取消的计划审批状态仍是 PENDING, 但没有可批的东西了 —— 不进待审批列表
                .filter(p -> p.getStatus() != ProductionPlanStatus.CANCELLED)
                .map(plan -> {
                    ProductionPlanDTO dto = productionPlanMapper.toDTO(plan);
                    attachWorkflowRef(factoryId, plan, dto);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /** 待审批列表: 走画布的计划带上运行中的实例 ID, 让手机端知道要走 OA 动作入口而不是直接审批 (直接入口对这类计划恒 409)。 */
    private void attachWorkflowRef(String factoryId, ProductionPlan plan, ProductionPlanDTO dto) {
        if (workflowEngine == null) {
            return;
        }
        Optional<ApprovalWorkflowInstance> running = workflowEngine.getCurrentInstance(
                factoryId, FORCE_INSERT_MODULE, plan.getId());
        if (running != null && running.isPresent()) {
            dto.setApprovalWorkflowInstanceId(running.get().getId());
        }
    }

    @Override
    @Transactional
    public int generateInsertSlots(String factoryId, int hoursAhead) {
        log.info("生成插单时段: factoryId={}, hoursAhead={}", factoryId, hoursAhead);
        resourceWriteLock.lockFactory(factoryId);
        if (hoursAhead <= 0 || hoursAhead > 168) {
            throw new BusinessException(400, "提前小时数必须在1到168之间")
                    .withHint("将提前小时数设置为1到168之间后重试");
        }

        // 清理旧的可用时段
        insertSlotRepository.deleteAvailableSlotsByFactoryId(factoryId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plusHours(hoursAhead);

        // Worker/switch defaults remain part of the existing recommendation
        // path, but a physical slot must never use a synthetic "default"
        // production line or present legacy hourly capacity as verified data.
        FactoryCapacityConfig capacityConfig = impactAnalysisService.getFactoryCapacityConfig(factoryId);
        List<ProductionLine> activeLines = productionLineRepository.findActiveLinesByFactory(factoryId);
        if (activeLines == null) {
            activeLines = List.of();
        }
        activeLines = activeLines.stream()
                .filter(line -> line != null && line.getId() != null && !line.getId().isBlank())
                .toList();
        if (activeLines.isEmpty()) {
            log.warn("工厂没有真实 active production line，不生成插单时段: factoryId={}", factoryId);
            return 0;
        }

        List<InsertSlot> newSlots = new ArrayList<>();

        // 按2小时间隔生成时段
        LocalDateTime slotStart = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        while (slotStart.isBefore(endTime)) {
            LocalDateTime slotEnd = slotStart.plusHours(2);

            // A1: 标准人数按产线配置、人员可用性以确认时的真实班次为准; 生成时段时不再用估算人数 (原先写死 6 人)
            Integer availableWorkers = null;

            // 初步影响分析/评分仍沿用原 APS 逻辑, but with no synthetic
            // quantity/capacity snapshot attached to the generated slot.
            ChainImpactResult impactResult = impactAnalysisService.calculateChainImpact(
                    factoryId, slotStart, slotEnd, null, null);

            // 计算多维度推荐评分
            RecommendScoreResult scoreResult = impactAnalysisService.calculateRecommendScore(
                    factoryId, slotStart, slotEnd, null, null, null);

            for (ProductionLine line : activeLines) {
                InsertSlot slot = InsertSlot.builder()
                        .id(UUID.randomUUID().toString())
                        .factoryId(factoryId)
                        .productionLineId(line.getId())
                        .productionLineName(line.getName())
                        .startTime(slotStart)
                        .endTime(slotEnd)
                        // This is intentionally null: actual capacity is
                        // checked later against the product/unit/line config.
                        .availableCapacity(null)
                        .impactLevel(impactResult.getImpactLevel())
                        // A1: 标准人数按产线配置; 没配 = null (不比较、不提示)
                        .requiredWorkers(line.getStandardWorkers() != null && line.getStandardWorkers() > 0
                                ? line.getStandardWorkers() : null)
                        .availableWorkers(availableWorkers)
                        .switchCostMinutes(capacityConfig.getSwitchCostMinutes())
                        .recommendScore(scoreResult.getTotalScore())
                        .recommendationReason(scoreResult.getRecommendationReason())
                        .status("available")
                        .build();

                // 存储详细影响分析结果 (JSON)
                try {
                    if (impactResult.getAffectedPlans() != null && !impactResult.getAffectedPlans().isEmpty()) {
                        String impactedPlansJson = objectMapper.writeValueAsString(
                                impactResult.getAffectedPlans().stream()
                                        .map(ap -> InsertSlotDTO.ImpactedPlanDTO.builder()
                                                .planId(ap.getPlanId())
                                                .planNumber(ap.getPlanNumber())
                                                .planName(ap.getProductName())
                                                .delayMinutes(ap.getDelayMinutes())
                                                .originalEndTime(ap.getOriginalEndTime())
                                                .delayedEndTime(ap.getDelayedEndTime())
                                                .isVipCustomer(ap.getIsVipCustomer())
                                                .impactType(ap.getImpactType())
                                                .impactLevel(ap.getImpactLevel())
                                                .build())
                                        .collect(Collectors.toList()));
                        slot.setImpactedPlans(impactedPlansJson);
                    }
                } catch (Exception e) {
                    log.warn("序列化受影响计划失败: {}", e.getMessage());
                }

                newSlots.add(slot);
            }
            slotStart = slotEnd;
        }

        insertSlotRepository.saveAll(newSlots);

        log.info("生成了 {} 个插单时段 (使用科学影响分析)", newSlots.size());
        return newSlots.size();
    }

    @Override
    public InsertSlotDTO getSlotDetail(String factoryId, String slotId) {
        InsertSlot slot = insertSlotRepository.findById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("InsertSlot", slotId));

        if (!factoryId.equals(slot.getFactoryId())) {
            throw new RuntimeException("无权访问该时段");
        }

        return toSlotDTO(slot);
    }

    @Override
    @Transactional
    public void markSlotAsSelected(String factoryId, String slotId) {
        resourceWriteLock.lockFactory(factoryId);
        // ⛔ 原来 findById 后无条件改成 selected: 已过期 / 已被别人占的时段也能被「选中」,
        //    并发两次都成功。改走与确认插单同一条条件更新。
        InsertSlot slot = loadOwnSlot(factoryId, slotId);
        claimSlotOrThrow(factoryId, slot);
    }

    @Override
    @Transactional
    public void releaseSlot(String factoryId, String slotId) {
        resourceWriteLock.lockFactory(factoryId);
        InsertSlot slot = insertSlotRepository.findByIdAndFactoryId(slotId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("InsertSlot", slotId));

        // 只有已选中的时段才能释放; 不是 selected 时与原来一样什么都不做
        if (!"selected".equals(slot.getStatus())) {
            return;
        }
        // ⛔ 确认插单后时段也是 selected。原来照样能释放 ⇒ 时段回到 available,
        //    别人再确认就在同一时段排出第二份计划。已有未取消计划的时段不许释放。
        if (slot.getStartTime() != null && productionPlanRepository.existsByFactoryIdAndStartTimeAndStatusNot(
                factoryId, slot.getStartTime(), ProductionPlanStatus.CANCELLED)) {
            throw new BusinessException(409, "该时段已排入插单计划，不能释放")
                    .withCode("INSERT_SLOT_HAS_PLAN")
                    .withHint("如需让出该时段，先到生产计划里取消对应的插单计划");
        }
        int released = insertSlotRepository.releaseSelectedSlot(slotId, factoryId, LocalDateTime.now());
        if (released != 1) {
            log.warn("插单时段释放冲突: factoryId={}, slotId={}, updatedRows={}", factoryId, slotId, released);
        }
    }

    @Override
    @Transactional
    public int cleanupExpiredSlots(String factoryId) {
        resourceWriteLock.lockFactory(factoryId);
        LocalDateTime now = LocalDateTime.now();
        return insertSlotRepository.markExpiredSlots(factoryId, now);
    }

    @Override
    public Map<String, Object> getUrgentInsertStatistics(String factoryId) {
        Map<String, Object> stats = new HashMap<>();

        long availableCount = insertSlotRepository.countByFactoryIdAndStatus(factoryId, "available");
        long selectedCount = insertSlotRepository.countByFactoryIdAndStatus(factoryId, "selected");
        long expiredCount = insertSlotRepository.countByFactoryIdAndStatus(factoryId, "expired");

        stats.put("availableSlots", availableCount);
        stats.put("selectedSlots", selectedCount);
        stats.put("expiredSlots", expiredCount);
        stats.put("totalSlots", availableCount + selectedCount + expiredCount);

        // 统计紧急插单计划数量
        long urgentPlanCount = productionPlanRepository.countByFactoryIdAndStatus(
                factoryId, ProductionPlanStatus.IN_PROGRESS);
        stats.put("activeUrgentPlans", urgentPlanCount);

        return stats;
    }

    // ==================== 私有辅助方法 ====================

    private InsertSlotDTO toSlotDTO(InsertSlot slot) {
        InsertSlotDTO dto = InsertSlotDTO.builder()
                .id(slot.getId())
                .factoryId(slot.getFactoryId())
                .productionLineId(slot.getProductionLineId())
                .productionLineName(slot.getProductionLineName())
                .startTime(slot.getStartTime())
                .endTime(slot.getEndTime())
                .durationHours(slot.getDurationHours())
                .availableCapacity(slot.getAvailableCapacity())
                .impactLevel(slot.getImpactLevel())
                .impactLevelDisplayName(slot.getImpactLevelDisplayName())
                .impactedPlans(parseImpactedPlans(slot.getImpactedPlans()))
                .requiredWorkers(slot.getRequiredWorkers())
                .availableWorkers(slot.getAvailableWorkers())
                .hasEnoughWorkers(slot.hasEnoughWorkers())
                .switchCostMinutes(slot.getSwitchCostMinutes())
                .recommendScore(slot.getRecommendScore())
                .recommendationReason(slot.getRecommendationReason())
                .status(slot.getStatus())
                .isAvailable(slot.isAvailable())
                .build();

        // 填充评分详情 (如需要可以重新计算)
        if (slot.getRecommendScore() != null) {
            FactoryCapacityConfig config = impactAnalysisService.getFactoryCapacityConfig(slot.getFactoryId());

            // 计算各因子
            double capacityFactor = slot.getAvailableCapacity() != null ?
                    Math.min(1.0, slot.getAvailableCapacity().doubleValue() / config.getHourlyCapacity().doubleValue() / 2) : 0.5;
            double workerFactor = slot.getAvailableWorkers() != null && slot.getRequiredWorkers() != null ?
                    Math.min(1.0, (double) slot.getAvailableWorkers() / slot.getRequiredWorkers()) : 0.5;
            double impactFactor = calculateImpactFactor(slot.getImpactLevel());
            double switchCostFactor = calculateSwitchCostFactor(slot.getSwitchCostMinutes(), config.getSwitchCostMinutes());

            InsertSlotDTO.ScoreBreakdown scoreBreakdown = InsertSlotDTO.ScoreBreakdown.builder()
                    .capacityFactor(capacityFactor)
                    .workerFactor(workerFactor)
                    .deadlineFactor(0.5)  // 默认中等紧迫度
                    .impactFactor(impactFactor)
                    .switchCostFactor(switchCostFactor)
                    .build();
            scoreBreakdown.calculateScores();
            dto.setScoreBreakdown(scoreBreakdown);
        }

        // 填充影响详情
        List<InsertSlotDTO.ImpactedPlanDTO> impactedPlans = dto.getImpactedPlans();
        if (impactedPlans != null && !impactedPlans.isEmpty()) {
            int directConflicts = (int) impactedPlans.stream()
                    .filter(p -> "DIRECT".equals(p.getImpactType()))
                    .count();
            int cascadeDelays = (int) impactedPlans.stream()
                    .filter(p -> "CASCADE".equals(p.getImpactType()))
                    .count();
            int maxDelayMinutes = impactedPlans.stream()
                    .mapToInt(p -> p.getDelayMinutes() != null ? p.getDelayMinutes() : 0)
                    .max()
                    .orElse(0);
            double avgDelayMinutes = impactedPlans.stream()
                    .mapToInt(p -> p.getDelayMinutes() != null ? p.getDelayMinutes() : 0)
                    .average()
                    .orElse(0);
            int vipCount = (int) impactedPlans.stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsVipCustomer()))
                    .count();

            InsertSlotDTO.ImpactDetails impactDetails = InsertSlotDTO.ImpactDetails.builder()
                    .directConflicts(directConflicts)
                    .cascadeDelays(cascadeDelays)
                    .totalAffectedPlans(impactedPlans.size())
                    .maxDelayHours(maxDelayMinutes / 60.0)
                    .averageDelayHours(avgDelayMinutes / 60.0)
                    .affectsVipCustomer(vipCount > 0)
                    .affectedVipCustomerCount(vipCount)
                    .impactScore(calculateImpactScore(impactedPlans))
                    .requiresApproval("high".equals(slot.getImpactLevel()) || "critical".equals(slot.getImpactLevel()))
                    .approvalLevel(determineApprovalLevel(slot.getImpactLevel(), vipCount))
                    .build();
            dto.setImpactDetails(impactDetails);
        }

        // 检查锁定状态 (TODO: 从 insert_slot_locks 表查询)
        dto.setIsLocked(false);
        // 时段列表不做原料/设备核对 ⇒ 不能标「可行」(原来恒为 true)。可行性只看影响分析接口。
        dto.setIsFeasible(false);
        dto.setInfeasibleReason("资源未验证: 时段列表不核对原料/设备/人员, 请查看该时段的影响分析");

        return dto;
    }

    /**
     * 根据影响等级计算影响因子 (0-1, 越高影响越小)
     */
    private double calculateImpactFactor(String impactLevel) {
        if (impactLevel == null || "none".equals(impactLevel)) {
            return 1.0;
        }
        switch (impactLevel) {
            case "low": return 0.8;
            case "medium": return 0.5;
            case "high": return 0.2;
            case "critical": return 0.0;
            default: return 0.5;
        }
    }

    /**
     * 根据换线成本计算因子 (0-1, 越高换线成本越低)
     */
    private double calculateSwitchCostFactor(Integer actualCost, int standardCost) {
        if (actualCost == null || actualCost == 0) {
            return 1.0;  // 无需换线
        }
        if (standardCost <= 0) {
            return 0.5;
        }
        double ratio = (double) actualCost / standardCost;
        if (ratio <= 0.5) return 0.9;
        if (ratio <= 1.0) return 0.7;
        if (ratio <= 1.5) return 0.5;
        return 0.3;
    }

    /**
     * 计算影响评分 (0-100)
     */
    private int calculateImpactScore(List<InsertSlotDTO.ImpactedPlanDTO> impactedPlans) {
        if (impactedPlans == null || impactedPlans.isEmpty()) {
            return 0;
        }
        int score = 0;
        score += Math.min(30, impactedPlans.size() * 10);  // 受影响计划数 (最多30分)
        int maxDelay = impactedPlans.stream()
                .mapToInt(p -> p.getDelayMinutes() != null ? p.getDelayMinutes() : 0)
                .max().orElse(0);
        score += Math.min(30, maxDelay / 30);  // 最大延误 (最多30分)
        long vipCount = impactedPlans.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsVipCustomer()))
                .count();
        score += Math.min(40, (int) vipCount * 20);  // VIP客户 (最多40分)
        return Math.min(100, score);
    }

    /**
     * 确定审批级别
     */
    private String determineApprovalLevel(String impactLevel, int vipCount) {
        if ("critical".equals(impactLevel) || vipCount >= 2) {
            return "DIRECTOR";
        }
        if ("high".equals(impactLevel) || vipCount >= 1) {
            return "MANAGER";
        }
        if ("medium".equals(impactLevel)) {
            return "SUPERVISOR";
        }
        return null;
    }

    private List<InsertSlotDTO.ImpactedPlanDTO> parseImpactedPlans(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<InsertSlotDTO.ImpactedPlanDTO>>() {});
        } catch (Exception e) {
            log.warn("解析受影响计划JSON失败: {}", json, e);
            return new ArrayList<>();
        }
    }

    private ProductionPlan createProductionPlanFromSlot(
            String factoryId, Long userId, InsertSlot slot, ConfirmUrgentInsertRequest request,
            String plannedUnit, UrgentInsertMaterialAssessor.Assessment material) {

        ProductionPlan plan = new ProductionPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setFactoryId(factoryId);
        plan.setPlanNumber("URGENT-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        plan.setProductTypeId(request.getProductTypeId());
        plan.setPlannedQuantity(request.getPlannedQuantity());
        plan.setPlannedUnit(plannedUnit);
        // 锁定预览时核对原料所用的配方版本, 之后配方改版不影响这张计划
        plan.setSelectedBomRecipeId(material.bomRecipeId());
        plan.setSelectedBomVersion(material.bomVersion());
        plan.setSuggestedProductionLineId(slot.getProductionLineId());
        plan.setStartTime(slot.getStartTime());
        plan.setEndTime(slot.getEndTime());
        plan.setExpectedCompletionDate(slot.getEndTime().toLocalDate());
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlanType(ProductionPlanType.FROM_INVENTORY);
        plan.setCustomerOrderNumber(request.getCustomerOrderNumber());
        plan.setPriority(request.getPriority() != null ? request.getPriority() : 9);
        plan.setNotes(request.getNotes());
        if (material.status() == UrgentInsertMaterialAssessor.Status.SHORT
                && request.getShortageForceReason() != null && !request.getShortageForceReason().isBlank()) {
            String trail = "[缺料强制插单] " + request.getShortageForceReason().trim() + "（缺料: " + material.message() + "）";
            plan.setNotes(request.getNotes() == null || request.getNotes().isBlank()
                    ? trail : request.getNotes() + " | " + trail);
        }
        plan.setCreatedBy(userId);

        // 设置调度员扩展字段
        plan.setSourceType(PlanSourceType.URGENT_INSERT);
        plan.setSourceOrderId(request.getCustomerOrderNumber());
        plan.setSourceCustomerName(request.getCustomerName());

        // 计算CR值（紧急插单通常CR值较低）
        plan.setCrValue(plan.calculateCrValue(1));  // 假设1天工期

        return plan;
    }

    /**
     * Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink for urgent-insert path.
     * Called from both confirmUrgentInsert + forceInsert after repository.save.
     */
    private void linkUrgentInsertPlan(String factoryId, ProductionPlan savedPlan, Long userId) {
        if (linkArrayService == null) return;
        String sourceOrderId = savedPlan.getSourceOrderId();
        if (sourceOrderId == null || sourceOrderId.isBlank()) return;
        try {
            linkArrayService.link(factoryId,
                    "PRODUCTION_PLAN", savedPlan.getId(),
                    "sale",
                    "SALES_ORDER", sourceOrderId,
                    "紧急插单源单",
                    userId != null ? userId.toString() : null);
        } catch (Exception e) {
            log.warn("BusinessLink double-write failed for urgent-insert PP {}: {}",
                    savedPlan.getId(), e.getMessage());
        }
    }

    private int calculateRecommendScore(LocalDateTime slotStart) {
        // 基于时间计算推荐分数
        // 越接近当前时间，分数越高（更容易安排）
        long hoursFromNow = ChronoUnit.HOURS.between(LocalDateTime.now(), slotStart);

        if (hoursFromNow <= 2) {
            return 95;  // 最近2小时，高分
        } else if (hoursFromNow <= 4) {
            return 85;
        } else if (hoursFromNow <= 8) {
            return 75;
        } else if (hoursFromNow <= 24) {
            return 65;
        } else {
            return 50;
        }
    }

    private String generateRecommendationReason(LocalDateTime slotStart) {
        long hoursFromNow = ChronoUnit.HOURS.between(LocalDateTime.now(), slotStart);

        if (hoursFromNow <= 2) {
            return "时段即将开始，人员和设备已就位，无需等待";
        } else if (hoursFromNow <= 4) {
            return "时段较近，可快速开始生产";
        } else if (hoursFromNow <= 8) {
            return "当日时段，便于安排人员";
        } else {
            return "正常时段，可提前准备原料和设备";
        }
    }

    /**
     * 构建强制插单审批上下文
     * 包含影响分析结果、受影响计划、VIP客户等信息
     * 用于 ApprovalChainService 判断是否需要审批
     *
     * @param slot    插单时段
     * @param request 插单请求
     * @return 审批上下文Map
     */
    private Map<String, Object> buildForceInsertApprovalContext(
            InsertSlot slot, ConfirmUrgentInsertRequest request, InsertSlotPreview preview,
            ChainImpactResult chainImpact) {

        Map<String, Object> context = new HashMap<>();

        // 基本影响信息
        context.put("impactLevel", chainImpact.getImpactLevel());

        // 解析受影响计划
        List<InsertSlotDTO.ImpactedPlanDTO> impactedPlans = parseImpactedPlans(slot.getImpactedPlans());
        context.put("affectedPlanCount", chainImpact.getTotalAffectedPlans());

        // 计算受影响产线数
        Set<String> affectedLines = new HashSet<>();
        int maxDelayMinutes = 0;
        int totalDelayMinutes = 0;
        int vipCustomerCount = 0;

        for (InsertSlotDTO.ImpactedPlanDTO plan : impactedPlans) {
            // 统计延误时间
            if (plan.getDelayMinutes() != null) {
                maxDelayMinutes = Math.max(maxDelayMinutes, plan.getDelayMinutes());
                totalDelayMinutes += plan.getDelayMinutes();
            }
            // 统计VIP客户
            if (Boolean.TRUE.equals(plan.getIsVipCustomer())) {
                vipCustomerCount++;
            }
        }

        // 使用链式影响分析的精确数据
        // 注：affectedLines 使用 totalAffectedPlans 作为近似值（每个计划通常对应一条产线）
        context.put("affectedLines", chainImpact.getTotalAffectedPlans());
        context.put("directConflicts", chainImpact.getDirectConflicts());
        context.put("cascadeDelays", chainImpact.getCascadeDelays());
        context.put("maxDelayMinutes", chainImpact.getMaxDelayMinutes());
        context.put("totalDelayMinutes", chainImpact.getTotalDelayMinutes());
        context.put("vipCustomerCount", chainImpact.getAffectedVipCustomers());
        context.put("criticalCrPlans", chainImpact.getCriticalCrPlans());
        context.put("exceedingDeadlinePlans", chainImpact.getExceedingDeadlinePlans());
        context.put("impactScore", chainImpact.getImpactScore());

        // 请求相关信息
        context.put("priority", request.getPriority());
        context.put("plannedQuantity", request.getPlannedQuantity());
        context.put("hasCustomerOrder", request.getCustomerOrderNumber() != null);

        // 时间紧迫性
        long hoursFromNow = ChronoUnit.HOURS.between(LocalDateTime.now(), slot.getStartTime());
        context.put("hoursFromNow", hoursFromNow);
        context.put("isUrgent", hoursFromNow <= 4);

        return context;
    }
}
