package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.AffectedPlanDTO;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.InsertSlotDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.orchestration.BomExpansionService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 影响分析服务实现
 * 提供科学的多维度影响分析算法
 *
 * 算法设计:
 * - Layer 1: 快速可行性检查 O(n)
 * - Layer 2: 多维度推荐评分 + BFS链式影响计算
 * - Layer 3: 风险评估与审批判定
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactAnalysisServiceImpl implements ImpactAnalysisService {

    private final ProductionPlanRepository productionPlanRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final EquipmentRepository equipmentRepository;
    private final TimeClockRecordRepository timeClockRecordRepository;
    private final ProductTypeRepository productTypeRepository;
    /** 原料核对的唯一真值路径: 当前 ACTIVE BOM 展开 + 批次 receipt−used−reserved (与生产/领料同源)。 */
    private final BomExpansionService bomExpansionService;
    /** 用于把 BOM 展开放进独立只读子事务 (见 checkMaterialAvailability 注释)。 */
    private final PlatformTransactionManager transactionManager;

    /** 设备核对没有真实数据可接时的固定原因 (插单时段是动态分配, 不绑产线/设备)。 */
    static final String EQUIPMENT_UNVERIFIED_REASON =
            "设备未验证: 插单时段未绑定产线/设备, 系统没有该时段的设备占用与维修数据可核对";

    // 权重配置
    private static final double W_CAPACITY = 0.30;
    private static final double W_WORKER = 0.20;
    private static final double W_DEADLINE = 0.20;
    private static final double W_IMPACT = 0.15;
    private static final double W_SWITCH = 0.15;

    // 工厂产能配置缓存 (简化版，实际应从数据库/Redis读取)
    private final Map<String, FactoryCapacityConfig> capacityConfigCache = new HashMap<>();

    // ======= Layer 1: 快速可行性检查 =======

    @Override
    @Transactional(readOnly = true)
    public FeasibilityResult checkFeasibility(
            String factoryId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal requiredQuantity,
            String productTypeId) {

        log.debug("检查可行性: factoryId={}, time={}-{}, qty={}, product={}",
                factoryId, startTime, endTime, requiredQuantity, productTypeId);

        // 1. 检查时间是否在工作时间内
        FactoryCapacityConfig config = getFactoryCapacityConfig(factoryId);
        int startHour = startTime.getHour();
        int endHour = endTime.getHour();

        // 简化版工作时间检查 (07:00 - 22:00)
        if (startHour < 7 || endHour > 22) {
            if (!config.isAllowOvertime()) {
                return FeasibilityResult.notFeasible("时段超出工作时间范围");
            }
        }

        // 2. 检查产能是否足够
        long durationHours = ChronoUnit.HOURS.between(startTime, endTime);
        BigDecimal availableCapacity = config.getHourlyCapacity()
                .multiply(BigDecimal.valueOf(durationHours));

        if (requiredQuantity.compareTo(availableCapacity) > 0) {
            Map<String, Object> details = new HashMap<>();
            details.put("requiredQuantity", requiredQuantity);
            details.put("availableCapacity", availableCapacity);
            details.put("durationHours", durationHours);
            return FeasibilityResult.notFeasible(
                    String.format("产能不足: 需要 %.1f kg, 可用 %.1f kg",
                            requiredQuantity.doubleValue(), availableCapacity.doubleValue()),
                    details);
        }

        // 3. 检查原料是否充足
        ResourceCheckResult materialCheck = checkMaterialAvailability(
                factoryId, productTypeId, requiredQuantity);
        if (!materialCheck.isAvailable()) {
            // unverified 的 message 自带「原料未验证: …」前缀, 不再冠以「原料不足」
            return FeasibilityResult.notFeasible(materialCheck.isVerified()
                    ? "原料不足: " + materialCheck.getMessage() : materialCheck.getMessage());
        }

        // 4. 检查设备是否可用
        ResourceCheckResult equipmentCheck = checkEquipmentAvailability(
                factoryId, startTime, endTime, null);
        if (!equipmentCheck.isAvailable()) {
            return FeasibilityResult.notFeasible(equipmentCheck.isVerified()
                    ? "设备不可用: " + equipmentCheck.getMessage() : equipmentCheck.getMessage());
        }

        // 5. 检查工人是否充足
        ResourceCheckResult workerCheck = checkWorkerAvailability(
                factoryId, startTime, endTime, config.getMinWorkers());
        if (!workerCheck.isAvailable()) {
            return FeasibilityResult.notFeasible("工人不足: " + workerCheck.getMessage());
        }

        log.debug("可行性检查通过: factoryId={}", factoryId);
        return FeasibilityResult.feasible();
    }

    // ======= Layer 2: 多维度推荐评分 =======

    @Override
    @Transactional(readOnly = true)
    public RecommendScoreResult calculateRecommendScore(
            String factoryId,
            LocalDateTime slotStartTime,
            LocalDateTime slotEndTime,
            BigDecimal requiredQuantity,
            String productTypeId,
            LocalDateTime deadline) {

        log.debug("计算推荐评分: factoryId={}, slot={}-{}",
                factoryId, slotStartTime, slotEndTime);

        RecommendScoreResult result = new RecommendScoreResult();
        FactoryCapacityConfig config = getFactoryCapacityConfig(factoryId);

        // 如果没有指定需求量，使用默认值 100kg
        BigDecimal effectiveQuantity = requiredQuantity != null && requiredQuantity.compareTo(BigDecimal.ZERO) > 0
                ? requiredQuantity
                : new BigDecimal("100");

        // 1. CapacityFactor: 产能利用率 (0-1)
        long durationHours = ChronoUnit.HOURS.between(slotStartTime, slotEndTime);
        BigDecimal availableCapacity = config.getHourlyCapacity()
                .multiply(BigDecimal.valueOf(Math.max(1, durationHours)));

        double capacityFactor = effectiveQuantity.compareTo(availableCapacity) <= 0
                ? 1.0
                : availableCapacity.divide(effectiveQuantity, 4, RoundingMode.HALF_UP).doubleValue();
        result.setCapacityFactor(Math.min(1.0, capacityFactor));

        // 2. WorkerFactor: 工人可用性 (0-1)
        int availableWorkers = estimateAvailableWorkers(factoryId, slotStartTime, slotEndTime);
        int requiredWorkers = estimateRequiredWorkers(effectiveQuantity, config);
        double workerFactor = requiredWorkers > 0
                ? Math.min(1.0, (double) availableWorkers / requiredWorkers)
                : 1.0;
        result.setWorkerFactor(workerFactor);

        // 3. DeadlineFactor: 交期紧迫度 (0-1)
        // 越紧急分数越高
        double deadlineFactor = 0.5; // 默认中等紧迫
        if (deadline != null) {
            long daysToDeadline = ChronoUnit.DAYS.between(LocalDateTime.now(), deadline);
            if (daysToDeadline <= 0) {
                deadlineFactor = 1.0; // 已过期，最紧急
            } else if (daysToDeadline <= 1) {
                deadlineFactor = 0.95;
            } else if (daysToDeadline <= 3) {
                deadlineFactor = 0.80;
            } else if (daysToDeadline <= 7) {
                deadlineFactor = 0.60;
            } else {
                deadlineFactor = Math.max(0.3, 1.0 - (daysToDeadline / 30.0));
            }
        }
        result.setDeadlineFactor(deadlineFactor);

        // 4. ImpactFactor: 影响程度 (0-1，越小影响越大)
        // 需要计算会影响多少现有计划
        List<ProductionPlan> conflictingPlans = findConflictingPlans(
                factoryId, slotStartTime, slotEndTime);
        int totalPlans = (int) productionPlanRepository.countByFactoryIdAndStatus(
                factoryId, ProductionPlanStatus.PENDING);
        totalPlans = Math.max(1, totalPlans);

        double impactFactor = 1.0 - ((double) conflictingPlans.size() / totalPlans);
        impactFactor = Math.max(0.0, Math.min(1.0, impactFactor));
        result.setImpactFactor(impactFactor);

        // 5. SwitchCostFactor: 换线成本 (0-1)
        String currentProductType = getCurrentProductType(factoryId, slotStartTime);
        double switchCostFactor = 1.0;
        if (currentProductType != null && !currentProductType.equals(productTypeId)) {
            String switchType = determineSwitchType(currentProductType, productTypeId);
            switch (switchType) {
                case "NONE":
                    switchCostFactor = 1.0;
                    break;
                case "SAME_MATERIAL":
                    switchCostFactor = 0.7;
                    break;
                case "FULL_SWITCH":
                default:
                    switchCostFactor = 0.3;
                    break;
            }
        }
        result.setSwitchCostFactor(switchCostFactor);

        // 计算综合评分
        result.calculateTotalScore();

        // 生成推荐理由
        result.generateRecommendationReason();

        log.debug("推荐评分结果: score={}, capacity={}, worker={}, deadline={}, impact={}, switch={}",
                result.getTotalScore(), result.getCapacityFactor(), result.getWorkerFactor(),
                result.getDeadlineFactor(), result.getImpactFactor(), result.getSwitchCostFactor());

        return result;
    }

    // ======= Layer 2: BFS链式影响计算 =======

    @Override
    @Transactional(readOnly = true)
    public ChainImpactResult calculateChainImpact(
            String factoryId,
            LocalDateTime insertStartTime,
            LocalDateTime insertEndTime,
            String productTypeId,
            BigDecimal requiredQuantity) {

        log.debug("计算链式影响: factoryId={}, insert={}-{}",
                factoryId, insertStartTime, insertEndTime);

        ChainImpactResult result = new ChainImpactResult();
        List<AffectedPlanDTO> affectedPlans = new ArrayList<>();

        // 1. 找出直接冲突的计划
        List<ProductionPlan> directConflicts = findConflictingPlans(
                factoryId, insertStartTime, insertEndTime);

        log.debug("发现 {} 个直接冲突计划", directConflicts.size());

        // 2. 计算插入时段的持续时间
        Duration insertDuration = Duration.between(insertStartTime, insertEndTime);

        // 3. BFS 遍历计算级联影响
        Queue<ProductionPlan> queue = new LinkedList<>(directConflicts);
        Set<String> visited = new HashSet<>();
        Map<String, Integer> planDelayMinutes = new HashMap<>();

        // 直接冲突计划的延误 = 插入时段持续时间
        int baseDelayMinutes = (int) insertDuration.toMinutes();

        while (!queue.isEmpty()) {
            ProductionPlan current = queue.poll();
            if (current == null || visited.contains(current.getId())) {
                continue;
            }
            visited.add(current.getId());

            // 判断是否为直接冲突
            boolean isDirect = directConflicts.stream()
                    .anyMatch(p -> p.getId().equals(current.getId()));
            int impactLevel = isDirect ? 1 : 2;

            // 计算当前计划的延误时间
            int delayMinutes;
            if (isDirect) {
                delayMinutes = baseDelayMinutes;
            } else {
                // 级联延误：取决于前置计划的延误
                delayMinutes = planDelayMinutes.getOrDefault(current.getId(), baseDelayMinutes / 2);
            }
            planDelayMinutes.put(current.getId(), delayMinutes);

            // 转换为 AffectedPlanDTO
            AffectedPlanDTO affectedPlan = convertToAffectedPlan(current, delayMinutes, impactLevel);
            affectedPlans.add(affectedPlan);

            // 4. 找出依赖当前计划的后续计划 (相同产线，时间紧随)
            List<ProductionPlan> dependents = findDependentPlans(factoryId, current);
            for (ProductionPlan dep : dependents) {
                if (!visited.contains(dep.getId())) {
                    // 级联延误递减
                    planDelayMinutes.put(dep.getId(), delayMinutes / 2);
                    queue.add(dep);
                }
            }
        }

        result.setAffectedPlans(affectedPlans);

        // 5. 计算统计信息
        result.calculateStatistics();

        // 6. 计算影响评分和等级
        result.calculateImpactScore();
        result.determineImpactLevel();

        // 7. 生成风险预警
        result.generateRiskWarnings();

        log.info("链式影响分析完成: 直接冲突={}, 级联延误={}, 影响等级={}",
                result.getDirectConflicts(), result.getCascadeDelays(), result.getImpactLevel());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ChainImpactResult calculateChainImpactForSlot(
            String factoryId,
            InsertSlotDTO slot,
            Map<String, Object> request) {

        BigDecimal requiredQuantity = BigDecimal.ZERO;
        String productTypeId = null;

        if (request != null) {
            if (request.get("requiredQuantity") != null) {
                requiredQuantity = new BigDecimal(request.get("requiredQuantity").toString());
            }
            if (request.get("productTypeId") != null) {
                productTypeId = request.get("productTypeId").toString();
            }
        }

        return calculateChainImpact(
                factoryId,
                slot.getStartTime(),
                slot.getEndTime(),
                productTypeId,
                requiredQuantity);
    }

    // ======= 资源验证方法 =======

    @Override
    @Transactional(readOnly = true)
    public ResourceCheckResult checkMaterialAvailability(
            String factoryId,
            String productTypeId,
            BigDecimal requiredQuantity) {

        // 2026-10-24 止血: 原来这里写死「可用库存 10000」, 异常分支也返回可用 ⇒ 插单永远判原料够。
        // 现在只接真实数据: 当前 ACTIVE BOM 展开 → 批次 receipt−used−reserved (BomExpansionService,
        // 与生产/领料同一口径)。拿不到真实数据 (没选产品 / 没数量 / 无激活 BOM / 单位无法换算 /
        // 数据源异常) 一律返回「未验证」, ⛔ 不返回可用。
        if (productTypeId == null || productTypeId.isBlank()) {
            return ResourceCheckResult.unverified("MATERIAL",
                    "原料未验证: 未指定产品, 无法按配方核对原料", requiredQuantity);
        }
        if (requiredQuantity == null || requiredQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return ResourceCheckResult.unverified("MATERIAL",
                    "原料未验证: 未指定插单数量, 无法核对原料", requiredQuantity);
        }
        try {
            MaterialCheckResult check = expandBomAndCheckIsolated(factoryId, productTypeId, requiredQuantity);
            if (check == null) {
                return ResourceCheckResult.unverified("MATERIAL",
                        "原料未验证: 原料核对没有返回结果", requiredQuantity);
            }
            if (check.isAllSatisfied()) {
                return ResourceCheckResult.available("MATERIAL", null, requiredQuantity);
            }
            List<MaterialShortfall> shortfalls = check.getShortfalls() != null
                    ? check.getShortfalls() : Collections.emptyList();
            String detail = shortfalls.stream()
                    .map(sf -> String.format("%s 需要 %s, 可用 %s",
                            sf.getMaterialTypeName() != null ? sf.getMaterialTypeName() : sf.getMaterialTypeId(),
                            plain(sf.getRequiredQuantity()), plain(sf.getAvailableQuantity())))
                    .collect(Collectors.joining("; "));
            // 调用方会冠以「原料不足: 」, 这里只放明细
            return ResourceCheckResult.unavailable("MATERIAL",
                    detail.isEmpty() ? "库存不满足配方需求" : detail,
                    null, requiredQuantity);
        } catch (BusinessException e) {
            // 无激活 BOM / 配方没有投入行 / BOM 单位与库存单位不可换算 —— 这是「没法核对」, 不是「够」。
            log.warn("插单原料无法核对: factoryId={}, productTypeId={}, reason={}",
                    factoryId, productTypeId, e.getMessage());
            return ResourceCheckResult.unverified("MATERIAL",
                    "原料未验证: " + e.getMessage(), requiredQuantity);
        } catch (Exception e) {
            log.error("检查原料可用性失败: factoryId={}, productTypeId={}", factoryId, productTypeId, e);
            return ResourceCheckResult.unverified("MATERIAL",
                    "原料未验证: 库存数据读取失败", requiredQuantity);
        }
    }

    /**
     * BOM 展开 + 库存核对放进独立只读子事务 (REQUIRES_NEW)。
     *
     * <p>expandBOM 在无激活 BOM 时抛 BusinessException; 若直接调用, 它会在自己的 AOP 边界上把
     * 外层共享事务标成 rollback-only, 这里 catch 住也抹不掉 (SupplyChainOrchestrator 2026-08-01
     * doomed-tx 事故同一机制)。独立子事务让失败只回滚它自己。
     */
    private MaterialCheckResult expandBomAndCheckIsolated(
            String factoryId, String productTypeId, BigDecimal requiredQuantity) {
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setReadOnly(true);
        isolated.setName("ImpactAnalysisServiceImpl.expandBomAndCheckIsolated");
        return isolated.execute(status -> {
            List<MaterialRequirement> requirements =
                    bomExpansionService.expandBOM(factoryId, productTypeId, requiredQuantity);
            if (requirements == null || requirements.isEmpty()) {
                // 配方只有副产行 / 物料未绑定: 没有可核对的投入 ⇒ 不能说「够」
                throw new BusinessException(409, "该产品配方没有可核对的原料投入行")
                        .withHint("先在该产品的 BOM 配方里补上原料投入行并绑定物料，再回来做插单分析");
            }
            return bomExpansionService.checkMaterialAvailability(factoryId, requirements);
        });
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceCheckResult checkEquipmentAvailability(
            String factoryId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String productionLineId) {

        // 2026-10-24 止血: 原来写死「5 台设备可用」, 异常分支也返回可用。
        // 插单时段是动态分配 (productionLineId="default"), 系统里没有「该时段哪台设备被占 / 在修」
        // 的真实数据可核对 ⇒ 如实返回「未验证」, 不编数字。接上产线/设备排程后再替换这里。
        return ResourceCheckResult.unverified("EQUIPMENT", EQUIPMENT_UNVERIFIED_REASON, BigDecimal.ONE);
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceCheckResult checkWorkerAvailability(
            String factoryId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int requiredWorkers) {

        try {
            // 估算可用工人数
            int availableWorkers = estimateAvailableWorkers(factoryId, startTime, endTime);

            if (availableWorkers >= requiredWorkers) {
                return ResourceCheckResult.available("WORKER",
                        BigDecimal.valueOf(availableWorkers),
                        BigDecimal.valueOf(requiredWorkers));
            } else {
                return ResourceCheckResult.unavailable(
                        "WORKER",
                        String.format("工人不足: 需要 %d 人, 可用 %d 人",
                                requiredWorkers, availableWorkers),
                        BigDecimal.valueOf(availableWorkers),
                        BigDecimal.valueOf(requiredWorkers));
            }
        } catch (Exception e) {
            log.error("检查工人可用性失败", e);
            return ResourceCheckResult.unverified("WORKER",
                    "人员未验证: 人员数据读取失败",
                    BigDecimal.valueOf(requiredWorkers));
        }
    }

    // ======= 换线成本计算 =======

    @Override
    public int calculateSwitchCost(String factoryId, String currentProductType, String newProductType) {
        if (currentProductType == null || currentProductType.equals(newProductType)) {
            return 0;
        }

        FactoryCapacityConfig config = getFactoryCapacityConfig(factoryId);
        String switchType = determineSwitchType(currentProductType, newProductType);

        switch (switchType) {
            case "NONE":
                return 0;
            case "SAME_MATERIAL":
                return config.getMaterialSwitchCostMinutes();
            case "FULL_SWITCH":
            default:
                return config.getSwitchCostMinutes();
        }
    }

    @Override
    public String determineSwitchType(String currentProductType, String newProductType) {
        if (currentProductType == null || currentProductType.equals(newProductType)) {
            return "NONE";
        }

        // 检查是否使用相同原料
        // 简化实现：通过产品类型ID前缀判断
        // 实际应该查询产品配方
        try {
            String currentPrefix = extractMaterialPrefix(currentProductType);
            String newPrefix = extractMaterialPrefix(newProductType);

            if (currentPrefix != null && currentPrefix.equals(newPrefix)) {
                return "SAME_MATERIAL";
            }
        } catch (Exception e) {
            log.warn("判断换线类型失败", e);
        }

        return "FULL_SWITCH";
    }

    // ======= 工厂产能配置 =======

    @Override
    public FactoryCapacityConfig getFactoryCapacityConfig(String factoryId) {
        return capacityConfigCache.computeIfAbsent(factoryId,
                id -> FactoryCapacityConfig.getDefault(id));
    }

    // ======= 私有辅助方法 =======

    /**
     * 查找与指定时段冲突的生产计划
     */
    private List<ProductionPlan> findConflictingPlans(
            String factoryId,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        // 查找在指定时段内有排产的计划
        List<ProductionPlan> pendingPlans = productionPlanRepository
                .findByFactoryIdAndStatus(factoryId, ProductionPlanStatus.PENDING);

        List<ProductionPlan> inProgressPlans = productionPlanRepository
                .findByFactoryIdAndStatus(factoryId, ProductionPlanStatus.IN_PROGRESS);

        List<ProductionPlan> allActivePlans = new ArrayList<>();
        allActivePlans.addAll(pendingPlans);
        allActivePlans.addAll(inProgressPlans);

        return allActivePlans.stream()
                .filter(plan -> {
                    LocalDateTime planStart = plan.getStartTime();
                    LocalDateTime planEnd = plan.getEndTime();

                    if (planStart == null || planEnd == null) {
                        return false;
                    }

                    // 检查时间重叠
                    return !(planEnd.isBefore(startTime) || planStart.isAfter(endTime));
                })
                .collect(Collectors.toList());
    }

    /**
     * 查找依赖指定计划的后续计划
     */
    private List<ProductionPlan> findDependentPlans(String factoryId, ProductionPlan currentPlan) {
        if (currentPlan.getEndTime() == null) {
            return Collections.emptyList();
        }

        // 查找结束时间紧随当前计划的计划 (在当前计划结束后4小时内开始)
        LocalDateTime searchStart = currentPlan.getEndTime();
        LocalDateTime searchEnd = searchStart.plusHours(4);

        List<ProductionPlan> candidates = productionPlanRepository
                .findByFactoryIdAndStatus(factoryId, ProductionPlanStatus.PENDING);

        return candidates.stream()
                .filter(plan -> {
                    LocalDateTime planStart = plan.getStartTime();
                    if (planStart == null) {
                        return false;
                    }
                    return planStart.isAfter(searchStart) && planStart.isBefore(searchEnd);
                })
                .collect(Collectors.toList());
    }

    /**
     * 将生产计划转换为受影响计划DTO
     */
    private AffectedPlanDTO convertToAffectedPlan(
            ProductionPlan plan,
            int delayMinutes,
            int impactLevel) {

        LocalDateTime originalEnd = plan.getEndTime();
        LocalDateTime delayedEnd = originalEnd != null
                ? originalEnd.plusMinutes(delayMinutes)
                : null;

        // 计算延迟后的CR值
        BigDecimal originalCr = plan.getCrValue();
        BigDecimal delayedCr = null;
        boolean becomesUrgent = false;

        if (originalCr != null && plan.getExpectedCompletionDate() != null) {
            // CR = (交期 - 今日) / 工期
            // 延迟会减少分子，使CR变小
            long originalDaysRemaining = ChronoUnit.DAYS.between(
                    LocalDate.now(), plan.getExpectedCompletionDate());
            double delayDays = delayMinutes / (24.0 * 60);
            long newDaysRemaining = (long) Math.max(0, originalDaysRemaining - delayDays);

            // 估算工期 (假设原CR对应的工期)
            if (originalCr.compareTo(BigDecimal.ZERO) > 0) {
                double originalWorkDays = originalDaysRemaining / originalCr.doubleValue();
                if (originalWorkDays > 0) {
                    delayedCr = BigDecimal.valueOf(newDaysRemaining / originalWorkDays)
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }

            becomesUrgent = (delayedCr != null && delayedCr.compareTo(BigDecimal.ONE) < 0)
                    && (originalCr.compareTo(BigDecimal.ONE) >= 0);
        }

        // 判断是否超期
        boolean exceedsDeadline = false;
        if (delayedEnd != null && plan.getExpectedCompletionDate() != null) {
            exceedsDeadline = delayedEnd.toLocalDate()
                    .isAfter(plan.getExpectedCompletionDate());
        }

        return AffectedPlanDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .productName(plan.getProductType() != null
                        ? plan.getProductType().getName()
                        : null)
                .productTypeId(plan.getProductTypeId())
                .plannedQuantity(plan.getPlannedQuantity())
                .originalStartTime(plan.getStartTime())
                .originalEndTime(originalEnd)
                .delayedStartTime(plan.getStartTime() != null
                        ? plan.getStartTime().plusMinutes(delayMinutes)
                        : null)
                .delayedEndTime(delayedEnd)
                .delayMinutes(delayMinutes)
                .delayHours(delayMinutes / 60.0)
                .customerName(plan.getSourceCustomerName())
                .isVipCustomer(false) // 需要从客户表获取
                .customerOrderNumber(plan.getCustomerOrderNumber())
                .crValue(originalCr)
                .delayedCrValue(delayedCr)
                .becomesUrgent(becomesUrgent)
                .exceedsDeadline(exceedsDeadline)
                .priority(plan.getPriority())
                .impactType(impactLevel == 1 ? "DIRECT" : "CASCADE")
                .impactLevel(impactLevel)
                .status(plan.getStatus() != null ? plan.getStatus().name() : null)
                .sourceType(plan.getSourceType() != null ? plan.getSourceType().name() : null)
                .build();
    }

    /**
     * 估算可用工人数
     */
    private int estimateAvailableWorkers(
            String factoryId,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        // 基于考勤数据估算
        // 简化实现：返回配置的标准工人数
        FactoryCapacityConfig config = getFactoryCapacityConfig(factoryId);

        // 检查是否为工作时间
        int hour = startTime.getHour();
        if (hour < 7 || hour >= 22) {
            // 非正常工作时间，可用工人减少
            return config.getMinWorkers();
        }

        return config.getStandardShiftWorkers();
    }

    /**
     * 估算需要的工人数
     */
    private int estimateRequiredWorkers(BigDecimal requiredQuantity, FactoryCapacityConfig config) {
        // 简化计算：每500kg需要1个工人
        int baseWorkers = requiredQuantity.divide(
                BigDecimal.valueOf(500), 0, RoundingMode.CEILING).intValue();
        return Math.max(config.getMinWorkers(), Math.min(baseWorkers, config.getMaxWorkers()));
    }

    /**
     * 获取当前正在生产的产品类型
     */
    private String getCurrentProductType(String factoryId, LocalDateTime time) {
        // 查找在指定时间正在进行的计划
        List<ProductionPlan> inProgress = productionPlanRepository
                .findByFactoryIdAndStatus(factoryId, ProductionPlanStatus.IN_PROGRESS);

        return inProgress.stream()
                .filter(p -> p.getStartTime() != null && p.getEndTime() != null)
                .filter(p -> !time.isBefore(p.getStartTime()) && !time.isAfter(p.getEndTime()))
                .map(ProductionPlan::getProductTypeId)
                .findFirst()
                .orElse(null);
    }

    /**
     * 提取产品类型的原料前缀
     */
    private String extractMaterialPrefix(String productTypeId) {
        if (productTypeId == null || productTypeId.length() < 4) {
            return null;
        }
        // 假设前4个字符代表原料类型
        return productTypeId.substring(0, 4);
    }
}
