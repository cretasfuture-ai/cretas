package com.cretas.aims.service.quality;

import com.cretas.aims.ai.tool.impl.quality.QualityCheckUpdateTool;
import com.cretas.aims.ai.tool.impl.quality.QualityDispositionExecuteTool;
import com.cretas.aims.ai.tool.impl.quality.QualityResultSubmitTool;
import com.cretas.aims.controller.QualityDispositionController;
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
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findById(any())).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        when(batchRepo.findByIdAndFactoryIdForUpdate(any(), eq(FACTORY))).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        when(batchRepo.findByFactoryIdAndProductionPlanId(eq(FACTORY), eq(PLAN))).thenAnswer(call -> List.copyOf(batches));
        when(batchRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(batchRepo.countQcFailedInPlans(eq(FACTORY), any(), anyString())).thenAnswer(call -> batches.stream()
                .filter(b -> b.getQualityStatus() != null && b.getQualityStatus().isDispositionLocked()).count());

        goodsRepo = mock(FinishedGoodsBatchRepository.class);
        when(goodsRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN))
                .thenAnswer(call -> List.copyOf(finishedGoods));
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

        DecisionAuditLogRepository auditRepo = mock(DecisionAuditLogRepository.class);
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
        QualityDispositionDecisionGuard guard = new QualityDispositionDecisionGuard(
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
                    .entityType("QualityInspection").entityId(call.getArgument(2))
                    .decisionType(DecisionAuditLog.DecisionType.RULE_EXECUTION)
                    .outputResult(new ObjectMapper().writeValueAsString(output)).build();
            audits.put(row.getId(), row);
            return row;
        });

        ApprovalChainService approvalChain = mock(ApprovalChainService.class);
        when(approvalChain.requiresApproval(anyString(), any(DecisionType.class), anyMap())).thenReturn(false);
        when(approvalChain.findMatchingConfig(anyString(), any(DecisionType.class), anyMap())).thenReturn(Optional.empty());

        UserRepository users = mock(UserRepository.class);
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

        controller = new QualityDispositionController(guard, ruleService, inspectionRepo, auditRepo,
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
}
