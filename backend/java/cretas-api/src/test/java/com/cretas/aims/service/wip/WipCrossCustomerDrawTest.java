package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 缺口 16 跨客户领料 —— WIP 服务的每个领用原语 + 入账落行, 用<b>真的</b>归属闸 (只 mock 仓库)。
 *
 * <p>每个领用点一条阴性 (跨归属 ⇒ 409 {@code INTERMEDIATE_CROSS_OWNER_FORBIDDEN}, 且库存一点没动)
 * + 一条阳性 (同归属照常扣)。入账: 代工计划的产品码产出落到 {@code <产品码>@<客户前8>} 这一行,
 * 不进本厂那一行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WipCrossCustomerDrawTest - 半成品跨客户领料 (缺口 16)")
class WipCrossCustomerDrawTest {

    private static final String F = "LIUSHANMEN";
    private static final String CUST_A = "eb0254ca-4f5a-4062-b81f-de1e8a45fb60";
    private static final Owner OWNER_A = new Owner(InventoryOwnership.CUSTOMER_OWNED, CUST_A);

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock private WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductionPlanRepository planRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private CustomerRepository customerRepo;

    private WipInventoryServiceImpl svc;
    /** 内存里的半成品表: 行号 → 行。 */
    private final Map<String, SemiFinishedInventory> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        IntermediateOwnershipGuard guard = new IntermediateOwnershipGuard(planRepo, batchRepo, customerRepo);
        svc = new WipInventoryServiceImpl(wipRepo, txnRepo, reportRepo, lineageEdgeRepo,
                taskRepo, workProcessRepo, productTypeRepo, workflowInstanceRepo, workflowTaskPortRepo,
                productFamilyResolver, eventPublisher, guard);

        // 计划 toll (代工客户 A) ← 批次 100; 计划 own (本厂) ← 批次 200
        ProductionPlan toll = new ProductionPlan();
        toll.setId("toll");
        toll.setFactoryId(F);
        toll.setOutputOwnership(InventoryOwnership.CUSTOMER_OWNED);
        toll.setCustomerId(CUST_A);
        ProductionPlan own = new ProductionPlan();
        own.setId("own");
        own.setFactoryId(F);
        own.setOutputOwnership(InventoryOwnership.COMPANY_OWNED);
        when(planRepo.findByIdAndFactoryId("toll", F)).thenReturn(Optional.of(toll));
        when(planRepo.findByIdAndFactoryId("own", F)).thenReturn(Optional.of(own));
        ProductionBatch b100 = new ProductionBatch();
        b100.setId(100L);
        b100.setProductionPlanId("toll");
        ProductionBatch b200 = new ProductionBatch();
        b200.setId(200L);
        b200.setProductionPlanId("own");
        when(batchRepo.findByIdAndFactoryId(100L, F)).thenReturn(Optional.of(b100));
        when(batchRepo.findByIdAndFactoryId(200L, F)).thenReturn(Optional.of(b200));

        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(F), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(1))));
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(F), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(1))));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId((long) (rows.size() + 1));
            rows.put(s.getIntermediateBatchNo(), s);
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wipRepo.findByIdForUpdate(anyLong())).thenAnswer(inv -> rows.values().stream()
                .filter(r -> r.getId().equals(inv.<Long>getArgument(0))).findFirst());
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.findByFactoryIdAndReportId(eq(F), anyLong())).thenReturn(List.of());
    }

    private SemiFinishedInventory stock(String no, InventoryOwnership ownership, String customerId, String qty) {
        SemiFinishedInventory s = SemiFinishedInventory.builder()
                .id((long) (rows.size() + 1)).factoryId(F).intermediateBatchNo(no).productTypeId("PT-SEMI")
                .unit("kg").producedQuantity(new BigDecimal(qty)).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal(qty)).status(SemiFinishedInventory.Status.AVAILABLE)
                .ownership(ownership).ownerCustomerId(customerId).build();
        rows.put(no, s);
        return s;
    }

    private static void assertCrossOwner(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CROSS_OWNER));
    }

    @Test
    @DisplayName("小结 / 结单 / 逐道录入的严格扣减: 客户 A 的料不能扣进本厂计划, 一点没扣; 扣进 A 的计划照常")
    void consumeClerkSemiStrict() {
        SemiFinishedInventory a = stock("S-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A, "10");
        assertCrossOwner(() -> svc.consumeClerkSemiStrict(F, Owner.COMPANY, "S-A", new BigDecimal("3")));
        assertThat(a.getConsumedQuantity()).isEqualByComparingTo("0");

        assertThat(svc.consumeClerkSemiStrict(F, OWNER_A, "S-A", new BigDecimal("3"))).isEqualByComparingTo("3");
        assertThat(a.getAvailableQuantity()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("App 报工选源半成品: 本厂的料不能领进代工计划; 本厂计划照常")
    void validateSourceWip() {
        stock("S-C", InventoryOwnership.COMPANY_OWNED, null, "10");
        assertCrossOwner(() -> svc.validateSourceWip(F, OWNER_A, "S-C", new BigDecimal("2"), "kg", null));
        assertThat(svc.validateSourceWip(F, Owner.COMPANY, "S-C", new BigDecimal("2"), "kg", null)).isNotNull();
    }

    @Test
    @DisplayName("二次加工开工扣减: 客户 A 的料不能扣进本厂的二次加工计划")
    void deductForSecondaryPlan() {
        SemiFinishedInventory a = stock("S-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A, "10");
        assertCrossOwner(() -> svc.deductForSecondaryPlan(a.getId(), new BigDecimal("1"), F, 1L, Owner.COMPANY));
        assertThat(a.getConsumedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("投料下拉: 带领用归属只列同归属的行; 不带 = 库存总览全列")
    void dropdownFiltersByOwner() {
        SemiFinishedInventory a = stock("S-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A, "10");
        SemiFinishedInventory c = stock("S-C", InventoryOwnership.COMPANY_OWNED, null, "10");
        SemiFinishedInventory legacy = stock("S-L", null, null, "10");
        when(wipRepo.findByFactoryIdForWeightView(F)).thenReturn(List.of(a, c, legacy));

        assertThat(svc.listWipByFactory(F, null, null, OWNER_A))
                .extracting(r -> r.getIntermediateBatchNo()).containsExactly("S-A");
        assertThat(svc.listWipByFactory(F, null, null, Owner.COMPANY))
                .extracting(r -> r.getIntermediateBatchNo()).containsExactly("S-C", "S-L");
        assertThat(svc.listWipByFactory(F, null, null, null)).hasSize(3);
    }

    @Test
    @DisplayName("产品码共用行入账: 代工计划的产出落到 <产品码>@<客户前8>, 本厂的落产品码那一行, 两行各自盖章")
    void pooledSemiOutputSplitsByOwner() {
        svc.postApprovedOutput(F, semiReport(1L, 100L), task(11L, 100L), 9L);
        svc.postApprovedOutput(F, semiReport(2L, 200L), task(12L, 200L), 9L);

        SemiFinishedInventory customerRow = rows.get("PTSEMI-LSM-0005@eb0254ca");
        SemiFinishedInventory companyRow = rows.get("PTSEMI-LSM-0005");
        assertThat(customerRow).isNotNull();
        assertThat(customerRow.getOwnership()).isEqualTo(InventoryOwnership.CUSTOMER_OWNED);
        assertThat(customerRow.getOwnerCustomerId()).isEqualTo(CUST_A);
        assertThat(customerRow.getProducedQuantity()).isEqualByComparingTo("5");
        assertThat(companyRow).isNotNull();
        assertThat(companyRow.getOwnership()).isEqualTo(InventoryOwnership.COMPANY_OWNED);
        assertThat(companyRow.getProducedQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("入账撞上别人的行 (旧数据 / 前缀撞号) ⇒ 409, 不混进去")
    void creditIntoForeignRowRejected() {
        stock("PTSEMI-LSM-0005", InventoryOwnership.CUSTOMER_OWNED, CUST_A, "1");
        assertThatThrownBy(() -> svc.postApprovedOutput(F, semiReport(3L, 200L), task(13L, 200L), 9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_POOL_OWNER_MISMATCH));
    }

    private static WorkProcessTask task(long id, long batchId) {
        return WorkProcessTask.builder().id(id).factoryId(F).productionBatchId(batchId)
                .productTypeId("PT-FG").processOrder(8).plannedUnit("kg").build();
    }

    private static ProductionReport semiReport(long id, long batchId) {
        return ProductionReport.builder().id(id).factoryId(F).batchId(batchId)
                .outputKind("SEMI").semiCode("PTSEMI-LSM-0005")
                .semiOutputQuantity(new BigDecimal("5")).semiOutputUnit("kg").build();
    }
}
