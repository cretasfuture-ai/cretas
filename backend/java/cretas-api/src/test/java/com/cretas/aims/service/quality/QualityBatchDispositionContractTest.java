package com.cretas.aims.service.quality;

import com.cretas.aims.ai.tool.impl.quality.QualityCheckUpdateTool;
import com.cretas.aims.ai.tool.impl.quality.QualityDispositionExecuteTool;
import com.cretas.aims.ai.tool.impl.quality.QualityResultSubmitTool;
import com.cretas.aims.controller.QualityDispositionController;
import com.cretas.aims.dto.quality.ApprovalDecisionRequest;
import com.cretas.aims.dto.quality.DispositionApplyRequest;
import com.cretas.aims.dto.quality.DispositionApproveRequest;
import com.cretas.aims.dto.quality.ExecuteDispositionRequest;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.StateMachineService;
import com.cretas.aims.service.impl.QualityDispositionRuleServiceImpl;
import com.cretas.aims.service.impl.QualityInspectionServiceImpl;
import com.cretas.aims.service.impl.SpecialApprovalServiceImpl;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-S302-01 (Owner 2026-11-04): 不合格批次只能走处置和复核改状态; 处置结果同步到成品, 批次和成品不分叉;
 * 原检验记录不可改。
 *
 * <p>整条链是真的: 真 QualityDispositionController → 真 QualityDispositionRuleServiceImpl → 真 Guard →
 * 真 QualityDispositionInventoryService, 检验写入走真 QualityInspectionServiceImpl, 入库状态走真
 * QcInspectionPolicy。只有仓储是内存桩。
 *
 * <p>⚠️ 成品批号用真实形状 (FG-PLAN-…-S1-…, 与生产批号 PB-PLAN-… 永不相等): 旧测试夹具把两边都写成
 * PB-100, 于是「按批号对」在测试里能翻, 在生产上一个都翻不动 (形态 B‴, 桩的形状要能在生产上出现)。
 */
class QualityBatchDispositionContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-1793087414891-94ECD656";
    private static final Long INSPECTOR = 11L;
    private static final Long MANAGER = 22L;

    private final List<ProductionBatch> batches = new ArrayList<>();
    private final List<FinishedGoodsBatch> finishedGoods = new ArrayList<>();
    private final Map<String, QualityInspection> inspections = new LinkedHashMap<>();
    private final Map<String, DecisionAuditLog> audits = new LinkedHashMap<>();
    private final List<ProcessTimeLimitEvent> timeLimitRows = new ArrayList<>();
    private final List<BatchLineageEdge> edgeRows = new ArrayList<>();
    private LocalDateTime clock = LocalDateTime.of(2026, 11, 4, 9, 0);
    private int auditSeq;

    private QualityInspectionRepository inspectionRepo;
    private ProductionBatchRepository batchRepo;
    private DecisionAuditLogRepository auditRepo;
    private QualityDispositionDecisionGuard guard;
    private UserRepository users;
    private FinishedGoodsBatchRepository goodsRepo;
    private QualityDispositionInventoryService bridge;
    private ProcessTimeLimitEventRepository timeLimitEvents;
    private FactoryRecallService recalls;
    private QcReleaseRecordRepository releaseRecords;
    private ProductTypeRepository productTypes;
    private QualityInspectionServiceImpl inspectionService;
    private QualityDispositionRuleServiceImpl ruleService;
    private QualityDispositionController controller;
    private ProductionBatch batch;
    private FinishedGoodsBatch goods;

    @BeforeEach
    void setUp() {
        batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findById(any())).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        when(batchRepo.findByIdAndFactoryIdForUpdate(any(), eq(FACTORY))).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        // 与真实查询一致: findByFactoryIdAndProductionPlanId 不含逐道录入的 CLERK_WIP 批次
        when(batchRepo.findByFactoryIdAndProductionPlanId(eq(FACTORY), eq(PLAN))).thenAnswer(call -> batches.stream()
                .filter(b -> !"CLERK_WIP".equals(b.getBatchType())).toList());
        when(batchRepo.findByIdAndFactoryId(any(), eq(FACTORY))).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        // 计划所有批次 (含 CLERK_WIP): 锁 + 锁后重读状态
        when(batchRepo.lockAllIdsInPlan(eq(FACTORY), eq(PLAN))).thenAnswer(call -> batches.stream()
                .map(ProductionBatch::getId).sorted().toList());
        when(batchRepo.findQcStatesInPlan(eq(FACTORY), eq(PLAN))).thenAnswer(call -> batches.stream()
                .map(QualityBatchDispositionContractTest::qcState).toList());
        when(batchRepo.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY), any())).thenAnswer(call -> {
            java.util.Collection<String> plans = call.getArgument(1);
            return batches.stream().filter(b -> plans.contains(b.getProductionPlanId())).toList();
        });
        when(batchRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(batchRepo.countQcFailedInPlans(eq(FACTORY), any(), anyString())).thenAnswer(call -> batches.stream()
                .filter(b -> b.getQualityStatus() != null && b.getQualityStatus().isDispositionLocked()).count());

        goodsRepo = mock(FinishedGoodsBatchRepository.class);
        when(goodsRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN))
                .thenAnswer(call -> List.copyOf(finishedGoods));
        when(goodsRepo.findQuarantinedPlanGoods(FACTORY)).thenAnswer(call -> finishedGoods.stream()
                .filter(g -> FinishedGoodsBatch.Status.DEFECTIVE.equals(g.getStatus())).toList());
        when(goodsRepo.findByFactoryIdAndProductionPlanIdForUpdate(FACTORY, PLAN))
                .thenAnswer(call -> List.copyOf(finishedGoods));
        when(goodsRepo.save(any())).thenAnswer(call -> call.getArgument(0));

        inspectionRepo = mock(QualityInspectionRepository.class);
        when(inspectionRepo.save(any())).thenAnswer(call -> {
            QualityInspection row = call.getArgument(0);
            if (row.getCreatedAt() == null) {
                clock = clock.plusMinutes(1);
                row.setCreatedAt(clock);
            }
            inspections.put(row.getId(), row);
            return row;
        });
        when(inspectionRepo.findById(anyString())).thenAnswer(call -> Optional.ofNullable(inspections.get(call.getArgument(0))));
        when(inspectionRepo.findByIdAndFactoryIdForUpdate(eq(FACTORY), anyString()))
                .thenAnswer(call -> Optional.ofNullable(inspections.get(call.getArgument(1))));
        when(inspectionRepo.findByFactoryIdAndProductionBatchIdForUpdate(eq(FACTORY), any()))
                .thenAnswer(call -> List.copyOf(inspections.values()));
        when(inspectionRepo.findByFactoryIdAndProductionBatchId(eq(FACTORY), any(), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.copyOf(inspections.values())));

        ProductionAlertRepository alerts = mock(ProductionAlertRepository.class);
        when(alerts.save(any())).thenAnswer(call -> call.getArgument(0));
        inspectionService = new QualityInspectionServiceImpl(inspectionRepo, alerts, mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(inspectionService, "productionBatchRepository", batchRepo);
        ReflectionTestUtils.setField(inspectionService, "finishedGoodsBatchRepository", goodsRepo);

        auditRepo = mock(DecisionAuditLogRepository.class);
        when(auditRepo.findById(anyString())).thenAnswer(call -> Optional.ofNullable(audits.get(call.getArgument(0))));
        when(auditRepo.save(any())).thenAnswer(call -> {
            DecisionAuditLog row = call.getArgument(0);
            if (row.getId() == null) row.setId("audit-" + (++auditSeq));
            audits.put(row.getId(), row);
            return row;
        });
        when(auditRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("QualityInspection"), anyString()))
                .thenAnswer(call -> audits.values().stream()
                        .filter(a -> call.getArgument(1).equals(a.getEntityId())).toList());

        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.find(eq(QualityInspection.class), any()))
                .thenAnswer(call -> inspections.get(call.getArgument(1)));
        guard = new QualityDispositionDecisionGuard(
                batchRepo, inspectionRepo, auditRepo, entityManager, new ObjectMapper());

        DecisionAuditService auditService = mock(DecisionAuditService.class);
        when(auditService.logPendingApproval(anyString(), anyString(), anyString(), anyMap(), anyString(), any(),
                anyLong(), any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            DecisionAuditLog row = DecisionAuditLog.builder().id("audit-" + (++auditSeq)).factoryId(FACTORY)
                    .entityType("QualityInspection").entityId(call.getArgument(2))
                    .decisionType(DecisionAuditLog.DecisionType.APPROVAL)
                    .decisionMade(call.getArgument(4)).executorId(call.getArgument(6))
                    .requiresApproval(true).approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING).build();
            audits.put(row.getId(), row);
            return row;
        });
        when(auditService.logRuleExecution(anyString(), anyString(), anyString(), anyMap(), anyMap(), anyList(),
                anyString(), anyLong(), any(), anyString())).thenAnswer(call -> {
            Map<String, Object> output = call.getArgument(4);
            DecisionAuditLog row = DecisionAuditLog.builder().id("audit-" + (++auditSeq)).factoryId(FACTORY)
                    .entityType(call.getArgument(1)).entityId(call.getArgument(2))
                    .executorId(call.getArgument(7))
                    .decisionType(DecisionAuditLog.DecisionType.RULE_EXECUTION)
                    .outputResult(new ObjectMapper().writeValueAsString(output)).build();
            audits.put(row.getId(), row);
            return row;
        });

        ApprovalChainService approvalChain = mock(ApprovalChainService.class);
        when(approvalChain.requiresApproval(anyString(), any(DecisionType.class), anyMap())).thenReturn(false);
        when(approvalChain.findMatchingConfig(anyString(), any(DecisionType.class), anyMap())).thenReturn(Optional.empty());

        users = mock(UserRepository.class);
        when(users.findById(any())).thenAnswer(call -> {
            User user = new User();
            user.setId(call.getArgument(0));
            user.setRoleCode("quality_manager");
            user.setFullName("质量");
            return Optional.of(user);
        });

        releaseRecords = mock(QcReleaseRecordRepository.class);
        productTypes = mock(ProductTypeRepository.class);
        QcInspectionPolicy policy = new QcInspectionPolicy(productTypes);
        ReflectionTestUtils.setField(policy, "productionBatchRepository", batchRepo);

        bridge = new QualityDispositionInventoryService();
        ReflectionTestUtils.setField(bridge, "materialBatchRepository", mock(MaterialBatchRepository.class));
        ReflectionTestUtils.setField(bridge, "productionBatchRepository", batchRepo);
        ReflectionTestUtils.setField(bridge, "finishedGoodsBatchRepository", goodsRepo);
        recalls = mock(FactoryRecallService.class);
        BatchLineageEdgeRepository lineage = mock(BatchLineageEdgeRepository.class);
        when(lineage.findByFactoryIdAndTargetIdIn(eq(FACTORY), any())).thenAnswer(call -> {
            java.util.Collection<String> targets = call.getArgument(1);
            return edgeRows.stream().filter(e -> targets.contains(e.getTargetId())).toList();
        });
        ReflectionTestUtils.setField(bridge, "batchLineageEdgeRepository", lineage);
        ReflectionTestUtils.setField(bridge, "factoryRecallService", recalls);
        ReflectionTestUtils.setField(bridge, "qcReleaseRecordRepository", releaseRecords);
        ReflectionTestUtils.setField(bridge, "qcInspectionPolicy", policy);
        timeLimitEvents = mock(ProcessTimeLimitEventRepository.class);
        when(timeLimitEvents.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(anyString(), anyString(), anyString(),
                anyString())).thenAnswer(call -> timeLimitRows.stream()
                .filter(e -> e.getFactoryId().equals(call.getArgument(0))
                        && e.getTargetType().equals(call.getArgument(1))
                        && e.getTargetId().equals(call.getArgument(2))
                        && e.getStatus().equals(call.getArgument(3)))
                .toList());
        ReflectionTestUtils.setField(bridge, "processTimeLimitEventRepository", timeLimitEvents);

        ruleService = new QualityDispositionRuleServiceImpl(approvalChain, auditService, users, guard);
        ReflectionTestUtils.setField(ruleService, "dispositionInventoryService", bridge);

        controller = new QualityDispositionController(guard, ruleService, inspectionRepo, auditRepo, batchRepo,
                mock(StateMachineService.class), mock(NotificationService.class), mock(PushNotificationService.class));

        batch = productionBatch(100L, "PB-PLAN-1793087414891-94ECD656-88763", "SKU-1", QualityStatus.PENDING_INSPECTION);
        goods = finishedGoods("FG-PLAN-1793087414891-94ECD656-S1-d2e185aa", "SKU-1", FinishedGoodsBatch.Status.AVAILABLE);
    }

    // ==================== 后补合格检验 ====================

    @Test
    @DisplayName("复现 D-S302-01: 不合格批后补全合格检验, 批次仍 FAILED, 成品仍 DEFECTIVE, 两条记录都在")
    void laterPassCannotClearFailedBatch() {
        QualityInspection fail = inspect("qi-fail", "FAIL", "0");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);

        QualityInspection pass = inspect("qi-pass", "PASS", "100");

        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(inspections).containsKeys(fail.getId(), pass.getId());
        assertThat(inspections.get("qi-fail").getResult()).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("阳性对照: 没判过不合格的批次, 检验合格照常改 PASSED")
    void passStillClearsUnfailedBatch() {
        inspect("qi-pass", "PASS", "100");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PASSED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("绕路也不行: 不合格批写待检 / 条件合格都不动状态, 待检任务补成 PASS 也不动")
    void pendingAndConditionalCannotClearFailedBatch() {
        inspect("qi-fail", "FAIL", "0");

        inspect("qi-cond", "CONDITIONAL", "90");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);

        inspect("qi-pending", "PENDING", null);
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);

        QualityInspection complete = new QualityInspection();
        complete.setResult("PASS");
        complete.setPassRate(new BigDecimal("100"));
        inspectionService.updateInspection(FACTORY, "qi-pending", complete);
        assertThat(inspections.get("qi-pending").getResult()).isEqualTo("PASS");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
    }

    @Test
    @DisplayName("返工中的批次再判一次 FAIL 回 FAILED 并隔离; 后补合格同样不解除返工状态")
    void reworkStaysLockedAndAFailStillLands() {
        batch.setQualityStatus(QualityStatus.REWORK_REQUIRED);

        inspect("qi-pass", "PASS", "100");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.REWORK_REQUIRED);

        inspect("qi-fail", "FAIL", "0");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    // ==================== 处置 + 复核 ====================

    @Test
    @DisplayName("放行不合格批: 后补合格 + 直接执行 RELEASE 只进待审批, 批次和成品都没动")
    void directReleaseOfFailedBatchGoesToApproval() {
        inspect("qi-fail", "FAIL", "0");
        inspect("qi-pass", "PASS", "100");

        var response = controller.executeDisposition(FACTORY, execute("qi-pass", "RELEASE", INSPECTOR), INSPECTOR);

        assertThat(response.getData().getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("阳性: 另一人复核通过, 批次 PASSED, 成品 (真实批号) 回 AVAILABLE, 响应读的是实际写下的状态")
    void secondPersonApprovalClearsBatchAndGoods() {
        inspect("qi-fail", "FAIL", "0");
        inspect("qi-pass", "PASS", "100");
        String applicationId = controller.executeDisposition(FACTORY, execute("qi-pass", "RELEASE", INSPECTOR), INSPECTOR)
                .getData().getApprovalRequestId();

        var approved = controller.approveDisposition(FACTORY, applicationId, approve(MANAGER, true), MANAGER);

        assertThat(approved.getData().getNewBatchStatus()).isEqualTo("PASSED");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PASSED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("阴性: 申请人自批 403, 驳回后批次和成品都不变")
    void selfApprovalAndRejectionChangeNothing() {
        inspect("qi-fail", "FAIL", "0");
        inspect("qi-pass", "PASS", "100");
        String applicationId = controller.executeDisposition(FACTORY, execute("qi-pass", "RELEASE", INSPECTOR), INSPECTOR)
                .getData().getApprovalRequestId();

        assertThatThrownBy(() -> controller.approveDisposition(FACTORY, applicationId, approve(INSPECTOR, true), INSPECTOR))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThatThrownBy(() -> controller.approveDisposition(FACTORY, applicationId, approve(INSPECTOR, true), MANAGER))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);

        controller.approveDisposition(FACTORY, applicationId, approve(MANAGER, false), MANAGER);
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("申请入口: 不合格批的放行能走 /apply 进审批; 没判过不合格的批次放行仍是「无需审批」")
    void applyAcceptsReleaseOnlyForUnclearedBatch() {
        inspect("qi-pass", "PASS", "100");
        assertThatThrownBy(() -> controller.applyDisposition(FACTORY, apply("qi-pass", "RELEASE"), INSPECTOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无需审批");

        batch.setQualityStatus(QualityStatus.FAILED);
        var application = controller.applyDisposition(FACTORY, apply("qi-pass", "RELEASE"), INSPECTOR);
        assertThat(application.getData().getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
    }

    @Test
    @DisplayName("评估与直接放行判定同口径: 不合格批上全合格检验, 评估说要审批, canDirectRelease 为 false")
    void evaluationTellsTheSameStory() {
        inspect("qi-fail", "FAIL", "0");
        QualityInspection pass = inspect("qi-pass", "PASS", "100");

        assertThat(ruleService.evaluateDisposition(FACTORY, pass).isRequiresApproval()).isTrue();
        assertThat(ruleService.canDirectRelease(FACTORY, pass)).isFalse();
    }

    @Test
    @DisplayName("条件放行通过: 批次 PARTIAL_PASS (枚举里真实存在的值), 成品回 AVAILABLE")
    void conditionalReleaseSyncsBoth() {
        inspect("qi-fail", "FAIL", "80");
        String applicationId = controller.applyDisposition(FACTORY, apply("qi-fail", "CONDITIONAL_RELEASE"), INSPECTOR)
                .getData().getApplicationId();

        var approved = controller.approveDisposition(FACTORY, applicationId, approve(MANAGER, true), MANAGER);

        assertThat(approved.getData().getNewBatchStatus()).isEqualTo("PARTIAL_PASS");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("返工 / 报废也同步: 批次判定不能卖时, 还可售的成品一起隔离, 不留分叉")
    void reworkAndScrapQuarantineSellableGoods() {
        inspect("qi-cond", "CONDITIONAL", "75");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);

        String rework = controller.applyDisposition(FACTORY, apply("qi-cond", "REWORK"), INSPECTOR)
                .getData().getApplicationId();
        controller.approveDisposition(FACTORY, rework, approve(MANAGER, true), MANAGER);

        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.REWORK_REQUIRED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("报废是终态: 批次 SCRAPPED, 之后放行 409, 成品不回来")
    void scrappedBatchCannotBeReleased() {
        inspect("qi-fail", "FAIL", "0");
        String scrap = controller.applyDisposition(FACTORY, apply("qi-fail", "SCRAP"), INSPECTOR)
                .getData().getApplicationId();
        controller.approveDisposition(FACTORY, scrap, approve(MANAGER, true), MANAGER);
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.SCRAPPED);

        inspect("qi-pass", "PASS", "100");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.SCRAPPED);

        String release = controller.applyDisposition(FACTORY, apply("qi-pass", "RELEASE"), INSPECTOR)
                .getData().getApplicationId();
        assertThatThrownBy(() -> controller.approveDisposition(FACTORY, release, approve(MANAGER, true), MANAGER))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("QUALITY_BATCH_SCRAPPED"));
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    // ==================== Q6: 报废是终态, 返工也不能把它带回来 ====================
    //
    // 每一个能对生产批次发起 / 执行返工的入口各一条: 409 + BATCH_SCRAPPED_CANNOT_REWORK, 而且在写任何东西之前 ——
    // 审计行数不变、批次仍 SCRAPPED、成品仍 DEFECTIVE。拿新的一条合格检验 (qi-pass) 去申请, 是为了绕开
    // 「本次质检已有最终处置」那道旧闸: 用报废那条检验去申请, 旧闸先拦, 这里的断言就在守空气。

    /** 报废批次 (走真实申请 + 复核), 再补一条合格检验 —— 批次仍 SCRAPPED, 返工申请用的就是这条新检验。 */
    private void scrapThenRecheckPass() {
        inspect("qi-fail", "FAIL", "0");
        String scrap = controller.applyDisposition(FACTORY, apply("qi-fail", "SCRAP"), INSPECTOR)
                .getData().getApplicationId();
        controller.approveDisposition(FACTORY, scrap, approve(MANAGER, true), MANAGER);
        inspect("qi-pass", "PASS", "100");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.SCRAPPED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    private void assertReworkRefusedAndNothingWritten(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        int auditsBefore = audits.size();
        Map<String, DecisionAuditLog.ApprovalStatus> statusesBefore = new LinkedHashMap<>();
        audits.forEach((id, row) -> statusesBefore.put(id, row.getApprovalStatus()));

        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("BATCH_SCRAPPED_CANNOT_REWORK");
                    assertThat(be.getActionHint()).isNotBlank();
                });

        assertThat(audits).hasSize(auditsBefore);
        audits.forEach((id, row) -> assertThat(row.getApprovalStatus()).as("审批行 %s 不许被改", id)
                .isEqualTo(statusesBefore.get(id)));
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.SCRAPPED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("Q6 /apply: 已报废批次申请返工 409, 不建申请行")
    void scrappedBatchReworkApplicationIsRefused() {
        scrapThenRecheckPass();
        assertReworkRefusedAndNothingWritten(() ->
                controller.applyDisposition(FACTORY, apply("qi-pass", "REWORK"), INSPECTOR));
    }

    @Test
    @DisplayName("Q6 /execute (与 App 处置接口、AI 处置工具同一个 executeDisposition): 已报废批次返工 409, 不建待审批")
    void scrappedBatchReworkExecutionIsRefused() {
        scrapThenRecheckPass();
        assertReworkRefusedAndNothingWritten(() ->
                controller.executeDisposition(FACTORY, execute("qi-pass", "REWORK", INSPECTOR), INSPECTOR));
    }

    @Test
    @DisplayName("Q6 复核通过的执行: executeApprovedDisposition 返工已报废批次 409, 批次不变")
    void scrappedBatchApprovedReworkIsRefused() {
        scrapThenRecheckPass();
        assertReworkRefusedAndNothingWritten(() -> ruleService.executeApprovedDisposition(FACTORY,
                inspections.get("qi-pass"), com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.REWORK,
                MANAGER, "复核"));
    }

    @Test
    @DisplayName("Q6 库存桥接 (批次 REWORK_REQUIRED 唯一写入方): 已报废批次返工 409")
    void bridgeRefusesReworkOfScrappedBatch() {
        scrapThenRecheckPass();
        assertReworkRefusedAndNothingWritten(() -> bridge.applyDisposition(inspections.get("qi-pass"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.REWORK, true));
    }

    @Test
    @DisplayName("Q6 特批申请: 已报废批次申请返工 409, 不建申请行")
    void scrappedBatchReworkSpecialApprovalIsRefused() {
        scrapThenRecheckPass();
        assertReworkRefusedAndNothingWritten(() -> specialApprovals().submitSpecialApproval(FACTORY, "qi-pass",
                specialRequest("REWORK"), INSPECTOR));
    }

    @Test
    @DisplayName("Q6 审批: 返工申请提交后批次被报废, 两个审批入口批准都 409, 审批行仍待审批")
    void reworkApplicationApprovedAfterScrapIsRefusedBeforeTheApprovalRowChanges() {
        inspect("qi-fail", "FAIL", "60");
        String application = controller.applyDisposition(FACTORY, apply("qi-fail", "REWORK"), INSPECTOR)
                .getData().getApplicationId();
        // 申请挂着时批次经别的路被报废 (或报废前就留下的旧申请)
        batch.setQualityStatus(QualityStatus.SCRAPPED);
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);

        assertReworkRefusedAndNothingWritten(() ->
                controller.approveDisposition(FACTORY, application, approve(MANAGER, true), MANAGER));
        assertReworkRefusedAndNothingWritten(() -> specialApprovals().processDecision(FACTORY, application,
                decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager"));
        assertThat(audits.get(application).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("Q6 阳性对照: 没报废的不合格批次, 同样的入口照常进返工 (申请 → 复核 → REWORK_REQUIRED; 特批同样)")
    void unscrappedBatchStillGoesToRework() {
        inspect("qi-fail", "FAIL", "60");
        String application = controller.applyDisposition(FACTORY, apply("qi-fail", "REWORK"), INSPECTOR)
                .getData().getApplicationId();
        controller.approveDisposition(FACTORY, application, approve(MANAGER, true), MANAGER);
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.REWORK_REQUIRED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);

        // 特批入口: 返工中的批次再判一次不合格, 特批申请返工 → 批准 → 返工
        inspect("qi-fail-2", "FAIL", "60");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        SpecialApprovalServiceImpl special = specialApprovals();
        String specialId = special.submitSpecialApproval(FACTORY, "qi-fail-2", specialRequest("REWORK"), INSPECTOR)
                .getApprovalId();
        special.processDecision(FACTORY, specialId, decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.REWORK_REQUIRED);
    }

    // ==================== Q11: 特批通过 = 按复核通过执行, 只执行一次 ====================

    private SpecialApprovalServiceImpl specialApprovals() {
        return new SpecialApprovalServiceImpl(auditRepo, inspectionRepo, batchRepo, users, ruleService,
                new ObjectMapper(), guard);
    }

    private static com.cretas.aims.dto.quality.SpecialApprovalRequest specialRequest(String action) {
        com.cretas.aims.dto.quality.SpecialApprovalRequest request = new com.cretas.aims.dto.quality.SpecialApprovalRequest();
        request.setRequestedAction(action);
        request.setReason("客户同意让步接收");
        return request;
    }

    private static ApprovalDecisionRequest decision(ApprovalDecisionRequest.ApprovalDecision decision) {
        ApprovalDecisionRequest request = new ApprovalDecisionRequest();
        request.setDecision(decision);
        request.setComment("同意");
        return request;
    }

    private List<DecisionAuditLog> pendingApprovals() {
        return audits.values().stream()
                .filter(a -> Boolean.TRUE.equals(a.getRequiresApproval())
                        && a.getApprovalStatus() == DecisionAuditLog.ApprovalStatus.PENDING)
                .toList();
    }

    private List<String> executedActions(String inspectionId) {
        return audits.values().stream()
                .filter(a -> a.getDecisionType() == DecisionAuditLog.DecisionType.RULE_EXECUTION
                        && inspectionId.equals(a.getEntityId()))
                .map(a -> {
                    try {
                        return new ObjectMapper().readTree(a.getOutputResult()).path("action").asText();
                    } catch (Exception e) {
                        throw new AssertionError("执行审计行读不出 action: " + a.getOutputResult(), e);
                    }
                })
                .toList();
    }

    @Test
    @DisplayName("Q11: 特批条件放行通过 —— 执行一次, 不再生出第二条待审批, 批次 PARTIAL_PASS 成品回 AVAILABLE (同普通复核)")
    void specialApprovalExecutesTheApprovedDispositionOnce() {
        inspect("qi-fail", "FAIL", "80");
        SpecialApprovalServiceImpl special = specialApprovals();
        String approvalId = special.submitSpecialApproval(FACTORY, "qi-fail", specialRequest("CONDITIONAL_RELEASE"),
                INSPECTOR).getApprovalId();
        assertThat(pendingApprovals()).extracting(DecisionAuditLog::getId).containsExactly(approvalId);

        special.processDecision(FACTORY, approvalId, decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager");

        assertThat(audits.get(approvalId).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.APPROVED);
        assertThat(pendingApprovals()).isEmpty();
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);

        // 再批一次: 拒绝, 处置不会执行第二次
        assertThatThrownBy(() -> special.processDecision(FACTORY, approvalId,
                decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager"));
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
    }

    @Test
    @DisplayName("Q11: 特批与普通复核读回同一个结果 (同一批次、同一动作, 两条路的批次 / 成品 / 执行审计一致)")
    void specialApprovalMatchesNormalApproval() {
        inspect("qi-fail", "FAIL", "80");
        String normal = controller.applyDisposition(FACTORY, apply("qi-fail", "CONDITIONAL_RELEASE"), INSPECTOR)
                .getData().getApplicationId();
        // 普通审批行同样出现在特批待审批列表里, 用特批入口批它
        specialApprovals().processDecision(FACTORY, normal,
                decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager");

        assertThat(pendingApprovals()).isEmpty();
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("Q11: 不改状态的动作 (待定) 特批通过也不再生出第二条待审批")
    void specialApprovalOfHoldDoesNotSpawnASecondApproval() {
        inspect("qi-fail", "FAIL", "80");
        SpecialApprovalServiceImpl special = specialApprovals();
        String approvalId = special.submitSpecialApproval(FACTORY, "qi-fail", specialRequest("HOLD"), INSPECTOR)
                .getApprovalId();

        special.processDecision(FACTORY, approvalId, decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager");

        assertThat(pendingApprovals()).isEmpty();
        assertThat(executedActions("qi-fail")).containsExactly("HOLD");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("Q11: 执行失败原样抛给审批人 (不再吞掉), 成品不动; 驳回照常只改审批行")
    void specialApprovalFailureSurfacesAndRejectionExecutesNothing() {
        inspect("qi-fail", "FAIL", "80");
        SpecialApprovalServiceImpl special = specialApprovals();
        // 申请挂着时本批进了召回: 复核通过的执行 (库存桥接) 拒绝放行, 必须原样报给审批人
        String release = special.submitSpecialApproval(FACTORY, "qi-fail", specialRequest("CONDITIONAL_RELEASE"),
                INSPECTOR).getApprovalId();
        org.mockito.Mockito.doThrow(new BusinessException(409, "本批次召回中，不能放行").withCode("RECALL_IN_PROGRESS"))
                .when(recalls).assertReleaseAllowed(FACTORY, 100L);
        assertThatThrownBy(() -> special.processDecision(FACTORY, release,
                decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("RECALL_IN_PROGRESS"));
        assertThat(executedActions("qi-fail")).isEmpty();
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        org.mockito.Mockito.reset(recalls);

        // 内存桩没有事务: 真库里这一笔连同审批行整体回滚, 审批行仍待审批 (真 PG 上的读回见
        // QualityDispositionInventoryPostgresContractTest); 这里把它驳回, 腾出位置验证驳回路径
        audits.get(release).setApprovalStatus(DecisionAuditLog.ApprovalStatus.REJECTED);
        String rejected = special.submitSpecialApproval(FACTORY, "qi-fail", specialRequest("CONDITIONAL_RELEASE"),
                INSPECTOR).getApprovalId();
        special.processDecision(FACTORY, rejected, decision(ApprovalDecisionRequest.ApprovalDecision.REJECT), MANAGER, MANAGER, "quality_manager");
        assertThat(audits.get(rejected).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.REJECTED);
        assertThat(executedActions("qi-fail")).isEmpty();
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("Q11 权限不变: 别厂的审批行 403, 什么都不执行")
    void specialApprovalKeepsFactoryCheck() {
        inspect("qi-fail", "FAIL", "80");
        SpecialApprovalServiceImpl special = specialApprovals();
        String approvalId = special.submitSpecialApproval(FACTORY, "qi-fail", specialRequest("CONDITIONAL_RELEASE"),
                INSPECTOR).getApprovalId();

        assertThatThrownBy(() -> special.processDecision("F999", approvalId,
                decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, MANAGER, "quality_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThat(audits.get(approvalId).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
        assertThat(executedActions("qi-fail")).isEmpty();
    }

    // ==================== Q11 审批身份: 与 /approve 同口径 (独立审查高危项) ====================
    //
    // 特批通过现在真的会执行处置, 所以「谁能批」必须和 /approve 一样: 只有审批角色, 申请人不能批自己的申请。
    // 身份以已认证的操作人为准 (HTTP: JWT 拦截器写入的 userId / role; AI 工具: 执行上下文 userId 及其账号角色),
    // 请求体里的 approverId 不作数 (那一项 —— 审批行记谁 —— 是另行暂缓的安全待办, 这里不动)。

    private org.springframework.test.web.servlet.MockMvc decisionEndpoint() {
        com.cretas.aims.controller.ProcessingController processing = new com.cretas.aims.controller.ProcessingController(
                mock(com.cretas.aims.service.ProcessingService.class), mock(com.cretas.aims.service.MobileService.class),
                mock(com.cretas.aims.service.AIEnterpriseService.class),
                mock(com.cretas.aims.service.ProcessingStageRecordService.class), ruleService, specialApprovals(),
                inspectionRepo, mock(com.cretas.aims.service.wip.WipInventoryService.class));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(processing)
                .setControllerAdvice(new com.cretas.aims.exception.GlobalExceptionHandler())
                .addInterceptors(new com.cretas.aims.config.RequireRoleInterceptor(new ObjectMapper()))
                .build();
    }

    private static org.springframework.test.web.servlet.RequestBuilder decide(String approvalId, Long tokenUserId,
                                                                               String tokenRole, Long bodyApproverId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mobile/" + FACTORY + "/processing/quality/approvals/" + approvalId + "/decision")
                .requestAttr("userId", tokenUserId).requestAttr("role", tokenRole)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"comment\":\"同意\",\"approverId\":" + bodyApproverId + "}");
    }

    private String pendingSpecialConditionalRelease() {
        inspect("qi-fail", "FAIL", "80");
        return specialApprovals().submitSpecialApproval(FACTORY, "qi-fail", specialRequest("CONDITIONAL_RELEASE"),
                INSPECTOR).getApprovalId();
    }

    private void assertNothingDecided(String approvalId) {
        assertThat(audits.get(approvalId).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
        assertThat(executedActions("qi-fail")).isEmpty();
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("Q11 /decision: 申请人用自己的令牌批自己的申请 403 —— 请求体里填别人的 approverId 也不行, 什么都不执行")
    void decisionEndpointRefusesSelfApproval() throws Exception {
        String approvalId = pendingSpecialConditionalRelease();
        var mvc = decisionEndpoint();

        for (Long bodyApprover : new Long[]{MANAGER, INSPECTOR}) {
            mvc.perform(decide(approvalId, INSPECTOR, "quality_manager", bodyApprover))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.errorCode").value("SPECIAL_APPROVAL_SELF_APPROVAL"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.actionHint").isNotEmpty());
            assertNothingDecided(approvalId);
        }
        // 令牌是别人、请求体却写申请人: 审批行上会记成申请人自批, 同样拒绝
        mvc.perform(decide(approvalId, MANAGER, "quality_manager", INSPECTOR))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errorCode").value("SPECIAL_APPROVAL_SELF_APPROVAL"));
        assertNothingDecided(approvalId);
    }

    @Test
    @DisplayName("Q11 /decision: 没有审批权限的账号 (操作员) 403, 什么都不执行 (接口闸是审批权限码, 服务层再判一次)")
    void decisionEndpointRefusesNonApproverRole() throws Exception {
        String approvalId = pendingSpecialConditionalRelease();

        // 纪律排查 B3: 接口上不再是 @RequireRole 名单而是 @RequirePermission(approval_quality_disposition:approve)
        // (见 approverPermissionIsTheSameEverywhere); 这里的 standalone MockMvc 没挂权限拦截器, 拦下它的是服务层同一判据。
        decisionEndpoint().perform(decide(approvalId, MANAGER, "operator", MANAGER))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errorCode").value(SpecialApprovalServiceImpl.SPECIAL_APPROVAL_ROLE_FORBIDDEN));
        assertNothingDecided(approvalId);
    }

    @Test
    @DisplayName("Q11 /decision 阳性: 申请人以外的质量经理批准 → 200, 执行一次, 成品回 AVAILABLE")
    void decisionEndpointLetsAnotherApproverApprove() throws Exception {
        String approvalId = pendingSpecialConditionalRelease();

        decisionEndpoint().perform(decide(approvalId, MANAGER, "quality_manager", MANAGER))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        assertThat(audits.get(approvalId).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.APPROVED);
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    private com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool orderApprovalTool(String accountRole) {
        com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool tool = new com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool();
        com.cretas.aims.ai.tool.ToolRbacGuard guardForRole = mock(com.cretas.aims.ai.tool.ToolRbacGuard.class);
        when(guardForRole.roleOf(any())).thenReturn(accountRole);
        ReflectionTestUtils.setField(tool, "specialApprovalService", specialApprovals());
        ReflectionTestUtils.setField(tool, "rbacGuard", guardForRole);
        return tool;
    }

    private static Map<String, Object> approveParams(String approvalId) {
        return new HashMap<>(Map.of("transferId", approvalId));
    }

    @Test
    @DisplayName("Q11 AI 工具 order_approval: 申请人在对话里批自己的申请 403 (服务层拦), 什么都不执行")
    void orderApprovalToolRefusesSelfApproval() {
        String approvalId = pendingSpecialConditionalRelease();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(orderApprovalTool("quality_manager"), "doExecute",
                FACTORY, approveParams(approvalId), new HashMap<String, Object>(Map.of("userId", INSPECTOR))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("SPECIAL_APPROVAL_SELF_APPROVAL"));
        assertNothingDecided(approvalId);
    }

    @Test
    @DisplayName("Q11 AI 工具 order_approval: 非审批角色 403 (服务层的角色校验, 不靠工具注册表), 审批角色照常批准")
    void orderApprovalToolChecksTheApproverRoleInTheService() {
        String approvalId = pendingSpecialConditionalRelease();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(orderApprovalTool("operator"), "doExecute",
                FACTORY, approveParams(approvalId), new HashMap<String, Object>(Map.of("userId", MANAGER))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("SPECIAL_APPROVAL_ROLE_FORBIDDEN"));
        assertNothingDecided(approvalId);

        ReflectionTestUtils.invokeMethod(orderApprovalTool("quality_manager"), "doExecute",
                FACTORY, approveParams(approvalId), new HashMap<String, Object>(Map.of("userId", MANAGER)));
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("Q11 平台管理员按身份不按字符串: 工厂账号角色写着 platform_admin 403; 已认证的平台身份照常可批")
    void platformApproverIsRecognisedByIdentityNotByRoleString() {
        String approvalId = pendingSpecialConditionalRelease();

        // 工厂账号的角色字符串写成平台角色 —— 服务层不认
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(orderApprovalTool("platform_admin"), "doExecute",
                FACTORY, approveParams(approvalId), new HashMap<String, Object>(Map.of("userId", MANAGER))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("SPECIAL_APPROVAL_ROLE_FORBIDDEN"));
        assertNothingDecided(approvalId);

        // 已认证的平台管理员身份 (服务端写入, 不来自客户端字符串)
        try (var scope = com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(
                new com.cretas.aims.security.AuthenticatedActor(
                        com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "platform_admin"))) {
            specialApprovals().processDecision(FACTORY, approvalId,
                    decision(ApprovalDecisionRequest.ApprovalDecision.APPROVE), MANAGER, 900L, "platform_admin");
        }
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
    }

    @Test
    @DisplayName("B3 审批人按权限: /approve、/decision、order_approval 都只认同一个审批权限码, 不再写死岗位名")
    void approverPermissionIsTheSameEverywhere() throws Exception {
        java.util.function.Function<java.lang.reflect.Method, java.util.Set<String>> permissionsOf = m ->
                java.util.Set.of(m.getAnnotation(com.cretas.aims.annotation.RequirePermission.class).value());
        java.lang.reflect.Method approve = java.util.Arrays.stream(QualityDispositionController.class.getMethods())
                .filter(m -> m.getName().equals("approveDisposition")).findFirst().orElseThrow();
        java.lang.reflect.Method decisionMethod = java.util.Arrays.stream(
                        com.cretas.aims.controller.ProcessingController.class.getMethods())
                .filter(m -> m.getName().equals("processApprovalDecision")).findFirst().orElseThrow();

        java.util.Set<String> code = java.util.Set.of("approval_quality_disposition:approve");
        assertThat(permissionsOf.apply(approve)).isEqualTo(code);
        assertThat(permissionsOf.apply(decisionMethod)).isEqualTo(code);
        // ⛔ 不再叠一层岗位名单: 叠着它, 工厂在权限配置里授给别的岗位也批不了
        assertThat(approve.getAnnotation(com.cretas.aims.config.RequireRole.class)).isNull();
        assertThat(decisionMethod.getAnnotation(com.cretas.aims.config.RequireRole.class)).isNull();
        com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool tool = new com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool();
        assertThat(tool.getRequiredPermissions()).isEqualTo(code);
        assertThat(tool.getRequiredRoles()).isEmpty();
    }

    @Test
    @DisplayName("特批提交: 不合格检验上申请直接放行当场 409 (不留一条永远批不成的待审批); 合格检验照常可申请")
    void releaseSpecialApprovalOnAFailedInspectionIsRefusedAtSubmit() {
        inspect("qi-fail", "FAIL", "80");
        int auditsBefore = audits.size();

        assertThatThrownBy(() -> specialApprovals().submitSpecialApproval(FACTORY, "qi-fail", specialRequest("RELEASE"),
                INSPECTOR))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("SPECIAL_APPROVAL_RELEASE_NOT_ELIGIBLE");
                    assertThat(be.getActionHint()).contains("条件放行");
                });
        assertThat(audits).hasSize(auditsBefore);

        inspect("qi-pass", "PASS", "100");
        String ok = specialApprovals().submitSpecialApproval(FACTORY, "qi-pass", specialRequest("RELEASE"), INSPECTOR)
                .getApprovalId();
        assertThat(audits.get(ok).getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("同计划另一批还不合格: 这批放行, 成品继续隔离, 并如实告诉操作人")
    void siblingFailureKeepsGoodsQuarantined() {
        ProductionBatch sibling = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1", QualityStatus.FAILED);
        inspect("qi-fail", "FAIL", "80");
        assertThat(sibling.getQualityStatus()).isEqualTo(QualityStatus.FAILED);

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("1 个成品批次仍在隔离中").contains("还有 1 个批次没有解除隔离");
        assertThat(result.getFinishedGoodsHeld()).isEqualTo(1);
    }

    /**
     * Q2 改写 (Owner 2026-11-09 裁定「计划里所有批次都解除后, 一次放行这个计划的全部成品」): 旧断言钉的是「同计划
     * 其他 SKU 不放」—— 联产计划的副产品同样是这个计划的货, 不再比 SKU。退货子批与质检放行单独判不合格的
     * 仍不放 (那是对那批货本身的结论), 断言保留。
     */
    @Test
    @DisplayName("放行不洗白别的货: 退货子批 / 质检放行单独判不合格的留在隔离; 同计划其他 SKU 一起放行 (Q2)")
    void releaseLeavesUnrelatedDefectiveGoodsAlone() {
        FinishedGoodsBatch returned = finishedGoods("RTN-R20261101-1", "SKU-1", FinishedGoodsBatch.Status.DEFECTIVE);
        FinishedGoodsBatch otherSku = finishedGoods("FG-PLAN-1793087414891-94ECD656-S1-aaaaaaaa", "SKU-2",
                FinishedGoodsBatch.Status.AVAILABLE);
        FinishedGoodsBatch rejected = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-d2e185aa", "SKU-1",
                FinishedGoodsBatch.Status.AVAILABLE);
        QcReleaseRecord rejection = QcReleaseRecord.builder().decision(QcReleaseRecord.Decision.REJECTED).build();
        when(releaseRecords.findByTarget(FACTORY, QcReleaseRecord.TargetType.FINISHED_GOODS, rejected.getId()))
                .thenReturn(List.of(rejection));

        inspect("qi-fail", "FAIL", "80");
        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(returned.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(otherSku.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(rejected.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("1 个成品批次仍在隔离中").contains("质检放行判不合格");
    }

    @Test
    @DisplayName("Q7: 工序时限超时判废的成品 (真 ProcessTimeLimitService.decide 写下的 DEFECTIVE), 计划复核放行后仍留在隔离")
    void timeLimitRejectedGoodsStayQuarantinedAfterPlanRelease() {
        FinishedGoodsBatch expired = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-d2e185aa", "SKU-1",
                FinishedGoodsBatch.Status.AVAILABLE);
        inspect("qi-fail", "FAIL", "80");
        assertThat(expired.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);

        // 真写入方: 超时事件判不合格, 由 ProcessTimeLimitService.writeOff 把这批成品写成 DEFECTIVE 并留下 REJECTED 事件
        ProcessTimeLimitEvent open = new ProcessTimeLimitEvent();
        open.setId(1L);
        open.setFactoryId(FACTORY);
        open.setTargetType(ProcessTimeLimitService.TargetType.FINISHED_GOODS);
        open.setTargetId(expired.getId());
        open.setBatchNumber(expired.getBatchNumber());
        open.setStatus(ProcessTimeLimitEvent.Status.OPEN);
        when(timeLimitEvents.findByIdAndFactoryIdForUpdate(1L, FACTORY)).thenReturn(Optional.of(open));
        when(timeLimitEvents.save(any())).thenAnswer(call -> {
            ProcessTimeLimitEvent row = call.getArgument(0);
            timeLimitRows.removeIf(e -> e.getId().equals(row.getId()));
            timeLimitRows.add(row);
            return row;
        });
        when(goodsRepo.findByIdAndFactoryIdForUpdate(anyString(), eq(FACTORY))).thenAnswer(call -> finishedGoods.stream()
                .filter(g -> g.getId().equals(call.getArgument(0))).findFirst());
        timeLimitService().decide(FACTORY, 1L,
                new ProcessTimeLimitService.DecisionCommand("REJECTED", "解冻超时，复检判不合格"), MANAGER, "质量经理");
        assertThat(timeLimitRows).extracting(ProcessTimeLimitEvent::getStatus)
                .containsExactly(ProcessTimeLimitEvent.Status.REJECTED);

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(expired.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("1 个成品批次仍在隔离中");
    }

    // ==================== Q4: 未锁定批次的放行、召回货、冻结货 ====================

    @Test
    @DisplayName("Q4 复现: 批次没锁定时直接放行 (无人复核) 不翻转本计划任何成品, 响应说明要复核")
    void directReleaseOfUnlockedBatchFlipsNoFinishedGoods() {
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);   // 例如召回 / 判废留下的隔离货
        inspect("qi-pass", "PASS", "100");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PASSED);

        var response = controller.executeDisposition(FACTORY, execute("qi-pass", "RELEASE", INSPECTOR), INSPECTOR);

        assertThat(response.getData().getStatus()).isEqualTo("EXECUTED");
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(response.getData().getMessage()).contains("1 个成品批次仍在隔离中").contains("复核");
    }

    @Test
    @DisplayName("Q4 复现: 同计划兄弟批发起过召回, 复核放行这批时召回冻结的成品不放出")
    void siblingRecallKeepsItsFrozenGoods() {
        ProductionBatch sibling = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.PASSED);
        FinishedGoodsBatch recalled = finishedGoods(sibling.getBatchNumber(), "SKU-1", FinishedGoodsBatch.Status.DEFECTIVE);
        when(recalls.hasRecallRecord(FACTORY, sibling.getId())).thenReturn(true);
        inspect("qi-fail", "FAIL", "80");

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(recalled.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("召回");
    }

    @Test
    @DisplayName("Q4 阴性对照: 兄弟批没有召回时, 同样的复核放行把这些成品放出 (证明上一条红在召回上)")
    void withoutRecallTheSameGoodsAreReleased() {
        ProductionBatch sibling = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.PASSED);
        FinishedGoodsBatch siblingGoods = finishedGoods(sibling.getBatchNumber(), "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        inspect("qi-fail", "FAIL", "80");

        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(siblingGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("Q4: 冻结 (FROZEN) 的成品不受放行影响, 放行前后都是 FROZEN")
    void frozenGoodsAreNotTouchedByRelease() {
        FinishedGoodsBatch frozen = finishedGoods("FG-PLAN-1793087414891-94ECD656-S3-d2e185aa", "SKU-1",
                FinishedGoodsBatch.Status.FROZEN);
        inspect("qi-fail", "FAIL", "80");
        assertThat(frozen.getStatus()).isEqualTo(FinishedGoodsBatch.Status.FROZEN);

        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(frozen.getStatus()).isEqualTo(FinishedGoodsBatch.Status.FROZEN);
    }

    // ==================== Q2: 计划级放行口径 (Owner 2026-11-09 裁定) ====================

    @Test
    @DisplayName("Q2: 报废的兄弟批不挡整个计划 —— 血缘边分得清时, 报废批的成品留在隔离, 本批成品放行")
    void scrappedSiblingKeepsOnlyItsOwnGoods() {
        ProductionBatch scrapped = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.SCRAPPED);
        FinishedGoodsBatch scrappedGoods = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        inspect("qi-fail", "FAIL", "80");
        edge(batch.getId(), goods.getId());
        edge(scrapped.getId(), scrappedGoods.getId());

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(scrappedGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("1 个成品批次仍在隔离中").contains("报废批次的成品 1 个");
    }

    @Test
    @DisplayName("Q2: 有报废兄弟批但分不清成品归属 (没有血缘、批号对不上) —— 整个计划不放, 如实说分不清, 不猜")
    void unattributableGoodsWithScrappedSiblingHoldTheWholePlan() {
        productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1", QualityStatus.SCRAPPED);
        FinishedGoodsBatch other = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        inspect("qi-fail", "FAIL", "80");

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(other.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("2 个成品批次仍在隔离中").contains("分不清");
    }

    @Test
    @DisplayName("Q2: 报废兄弟批 + 没有血缘但批号相同 —— 批号算确定归属, 报废批号那批留下, 本批号那批放行")
    void sameBatchNumberAttributesWithoutLineage() {
        ProductionBatch scrapped = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.SCRAPPED);
        FinishedGoodsBatch scrappedGoods = finishedGoods(scrapped.getBatchNumber(), "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        goods.setBatchNumber(batch.getBatchNumber());
        inspect("qi-fail", "FAIL", "80");

        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(scrappedGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("Q2: 血缘不全 (一个成品有边、另一个没有) 只在要区分批次时才挡; 没有报废/召回批次时照常一次放行")
    void incompleteLineageOnlyMattersWhenBatchesMustBeTold() {
        ProductionBatch sibling = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.PASSED);
        FinishedGoodsBatch other = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        inspect("qi-fail", "FAIL", "80");
        edge(sibling.getId(), other.getId());   // goods 没有边

        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(other.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("Q2: 血缘指向本计划以外的生产批次 —— 归属不明, 整个计划不放")
    void lineagePointingOutsideThePlanHoldsEverything() {
        inspect("qi-fail", "FAIL", "80");
        edge(999L, goods.getId());

        var result = ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(result.getMessage()).contains("本计划以外");
    }

    @Test
    @DisplayName("Q2: 先放行这批、后报废另一批 —— 报废复核通过那一刻, 已解除批次的成品放行, 报废批的留下")
    void scrapAsTheLastClearingActReleasesTheOtherBatches() {
        ProductionBatch failedSibling = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.FAILED);
        FinishedGoodsBatch siblingGoods = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        edge(batch.getId(), goods.getId());
        edge(failedSibling.getId(), siblingGoods.getId());
        inspect("qi-fail", "FAIL", "80");
        // 本批先复核放行: 兄弟批还 FAILED, 什么都不放
        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);

        // 兄弟批复核报废 (同一桥接, 质检挂在兄弟批上)
        QualityInspection siblingInspection = new QualityInspection();
        siblingInspection.setId("qi-sibling");
        siblingInspection.setFactoryId(FACTORY);
        siblingInspection.setProductionBatchId(failedSibling.getId());
        siblingInspection.setResult("FAIL");
        var scrapped = bridge.applyDisposition(siblingInspection,
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.SCRAP, true);

        assertThat(failedSibling.getQualityStatus()).isEqualTo(QualityStatus.SCRAPPED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(siblingGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(scrapped.getFinishedGoodsReleased()).isEqualTo(1);
        assertThat(scrapped.getFinishedGoodsNotice()).contains("报废批次的成品 1 个");
    }

    @Test
    @DisplayName("Q2/审批入口: 批准后的响应原话带上「还有几批没解除」, 前端据此给出不消失的警告")
    void approvalResponseCarriesHeldGoodsMessage() {
        productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1", QualityStatus.FAILED);
        inspect("qi-fail", "FAIL", "80");
        String applicationId = controller.applyDisposition(FACTORY, apply("qi-fail", "CONDITIONAL_RELEASE"), INSPECTOR)
                .getData().getApplicationId();

        var approved = controller.approveDisposition(FACTORY, applicationId, approve(MANAGER, true), MANAGER).getData();

        assertThat(approved.getFinishedGoodsHeld()).isEqualTo(1);
        assertThat(approved.getMessage()).startsWith("处置申请已批准").contains("还有 1 个批次没有解除隔离");
    }

    // ==================== 已放行批次申请放行隔离成品 (Owner 2026-11-09) ====================

    /** 本批复核放行时有报废兄弟批、成品分不清归属 ⇒ 全部留在隔离 (Q2)。返回报废兄弟批的成品。 */
    private FinishedGoodsBatch heldBecauseOwnershipWasUnclear() {
        productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1", QualityStatus.SCRAPPED);
        FinishedGoodsBatch scrappedGoods = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        inspect("qi-fail", "FAIL", "80");
        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(scrappedGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        return scrappedGoods;
    }

    @Test
    @DisplayName("Owner: 分不清归属而留下的成品 → 补齐血缘 → 已放行批次单人申请 → 本批成品放行, 报废批的留下, 留审计")
    void passedBatchRequestReleasesAfterLineageIsFixed() {
        FinishedGoodsBatch scrappedGoods = heldBecauseOwnershipWasUnclear();
        assertThat(bridge.listPassedBatchesWithHeldGoods(FACTORY))
                .extracting(QualityDispositionInventoryService.HeldGoodsBatch::productionBatchId).containsExactly(100L);
        edge(batch.getId(), goods.getId());
        edge(101L, scrappedGoods.getId());

        var response = controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR).getData();

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(scrappedGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(response.getMessage()).contains("放行 1 个成品批次").contains("报废批次的成品 1 个");
        assertThat(response.getFinishedGoodsHeld()).isEqualTo(1);
        // 单人: 申请人自己就执行了, 没有进待审批
        assertThat(response.getStatus()).isEqualTo("EXECUTED");
        assertThat(audits.values()).anySatisfy(a -> {
            assertThat(a.getEntityType()).isEqualTo("ProductionBatch");
            assertThat(a.getEntityId()).isEqualTo("100");
            assertThat(a.getExecutorId()).isEqualTo(INSPECTOR);
            assertThat(a.getOutputResult()).contains("\"finishedGoodsReleased\":1");
        });
    }

    @Test
    @DisplayName("Owner: 归属仍分不清 → 申请一个不放, 说清为什么")
    void passedBatchRequestStillUnclearReleasesNothing() {
        FinishedGoodsBatch scrappedGoods = heldBecauseOwnershipWasUnclear();

        var response = controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR).getData();

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(scrappedGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(response.getMessage()).contains("放行 0 个成品批次").contains("分不清");
    }

    @Test
    @DisplayName("Owner: 召回过的兄弟批的货, 申请放行后仍留在隔离; 本批的照常放")
    void passedBatchRequestKeepsRecalledGoodsHeld() {
        ProductionBatch recalled = productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1",
                QualityStatus.PASSED);
        FinishedGoodsBatch recalledGoods = finishedGoods(recalled.getBatchNumber(), "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        when(recalls.hasRecallRecord(FACTORY, 101L)).thenReturn(true);
        batch.setQualityStatus(QualityStatus.PASSED);
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        edge(batch.getId(), goods.getId());
        edge(101L, recalledGoods.getId());

        var response = controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR).getData();

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(recalledGoods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(response.getMessage()).contains("召回批次的成品 1 个");
    }

    @Test
    @DisplayName("阴性: 还没放行的批次不能用这个入口 (不合格的走处置复核), 什么都不动")
    void unpassedBatchCannotUseTheRequest() {
        inspect("qi-fail", "FAIL", "0");
        assertThatThrownBy(() -> controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("QUALITY_BATCH_NOT_PASSED"));
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    // ==================== #1548 第二轮审查 ====================

    @Test
    @DisplayName("审查 1 复现: 逐道录入的 CLERK_WIP 批次判了不合格 —— 已放行批次单人申请也一个不放, 说还有 1 批没解除")
    void clerkWipFailureStillHoldsThePlan() {
        batch.setQualityStatus(QualityStatus.PASSED);
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        ProductionBatch clerkWip = productionBatch(102L, "CLK-B-PLAN-1793087414891-94ECD656-1", "SKU-1",
                QualityStatus.FAILED);
        clerkWip.setBatchType("CLERK_WIP");

        var response = controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR).getData();

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(response.getMessage()).contains("放行 0 个成品批次").contains("还有 1 个批次没有解除隔离");
    }

    @Test
    @DisplayName("审查 3: 放行先锁计划所有生产批次, 再锁成品, 锁到之后才重读批次状态 (锁顺序 = 生产批次 → 成品)")
    void releaseLocksBatchesThenGoodsThenReadsStates() {
        inspect("qi-fail", "FAIL", "80");
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);

        // 复核放行这条路上只有计划级放行自己拿计划锁 (单人申请那条路在入口处还会先锁一次, 测不出这里)
        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(batchRepoOf(), goodsRepo);
        order.verify(batchRepoOf()).lockAllIdsInPlan(FACTORY, PLAN);
        order.verify(goodsRepo).findByFactoryIdAndProductionPlanIdForUpdate(FACTORY, PLAN);
        order.verify(batchRepoOf(), org.mockito.Mockito.atLeastOnce()).findQcStatesInPlan(FACTORY, PLAN);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("审查 3 另一边: 质检判不合格先拿这批的行锁, 再隔离成品")
    void inspectionFailureLocksTheBatchBeforeQuarantining() {
        inspect("qi-fail", "FAIL", "0");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(batchRepoOf(), goodsRepo);
        order.verify(batchRepoOf()).findByIdAndFactoryIdForUpdate(100L, FACTORY);
        order.verify(goodsRepo).findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    @Test
    @DisplayName("审查 5: 申请被拒 (还没放行 / 不在本厂) 也留审计 —— 谁、哪个批次、为什么")
    void refusedRequestsAreAudited() {
        inspect("qi-fail", "FAIL", "0");
        assertThatThrownBy(() -> controller.releaseHeldGoods(FACTORY, 100L, INSPECTOR))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.releaseHeldGoods(FACTORY, 4040L, INSPECTOR))
                .isInstanceOf(BusinessException.class);

        assertThat(audits.values()).filteredOn(a -> "ProductionBatch".equals(a.getEntityType()))
                .extracting(DecisionAuditLog::getEntityId, DecisionAuditLog::getExecutorId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("100", INSPECTOR),
                        org.assertj.core.groups.Tuple.tuple("4040", INSPECTOR));
        assertThat(audits.values()).filteredOn(a -> "100".equals(a.getEntityId()))
                .allSatisfy(a -> assertThat(a.getOutputResult()).contains("\"refused\":true")
                        .contains("QUALITY_BATCH_NOT_PASSED"));
        assertThat(audits.values()).filteredOn(a -> "4040".equals(a.getEntityId()))
                .allSatisfy(a -> assertThat(a.getOutputResult()).contains("PRODUCTION_BATCH_NOT_FOUND"));
    }

    @Test
    @DisplayName("审查 6: 列表只给按一下有用的计划 —— 能放的数 / 分不清的数分开给; 只剩报废批的货就不列")
    void heldListOnlyShowsPlansARequestCanHelp() {
        // 能放: 本批已放行, 成品只是还隔离着
        batch.setQualityStatus(QualityStatus.PASSED);
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(bridge.listPassedBatchesWithHeldGoods(FACTORY)).singleElement().satisfies(row -> {
            assertThat(row.releasableNow()).isEqualTo(1);
            assertThat(row.unclearOwnership()).isZero();
        });

        // 只剩报废批的货 (血缘分得清): 按钮放不出任何东西 ⇒ 不列
        goods.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        productionBatch(101L, "PB-PLAN-1793087414891-94ECD656-11111", "SKU-1", QualityStatus.SCRAPPED);
        FinishedGoodsBatch scrappedGoods = finishedGoods("FG-PLAN-1793087414891-94ECD656-S2-aaaaaaaa", "SKU-1",
                FinishedGoodsBatch.Status.DEFECTIVE);
        edge(101L, scrappedGoods.getId());
        assertThat(bridge.listPassedBatchesWithHeldGoods(FACTORY)).isEmpty();

        // 分不清归属: 列出, 能放 0、分不清 2, 并带原因 (补齐血缘后按按钮就放得出)
        edgeRows.clear();
        goods.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(bridge.listPassedBatchesWithHeldGoods(FACTORY)).singleElement().satisfies(row -> {
            assertThat(row.releasableNow()).isZero();
            assertThat(row.unclearOwnership()).isEqualTo(2);
            assertThat(row.unclearReason()).contains("分不清");
        });
    }

    private ProductionBatchRepository batchRepoOf() {
        return (ProductionBatchRepository) ReflectionTestUtils.getField(bridge, "productionBatchRepository");
    }

    private void edge(Long productionBatchId, String finishedGoodsId) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY);
        e.setEdgeType(com.cretas.aims.service.lineage.LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED);
        e.setSourceType(com.cretas.aims.service.lineage.LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
        e.setSourceId(String.valueOf(productionBatchId));
        e.setTargetType(com.cretas.aims.service.lineage.LineageEdgeRecorder.NODE_FINISHED_BATCH);
        e.setTargetId(finishedGoodsId);
        edgeRows.add(e);
    }

    private ProcessTimeLimitService timeLimitService() {
        return new ProcessTimeLimitService(mock(com.cretas.aims.repository.workflow.WorkflowTaskPortRepository.class),
                mock(com.cretas.aims.repository.workprocess.WorkProcessTaskRepository.class),
                mock(com.cretas.aims.repository.WorkProcessRepository.class), mock(ProductionBatchRepository.class),
                mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository.class),
                mock(com.cretas.aims.repository.ProductionReportRepository.class),
                mock(com.cretas.aims.repository.SemiFinishedInventoryRepository.class),
                mock(com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository.class),
                mock(MaterialBatchRepository.class), goodsRepo,
                mock(com.cretas.aims.repository.lineage.BatchLineageEdgeRepository.class),
                mock(com.cretas.aims.repository.foodsafety.ProcessTimeMarkRepository.class), timeLimitEvents,
                mock(com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitEventWriter.class),
                mock(com.cretas.aims.service.quality.release.QcReleaseService.class),
                mock(com.cretas.aims.service.factory.WarehouseResolver.class),
                mock(com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("需检产品解除隔离后回待检, 走质检放行, 处置不替代那一步")
    void inspectionRequiredProductGoesBackToPending() {
        ProductType product = new ProductType();
        product.setInspectionRequired(true);
        when(productTypes.findByIdAndFactoryId("SKU-1", FACTORY)).thenReturn(Optional.of(product));

        inspect("qi-fail", "FAIL", "80");
        ruleService.executeApprovedDisposition(FACTORY, inspections.get("qi-fail"),
                com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.CONDITIONAL_RELEASE, MANAGER, "复核");

        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
    }

    @Test
    @DisplayName("先判不合格后入库: 所有成品入库路径共用的状态叠加给 DEFECTIVE, 解除后才恢复")
    void outputAfterFailureIsQuarantinedOnEveryInboundPath() {
        QcInspectionPolicy policy = new QcInspectionPolicy(productTypes);
        ReflectionTestUtils.setField(policy, "productionBatchRepository",
                ReflectionTestUtils.getField(inspectionService, "productionBatchRepository"));

        batch.setQualityStatus(QualityStatus.REWORK_REQUIRED);
        assertThat(policy.finishedGoodsStatusForOutput(FACTORY, "SKU-1", PLAN, FinishedGoodsBatch.Status.AVAILABLE, "FG-X"))
                .isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        assertThat(policy.finishedGoodsStatusForOutput(FACTORY, "SKU-1", PLAN, FinishedGoodsBatch.Status.DEPLETED, "FG-X"))
                .isEqualTo(FinishedGoodsBatch.Status.DEPLETED);

        batch.setQualityStatus(QualityStatus.PASSED);
        assertThat(policy.finishedGoodsStatusForOutput(FACTORY, "SKU-1", PLAN, FinishedGoodsBatch.Status.AVAILABLE, "FG-X"))
                .isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    // ==================== 原检验记录不可改 ====================

    @Test
    @DisplayName("已完成记录: 判定 / 数量 / 检验人 / 删原备注都 409; 只追加备注可以")
    void completedRecordIsImmutable() {
        QualityInspection fail = inspect("qi-fail", "FAIL", "0");
        fail.setNotes("温度超标");

        for (java.util.function.Consumer<QualityInspection> change : List.<java.util.function.Consumer<QualityInspection>>of(
                row -> row.setResult("PASS"),
                row -> row.setPassRate(new BigDecimal("100")),
                row -> row.setPassCount(new BigDecimal("1")),
                row -> row.setInspectorId(MANAGER),
                row -> row.setNotes(""))) {
            QualityInspection patch = new QualityInspection();
            change.accept(patch);
            assertThatThrownBy(() -> inspectionService.updateInspection(FACTORY, "qi-fail", patch))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("QUALITY_INSPECTION_DECISION_IMMUTABLE"));
        }
        assertThat(inspections.get("qi-fail").getResult()).isEqualTo("FAIL");
        assertThat(inspections.get("qi-fail").getNotes()).isEqualTo("温度超标");

        QualityInspection append = new QualityInspection();
        append.setNotes("温度超标 | 已通知车间");
        inspectionService.updateInspection(FACTORY, "qi-fail", append);
        assertThat(inspections.get("qi-fail").getNotes()).isEqualTo("温度超标 | 已通知车间");
    }

    @Test
    @DisplayName("AI 工具入口: 更新 / 提交工具改不了 FAIL 记录, 且没有改到查回来的实体 (open-in-view 下它是托管的)")
    void aiToolsCannotRewriteCompletedRecord() {
        inspect("qi-fail", "FAIL", "0");
        QualityInspectionService viaService = inspectionService;

        QualityCheckUpdateTool update = new QualityCheckUpdateTool();
        ReflectionTestUtils.setField(update, "qualityInspectionService", viaService);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(update, "doExecute", FACTORY,
                new HashMap<>(Map.of("checkId", "qi-fail", "result", "PASS")), new HashMap<String, Object>()))
                .isInstanceOf(BusinessException.class);

        QualityResultSubmitTool submit = new QualityResultSubmitTool();
        ReflectionTestUtils.setField(submit, "qualityInspectionService", viaService);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(submit, "doExecute", FACTORY,
                new HashMap<>(Map.of("checkId", "qi-fail", "result", "PASS", "conclusion", "复检合格")),
                new HashMap<String, Object>(Map.of("userId", INSPECTOR))))
                .isInstanceOf(BusinessException.class);

        assertThat(inspections.get("qi-fail").getResult()).isEqualTo("FAIL");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
    }

    @Test
    @DisplayName("AI 提交工具: 判 FAIL 结论全空白不落库 (D-S302-02); 有结论才建记录")
    void aiSubmitToolNeedsAReasonToFail() {
        QualityResultSubmitTool submit = new QualityResultSubmitTool();
        ReflectionTestUtils.setField(submit, "qualityInspectionService", inspectionService);
        Map<String, Object> blank = new HashMap<>(Map.of("checkId", "qi-ai", "result", "FAIL",
                "conclusion", "   ", "productionBatchId", 100L));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(submit, "doExecute", FACTORY, blank,
                new HashMap<String, Object>(Map.of("userId", INSPECTOR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原因");
        assertThat(inspections).doesNotContainKey("qi-ai");
    }

    @Test
    @DisplayName("AI 更新工具: 待检任务判 FAIL 没原因不写, 带原因才写 (D-S302-02)")
    void aiUpdateToolNeedsAReasonToFail() {
        // 自动建的待检任务自带这句备注, 它不是不合格原因
        inspect("qi-pending", "PENDING", null).setNotes("批次完成自动创建质检任务");
        QualityCheckUpdateTool update = new QualityCheckUpdateTool();
        ReflectionTestUtils.setField(update, "qualityInspectionService", inspectionService);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(update, "doExecute", FACTORY,
                new HashMap<>(Map.of("checkId", "qi-pending", "result", "FAIL", "notes", "  ")),
                new HashMap<String, Object>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原因");
        assertThat(inspections.get("qi-pending").getResult()).isEqualTo("PENDING");

        ReflectionTestUtils.invokeMethod(update, "doExecute", FACTORY,
                new HashMap<>(Map.of("checkId", "qi-pending", "result", "FAIL", "notes", "温度超标")),
                new HashMap<String, Object>());
        assertThat(inspections.get("qi-pending").getResult()).isEqualTo("FAIL");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
    }

    @Test
    @DisplayName("AI 处置工具: 放行不合格批只是提交审批, 不说「执行成功」, 批次不动")
    void aiDispositionToolTellsTheTruthAboutApproval() {
        inspect("qi-fail", "FAIL", "0");
        QualityInspection pass = inspect("qi-pass", "PASS", "100");
        when(inspectionRepo.findFirstByFactoryIdAndProductionBatchIdOrderByInspectionDateDesc(FACTORY, 100L))
                .thenReturn(Optional.of(pass));
        QualityDispositionExecuteTool tool = new QualityDispositionExecuteTool();
        ReflectionTestUtils.setField(tool, "qualityInspectionRepository", inspectionRepo);
        ReflectionTestUtils.setField(tool, "qualityDispositionRuleService", ruleService);

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(tool, "doExecute", FACTORY,
                new HashMap<>(Map.of("productionBatchId", 100L, "action", "RELEASE", "reason", "复检合格")),
                new HashMap<String, Object>(Map.of("userId", INSPECTOR)));

        assertThat(String.valueOf(result.get("message"))).contains("已提交审批").doesNotContain("执行成功");
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(goods.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
    }

    // ==================== 夹具 ====================

    private QualityInspection inspect(String id, String result, String passRate) {
        QualityInspection row = new QualityInspection();
        row.setId(id);
        row.setProductionBatchId(batch.getId());
        row.setInspectorId(INSPECTOR);
        row.setInspectionDate(LocalDate.of(2026, 11, 4));
        row.setSampleSize(BigDecimal.ONE);
        row.setPassCount(BigDecimal.ZERO);
        row.setFailCount(BigDecimal.ONE);
        row.setResult(result);
        row.setPassRate(passRate == null ? null : new BigDecimal(passRate));
        return inspectionService.createInspection(FACTORY, row);
    }

    private static ExecuteDispositionRequest execute(String inspectionId, String action, Long executorId) {
        return ExecuteDispositionRequest.builder().batchId(100L).inspectionId(inspectionId)
                .actionCode(action).executorId(executorId).operatorComment("复核").build();
    }

    private static DispositionApplyRequest apply(String inspectionId, String action) {
        DispositionApplyRequest request = new DispositionApplyRequest();
        request.setBatchId(100L);
        request.setInspectionId(inspectionId);
        request.setActionCode(action);
        request.setApplicantId(INSPECTOR);
        request.setApplicantName("质检员");
        request.setReason("复核");
        return request;
    }

    private static DispositionApproveRequest approve(Long approverId, boolean approved) {
        DispositionApproveRequest request = new DispositionApproveRequest();
        request.setApproved(approved);
        request.setApproverId(approverId);
        request.setApproverName("质量经理");
        request.setComment("复核");
        return request;
    }

    private static ProductionBatchRepository.PlanBatchQcState qcState(ProductionBatch b) {
        return new ProductionBatchRepository.PlanBatchQcState() {
            @Override
            public Long getId() {
                return b.getId();
            }

            @Override
            public String getBatchNumber() {
                return b.getBatchNumber();
            }

            @Override
            public QualityStatus getQualityStatus() {
                return b.getQualityStatus();
            }
        };
    }

    private ProductionBatch productionBatch(Long id, String number, String sku, QualityStatus status) {
        ProductionBatch row = new ProductionBatch();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setBatchNumber(number);
        row.setProductTypeId(sku);
        row.setProductionPlanId(PLAN);
        row.setQualityStatus(status);
        batches.add(row);
        return row;
    }

    private FinishedGoodsBatch finishedGoods(String number, String sku, String status) {
        FinishedGoodsBatch row = new FinishedGoodsBatch();
        row.setId("fg-" + (finishedGoods.size() + 1));
        row.setFactoryId(FACTORY);
        row.setBatchNumber(number);
        row.setProductTypeId(sku);
        row.setProductionPlanId(PLAN);
        row.setStatus(status);
        finishedGoods.add(row);
        return row;
    }
    // ==================== 纪律排查 B3: 工厂级「允许本人审批」开关 ====================
    //
    // 两个入口都要过: /quality-disposition/{id}/approve (控制器) 与特批 processDecision (App /decision + AI 工具)。

    private com.cretas.aims.service.workflow.FactorySelfApprovalGate selfApprovalGate(boolean on) {
        com.cretas.aims.repository.FactorySettingsRepository settings =
                mock(com.cretas.aims.repository.FactorySettingsRepository.class);
        when(settings.findAllowSelfApprovalByFactoryId(FACTORY)).thenReturn(on);
        return new com.cretas.aims.service.workflow.FactorySelfApprovalGate(settings);
    }

    private SpecialApprovalServiceImpl specialApprovalsWithSwitch(boolean on) {
        SpecialApprovalServiceImpl special = specialApprovals();
        ReflectionTestUtils.setField(special, "selfApprovalGate", selfApprovalGate(on));
        return special;
    }

    private static ApprovalDecisionRequest selfDecision(String comment, String selfReason) {
        ApprovalDecisionRequest request = new ApprovalDecisionRequest();
        request.setDecision(ApprovalDecisionRequest.ApprovalDecision.APPROVE);
        request.setComment(comment);
        request.setSelfApprovalReason(selfReason);
        return request;
    }

    @Test
    @DisplayName("B3 特批 开关关: 申请人自批 (写了原因也不行) → 403 SPECIAL_APPROVAL_SELF_APPROVAL, 什么都不执行")
    void specialSelfApprovalForbiddenWhenSwitchOff() {
        String approvalId = pendingSpecialConditionalRelease();
        SpecialApprovalServiceImpl special = specialApprovalsWithSwitch(false);

        assertThatThrownBy(() -> special.processDecision(FACTORY, approvalId, selfDecision("同意", "只有我一个人"),
                INSPECTOR, INSPECTOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(SpecialApprovalServiceImpl.SPECIAL_APPROVAL_SELF_APPROVAL));
        assertNothingDecided(approvalId);
        assertThat(audits.get(approvalId).isSelfApproved()).isFalse();
    }

    @Test
    @DisplayName("B3 特批 开关开 + 写了原因: 申请人自批放行, 执行一次, 审批行标本人审批并存原因")
    void specialSelfApprovalAllowedAndTracedWhenSwitchOn() {
        String approvalId = pendingSpecialConditionalRelease();
        SpecialApprovalServiceImpl special = specialApprovalsWithSwitch(true);

        special.processDecision(FACTORY, approvalId, selfDecision("同意", "本厂只有我一个人管质量"),
                INSPECTOR, INSPECTOR, "factory_super_admin");

        DecisionAuditLog row = audits.get(approvalId);
        assertThat(row.getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.APPROVED);
        assertThat(row.isSelfApproved()).isTrue();
        assertThat(row.getSelfApprovalReason()).isEqualTo("本厂只有我一个人管质量");
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
    }

    @Test
    @DisplayName("B3 特批 开关开 + 没写原因 (意见也空): 申请人自批 → 422, 什么都不执行")
    void specialSelfApprovalWithoutReasonRejectedWhenSwitchOn() {
        String approvalId = pendingSpecialConditionalRelease();
        SpecialApprovalServiceImpl special = specialApprovalsWithSwitch(true);

        assertThatThrownBy(() -> special.processDecision(FACTORY, approvalId, selfDecision(" ", null),
                INSPECTOR, INSPECTOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(422);
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(com.cretas.aims.service.workflow.FactorySelfApprovalGate.REASON_REQUIRED);
                });
        assertNothingDecided(approvalId);
    }

    @Test
    @DisplayName("B3 特批 阴性对照: 开关开 + 他人审批 → 不标本人审批")
    void specialOtherApproverNotMarkedWhenSwitchOn() {
        String approvalId = pendingSpecialConditionalRelease();
        specialApprovalsWithSwitch(true).processDecision(FACTORY, approvalId, selfDecision("同意", null),
                MANAGER, MANAGER, "quality_manager");
        assertThat(audits.get(approvalId).isSelfApproved()).isFalse();
        assertThat(audits.get(approvalId).getSelfApprovalReason()).isNull();
    }

    private String pendingDispositionRelease() {
        inspect("qi-fail", "FAIL", "0");
        inspect("qi-pass", "PASS", "100");
        return controller.executeDisposition(FACTORY, execute("qi-pass", "RELEASE", INSPECTOR), INSPECTOR)
                .getData().getApprovalRequestId();
    }

    private static DispositionApproveRequest selfApprove(String comment, String selfReason) {
        DispositionApproveRequest request = approve(INSPECTOR, true);
        request.setComment(comment);
        request.setSelfApprovalReason(selfReason);
        return request;
    }

    @Test
    @DisplayName("B3 处置审批 开关关: 申请人自批 (写了原因也不行) → 403, 批次不变")
    void dispositionSelfApprovalForbiddenWhenSwitchOff() {
        String applicationId = pendingDispositionRelease();
        ReflectionTestUtils.setField(controller, "selfApprovalGate", selfApprovalGate(false));

        assertThatThrownBy(() -> controller.approveDisposition(FACTORY, applicationId,
                selfApprove("复核", "只有我一个人"), INSPECTOR))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
        assertThat(audits.get(applicationId).isSelfApproved()).isFalse();
    }

    @Test
    @DisplayName("B3 处置审批 开关开 + 写了原因: 申请人自批放行, 批次 PASSED, 审批行标本人审批并存原因")
    void dispositionSelfApprovalAllowedAndTracedWhenSwitchOn() {
        String applicationId = pendingDispositionRelease();
        ReflectionTestUtils.setField(controller, "selfApprovalGate", selfApprovalGate(true));

        controller.approveDisposition(FACTORY, applicationId, selfApprove("复核", "本厂只有我能放行"), INSPECTOR);

        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.PASSED);
        DecisionAuditLog row = audits.get(applicationId);
        assertThat(row.isSelfApproved()).isTrue();
        assertThat(row.getSelfApprovalReason()).isEqualTo("本厂只有我能放行");
    }

    @Test
    @DisplayName("B3 处置审批 开关开 + 没写原因 (意见也空): 申请人自批 → 422, 批次不变")
    void dispositionSelfApprovalWithoutReasonRejectedWhenSwitchOn() {
        String applicationId = pendingDispositionRelease();
        ReflectionTestUtils.setField(controller, "selfApprovalGate", selfApprovalGate(true));

        assertThatThrownBy(() -> controller.approveDisposition(FACTORY, applicationId, selfApprove(null, ""), INSPECTOR))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(422));
        assertThat(batch.getQualityStatus()).isEqualTo(QualityStatus.FAILED);
    }
    // ---- #1580 独立审查必修 2: AI 工具代填的「通过」不能当本人审批原因 ----

    private com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool orderApprovalToolWithSwitchOn(String accountRole) {
        com.cretas.aims.ai.tool.impl.dataop.OrderApprovalTool tool = orderApprovalTool(accountRole);
        ReflectionTestUtils.setField(tool, "specialApprovalService", specialApprovalsWithSwitch(true));
        return tool;
    }

    @Test
    @DisplayName("B3 AI 工具 开关开 + 模型没给原因: 不拿占位词「通过」顶替, 422 追问原因, 什么都不执行")
    void orderApprovalToolAsksForTheReasonInsteadOfUsingThePlaceholder() {
        String approvalId = pendingSpecialConditionalRelease();

        for (Map<String, Object> params : List.of(approveParams(approvalId),
                new HashMap<String, Object>(Map.of("transferId", approvalId, "reason", "   ")))) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(orderApprovalToolWithSwitchOn("factory_super_admin"),
                    "doExecute", FACTORY, params, new HashMap<String, Object>(Map.of("userId", INSPECTOR))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        assertThat(((BusinessException) e).getCode()).isEqualTo(422);
                        assertThat(((BusinessException) e).getErrorCode())
                                .isEqualTo(com.cretas.aims.service.workflow.FactorySelfApprovalGate.REASON_REQUIRED);
                        assertThat(e.getMessage()).contains("请告诉我");
                    });
            assertNothingDecided(approvalId);
        }
    }

    @Test
    @DisplayName("B3 AI 工具 开关开 + 用户给了原因: 本人批准, 执行一次, 审批行存的是用户的原因")
    void orderApprovalToolPassesTheUsersReasonAsTheSelfApprovalReason() {
        String approvalId = pendingSpecialConditionalRelease();
        Map<String, Object> params = new HashMap<>(Map.of("transferId", approvalId, "reason", " 本厂只有我管质量 "));

        ReflectionTestUtils.invokeMethod(orderApprovalToolWithSwitchOn("factory_super_admin"), "doExecute",
                FACTORY, params, new HashMap<String, Object>(Map.of("userId", INSPECTOR)));

        DecisionAuditLog row = audits.get(approvalId);
        assertThat(row.getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.APPROVED);
        assertThat(row.isSelfApproved()).isTrue();
        assertThat(row.getSelfApprovalReason()).isEqualTo("本厂只有我管质量");
        assertThat(executedActions("qi-fail")).containsExactly("CONDITIONAL_RELEASE");
    }

    @Test
    @DisplayName("B3 AI 工具 阴性对照: 他人审批不需要原因, 占位词照常写进审批意见")
    void orderApprovalToolStillWorksForAnotherApproverWithoutReason() {
        String approvalId = pendingSpecialConditionalRelease();
        ReflectionTestUtils.invokeMethod(orderApprovalToolWithSwitchOn("quality_manager"), "doExecute",
                FACTORY, approveParams(approvalId), new HashMap<String, Object>(Map.of("userId", MANAGER)));
        DecisionAuditLog row = audits.get(approvalId);
        assertThat(row.getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.APPROVED);
        assertThat(row.isSelfApproved()).isFalse();
        assertThat(row.getApprovalComment()).isEqualTo("通过");
    }
}
