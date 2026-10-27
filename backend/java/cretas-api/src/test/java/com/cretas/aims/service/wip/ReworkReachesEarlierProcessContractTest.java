package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回料必须能被<b>靠前的工序</b>领到 —— 否则「不良品变成下一批领得到的回料」这句话是假的。
 *
 * <h2>缺陷 (2026-08-28 对抗性审计 Critical 1)</h2>
 *
 * <p>{@code listWipByFactory} 的阶段过滤是一道<b>防回锅</b>闸:
 * <pre>w.getProcessOrder() != null &amp;&amp; w.getProcessOrder() &lt; maxProcessOrder</pre>
 * 金检 ({@code processOrder=5}) 产出的回料行, {@code processOrder} 被
 * {@code ensureRowExists} 钉成产出它的那一道 = 5。下一批在绞肉 ({@code processOrder=1}) 开投料
 * 下拉, web-admin 传 {@code maxProcessOrder=1} ⇒ {@code 5 < 1} 为假 ⇒
 * <b>回料行不出现在下拉里, 操作员领不到, 且界面上没有任何解释。</b>
 *
 * <p>而回料的业务目的<b>恰恰是</b>跨批次回到靠前的工序 —— 它是「合法的回锅」。
 *
 * <h2>修法 (为什么不是把 processOrder 改成 0)</h2>
 *
 * <p>{@code process_order} 这一列还被 {@code ProcessSheetServiceImpl.getInventoryYieldCard}
 * 用来<b>排序并串起出成率链</b> (排完序后「上一行的 produced = 下一行的投入」)。回料行排到
 * 最前面, 良品那道的 stepYieldRate 会被算成 950/50 = <b>1900%</b>; 另外
 * {@code YieldReportServiceImpl.resolveSourceWipNo/Available} 按
 * {@code processOrder == 本道-1} 找源 WIP, {@code 0} 对不上任何一道。
 *
 * <p>⇒ {@code processOrder} 仍<b>诚实</b>记产出它的那一道; 「这一行是回料」落成
 * {@code semi_finished_inventory.is_rework} 一列, 阶段过滤对它放行。
 *
 * <p>判据来源唯一 = 画布 {@code data.isRework} ({@link com.cretas.aims.service.workflow.WorkflowReworkNodes})
 * 经 {@code workflow_task_ports.material_node_id} 反查 —— ⛔ <b>不信</b>客户端在
 * {@code semi_outputs} 里带上来的那个同名字段 (由 {@link #clientClaimedRework_isNotTrusted()} 钉住)。
 */
@DisplayName("Critical 1: 回料要能被靠前的工序领到 (阶段过滤对回料放行)")
class ReworkReachesEarlierProcessContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 8001L;
    private static final Long TASK_ID = 7001L;
    private static final Long INSTANCE_ID = 84L;

    /** 金检良品 (普通半成品产出端口) */
    private static final String GOOD_CODE = "PTSEMI-LSM-0010";
    private static final String GOOD_SKU = "sku-good-0010";
    /** 美式回料 (画布上打了 isRework 标记的产出端口) */
    private static final String REWORK_CODE = "PTSEMI-LSM-0003";
    private static final String REWORK_SKU = "sku-rework-0003";

    private SemiFinishedInventoryRepository wipRepo;
    private SemiFinishedInventoryTransactionRepository txnRepo;
    private ProductTypeRepository productTypeRepo;
    private ProductionWorkflowInstanceRepository workflowInstanceRepo;
    private WorkflowTaskPortRepository workflowTaskPortRepo;
    private ProductFamilyResolver productFamilyResolver;
    private WipInventoryServiceImpl svc;

    private final Map<String, SemiFinishedInventory> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        wipRepo = mock(SemiFinishedInventoryRepository.class);
        txnRepo = mock(SemiFinishedInventoryTransactionRepository.class);
        productTypeRepo = mock(ProductTypeRepository.class);
        workflowInstanceRepo = mock(ProductionWorkflowInstanceRepository.class);
        workflowTaskPortRepo = mock(WorkflowTaskPortRepository.class);
        productFamilyResolver = mock(ProductFamilyResolver.class);

        svc = new WipInventoryServiceImpl(
                wipRepo,
                txnRepo,
                mock(ProductionReportRepository.class),
                mock(BatchLineageEdgeRepository.class),
                mock(WorkProcessTaskRepository.class),
                mock(WorkProcessRepository.class),
                productTypeRepo,
                workflowInstanceRepo,
                workflowTaskPortRepo,
                productFamilyResolver,
                mock(ApplicationEventPublisher.class), org.mockito.Mockito.mock(com.cretas.aims.service.processentry.IntermediateOwnershipGuard.class));
    }

    // ==================== 半成品行 (下拉数据源) ====================

    private SemiFinishedInventory row(String batchNo, int processOrder, boolean isRework) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setId((long) batchNo.hashCode());
        w.setFactoryId(FACTORY);
        w.setBatchId(BATCH_ID);
        w.setIntermediateBatchNo(batchNo);
        w.setProcessOrder(processOrder);
        w.setProducedQuantity(new BigDecimal("50.00"));
        w.setConsumedQuantity(BigDecimal.ZERO);
        w.setAvailableQuantity(new BigDecimal("50.00"));
        w.setUnit("kg");
        w.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        w.setIsRework(isRework);
        return w;
    }

    /**
     * 🔴 阳性: 金检 (第 5 道) 产出的回料, 在绞肉 (第 1 道) 的投料下拉里必须查得到。
     * 阴性对照同格: 同样挂在第 5 道的<b>普通半成品</b>仍然被防回锅闸挡住 —— 证明闸没被拆掉。
     */
    @Test
    @DisplayName("🔴 C1-a: 回料穿过阶段过滤; 同一道的普通半成品仍被挡住 (阴性对照)")
    void reworkPassesStageFilter_normalSemiStillBlocked() {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY)).thenReturn(List.of(
                row(REWORK_CODE, 5, true),
                row(GOOD_CODE, 5, false)));

        List<WipRowDTO> visible = svc.listWipByFactory(FACTORY, null, 1);

        assertThat(visible).extracting(WipRowDTO::getIntermediateBatchNo)
                .as("回料对下一批而言是期初就在库里的料, 必须能被靠前的绞肉道领到")
                .contains(REWORK_CODE);
        assertThat(visible).extracting(WipRowDTO::getIntermediateBatchNo)
                .as("阴性对照: 防回锅闸对普通半成品仍然有效 —— 拆了闸这条会红")
                .doesNotContain(GOOD_CODE);
    }

    /** 回料行的 processOrder 必须仍是产出它的那一道 (⛔ 不许为了过闸把它改成 0)。 */
    @Test
    @DisplayName("C1-b: 回料行照样报出产出它的那道工序序 (出成率链靠它排序)")
    void reworkRowKeepsItsOriginatingProcessOrder() {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY)).thenReturn(List.of(row(REWORK_CODE, 5, true)));

        List<WipRowDTO> visible = svc.listWipByFactory(FACTORY, null, 1);

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getProcessOrder())
                .as("processOrder 是「谁产出的」, 不是「谁能领」—— 改成 0 会把出成率链的第一段算成 1900%")
                .isEqualTo(5);
    }

    // ==================== 入账时怎么认出「这一行是回料」 ====================

    private WorkProcessTask task() {
        return WorkProcessTask.builder()
                .id(TASK_ID).factoryId(FACTORY).productionBatchId(BATCH_ID)
                .productTypeId("PT-BURGER").processOrder(5).plannedUnit("kg")
                .build();
    }

    private WorkflowTaskPort outputPort(String materialNodeId, String skuId) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setFactoryId(FACTORY);
        p.setWorkflowInstanceId(INSTANCE_ID);
        p.setTaskId(TASK_ID);
        p.setWorkflowPortId("out:" + materialNodeId);
        p.setDirection(WorkflowTaskPort.Direction.OUTPUT);
        p.setOrdinal(0);
        p.setMaterialNodeId(materialNodeId);
        p.setMaterialKind("SEMI_FINISHED");
        p.setSkuId(skuId);
        p.setUnit("kg");
        return p;
    }

    private ProductType semiType(String id, String code, String name) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setFactoryId(FACTORY);
        pt.setCode(code);
        pt.setName(name);
        return pt;
    }

    /** @param nodesJson 画布快照 —— 回料判据的唯一权威源 */
    private void stubCanvas(String nodesJson) {
        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                FACTORY, BATCH_ID, "PT-BURGER", 171L, 3, nodesJson, "[]", LocalDateTime.now());
        instance.setId(INSTANCE_ID);
        when(workflowInstanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, BATCH_ID))
                .thenReturn(Optional.of(instance));
        when(workflowTaskPortRepo.findByFactoryIdAndWorkflowInstanceId(FACTORY, INSTANCE_ID))
                .thenReturn(List.of(
                        outputPort("material:semi:good", GOOD_SKU),
                        outputPort("material:semi:rw", REWORK_SKU)));
        // ⚠️ 桩必须只返回【被问到的那些 id】—— 无条件返回全部会让「问回料端口」也拿到良品那条,
        //    于是良品被误判成回料而断言看起来像产品缺陷 (第一次跑就踩到了, 这是桩的失真不是被测行为)。
        List<ProductType> catalog = List.of(
                semiType(GOOD_SKU, GOOD_CODE, "金检良品"),
                semiType(REWORK_SKU, REWORK_CODE, "美式回料"));
        when(productTypeRepo.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenAnswer(inv -> {
            Collection<String> asked = inv.getArgument(1);
            return catalog.stream().filter(pt -> asked.contains(pt.getId())).collect(Collectors.toList());
        });
    }

    private void stubFirstIn() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(1, String.class))));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(1000L + rows.size());
            rows.put(s.getIntermediateBatchNo(), s);
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.findByFactoryIdAndReportId(eq(FACTORY), anyLong())).thenReturn(Collections.emptyList());
    }

    private ProductionReport reportWith(List<Map<String, Object>> semiOutputs) {
        return ProductionReport.builder()
                .id(5001L).factoryId(FACTORY).batchId(BATCH_ID).workProcessTaskId(TASK_ID)
                .outputKind("SEMI").semiOutputUnit("kg")
                .laborCost(new BigDecimal("40.00")).materialCost(new BigDecimal("60.00"))
                .semiOutputs(semiOutputs)
                .build();
    }

    private static final String CANVAS_WITH_REWORK = """
        [
          {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
          {"id":"material:semi:rw","kind":"SEMI_FINISHED","data":{"name":"美式回料","isRework":true}}
        ]
        """;

    /**
     * 🔴 入账时必须把「这一行是回料」落到行上 —— 否则阶段过滤没有东西可读, C1-a 那条通路是空的。
     * 同时: 回料行的 productTypeId 必须是<b>回料自己</b>的产品 (C-B), 否则 web-admin 的
     * {@code sfiMatchesUpstreamPort} (按上游端口 skuId 白名单过滤) 会把它筛掉 ——
     * 阶段过滤放行了也照样到不了操作员眼前。
     */
    @Test
    @DisplayName("🔴 C1-c: 入账时按画布标记落 is_rework, 且回料行记回料自己的 productTypeId")
    void postingMarksReworkRowFromCanvas() {
        stubCanvas(CANVAS_WITH_REWORK);
        stubFirstIn();

        svc.postApprovedOutput(FACTORY, reportWith(List.of(
                Map.of("semiCode", GOOD_CODE, "quantity", new BigDecimal("950.00")),
                Map.of("semiCode", REWORK_CODE, "quantity", new BigDecimal("50.00")))), task(), 42L);

        ArgumentCaptor<SemiFinishedInventory> cap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, times(2)).save(cap.capture());
        SemiFinishedInventory rework = cap.getAllValues().stream()
                .filter(s -> REWORK_CODE.equals(s.getIntermediateBatchNo())).findFirst().orElseThrow();
        SemiFinishedInventory good = cap.getAllValues().stream()
                .filter(s -> GOOD_CODE.equals(s.getIntermediateBatchNo())).findFirst().orElseThrow();

        assertThat(rework.getIsRework())
                .as("画布把这个产出端口标了 isRework ⇒ 行上必须落下来")
                .isTrue();
        assertThat(rework.getProductTypeId())
                .as("回料行记的是回料自己的产品, ⛔ 不是批次的成品 —— 否则上游端口白名单筛不中它")
                .isEqualTo(REWORK_SKU);
        assertThat(good.getIsRework())
                .as("阴性对照: 同一份报工里的良品端口不能被误标成回料 (误标 = 良品也能回锅)")
                .isFalse();
    }

    /**
     * 阴性对照 (判据来源): 客户端在 {@code semi_outputs} 里自称 {@code isRework=true},
     * 但画布上<b>没有</b>这个标记 ⇒ 服务端不认。
     *
     * <p>这条守的是「判据是画布, 不是请求体」: 若哪天有人图省事改成读 entry 里的 isRework,
     * 这条会红 —— 那等于让客户端自行决定哪些料可以绕过防回锅闸。
     */
    @Test
    @DisplayName("C1-d 阴性对照: 客户端自称回料而画布没标 → 不认")
    void clientClaimedRework_isNotTrusted() {
        stubCanvas("""
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:rw","kind":"SEMI_FINISHED","data":{"name":"美式回料"}}
            ]
            """);
        stubFirstIn();

        svc.postApprovedOutput(FACTORY, reportWith(List.of(
                Map.of("semiCode", REWORK_CODE, "quantity", new BigDecimal("50.00"),
                        "isRework", true))), task(), 42L);

        ArgumentCaptor<SemiFinishedInventory> cap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getIsRework())
                .as("回料判据只认画布 (WorkflowReworkNodes), ⛔ 不认请求体自称的那个字段")
                .isFalse();
        assertThat(cap.getValue().getProductTypeId())
                .as("不是回料 ⇒ productTypeId 维持既有语义 (批次成品), 本次不动它")
                .isEqualTo("PT-BURGER");
    }
}
