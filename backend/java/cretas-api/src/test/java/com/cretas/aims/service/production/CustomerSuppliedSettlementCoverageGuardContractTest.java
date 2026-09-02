package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷 48 回归：本闸的方向曾经是反的（要求单个批次一次性报完整张 SO 客供清单的全部数量），
 * 见 {@link CustomerSuppliedSettlementCoverageGuard} 类注释。这份测试钉住修好之后的两条判据：
 * 「用的料必须在清单里」+「同一种料跨已结单批次累计不能超过已收数量」，以及 SO 清单里没用到
 * /只用了一部分的料不产生任何提示。
 */
class CustomerSuppliedSettlementCoverageGuardContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "plan-a";
    private static final String ORDER = "order-a";

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository =
            mock(SalesOrderSuppliedMaterialRequirementRepository.class);
    private final MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
    private final ProductionSettlementConsumptionRepository settlementConsumptionRepository =
            mock(ProductionSettlementConsumptionRepository.class);
    private final RawMaterialTypeRepository rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
    private final CustomerSuppliedSettlementCoverageGuard guard =
            new CustomerSuppliedSettlementCoverageGuard(
                    planRepository, requirementRepository, materialBatchRepository,
                    settlementConsumptionRepository, rawMaterialTypeRepository);

    private Logger guardLogger;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 缺陷48终审②：批次归属登记表，喂给 {@code materialBatchRepository.findAllById(...)} 的
     * mock 回答——{@link CustomerSuppliedSettlementCoverageGuard#filterCustomerOwned} 批量按
     * materialBatchId 查一次，这里模拟同一份批量查询行为（不逐条 stub，与生产代码一致）。
     */
    private final Map<String, MaterialBatch> knownBatches = new LinkedHashMap<>();

    @BeforeEach
    void noSiblingPlansByDefault() {
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER)).thenReturn(List.of());
        when(materialBatchRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            List<MaterialBatch> found = new ArrayList<>();
            for (String id : ids) {
                MaterialBatch batch = knownBatches.get(id);
                if (batch != null) {
                    found.add(batch);
                }
            }
            return found;
        });
        guardLogger = (Logger) LoggerFactory.getLogger(CustomerSuppliedSettlementCoverageGuard.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        guardLogger.addAppender(logAppender);
        guardLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachLogAppender() {
        guardLogger.detachAppender(logAppender);
    }

    private List<String> warnLines() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ------------------------------------------------------------------
    // audit() — 核对结单弹窗
    // ------------------------------------------------------------------

    @Test
    void prodShapePartialUseOfOneOfTwoCustomerMaterialsStaysClean() {
        // PLAN-1788280683352-34F8CD82 的真实形状: SO 有两种客供料 (1399上脑/50CL),
        // 这一批 BOM 只含 1399上脑, 且只用掉 5900kg 里的 100kg —— 不该有任何提示。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("pork-1399", "1399上脑", "5900", "5900", "kg"),
                        requirement("beef-50cl", "50CL", "3000", "3000", "kg")));

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "100")));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
        guard.requireCleanForOneClick(audited);
    }

    @Test
    void companyOwnedAuxiliaryLinesNeverTriggerAnyMessageEvenWhenNotOnTheCustomerList() {
        // 缺陷48终审②(2026-09, prod PLAN-...-34F8CD82 实测): 同一批实际报工里, 客供
        // 1399上脑(CUSTOMER_OWNED) 与工厂自购的冰水/L1纸箱(COMPANY_OWNED) 混在一起——
        // 后两者根本不在任何客供清单里, 规则①②只该管前者。阳性对照。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill(
                line("pork-1399", "100"),
                companyOwnedLine("ice-water", "20", "kg"),
                companyOwnedLine("l1-carton", "6.94", "个")));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
        guard.requireCleanForOneClick(audited);
    }

    @Test
    void overageMessageNeverNamesCompanyOwnedMaterialsEvenWhenACustomerMaterialIsOverReceived() {
        // 「只断言没拦」不够——还要断言消息文本里真的没提到工厂自有料的名字, 否则一个
        // 把过滤写反/漏掉的实现也可能在这条阳性对照上侥幸通过(见交接说明)。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER))
                .thenReturn(List.of(plan("plan-b", MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(settlementConsumptionRepository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                FACTORY, List.of("plan-b")))
                .thenReturn(List.of(settledConsumption("pork-1399", "5850")));
        // 就算过滤被写反/漏掉让 ice-water 混进 foreign 名单, 它的显示名也会解析成「冰水」——
        // 这样断言「消息不含冰水」才是真的在守规则, 不是碰巧没人给这个 id 起中文名。
        when(rawMaterialTypeRepository.findByIdIn(Set.of("ice-water")))
                .thenReturn(List.of(rawMaterialType("ice-water", "冰水")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill(
                line("pork-1399", "100"),
                companyOwnedLine("ice-water", "20", "kg")));

        assertFalse(audited.getAudit().isClean());
        String allMessages = audited.getAudit().getIssues().stream()
                .map(ProductionSettlementPrefillResponse.Issue::getMessage)
                .collect(Collectors.joining(" | "));
        assertTrue(allMessages.contains("1399上脑"), allMessages);
        assertFalse(allMessages.contains("冰水"), allMessages);
    }

    @Test
    void materialNotOnTheOrdersSuppliedListBlocksAndNamesIt() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        when(rawMaterialTypeRepository.findByIdIn(Set.of("borrowed-lamb")))
                .thenReturn(List.of(rawMaterialType("borrowed-lamb", "别家订单的羊肉")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN,
                prefill(line("pork-1399", "100"), line("borrowed-lamb", "5")));

        assertFalse(audited.getAudit().isClean());
        assertEquals(1, audited.getAudit().getIssues().size());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, issue.getCode());
        assertEquals(ProductionSettlementPrefillResponse.Severity.BLOCKER, issue.getSeverity());
        assertTrue(issue.getMessage().contains("客供清单之外的料"));
        assertTrue(issue.getMessage().contains("别家订单的羊肉"));
        assertFalse(issue.getMessage().contains("1399上脑"));
        assertThrows(BusinessException.class, () -> guard.requireCleanForOneClick(audited));
    }

    @Test
    void cumulativeConsumptionAcrossSettledSiblingPlansOverReceivedBlocksWithNumbers() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionPlan sibling = plan("plan-b", MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER)).thenReturn(List.of(sibling, plan(PLAN, MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(settlementConsumptionRepository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                FACTORY, List.of("plan-b")))
                .thenReturn(List.of(settledConsumption("pork-1399", "5850")));

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "100")));

        assertFalse(audited.getAudit().isClean());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED, issue.getCode());
        assertTrue(issue.getMessage().contains("累计已用 5950"), issue.getMessage());
        assertTrue(issue.getMessage().contains("超过已收 5900"), issue.getMessage());
    }

    @Test
    void mismatchedUnitBetweenConsumedAndReceivedSkipsTheCumulativeCheckAndWarns() {
        // 终审订正: 旧实现按 (materialTypeId, unit) 建 key, 新实现只按 materialTypeId 聚合就
        // 把单位丢了 —— 已收按 kg 记, 这批却按「个」报, 数值直接相减比较会双向坏(小单位数值大
        // 就假拦, 大单位数值小就放过超收)。单位不一致时必须不拦, 按硬约束4 当「没量到」处理。
        //
        // #496 non-blocking review ①: 「不拦」不等于「什么都不说」——audit() 弹窗现在会带一条
        // INFO 提示(不影响 clean/一键确认), 讲清楚这种料本次没做累计校验。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "9999", "个")));

        assertTrue(audited.getAudit().isClean(), "单位不一致不该拦, 应当当「没量到」处理");
        assertEquals(1, audited.getAudit().getIssues().size());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_UNIT_MISMATCH, issue.getCode());
        assertEquals(ProductionSettlementPrefillResponse.Severity.INFO, issue.getSeverity(),
                "INFO 级——不该让 clean 变 false");
        assertTrue(issue.getMessage().contains("1399上脑"), issue.getMessage());
        assertTrue(issue.getMessage().contains("kg") && issue.getMessage().contains("个"), issue.getMessage());
        assertTrue(issue.getMessage().contains("未做累计校验"), issue.getMessage());
        assertTrue(warnLines().stream().anyMatch(msg -> msg.contains("pork-1399")
                        && msg.contains("kg") && msg.contains("个")),
                "单位不一致时必须留 warn 让人核对口径, 实际打的 WARN 行: " + warnLines());
    }

    @Test
    void mismatchedUnitInfoIssueDoesNotDuplicateOnRepeatedAudit() {
        // 与 duplicateAuditDoesNotDuplicateTheBlocker 同形状, 只是这次守的是 INFO 级 issue。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionSettlementPrefillResponse response = prefill(line("pork-1399", "9999", "个"));

        guard.audit(FACTORY, PLAN, response);
        guard.audit(FACTORY, PLAN, response);

        assertEquals(1, response.getAudit().getIssues().stream()
                .filter(issue -> CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_UNIT_MISMATCH
                        .equals(issue.getCode()))
                .count());
    }

    @Test
    void unresolvableCustomerSuppliedBatchIsWarnedNotSilentlySkippedLikeLegacyNullOwnership() {
        // #496 non-blocking review ②: 批次查不到(跨工厂/脏数据) 与 「查到了但 ownership=null
        // (老批次, 按设计视为工厂自有)」是两件不同的事——前者是数据缺口, 必须留痕。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        // 故意不 registerBatch —— materialBatchRepository.findAllById(...) 的默认 mock 找不到它。
        ProductionSettlementRequest.ConsumptionLine unresolvable =
                ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("ghost-batch").materialTypeId("pork-1399")
                        .quantity(new BigDecimal("5")).unit("kg").build();

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill(unresolvable));

        // 查不到的批次不计入客供口径核算 (与 null-ownership 一样被过滤掉), 但必须留下 warn 痕迹。
        assertTrue(audited.getAudit().isClean());
        assertTrue(warnLines().stream().anyMatch(msg -> msg.contains("ghost-batch") && msg.contains(FACTORY)),
                "批次不可解析必须留 warn, 实际打的 WARN 行: " + warnLines());
    }

    @Test
    void legacyNullOwnershipBatchIsSkippedSilentlyWithoutTheUnresolvableWarning() {
        // 阴性对照: 查得到批次、只是 ownership=null (老批次, 按设计当工厂自有) —— 不该触发
        // 「批次不可解析」的 warn, 这是 unresolvableCustomerSuppliedBatchIsWarnedNotSilentlySkipped...
        // 要守住的边界: 两种「不算客供」的成因必须留下不同的痕迹(或不留痕迹)。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        registerBatch("legacy-batch", "pork-1399", null);
        ProductionSettlementRequest.ConsumptionLine legacyLine =
                ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("legacy-batch").materialTypeId("pork-1399")
                        .quantity(new BigDecimal("5")).unit("kg").build();

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill(legacyLine));

        assertTrue(audited.getAudit().isClean());
        assertTrue(warnLines().stream().noneMatch(msg -> msg.contains("批次不可解析")),
                "ownership=null 是已知的 legacy 形态, 不该触发「批次不可解析」的 warn: " + warnLines());
    }

    @Test
    void unusedAndPartiallyUsedOrderMaterialsProduceNoMessage() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("pork-1399", "1399上脑", "5900", "5900", "kg"),
                        requirement("box", "成品盒", "1", "1", "盒")));

        // 完全没用 box, 只用了 1399上脑 的一部分 —— 两者都不该产生提示。
        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "1")));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
    }

    @Test
    void factorySuppliedPlanIsOutsideThisGuard() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.FACTORY_SUPPLIED)));
        ProductionSettlementPrefillResponse response = prefill();

        assertTrue(guard.audit(FACTORY, PLAN, response).getAudit().isClean());
        verify(requirementRepository, never())
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER);
    }

    @Test
    void customerSuppliedPlanWithNoSuppliedMaterialRequirementsAtAllIsANoOp() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of());

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("anything", "1")));

        assertTrue(audited.getAudit().isClean());
    }

    @Test
    void duplicateAuditDoesNotDuplicateTheBlocker() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("box", "成品盒", "1", "1", "盒")));
        ProductionSettlementPrefillResponse response = prefill(line("box", "2", "盒"));

        guard.audit(FACTORY, PLAN, response);
        guard.audit(FACTORY, PLAN, response);

        assertEquals(1, response.getAudit().getIssues().stream()
                .filter(issue -> CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED
                        .equals(issue.getCode()))
                .count());
    }

    // ------------------------------------------------------------------
    // mergeMissingManualRawConsumptions() — 结单写路径, 必须与 audit() 同一份口径
    // ------------------------------------------------------------------

    @Test
    void mergeAllowsProdShapeCustomerAndCompanyOwnedMixWithoutBlocking() {
        // 真正结单的写路径必须和 audit() 弹窗同一个结论——这是这次事故实际打到的入口
        // (settleProduction -> mergeMissingManualRawConsumptions), 不是弹窗本身。
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-pork", "pork-1399", "100", "kg"))))
                .build();
        registerBatch("batch-pork", "pork-1399", InventoryOwnership.CUSTOMER_OWNED);
        registerBatch("batch-ice", "ice-water", InventoryOwnership.COMPANY_OWNED);
        registerBatch("batch-carton", "l1-carton", InventoryOwnership.COMPANY_OWNED);
        derived.getRawMaterialConsumptions().add(
                ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("batch-ice").materialTypeId("ice-water")
                        .quantity(new BigDecimal("20")).unit("kg").build());
        derived.getRawMaterialConsumptions().add(
                ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId("batch-carton").materialTypeId("l1-carton")
                        .quantity(new BigDecimal("6.94")).unit("个").build());
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of()).build();

        guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted);

        assertEquals(3, derived.getRawMaterialConsumptions().size());
    }

    @Test
    void mergeAddsCustomerBatchWithinRemainingAllowance() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("raw-a", "原料A", "0.21", "0.21", "kg"),
                        requirement("box", "成品盒", "1", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));
        ProductionSettlementRequest.ConsumptionLine reported = consumptionLine("batch-a", "raw-a", "0.21", "kg");
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(reported))).build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(
                        consumptionLine("batch-a", "spoofed", "0.21", "kg"),
                        consumptionLine("batch-box", "spoofed", "1", "kg")))
                .build();

        guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted);

        assertEquals(2, derived.getRawMaterialConsumptions().size());
        assertEquals("raw-a", derived.getRawMaterialConsumptions().get(0).getMaterialTypeId());
        ProductionSettlementRequest.ConsumptionLine added = derived.getRawMaterialConsumptions().get(1);
        assertEquals("box", added.getMaterialTypeId());
        assertEquals("盒", added.getUnit());
        assertEquals("MB-batch-box", added.getBatchNumber());
    }

    @Test
    void mergeRejectsAnyAttemptToOverrideReportedQuantity() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("raw-a", "原料A", "0.21", "0.21", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-a", "raw-a", "0.21", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(consumptionLine("batch-a", "raw-a", "0.22", "kg")))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals("CUSTOMER_SUPPLIED_REPORTED_FACT_OVERRIDE", ex.getErrorCode());
        assertEquals(1, derived.getRawMaterialConsumptions().size());
        verify(materialBatchRepository, never()).findByIdAndFactoryId("batch-a", FACTORY);
    }

    @Test
    void mergeRejectsManualBatchNotOnTheListAndOverGapBatch() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("box", "成品盒", "1", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-extra", FACTORY))
                .thenReturn(Optional.of(batch("batch-extra", "not-required", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));

        BusinessException extra = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(consumptionLine("batch-extra", "not-required", "1", "盒"))).build()));
        BusinessException over = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(consumptionLine("batch-box", "box", "2", "盒"))).build()));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, extra.getErrorCode());
        assertEquals("CUSTOMER_SUPPLIED_MANUAL_QUANTITY_EXCEEDS_GAP", over.getErrorCode());
    }

    @Test
    void mergeFinalEvaluationCatchesForeignMaterialAlreadyInTheAutoDerivedLines() {
        // 「拿别人的料做这单」不止发生在手工补录 —— 逐道报工本身也可能带出一行不在
        // 客供清单里的批次, 最终整批校验必须照样拦, 不能只查 submitted 里新加的那几行。
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-foreign", "borrowed-lamb", "5", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of()).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, ex.getErrorCode());
    }

    @Test
    void mergeFinalEvaluationCatchesCumulativeOverageAcrossSettledSiblingPlans() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER))
                .thenReturn(List.of(plan("plan-b", MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(settlementConsumptionRepository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                FACTORY, List.of("plan-b")))
                .thenReturn(List.of(settledConsumption("pork-1399", "5850")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-a", "pork-1399", "100", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of()).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("5950"), ex.getMessage());
    }

    @Test
    void mergeSkipsTheGapCheckOnUnitMismatchInsteadOfBlockingOrSilentlyPassing() {
        // 已收按「盒」记, 这个批次自己按「个」记 —— 数值上 50 远超剩余额度 1, 若单位不看会
        // 被误拦(EXCEEDS_GAP); 单位一致性判断必须先拦住这条比较本身, 不拦这一笔手工补录,
        // 只留 warn。写路径与 audit() 弹窗必须是同一份口径(形态 D)。
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("box", "成品盒", "1", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-loose", FACTORY))
                .thenReturn(Optional.of(batch("batch-loose", "box", "个")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder().build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(consumptionLine("batch-loose", "box", "50", "个")))
                .build();

        guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted);

        assertEquals(1, derived.getRawMaterialConsumptions().size());
        assertEquals("个", derived.getRawMaterialConsumptions().get(0).getUnit());
        assertTrue(warnLines().stream().anyMatch(msg -> msg.contains("box")
                        && msg.contains("盒") && msg.contains("个")),
                "单位不一致时必须留 warn, 实际打的 WARN 行: " + warnLines());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ProductionPlan plan(MaterialSupplyMode supplyMode) {
        return plan(PLAN, supplyMode);
    }

    private static ProductionPlan plan(String id, MaterialSupplyMode supplyMode) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY);
        plan.setSourceOrderId(ORDER);
        plan.setMaterialSupplyMode(supplyMode);
        plan.setPlannedQuantity(BigDecimal.ONE);
        return plan;
    }

    private static SalesOrderSuppliedMaterialRequirement requirement(String typeId,
                                                                       String name,
                                                                       String expectedQuantity,
                                                                       String receivedQuantity,
                                                                       String unit) {
        SalesOrderSuppliedMaterialRequirement requirement = new SalesOrderSuppliedMaterialRequirement();
        requirement.setFactoryId(FACTORY);
        requirement.setSalesOrderId(ORDER);
        requirement.setMaterialTypeId(typeId);
        requirement.setMaterialName(name);
        requirement.setExpectedQuantity(new BigDecimal(expectedQuantity));
        requirement.setReceivedQuantity(new BigDecimal(receivedQuantity));
        requirement.setUnit(unit);
        requirement.setExpectedArrivalAt(LocalDateTime.now());
        return requirement;
    }

    private static RawMaterialType rawMaterialType(String id, String name) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setFactoryId(FACTORY);
        type.setName(name);
        return type;
    }

    /** 默认客供（CUSTOMER_OWNED）——本测试文件里绝大多数批次原本就代表客供物料。 */
    private static MaterialBatch batch(String id, String materialTypeId, String unit) {
        return batch(id, materialTypeId, unit, InventoryOwnership.CUSTOMER_OWNED);
    }

    private static MaterialBatch batch(String id, String materialTypeId, String unit,
                                        InventoryOwnership ownership) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setMaterialTypeId(materialTypeId);
        batch.setBatchNumber("MB-" + id);
        batch.setQuantityUnit(unit);
        batch.setWarehouseId("WH-RAW");
        batch.setOwnership(ownership);
        return batch;
    }

    private static ProductionSettlementConsumption settledConsumption(String materialTypeId, String quantity) {
        ProductionSettlementConsumption row = new ProductionSettlementConsumption();
        row.setFactoryId(FACTORY);
        row.setProductionPlanId("plan-b");
        row.setSourceType("RAW_MATERIAL");
        row.setMaterialTypeId(materialTypeId);
        row.setQuantity(new BigDecimal(quantity));
        row.setUnit("kg");
        return row;
    }

    private MaterialBatch registerBatch(String id, String materialTypeId, InventoryOwnership ownership) {
        MaterialBatch registered = batch(id, materialTypeId, "kg", ownership);
        knownBatches.put(id, registered);
        return registered;
    }

    /** derived（auto-derived from 逐道报工）用的消耗行——批次默认客供，与既有测试原意一致。 */
    private ProductionSettlementRequest.ConsumptionLine consumptionLine(
            String batchId, String materialTypeId, String quantity, String unit) {
        if (!knownBatches.containsKey(batchId)) {
            registerBatch(batchId, materialTypeId, InventoryOwnership.CUSTOMER_OWNED);
        }
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialBatchId(batchId)
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    /**
     * Prefill 用的报工带入行——自动登记一个 CUSTOMER_OWNED 批次（缺陷48终审②之前, 这里的
     * 行天然就代表客供物料; 现在规则①②只认批次 ownership, 所以显式登记一个批次让
     * {@code filterCustomerOwned} 能查到）。
     */
    private ProductionSettlementRequest.ConsumptionLine line(String materialTypeId, String quantity) {
        return line(materialTypeId, quantity, "kg");
    }

    private ProductionSettlementRequest.ConsumptionLine line(
            String materialTypeId, String quantity, String unit) {
        String batchId = "cust-batch-" + materialTypeId;
        if (!knownBatches.containsKey(batchId)) {
            registerBatch(batchId, materialTypeId, InventoryOwnership.CUSTOMER_OWNED);
        }
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialBatchId(batchId)
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    /**
     * 工厂自有（COMPANY_OWNED）消耗行——缺陷48终审②的核心夹具：prod 实测同一批次报工里
     * 混着客供 1399上脑与工厂自购的冰水/L1纸箱，规则①②必须只管前者、对后者零提示。
     */
    private ProductionSettlementRequest.ConsumptionLine companyOwnedLine(
            String materialTypeId, String quantity, String unit) {
        String batchId = "co-batch-" + materialTypeId;
        if (!knownBatches.containsKey(batchId)) {
            registerBatch(batchId, materialTypeId, InventoryOwnership.COMPANY_OWNED);
        }
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialBatchId(batchId)
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    private static ProductionSettlementPrefillResponse prefill(ProductionSettlementRequest.ConsumptionLine... lines) {
        List<ProductionSettlementRequest.ConsumptionLine> list = new ArrayList<>(List.of(lines));
        return ProductionSettlementPrefillResponse.builder()
                .prefill(ProductionSettlementRequest.builder()
                        .rawMaterialConsumptions(list)
                        .build())
                .audit(ProductionSettlementPrefillResponse.Audit.builder()
                        .clean(true)
                        .issues(new ArrayList<>())
                        .build())
                .build();
    }
}
