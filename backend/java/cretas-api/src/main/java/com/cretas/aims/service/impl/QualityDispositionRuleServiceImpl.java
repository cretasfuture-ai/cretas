package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.decision.QualityRecommendationEvaluatorV1;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 质检处置规则服务实现
 *
 * 基于可配置规则评估质检结果，确定处置动作
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityDispositionRuleServiceImpl implements QualityDispositionRuleService {

    private static final QualityRecommendationEvaluatorV1 RECOMMENDATION_EVALUATOR =
            new QualityRecommendationEvaluatorV1();

    private final ApprovalChainService approvalChainService;
    private final DecisionAuditService decisionAuditService;
    private final UserRepository userRepository;
    private final com.cretas.aims.service.quality.QualityDispositionDecisionGuard decisionGuard;

    /**
     * 质检处置 → 库存桥接。放行 (RELEASE/CONDITIONAL_RELEASE) 时把本次质检隔离的成品/原料批次翻回
     * AVAILABLE, 报废 (SCRAP) 时把原料置 SCRAPPED —— 让「人的处置决策」真正执行到库存, 而非只写审计日志。
     * {@code required=false} 以不破坏未 wire 该 bean 的单测; 缺失时 graceful skip。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.quality.QualityDispositionInventoryService dispositionInventoryService;

    // ===================== 阈值配置（未来可配置化） =====================
    private static final BigDecimal PASS_RATE_THRESHOLD_RELEASE = new BigDecimal("95.00");
    private static final BigDecimal PASS_RATE_THRESHOLD_CONDITIONAL = new BigDecimal("85.00");
    private static final BigDecimal PASS_RATE_THRESHOLD_REWORK = new BigDecimal("70.00");
    private static final BigDecimal DEFECT_RATE_THRESHOLD_SCRAP = new BigDecimal("50.00");

    @Override
    public DispositionResult evaluateDisposition(String factoryId, QualityInspection inspection) {
        return evaluateDisposition(factoryId, inspection, new HashMap<>());
    }

    @Override
    public DispositionResult evaluateDisposition(
            String factoryId,
            QualityInspection inspection,
            Map<String, Object> context) {

        log.info("评估质检处置: factoryId={}, inspectionId={}, passRate={}",
                factoryId, inspection.getId(), inspection.getPassRate());

        // 构建评估上下文
        Map<String, Object> evalContext = buildEvaluationContext(factoryId, inspection, context);

        // 基于规则评估处置动作
        QualityRecommendationEvaluatorV1.Evaluation evaluation = RECOMMENDATION_EVALUATOR.evaluate(inspection);
        DispositionAction recommendedAction = evaluation.recommendedAction();
        BigDecimal confidence = evaluation.confidence();

        // 检查是否需要审批
        boolean requiresApproval = recommendedAction.requiresApproval()
                || releasesUnclearedBatch(recommendedAction, inspection)
                || checkApprovalRequirement(factoryId, recommendedAction, evalContext);

        // 查找匹配的规则配置
        Optional<ApprovalChainConfig> matchedConfig = Optional.empty();
        if (requiresApproval) {
            matchedConfig = approvalChainService.findMatchingConfig(
                    factoryId, DecisionType.QUALITY_RELEASE, evalContext);
        }

        // 确定备选动作
        DispositionAction[] alternatives = determineAlternatives(recommendedAction);

        DispositionResult result = DispositionResult.builder()
                .recommendedAction(recommendedAction)
                .requiresApproval(requiresApproval)
                .triggeredRuleName(matchedConfig.map(ApprovalChainConfig::getName).orElse(null))
                .ruleConfigId(matchedConfig.map(ApprovalChainConfig::getId).orElse(null))
                .ruleVersion(matchedConfig.map(ApprovalChainConfig::getVersion).orElse(null))
                .confidence(confidence)
                .reason(buildReasonString(inspection, recommendedAction))
                .alternativeActions(alternatives)
                .evaluationContext(evalContext)
                .build();

        log.info("质检处置评估完成: action={}, requiresApproval={}, confidence={}",
                recommendedAction, requiresApproval, confidence);

        return result;
    }

    @Override
    public boolean canDirectRelease(String factoryId, QualityInspection inspection) {
        // 直接放行条件：
        // 1. 合格率 >= 95%
        // 2. 结果为 PASS
        // 3. 无需审批
        if (inspection.getPassRate() == null) {
            return false;
        }

        boolean passRateOk = inspection.getPassRate().compareTo(PASS_RATE_THRESHOLD_RELEASE) >= 0;
        boolean resultPass = "PASS".equalsIgnoreCase(inspection.getResult());

        if (!passRateOk || !resultPass || isAwaitingDisposition(inspection)) {
            return false;
        }

        // 检查是否有阻止直接放行的规则
        Map<String, Object> context = buildEvaluationContext(factoryId, inspection, new HashMap<>());
        return !approvalChainService.requiresApproval(
                factoryId, DecisionType.QUALITY_RELEASE, context);
    }

    @Override
    @Transactional
    public DispositionExecutionResult recheckPlanRelease(String factoryId, Long productionBatchId, Long userId) {
        if (dispositionInventoryService == null) {
            throw new IllegalStateException("质检处置库存桥接未注入，不能重新核对放行");
        }
        User executor = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
        var applied = dispositionInventoryService.releaseHeldGoodsOfPassedBatch(factoryId, productionBatchId);

        Map<String, Object> context = new HashMap<>();
        context.put("productionBatchId", productionBatchId);
        context.put("executorId", userId);
        context.put("trigger", "PASSED_BATCH_RELEASE_REQUEST");
        Map<String, Object> output = new HashMap<>();
        output.put("finishedGoodsReleased", applied.getFinishedGoodsReleased());
        output.put("finishedGoodsHeld", applied.getFinishedGoodsHeld());
        output.put("notice", applied.getFinishedGoodsNotice());
        var auditLog = decisionAuditService.logRuleExecution(
                factoryId,
                "ProductionBatch",
                String.valueOf(productionBatchId),
                context,
                output,
                List.of("PLAN_RELEASE_RECHECK"),
                "已放行批次申请放行隔离成品 (单人决定, Owner 2026-11-09)",
                userId,
                executor.getFullName(),
                executor.getRole() != null ? executor.getRole() : "UNKNOWN");

        int released = applied.getFinishedGoodsReleased();
        int held = applied.getFinishedGoodsHeld();
        String message = released == 0 && held == 0
                ? "这个计划没有在隔离中的成品"
                : "已重新核对: 放行 " + released + " 个成品批次"
                        + (held > 0 ? "；仍有 " + held + " 个成品批次在隔离中，不可销售"
                                + (applied.getFinishedGoodsNotice() != null
                                        ? "（" + applied.getFinishedGoodsNotice() + "）" : "")
                                : "");
        return DispositionExecutionResult.builder()
                .success(true)
                .executedAction(DispositionAction.RELEASE)
                .approvalInitiated(false)
                .auditLogId(auditLog != null ? auditLog.getId() : null)
                .message(message)
                .newBatchStatus(applied.getBatchQualityStatus())
                .finishedGoodsHeld(held)
                .build();
    }

    @Override
    @Transactional
    public void recordRefusedPlanRelease(String factoryId, Long productionBatchId, Long userId, String code,
                                         String reason) {
        User executor = userRepository.findById(userId).orElse(null);
        Map<String, Object> context = new HashMap<>();
        context.put("productionBatchId", productionBatchId);
        context.put("executorId", userId);
        context.put("trigger", "PASSED_BATCH_RELEASE_REQUEST");
        Map<String, Object> output = new HashMap<>();
        output.put("refused", true);
        output.put("code", code);
        output.put("reason", reason);
        decisionAuditService.logRuleExecution(
                factoryId,
                "ProductionBatch",
                String.valueOf(productionBatchId),
                context,
                output,
                List.of("PLAN_RELEASE_RECHECK"),
                "已放行批次申请放行隔离成品被拒: " + reason,
                userId,
                executor != null ? executor.getFullName() : null,
                executor != null && executor.getRole() != null ? executor.getRole() : "UNKNOWN");
    }

    @Override
    public boolean isAwaitingDisposition(QualityInspection inspection) {
        return dispositionInventoryService != null && dispositionInventoryService.isAwaitingDisposition(inspection);
    }

    /** D-S302-01: 批次还不合格时, 哪怕最新一次检验全合格, 放行也要另一个人复核。 */
    private boolean releasesUnclearedBatch(DispositionAction action, QualityInspection inspection) {
        return action == DispositionAction.RELEASE && isAwaitingDisposition(inspection);
    }

    @Override
    public boolean requiresSpecialApproval(
            String factoryId,
            QualityInspection inspection,
            DispositionAction requestedAction) {

        // 特批场景：
        // 1. 合格率低于阈值但请求放行
        // 2. 不合格但请求条件放行
        // 3. 严重缺陷但不报废

        Map<String, Object> context = buildEvaluationContext(factoryId, inspection, new HashMap<>());
        context.put("requestedAction", requestedAction.name());

        // 检查是否匹配特批规则
        return approvalChainService.requiresApproval(
                factoryId, DecisionType.QUALITY_EXCEPTION, context);
    }

    @Override
    @Transactional
    public DispositionExecutionResult executeDisposition(
            String factoryId,
            QualityInspection inspection,
            DispositionAction action,
            Long executorId,
            String reason) {

        return executeDispositionInternal(factoryId, inspection, action, executorId, reason, false);
    }

    @Override
    @Transactional
    public DispositionExecutionResult executeApprovedDisposition(
            String factoryId,
            QualityInspection inspection,
            DispositionAction action,
            Long approverId,
            String reason) {

        return executeDispositionInternal(factoryId, inspection, action, approverId, reason, true);
    }

    private DispositionExecutionResult executeDispositionInternal(
            String factoryId,
            QualityInspection inspection,
            DispositionAction action,
            Long executorId,
            String reason,
            boolean approvalGranted) {

        log.info("执行质检处置: factoryId={}, inspectionId={}, action={}, executorId={}",
                factoryId, inspection.getId(), action, executorId);

        inspection = decisionGuard.lockInspection(factoryId, inspection);
        // Q6: 已报废批次不能返工 —— 在待审批申请 / 库存翻转任何一笔写入之前, 读的是锁后的批次状态
        decisionGuard.assertReworkAllowed(factoryId, inspection.getProductionBatchId(), action.name());
        if (action == DispositionAction.RELEASE && (!Set.of("PASS", "PASSED", "QUALIFIED")
                .contains(inspection.getResult() == null ? "" : inspection.getResult().toUpperCase(Locale.ROOT))
                || inspection.getPassRate() == null || inspection.getPassRate().compareTo(PASS_RATE_THRESHOLD_RELEASE) < 0)) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "本次质检未达到直接放行条件，请提交条件放行申请并由另一人复核");
        }
        if (!approvalGranted) decisionGuard.assertCanStart(inspection);

        // 获取执行人信息
        User executor = userRepository.findById(executorId)
                .orElseThrow(() -> new EntityNotFoundException("User", executorId));

        // 构建上下文
        Map<String, Object> context = buildEvaluationContext(factoryId, inspection, new HashMap<>());
        context.put("requestedAction", action.name());
        context.put("executorId", executorId);
        context.put("reason", reason);

        // 动作静态审批契约是最低门禁；工厂动态规则可以进一步要求审批，不能放宽。
        boolean needsApproval = !approvalGranted && (action.requiresApproval()
                || releasesUnclearedBatch(action, inspection)
                || requiresSpecialApproval(factoryId, inspection, action));

        String auditLogId = null;
        String approvalRequestId = null;
        boolean approvalInitiated = false;
        String newStatus = null;
        int finishedGoodsHeld = 0;
        String finishedGoodsNotice = null;

        if (needsApproval) {
            // 需要特批 - 记录审计并启动审批流程
            Optional<ApprovalChainConfig> config = approvalChainService.findMatchingConfig(
                    factoryId, DecisionType.QUALITY_EXCEPTION, context);

            var auditLog = decisionAuditService.logPendingApproval(
                    factoryId,
                    "QualityInspection",
                    inspection.getId(),
                    context,
                    action.name(),
                    reason,
                    executorId,
                    executor.getFullName(),
                    executor.getRole() != null ? executor.getRole() : "UNKNOWN",
                    config.map(ApprovalChainConfig::getId).orElse(null),
                    config.map(ApprovalChainConfig::getVersion).orElse(null),
                    config.map(ApprovalChainConfig::getName).orElse(null)
            );

            auditLogId = auditLog.getId();
            approvalInitiated = true;
            approvalRequestId = auditLog.getId(); // 使用审计日志ID作为审批请求ID
            newStatus = "PENDING_APPROVAL";

            log.info("质检处置需要特批: auditLogId={}", auditLogId);

        } else {
            // 🔒 库存桥接: 只在<b>最终直接执行</b>分支翻转库存 (非 approval-initiated 分支)。
            // 未审批申请 / 被拒审批不会走到这里, 保证「审批门禁」—— 翻转发生在最终执行/审批通过, 不在提交时。
            // 在同一 @Transactional 内, 与审计日志一起提交/回滚。批次状态由它写, 所以排在审计之前:
            // 审计和响应里的 newStatus 读实际写下的值, 不再是 mapActionToStatus 的声明。
            newStatus = mapActionToStatus(action);
            if (dispositionInventoryService != null) {
                try {
                    // 成品只在复核通过的处置里解除隔离 (Q4): approvalGranted = 走过另一人复核
                    var applied = dispositionInventoryService.applyDisposition(inspection, action, approvalGranted);
                    if (applied != null) {
                        finishedGoodsHeld = applied.getFinishedGoodsHeld();
                        finishedGoodsNotice = applied.getFinishedGoodsNotice();
                        if (applied.getBatchQualityStatus() != null) newStatus = applied.getBatchQualityStatus();
                    }
                } catch (Exception e) {
                    // 库存翻转失败必须让整个处置事务回滚 (审计日志与库存状态不能不一致): 食安关键路径, fail-loud。
                    log.error("质检处置库存桥接失败, 回滚整个处置: inspectionId={}, action={}: {}",
                            inspection.getId(), action, e.getMessage(), e);
                    throw e;
                }
            }

            // 直接执行
            Map<String, Object> outputResult = new HashMap<>();
            outputResult.put("action", action.name());
            outputResult.put("newStatus", newStatus);

            var auditLog = decisionAuditService.logRuleExecution(
                    factoryId,
                    "QualityInspection",
                    inspection.getId(),
                    context,
                    outputResult,
                    List.of("QUALITY_DISPOSITION_RULE"),
                    "质检处置: " + action.getDescription(),
                    executorId,
                    executor.getFullName(),
                    executor.getRole() != null ? executor.getRole() : "UNKNOWN"
            );

            auditLogId = auditLog.getId();

            log.info("质检处置已执行: action={}, newStatus={}", action, newStatus);
        }

        return DispositionExecutionResult.builder()
                .success(true)
                .executedAction(action)
                .approvalInitiated(approvalInitiated)
                .approvalRequestId(approvalRequestId)
                .auditLogId(auditLogId)
                .message(approvalInitiated
                        ? "处置请求已提交，等待审批"
                        : "处置已执行: " + action.getDescription()
                                + (finishedGoodsHeld > 0
                                        ? "；同计划还有 " + finishedGoodsHeld + " 个成品批次仍在隔离中，不可销售"
                                                + (finishedGoodsNotice != null ? "（" + finishedGoodsNotice + "）" : "")
                                        : ""))
                .newBatchStatus(newStatus)
                .finishedGoodsHeld(finishedGoodsHeld)
                .build();
    }

    // ===================== 私有辅助方法 =====================

    /**
     * 构建评估上下文
     */
    private Map<String, Object> buildEvaluationContext(
            String factoryId,
            QualityInspection inspection,
            Map<String, Object> additionalContext) {

        Map<String, Object> context = new HashMap<>(additionalContext);

        context.put("inspectionId", inspection.getId());
        context.put("productionBatchId", inspection.getProductionBatchId());
        context.put("passRate", inspection.getPassRate());
        context.put("defectRate", inspection.getDefectRate());
        context.put("result", inspection.getResult());
        context.put("qualityGrade", inspection.getQualityGrade());
        context.put("sampleSize", inspection.getSampleSize());
        context.put("passCount", inspection.getPassCount());
        context.put("failCount", inspection.getFailCount());
        context.put("inspectionDate", inspection.getInspectionDate());

        // Reserved server evidence is written last so callers cannot inject or
        // replace a replayable snapshot through additionalContext.
        context.put(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY,
                buildRecommendationSnapshot(factoryId, inspection));

        return context;
    }

    private Map<String, Object> buildRecommendationSnapshot(
            String factoryId, QualityInspection inspection) {
        QualityRecommendationEvaluatorV1.Evaluation evaluation =
                RECOMMENDATION_EVALUATOR.evaluate(inspection);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", QualityRecommendationEvaluatorV1.SNAPSHOT_SCHEMA_VERSION);
        snapshot.put("evaluatorId", QualityRecommendationEvaluatorV1.EVALUATOR_ID);
        snapshot.put("evaluatorVersion", QualityRecommendationEvaluatorV1.EVALUATOR_VERSION);
        snapshot.put("ruleBundleHash", QualityRecommendationEvaluatorV1.RULE_BUNDLE_HASH);
        snapshot.put("factoryId", factoryId);
        snapshot.put("inspectionId", inspection.getId());
        snapshot.put("productionBatchId", inspection.getProductionBatchId() == null
                ? null : inspection.getProductionBatchId().toString());
        snapshot.put("inspectionDate", inspection.getInspectionDate() == null
                ? null : inspection.getInspectionDate().toString());
        snapshot.put("passRate", decimalText(inspection.getPassRate()));
        snapshot.put("defectRate", decimalText(inspection.getDefectRate()));
        snapshot.put("result", inspection.getResult());
        snapshot.put("qualityGrade", inspection.getQualityGrade());
        snapshot.put("sampleSize", decimalText(inspection.getSampleSize()));
        snapshot.put("passCount", decimalText(inspection.getPassCount()));
        snapshot.put("failCount", decimalText(inspection.getFailCount()));
        snapshot.put("recordedRecommendation", evaluation.recommendedAction().name());
        snapshot.put("recordedConfidence", decimalText(evaluation.confidence()));
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("releasePassRateMin", "95.00");
        rules.put("conditionalPassRateMin", "85.00");
        rules.put("reworkPassRateMin", "70.00");
        rules.put("scrapDefectRateMin", "50.00");
        rules.put("nullPassRateAction", DispositionAction.HOLD.name());
        snapshot.put("rules", rules);
        snapshot.put("snapshotHash", snapshotHash(snapshot));
        return snapshot;
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String snapshotHash(Map<String, Object> snapshot) {
        return QualityRecommendationEvaluatorV1.canonicalHash(snapshot);
    }

    /**
     * 检查是否需要审批
     */
    private boolean checkApprovalRequirement(
            String factoryId,
            DispositionAction action,
            Map<String, Object> context) {

        // RELEASE 通常不需要审批（除非特殊规则）
        if (action == DispositionAction.RELEASE) {
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // CONDITIONAL_RELEASE, REWORK 可能需要审批
        if (action == DispositionAction.CONDITIONAL_RELEASE ||
                action == DispositionAction.REWORK) {
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // SCRAP 通常需要审批
        if (action == DispositionAction.SCRAP) {
            context.put("dispositionType", "SCRAP");
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // SPECIAL_APPROVAL 总是需要审批
        return action == DispositionAction.SPECIAL_APPROVAL;
    }

    /**
     * 确定备选动作
     */
    private DispositionAction[] determineAlternatives(DispositionAction recommended) {
        switch (recommended) {
            case RELEASE:
                return new DispositionAction[]{};
            case CONDITIONAL_RELEASE:
                return new DispositionAction[]{DispositionAction.REWORK, DispositionAction.SPECIAL_APPROVAL};
            case REWORK:
                return new DispositionAction[]{DispositionAction.CONDITIONAL_RELEASE, DispositionAction.SCRAP};
            case SCRAP:
                return new DispositionAction[]{DispositionAction.REWORK, DispositionAction.SPECIAL_APPROVAL};
            case SPECIAL_APPROVAL:
                return new DispositionAction[]{DispositionAction.SCRAP, DispositionAction.REWORK};
            default:
                return new DispositionAction[]{DispositionAction.SPECIAL_APPROVAL};
        }
    }

    /**
     * 构建处置理由字符串
     */
    private String buildReasonString(QualityInspection inspection, DispositionAction action) {
        StringBuilder sb = new StringBuilder();
        sb.append("合格率: ").append(inspection.getPassRate()).append("%, ");
        sb.append("缺陷率: ").append(inspection.getDefectRate()).append("%, ");
        sb.append("质量等级: ").append(inspection.getQualityGrade()).append(". ");

        switch (action) {
            case RELEASE:
                sb.append("质检合格，符合放行条件。");
                break;
            case CONDITIONAL_RELEASE:
                sb.append("合格率达标但未达最优，建议条件放行。");
                break;
            case REWORK:
                sb.append("合格率偏低，需要返工处理。");
                break;
            case SCRAP:
                sb.append("缺陷率过高，建议报废处理。");
                break;
            case SPECIAL_APPROVAL:
                sb.append("不满足常规规则，需要特批审批。");
                break;
            default:
                sb.append("需要进一步评估。");
        }

        return sb.toString();
    }

    /**
     * 将动作映射到批次状态
     */
    private String mapActionToStatus(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return "PASSED";
            case CONDITIONAL_RELEASE:
                return "PARTIAL_PASS";
            case REWORK:
                return "REWORK_REQUIRED";
            case SCRAP:
                return "SCRAPPED";
            case SPECIAL_APPROVAL:
                return "PENDING_APPROVAL";
            default:
                return "PENDING";
        }
    }
}
