package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 份额收敛票 (2026-09-02): 领料明细的主料份额从哪来。
 *
 * <p>① 计划已转批次且实例已物化 ⇒ 编译列 (与报工同源) —— 阴性: 改了修订 nodesJson 但没重编译, 领料<b>不变</b>;
 * ② 否则 ⇒ 修订 nodesJson, 解析走编译器同一个 parsePercent —— 越界份额 ⇒ 该端口无份额, 不拦单;
 * ③ 没接 WorkflowClerkSheetService (老装配) ⇒ 只走 nodesJson, 行为与 #503 一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactoryMaterialRequisitionInputShareSourceTest {

    private static final String FACTORY_ID = "F-SHARE";
    private static final String PLAN_ID = "plan-share-1";
    private static final String PRODUCT_TYPE_ID = "pt-burger";
    private static final Long REVISION_ID = 315L;

    @Mock private FactoryMaterialRequisitionRepository repository;
    @Mock private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private FactoryWarehouseRepository warehouseRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionMaterialReturnRepository productionMaterialReturnRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private ProductProcessWorkflowRevisionRepository workflowRevisionRepository;
    @Mock private WorkflowClerkSheetService workflowClerkSheetService;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "workflowRevisionRepository", workflowRevisionRepository);
        ReflectionTestUtils.setField(service, "workflowClerkSheetService", workflowClerkSheetService);

        // 🔴 2026-09-05: 那段两级解析已从本 service 的 private 方法抽到 PlanInputShareResolver
        //    (缺料预警要用同一份份额, 复制过去必漂)。本测试守的三条 ①②③ 一条没变, 只是
        //    接缝挪了 —— 所以这里装一个**真的** resolver, 把同样的两个 mock 喂给它,
        //    ⛔ 不 mock 掉 resolver: mock 掉就等于把本测试真正要守的那段逻辑跳过去了,
        //    四条断言会变成恒真式。
        com.cretas.aims.service.workflow.PlanInputShareResolver shareResolver =
                new com.cretas.aims.service.workflow.PlanInputShareResolver();
        ReflectionTestUtils.setField(shareResolver, "workflowClerkSheetService", workflowClerkSheetService);
        ReflectionTestUtils.setField(shareResolver, "workflowRevisionRepository", workflowRevisionRepository);
        ReflectionTestUtils.setField(service, "planInputShareResolver", shareResolver);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PLAN-SHARE-001");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setSelectedWorkflowRevisionId(REVISION_ID);
        plan.setExpectedCompletionDate(LocalDate.now().plusDays(3));
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));

        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(rawBom(1L, "2082上脑", "in:a"), rawBom(2L, "1399上脑", "in:b")));
        when(warehouseRepository.findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID, WarehouseType.WORKSHOP))
                .thenReturn(List.of(warehouse("wh-workshop", WarehouseType.WORKSHOP)));
        when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn("wh-raw");
        when(repository.countByFactoryIdAndRequisitionNoPrefix(eq(FACTORY_ID), any())).thenReturn(0L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 修订 nodesJson: in:a=30 / in:b=70 (转批次前的来源)
        when(workflowRevisionRepository.findById(REVISION_ID)).thenReturn(Optional.of(revision(nodes(30, 70))));
        // 默认: 还没转批次 ⇒ 没有编译列
        when(workflowClerkSheetService.resolveCompiledInputShares(FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
    }

    private static String nodes(Object a, Object b) {
        return "[{\"id\":\"p1\",\"kind\":\"PROCESS\",\"data\":{\"ports\":["
                + "{\"id\":\"in:a\",\"direction\":\"INPUT\",\"inputShareRatio\":" + a + "},"
                + "{\"id\":\"in:b\",\"direction\":\"INPUT\",\"inputShareRatio\":" + b + "}]}}]";
    }

    private static ProductProcessWorkflowRevision revision(String nodesJson) {
        ProductProcessWorkflowRevision rev = new ProductProcessWorkflowRevision();
        rev.setFactoryId(FACTORY_ID);
        rev.setNodesJson(nodesJson);
        return rev;
    }

    private static BomRecipeItem rawBom(Long id, String name, String portId) {
        BomRecipeItem bom = new BomRecipeItem();
        bom.setId(id);
        bom.setMaterialTypeId("MAT-" + id);
        bom.setMaterialName(name);
        bom.setUnit("kg");
        bom.setMaterialCategory("RAW");
        bom.setWorkflowInputPortId(portId);
        return bom;
    }

    private static FactoryWarehouse warehouse(String id, WarehouseType type) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setFactoryId(FACTORY_ID);
        w.setCode(id);
        w.setName(id);
        w.setType(type);
        w.setIsActive(true);
        return w;
    }

    private static Map<String, BigDecimal> sharesByPort(FactoryMaterialRequisition mr) {
        return mr.getItems().stream()
                .filter(i -> i.getMaterialCategory() == FactoryMaterialRequisitionItem.MaterialCategory.RAW)
                .collect(java.util.stream.Collectors.toMap(FactoryMaterialRequisitionItem::getMaterialName,
                        i -> i.getInputShareRatio() == null ? BigDecimal.valueOf(-1) : i.getInputShareRatio()));
    }

    @Test
    @DisplayName("已转批次且实例已物化 ⇒ 读编译列; 阴性: 修订 nodesJson 改成 10/90 没重编译, 领料仍是 30/70")
    void compiledColumnWinsAndStaleNodesJsonDoesNotLeak() {
        when(workflowClerkSheetService.resolveCompiledInputShares(FACTORY_ID, PLAN_ID))
                .thenReturn(Optional.of(Map.of("in:a", new BigDecimal("30"), "in:b", new BigDecimal("70"))));
        when(workflowRevisionRepository.findById(REVISION_ID)).thenReturn(Optional.of(revision(nodes(10, 90))));

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        Map<String, BigDecimal> shares = sharesByPort(mr);
        assertThat(shares.get("2082上脑")).isEqualByComparingTo("30");
        assertThat(shares.get("1399上脑")).isEqualByComparingTo("70");
        verify(workflowRevisionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("还没转批次 (Optional.empty) ⇒ 读修订 nodesJson: 30/70 (#503 判据不动)")
    void fallsBackToRevisionBeforeBatchExists() {
        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        Map<String, BigDecimal> shares = sharesByPort(mr);
        assertThat(shares.get("2082上脑")).isEqualByComparingTo("30");
        assertThat(shares.get("1399上脑")).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("老装配 (没接 WorkflowClerkSheetService) ⇒ 只走 nodesJson, 行为与 #503 一致")
    void withoutClerkSheetServiceStillReadsRevision() {
        // 老装配 = 没接 WorkflowClerkSheetService。2026-09-05 起这个依赖在 PlanInputShareResolver 上,
        // 所以要置空的是它那一份 —— 置 service 上的那个已经不起作用了 (它不再参与份额解析)。
        ReflectionTestUtils.setField(service, "workflowClerkSheetService", null);
        ReflectionTestUtils.setField(
                ReflectionTestUtils.getField(service, "planInputShareResolver"),
                "workflowClerkSheetService", null);

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertThat(sharesByPort(mr).get("2082上脑")).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("漂移闸 (领料侧): 修订里 in:a=150 (编译器会拒发布) ⇒ 领料该端口无份额, 另一端口照收, 不拦单")
    void outOfRangeShareInRevisionIsDroppedNotAccepted() {
        when(workflowRevisionRepository.findById(REVISION_ID)).thenReturn(Optional.of(revision(nodes(150, 70))));

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        Map<String, BigDecimal> shares = sharesByPort(mr);
        assertThat(shares.get("2082上脑")).as("越界份额不能被领料照收成 150").isEqualByComparingTo("-1");
        assertThat(shares.get("1399上脑")).isEqualByComparingTo("70");
    }
}
