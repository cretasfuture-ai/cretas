package com.cretas.aims.service.impl;

import com.cretas.aims.dto.quality.*;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.DecisionAuditLog.ApprovalStatus;
import com.cretas.aims.entity.DecisionAuditLog.DecisionType;
import com.cretas.aims.entity.DecisionAuditLog.ExecutionMode;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.SpecialApprovalService;
import com.cretas.aims.service.quality.QualityDispositionDecisionGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 特批放行审批服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpecialApprovalServiceImpl implements SpecialApprovalService {

    private final DecisionAuditLogRepository decisionAuditLogRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final UserRepository userRepository;
    private final QualityDispositionRuleService qualityDispositionRuleService;
    private final ObjectMapper objectMapper;
    private final QualityDispositionDecisionGuard decisionGuard;

    /** 纪律排查 B3: 审批权限码 (默认 = APPROVER_ROLES, 各厂可改)。未注入 (手工构造的单测) = 按默认名单。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.ApprovalAuthority approvalAuthority;

    /** 纪律排查 B3: 工厂级「允许本人审批」。未注入 = 开关关 (四眼照旧)。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.FactorySelfApprovalGate selfApprovalGate;

    public static final String SPECIAL_APPROVAL_SELF_APPROVAL = "SPECIAL_APPROVAL_SELF_APPROVAL";
    public static final String SPECIAL_APPROVAL_ROLE_FORBIDDEN = "SPECIAL_APPROVAL_ROLE_FORBIDDEN";
    public static final String SPECIAL_APPROVAL_ACTOR_UNKNOWN = "SPECIAL_APPROVAL_ACTOR_UNKNOWN";
    public static final String SPECIAL_APPROVAL_RELEASE_NOT_ELIGIBLE = "SPECIAL_APPROVAL_RELEASE_NOT_ELIGIBLE";

    @Override
    @Transactional
    public SpecialApprovalDTO submitSpecialApproval(
            String factoryId,
            String inspectionId,
            SpecialApprovalRequest request,
            Long requesterId
    ) {
        log.info("提交特批放行申请: factoryId={}, inspectionId={}, requesterId={}",
                factoryId, inspectionId, requesterId);

        // 1. 获取质检记录
        QualityInspection inspection = qualityInspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("质检记录", "id", inspectionId));

        // 验证工厂ID
        if (!inspection.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "质检记录不属于该工厂")
                    .withHint("请切换到该质检记录所属的工厂后再操作");
        }

        // 2. 获取生产批次
        ProductionBatch batch = productionBatchRepository.findById(inspection.getProductionBatchId())
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("生产批次", "id", String.valueOf(inspection.getProductionBatchId())));

        // 3. 获取申请人信息
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("申请人不存在"));

        // 申请的动作在写申请行之前就核对: 认不出的动作、批不下来的直接放行, 不留一条永远批不成的待审批
        QualityDispositionRuleService.DispositionAction requestedAction =
                parseApprovedAction(request.getRequestedAction());
        if (requestedAction == QualityDispositionRuleService.DispositionAction.RELEASE
                && !qualityDispositionRuleService.meetsDirectReleaseCriteria(inspection)) {
            throw new BusinessException(409, "本次质检未达到直接放行条件，特批也不能直接放行")
                    .withCode(SPECIAL_APPROVAL_RELEASE_NOT_ELIGIBLE)
                    .withHint("请改为申请条件放行，由质量经理复核后放行");
        }

        // Q6: 已报废批次不能申请返工, 在写申请行之前拒绝
        decisionGuard.assertReworkAllowed(factoryId, inspection.getProductionBatchId(), requestedAction.name());

        // 4. 评估处置建议 (获取规则信息)
        QualityDispositionRuleService.DispositionResult dispositionResult =
                qualityDispositionRuleService.evaluateDisposition(factoryId, inspection);

        // 5. 构建输入上下文
        Map<String, Object> inputContext = new HashMap<>();
        inputContext.put("inspectionId", inspectionId);
        inputContext.put("productionBatchId", inspection.getProductionBatchId());
        inputContext.put("batchNumber", batch.getBatchNumber());
        inputContext.put("passRate", inspection.getPassRate());
        inputContext.put("defectRate", inspection.getDefectRate());
        inputContext.put("requestedAction", request.getRequestedAction());
        inputContext.put("reason", request.getReason());
        inputContext.put("urgency", request.getUrgency());
        inputContext.put("expectedImpact", request.getExpectedImpact());
        inputContext.put("riskAssessment", request.getRiskAssessment());
        inputContext.put("mitigationPlan", request.getMitigationPlan());

        // 6. 构建审计日志
        DecisionAuditLog auditLog = DecisionAuditLog.builder()
                .decisionType(DecisionType.APPROVAL)
                .decisionCode("SPECIAL_APPROVAL_REQUEST")
                .entityType("QualityInspection")
                .entityId(inspectionId)
                .factoryId(factoryId)
                .inputContext(toJson(inputContext))
                .ruleConfigId(dispositionResult.getRuleConfigId())
                .ruleConfigVersion(dispositionResult.getRuleVersion())
                .ruleConfigName(dispositionResult.getTriggeredRuleName())
                .decisionMade(request.getRequestedAction())
                .reason(request.getReason())
                .confidence(dispositionResult.getConfidence())
                .executorId(requesterId)
                .executorName(requester.getName())
                .executorRole(requester.getRole())
                .executionMode(ExecutionMode.MANUAL)
                .requiresApproval(true)
                .approvalStatus(ApprovalStatus.PENDING)
                .isReplayable(true)
                .build();

        DecisionAuditLog saved = decisionAuditLogRepository.save(auditLog);
        log.info("特批放行申请已创建: approvalId={}", saved.getId());

        // 7. 转换为DTO返回
        return convertToDTO(saved, inspection, batch, requester, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecialApprovalDTO> getPendingApprovals(String factoryId) {
        log.info("获取待审批列表: factoryId={}", factoryId);

        List<DecisionAuditLog> pendingApprovals =
                decisionAuditLogRepository.findQualityPendingApprovals(factoryId);

        // 使用批量加载方法解决 N+1 查询问题
        return batchEnrichAndConvertToDTOs(pendingApprovals);
    }

    @Override
    @Transactional(readOnly = true)
    public SpecialApprovalDTO getApprovalById(String factoryId, String approvalId) {
        log.info("获取审批详情: factoryId={}, approvalId={}", factoryId, approvalId);

        DecisionAuditLog auditLog = decisionAuditLogRepository.findById(approvalId)
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("审批记录", "id", approvalId));

        // 验证工厂ID
        if (!auditLog.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "审批记录不属于该工厂")
                    .withHint("请切换到该审批记录所属的工厂后再操作");
        }

        return enrichAndConvertToDTO(auditLog);
    }

    @Override
    @Transactional
    public SpecialApprovalDTO processDecision(
            String factoryId,
            String approvalId,
            ApprovalDecisionRequest decision,
            Long approverId,
            Long actingUserId,
            String actingRole
    ) {
        log.info("处理审批决策: factoryId={}, approvalId={}, decision={}, approverId={}, actingUserId={}, actingRole={}",
                factoryId, approvalId, decision.getDecision(), approverId, actingUserId, actingRole);

        // 1. 获取审批记录
        DecisionAuditLog auditLog = decisionAuditLogRepository.findById(approvalId)
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("审批记录", "id", approvalId));

        // 验证工厂ID
        if (!auditLog.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "审批记录不属于该工厂")
                    .withHint("请切换到该审批记录所属的工厂后再操作");
        }

        // 验证状态
        if (auditLog.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new IllegalStateException("审批已处理，当前状态: " + auditLog.getApprovalStatus());
        }

        // 审批身份 (与 /approve 同口径, 批准和驳回都要过): 在任何写入之前。所有入口 (App 接口、AI 工具) 都经过这里。
        com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision selfDecision =
                assertMayDecide(auditLog, approverId, actingUserId, actingRole,
                        firstNonBlank(decision.getSelfApprovalReason(),
                                decision.isCommentIsPlaceholder() ? null : decision.getComment()));

        // 2. 获取审批人信息
        User approver = userRepository.findById(approverId)
                .orElseThrow(() -> new IllegalArgumentException("审批人不存在"));

        // Q11: 批准 = 执行这条质检处置, 与 /quality-disposition/{id}/approve 走同一条路 (同锁顺序、同复核检查、
        // 同一个 executeApprovedDisposition)。不是质检处置的审批行这里执行不了, 直接拒绝, 不再「标成已批准却什么都没做」。
        if (!"QualityInspection".equals(auditLog.getEntityType())) {
            throw new BusinessException(400, "该审批不是质检处置申请");
        }
        boolean approve = decision.getDecision() == ApprovalDecisionRequest.ApprovalDecision.APPROVE;
        QualityInspection inspection = qualityInspectionRepository.findById(auditLog.getEntityId())
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException(
                        "质检记录", "id", auditLog.getEntityId()));
        // 锁顺序与普通审批一致: 生产批次 → 质检刷新 → 审批行。两人同时批同一条, 后到的读到已处理, 处置只执行一次。
        inspection = decisionGuard.lockInspection(factoryId, inspection);
        decisionGuard.refreshApproval(auditLog);
        if (auditLog.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(409, "审批已被处理，当前状态: " + auditLog.getApprovalStatus())
                    .withHint("请刷新待审批列表查看处理结果");
        }
        QualityDispositionRuleService.DispositionAction action = null;
        if (approve) {
            action = parseApprovedAction(auditLog.getDecisionMade());
            // 写审批行之前: 已报废批次不能返工 (Q6), 本次质检已有别的最终处置也不能再批
            decisionGuard.assertReworkAllowed(factoryId, inspection.getProductionBatchId(), action.name());
            decisionGuard.assertApprovalCurrent(inspection);
        }

        // 3. 更新审批状态
        ApprovalStatus newStatus = approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;

        auditLog.setApprovalStatus(newStatus);
        auditLog.setApproverId(approverId);
        auditLog.setApproverName(approver.getName());
        auditLog.setApprovedAt(LocalDateTime.now());
        auditLog.setApprovalComment(decision.getComment());
        // 纪律排查 B3: 本人审批 (批准或驳回自己的申请) 留痕
        auditLog.setSelfApproved(selfDecision.selfApproved());
        auditLog.setSelfApprovalReason(selfDecision.selfApproved() ? selfDecision.reason() : null);

        // 4. 更新输出结果
        Map<String, Object> outputResult = new HashMap<>();
        outputResult.put("approvalStatus", newStatus.name());
        outputResult.put("approverId", approverId);
        outputResult.put("approverName", approver.getName());
        outputResult.put("approvedAt", LocalDateTime.now().toString());
        outputResult.put("comment", decision.getComment());
        outputResult.put("conditions", decision.getConditions());
        outputResult.put("followUpRequirements", decision.getFollowUpRequirements());
        auditLog.setOutputResult(toJson(outputResult));

        DecisionAuditLog saved = decisionAuditLogRepository.save(auditLog);
        log.info("审批决策已处理: approvalId={}, status={}", approvalId, newStatus);

        // 5. 如果批准，执行处置动作: 已复核通过的执行 (不再建第二条待审批), 失败原样抛出、整笔回滚 ——
        // 不吞异常, 否则内层事务已标记回滚, 外层提交时才炸 UnexpectedRollbackException, 审批人看不到真正原因。
        if (approve) {
            String reason = String.format("审批通过: approvalId=%s, approvedBy=%s",
                    auditLog.getId(), approver.getName());
            QualityDispositionRuleService.DispositionExecutionResult result =
                    qualityDispositionRuleService.executeApprovedDisposition(
                            factoryId, inspection, action, approver.getId(), reason);
            log.info("特批通过后处置已执行: approvalId={}, action={}, newBatchStatus={}",
                    approvalId, action, result.getNewBatchStatus());
        }

        return enrichAndConvertToDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecialApprovalDTO> getMyRequests(String factoryId, Long requesterId) {
        log.info("获取我的申请记录: factoryId={}, requesterId={}", factoryId, requesterId);

        List<DecisionAuditLog> myRequests =
                decisionAuditLogRepository.findMyQualityApprovalRequests(factoryId, requesterId);

        // 使用批量加载方法解决 N+1 查询问题
        return batchEnrichAndConvertToDTOs(myRequests);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecialApprovalDTO> getMyApprovals(String factoryId, Long approverId) {
        log.info("获取我的审批记录: factoryId={}, approverId={}", factoryId, approverId);

        List<DecisionAuditLog> myApprovals =
                decisionAuditLogRepository.findMyQualityApprovalDecisions(factoryId, approverId);

        // 使用批量加载方法解决 N+1 查询问题
        return batchEnrichAndConvertToDTOs(myApprovals);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 批量转换审计日志为DTO - 解决 N+1 查询问题
     * 预先批量加载所有关联实体，避免在循环中逐条查询
     */
    private List<SpecialApprovalDTO> batchEnrichAndConvertToDTOs(List<DecisionAuditLog> auditLogs) {
        if (auditLogs.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 收集所有需要查询的ID
        Set<String> inspectionIds = auditLogs.stream()
                .map(DecisionAuditLog::getEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> userIds = auditLogs.stream()
                .flatMap(log -> {
                    List<Long> ids = new ArrayList<>();
                    if (log.getExecutorId() != null) ids.add(log.getExecutorId());
                    if (log.getApproverId() != null) ids.add(log.getApproverId());
                    return ids.stream();
                })
                .collect(Collectors.toSet());

        // 2. 批量查询质检记录
        Map<String, QualityInspection> inspectionMap = inspectionIds.isEmpty()
                ? Collections.emptyMap()
                : qualityInspectionRepository.findByIdIn(inspectionIds).stream()
                    .collect(Collectors.toMap(QualityInspection::getId, i -> i));

        // 3. 收集生产批次ID并批量查询
        Set<Long> batchIds = inspectionMap.values().stream()
                .map(QualityInspection::getProductionBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, ProductionBatch> batchMap = batchIds.isEmpty()
                ? Collections.emptyMap()
                : productionBatchRepository.findByIdIn(batchIds).stream()
                    .collect(Collectors.toMap(ProductionBatch::getId, b -> b));

        // 4. 批量查询用户
        Map<Long, User> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userRepository.findByIdIn(userIds).stream()
                    .collect(Collectors.toMap(User::getId, u -> u));

        // 5. 转换为DTO
        return auditLogs.stream()
                .map(auditLog -> {
                    QualityInspection inspection = inspectionMap.get(auditLog.getEntityId());
                    ProductionBatch batch = inspection != null
                            ? batchMap.get(inspection.getProductionBatchId())
                            : null;
                    User requester = auditLog.getExecutorId() != null
                            ? userMap.get(auditLog.getExecutorId())
                            : null;
                    User approver = auditLog.getApproverId() != null
                            ? userMap.get(auditLog.getApproverId())
                            : null;
                    return convertToDTO(auditLog, inspection, batch, requester, approver);
                })
                .collect(Collectors.toList());
    }

    /**
     * 谁能对这条申请做决定。身份以已认证的操作人为准 ({@code actingUserId} / {@code actingRole}, 调用方从令牌或 AI 执行上下文取);
     * 记录在审批行上的 {@code approverId} 若等于申请人同样拒绝 —— 否则审批行上会出现「申请人批了自己」。
     */
    private com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision assertMayDecide(
            DecisionAuditLog auditLog, Long approverId, Long actingUserId, String actingRole, String selfApprovalReason) {
        if (actingUserId == null) {
            throw new BusinessException(403, "无法确认审批人身份")
                    .withCode(SPECIAL_APPROVAL_ACTOR_UNKNOWN)
                    .withHint("请重新登录后再审批");
        }
        String role = actingRole == null ? null : actingRole.trim().toLowerCase(Locale.ROOT);
        // 平台管理员只认已认证的平台身份, 不认角色字符串 (工厂令牌写着平台角色不算数)
        boolean platformApprover = com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .filter(com.cretas.aims.security.AuthenticatedActor::hasFactoryBypass).isPresent();
        boolean mayApprove = approvalAuthority != null
                ? approvalAuthority.canApprove(actingUserId,
                        com.cretas.aims.service.impl.PermissionServiceImpl.APPROVAL_QUALITY_DISPOSITION)
                : role != null && APPROVER_ROLES.contains(role);
        if (!platformApprover && !mayApprove) {
            throw new BusinessException(403, "当前账号没有质检处置审批权限")
                    .withCode(SPECIAL_APPROVAL_ROLE_FORBIDDEN)
                    .withHint("默认由质量经理或工厂超级管理员审批; 工厂管理员可在权限配置里把「质检处置审批」授给其他岗位");
        }
        // 纪律排查 B3 (Owner 2026-11-10): 工厂开了「允许本人审批」时申请人可自批, 必须写原因并留痕。
        boolean self = Objects.equals(auditLog.getExecutorId(), actingUserId)
                || (approverId != null && Objects.equals(auditLog.getExecutorId(), approverId));
        com.cretas.aims.service.workflow.FactorySelfApprovalGate.Decision selfDecision =
                com.cretas.aims.service.workflow.FactorySelfApprovalGate.decide(
                        selfApprovalGate, auditLog.getFactoryId(), self, selfApprovalReason);
        if (selfDecision.forbidden()) {
            throw new BusinessException(403, "申请人与审批人不能为同一用户")
                    .withCode(SPECIAL_APPROVAL_SELF_APPROVAL)
                    .withHint("请由申请人以外的质量经理审批; "
                            + com.cretas.aims.service.workflow.FactorySelfApprovalGate.SWITCH_HINT);
        }
        return selfDecision;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** 审批行里记的处置动作; 认不出就在写任何东西之前拒绝, 不把一条执行不了的审批标成已批准。 */
    private static QualityDispositionRuleService.DispositionAction parseApprovedAction(String decisionMade) {
        try {
            return QualityDispositionRuleService.DispositionAction.valueOf(decisionMade);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(400, "审批记录里的处置动作无效: " + decisionMade);
        }
    }

    /**
     * 丰富并转换为DTO - 复用批量查询逻辑，避免 N+1 查询问题
     * 注意：虽然当前方法只处理单个记录，但复用批量方法可保持代码一致性，
     * 并在未来可能的循环调用场景下保持良好性能
     */
    private SpecialApprovalDTO enrichAndConvertToDTO(DecisionAuditLog auditLog) {
        // 复用批量方法，避免重复的查询逻辑
        List<SpecialApprovalDTO> result = batchEnrichAndConvertToDTOs(Collections.singletonList(auditLog));
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 转换为DTO
     */
    private SpecialApprovalDTO convertToDTO(
            DecisionAuditLog auditLog,
            QualityInspection inspection,
            ProductionBatch batch,
            User requester,
            User approver
    ) {
        // 解析输入上下文获取额外信息
        Map<String, Object> inputContext = parseJson(auditLog.getInputContext());
        String urgency = inputContext != null ? (String) inputContext.get("urgency") : null;

        // 构建质检摘要
        DispositionEvaluationDTO.InspectionSummary inspectionSummary = null;
        if (inspection != null) {
            inspectionSummary = DispositionEvaluationDTO.InspectionSummary.builder()
                    .passRate(inspection.getPassRate())
                    .defectRate(inspection.getDefectRate())
                    .sampleSize(inspection.getSampleSize())
                    .passCount(inspection.getPassCount())
                    .failCount(inspection.getFailCount())
                    .inspectionResult(inspection.getResult())
                    .qualityGrade(inspection.getQualityGrade())
                    .build();
        }

        // 获取动作描述
        String actionDescription = getActionDescription(auditLog.getDecisionMade());

        return SpecialApprovalDTO.builder()
                .approvalId(auditLog.getId())
                .inspectionId(auditLog.getEntityId())
                .productionBatchId(inspection != null ? inspection.getProductionBatchId() : null)
                .batchNumber(batch != null ? batch.getBatchNumber() : null)
                .requestedAction(auditLog.getDecisionMade())
                .requestedActionDescription(actionDescription)
                .reason(auditLog.getReason())
                .requesterId(auditLog.getExecutorId())
                .requesterName(auditLog.getExecutorName())
                .requesterRole(auditLog.getExecutorRole())
                .requestTime(auditLog.getCreatedAt())
                .status(auditLog.getApprovalStatus() != null ? auditLog.getApprovalStatus().name() : null)
                .urgency(urgency)
                .inspectionSummary(inspectionSummary)
                .triggeredRuleName(auditLog.getRuleConfigName())
                .ruleConfigId(auditLog.getRuleConfigId())
                .approverId(auditLog.getApproverId())
                .approverName(auditLog.getApproverName())
                .approvalTime(auditLog.getApprovedAt())
                .approvalComment(auditLog.getApprovalComment())
                .build();
    }

    /**
     * 获取动作描述
     */
    private String getActionDescription(String action) {
        if (action == null) return null;

        Map<String, String> descriptions = Map.of(
            "RELEASE", "直接放行",
            "CONDITIONAL_RELEASE", "条件放行",
            "REWORK", "返工处理",
            "SCRAP", "报废处理",
            "SPECIAL_APPROVAL", "特批放行",
            "HOLD", "暂扣待定"
        );

        return descriptions.getOrDefault(action, action);
    }

    /**
     * 对象转JSON字符串
     */
    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JSON字符串转Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("JSON解析失败: {}", e.getMessage());
            return null;
        }
    }
}
