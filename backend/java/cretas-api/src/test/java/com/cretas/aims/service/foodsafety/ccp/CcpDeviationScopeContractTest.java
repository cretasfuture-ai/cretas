package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.quality.release.QcDeviationHold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 13 —— CCP 偏离只管<b>出事这一批 + 工艺图下游</b> (Steve 2026-11-03)。
 *
 * <p>形状照 prod F006 计划 58c806d3 实读: 5 道直链 (修油 → 滚揉 → … → 包装), 第 1 道另出一个副产 bp;
 * 第 2 道 (滚揉) 偏离时, 第 1 道的在制批次与半成品行以前也被扣成待检 —— 它们是偏离之前产出的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CCP 偏离管辖范围: 这一批 + 下游")
class CcpDeviationScopeContractTest {

    private static final String F = "F-SCOPE";
    private static final String PLAN = "58c806d3-plan";
    private static final long INSTANCE = 152L;

    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkflowTaskPortRepository portRepo;
    @Mock private ProcessSheetRowRepository sheetRowRepo;

    private CcpDeviationScope scope;

    private static WorkProcessTask task(long id, int order) {
        return WorkProcessTask.builder().id(id).factoryId(F).productionBatchId(1L)
                .workflowInstanceId(INSTANCE).processOrder(order).build();
    }

    private static WorkflowTaskPort port(long taskId, WorkflowTaskPort.Direction dir, String node) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setFactoryId(F);
        p.setWorkflowInstanceId(INSTANCE);
        p.setTaskId(taskId);
        p.setDirection(dir);
        p.setMaterialNodeId(node);
        return p;
    }

    private static WorkflowTaskPort in(long taskId, String node) {
        return port(taskId, WorkflowTaskPort.Direction.INPUT, node);
    }

    private static WorkflowTaskPort out(long taskId, String node) {
        return port(taskId, WorkflowTaskPort.Direction.OUTPUT, node);
    }

    private static ProductionBatch pb(long id, String no) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber(no);
        b.setProductionPlanId(PLAN);
        return b;
    }

    private static ProcessSheetRow sheetRow(long batchId, int order) {
        ProcessSheetRow r = new ProcessSheetRow();
        r.setFactoryId(F);
        r.setPlanId(PLAN);
        r.setBatchId(batchId);
        r.setProcessOrder(order);
        return r;
    }

    private void graph(List<WorkProcessTask> tasks, List<WorkflowTaskPort> ports) {
        when(productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(List.of(pb(1L, "PB-PLAN")));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 1L)).thenReturn(tasks);
        when(portRepo.findByFactoryIdAndWorkflowInstanceId(F, INSTANCE)).thenReturn(ports);
    }

    /** prod F006 计划 58c806d3 的工艺图 (workflow_task_ports 实读, 12 个端口)。 */
    private void f006Chain() {
        graph(List.of(task(2008, 1), task(2009, 2), task(2010, 3), task(2011, 4), task(2012, 5)), List.of(
                in(2008, "m:r1"), in(2008, "m:r2"), out(2008, "m:s1"), out(2008, "m:bp"),
                in(2009, "m:s1"), out(2009, "m:s2"),
                in(2010, "m:s2"), out(2010, "m:s3"),
                in(2011, "m:s3"), out(2011, "m:s4"),
                in(2012, "m:s4"), out(2012, "m:f")));
    }

    @BeforeEach
    void setUp() {
        scope = new CcpDeviationScope(productionBatchRepo, taskRepo, portRepo, sheetRowRepo);
    }

    @Nested
    @DisplayName("工艺图上的下游")
    class Downstream {

        @Test
        @DisplayName("F006 直链: 第 2 道偏离 → 下游 3/4/5, 不含第 1 道")
        void linearChainFromProd() {
            f006Chain();
            Set<Integer> d = scope.downstreamOrders(F, PLAN, 2);
            System.out.println("F006_DOWNSTREAM_OF_2=" + d);
            assertThat(d).containsExactly(3, 4, 5);
        }

        @Test
        @DisplayName("并行分支: 第 2 道偏离只管走得到的第 4 道, 并行的第 3 道不管")
        void parallelBranchIsNotDownstream() {
            graph(List.of(task(1, 1), task(2, 2), task(3, 3), task(4, 4)), List.of(
                    out(1, "m:a"), out(1, "m:b"),
                    in(2, "m:a"), out(2, "m:a2"),
                    in(3, "m:b"), out(3, "m:b2"),
                    in(4, "m:a2"), in(4, "m:b2"), out(4, "m:f")));
            assertThat(scope.downstreamOrders(F, PLAN, 2)).containsExactly(4);
            assertThat(scope.downstreamOrders(F, PLAN, 3)).containsExactly(4);
        }

        @Test
        @DisplayName("回投边 (第 4 道的料回到第 2 道) 不把更早的道算进下游")
        void reworkBackEdgeIgnored() {
            graph(List.of(task(1, 1), task(2, 2), task(3, 3), task(4, 4)), List.of(
                    out(1, "m:a"), in(2, "m:a"), in(2, "m:back"), out(2, "m:b"),
                    in(3, "m:b"), out(3, "m:c"), in(4, "m:c"), out(4, "m:back"), out(4, "m:f")));
            assertThat(scope.downstreamOrders(F, PLAN, 3)).containsExactly(4);
        }

        @Test
        @DisplayName("计划没有工艺图 → null (后面各道都算)")
        void noGraphMeansEveryLaterStep() {
            when(productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                    .thenReturn(List.of(pb(1L, "PB-PLAN")));
            when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 1L)).thenReturn(List.of());
            assertThat(scope.downstreamOrders(F, PLAN, 2)).isNull();
            assertThat(scope.of(F, PLAN, 2, 20L, "B-20").covers(9, Set.of(), Set.of())).isTrue();
            assertThat(scope.of(F, PLAN, 2, 20L, "B-20").covers(1, Set.of(), Set.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("covers 判据")
    class Covers {

        private final CcpDeviationScope.Scope s = new CcpDeviationScope.Scope(2, Set.of(3, 5), 20L, "B-20");

        @Test
        @DisplayName("上游 → 不管; 同一道别的批次 → 不管; 走不到的后道 → 不管")
        void notCovered() {
            assertThat(s.covers(1, Set.of(20L), Set.of("B-20"))).isFalse();
            assertThat(s.covers(2, Set.of(21L), Set.of("B-21"))).isFalse();
            assertThat(s.covers(4, Set.of(), Set.of())).isFalse();
        }

        @Test
        @DisplayName("这一批 (同生产批次或同批号) / 走得到的后道 / 不知道在哪道 → 管")
        void covered() {
            assertThat(s.covers(2, Set.of(20L), Set.of())).isTrue();
            assertThat(s.covers(2, Set.of(), Set.of("B-20"))).isTrue();
            assertThat(s.covers(3, Set.of(), Set.of())).isTrue();
            assertThat(s.covers(5, Set.of(), Set.of())).isTrue();
            assertThat(s.covers(null, Set.of(), Set.of())).isTrue();
        }

        @Test
        @DisplayName("偏离不知道在哪道 → 同计划全管 (旧口径)")
        void unknownDeviationStepCoversAll() {
            assertThat(scope.of(F, PLAN, null, 20L, "B-20")).isSameAs(CcpDeviationScope.Scope.WHOLE_PLAN);
            assertThat(CcpDeviationScope.Scope.WHOLE_PLAN.covers(1, Set.of(), Set.of())).isTrue();
        }
    }

    /**
     * 扣留端到端 (仓储打桩): F006 直链, 计划下 4 个逐道录入批次 —— 10 (第 1 道, 即 prod 23832 那种)、
     * 20 (第 2 道, 出事这一批)、21 (第 2 道, 同道另一批)、30 (第 3 道)。
     */
    @Nested
    @DisplayName("QcDeviationHold: 只扣这一批与下游")
    class Hold {

        @Mock private SemiFinishedInventoryRepository semiRepo;
        @Mock private MaterialBatchRepository materialBatchRepo;
        @Mock private FinishedGoodsBatchRepository fgRepo;

        private QcDeviationHold hold;
        private SemiFinishedInventory upstreamAnchor;
        private SemiFinishedInventory downstreamAnchor;

        private SemiFinishedInventory semi(long id, String no, int order) {
            return SemiFinishedInventory.builder().id(id).factoryId(F).intermediateBatchNo(no).processOrder(order)
                    .producedQuantity(new BigDecimal("10")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("10")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
        }

        @BeforeEach
        void wire() {
            f006Chain();
            when(productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                    .thenReturn(List.of(pb(1L, "PB-PLAN"), pb(10L, "CLK-W-10"), pb(20L, "CLK-W-20"),
                            pb(21L, "CLK-W-21"), pb(30L, "CLK-W-30")));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 10L)).thenReturn(List.of(sheetRow(10L, 1)));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 20L)).thenReturn(List.of(sheetRow(20L, 2)));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 21L)).thenReturn(List.of(sheetRow(21L, 2)));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 30L)).thenReturn(List.of(sheetRow(30L, 3)));
            upstreamAnchor = semi(386L, "CLK-SEMI-58c806d3-RMT_74b0", 1);
            downstreamAnchor = semi(387L, "CLK-SEMI-58c806d3-SEMI_S3", 3);
            when(semiRepo.findByFactoryIdAndBatchIdInForHold(eq(F), anyCollection())).thenReturn(List.of());
            when(semiRepo.findSemiIdsPostedByProductionBatches(eq(F), anyCollection())).thenReturn(List.of());
            when(semiRepo.findByFactoryIdAndBatchNoOrAnchorPrefixForHold(eq(F), anyCollection(), anyString()))
                    .thenReturn(List.of(upstreamAnchor, downstreamAnchor));
            when(semiRepo.lockForDeviationHold(eq(F), anyCollection()))
                    .thenReturn(List.of(upstreamAnchor, downstreamAnchor));
            when(materialBatchRepo.lockWipBatchesForDeviationHold(eq(F), anyCollection())).thenAnswer(inv -> {
                Collection<String> pbs = inv.getArgument(1);
                List<MaterialBatch> out = new ArrayList<>();
                for (String id : pbs) {
                    MaterialBatch mb = new MaterialBatch();
                    mb.setBatchNumber("WIP-CLK-W-" + id);
                    mb.setSourceDocId(id);
                    mb.setStatus(MaterialBatchStatus.AVAILABLE);
                    out.add(mb);
                }
                return out;
            });
            when(fgRepo.lockForDeviationHold(eq(F), anyCollection(), anyCollection())).thenReturn(List.of());
            hold = new QcDeviationHold(semiRepo, materialBatchRepo, fgRepo, productionBatchRepo, scope);
        }

        @SuppressWarnings("unchecked")
        private Collection<String> wipBatchesLocked() {
            ArgumentCaptor<Collection<String>> c = ArgumentCaptor.forClass(Collection.class);
            verify(materialBatchRepo).lockWipBatchesForDeviationHold(eq(F), c.capture());
            return c.getValue();
        }

        @Test
        @DisplayName("第 2 道批次 20 偏离 → 扣 20 与第 3 道 30 的在制、第 3 道半成品; 第 1 道 10、同道 21、第 1 道半成品不动")
        void holdsThisBatchAndDownstreamOnly() {
            QcDeviationHold.HoldResult r = hold.holdForDeviation(F, "CLK-W-20", 20L, PLAN, 2);
            Collection<String> locked = wipBatchesLocked();
            System.out.println("SCOPED_HOLD wip=" + locked + " semi=" + r.semiRows()
                    + " upstream386=" + upstreamAnchor.getInspectionStatus()
                    + " downstream387=" + downstreamAnchor.getInspectionStatus());
            assertThat(locked).containsExactlyInAnyOrder("20", "1", "30");   // 1 = App 整计划批次, 推不出道 ⇒ 保守留着
            assertThat(upstreamAnchor.getInspectionStatus()).isNull();
            assertThat(downstreamAnchor.getInspectionStatus())
                    .isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
            assertThat(r.semiRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("阴性对照: 偏离不知道在哪道 → 同计划全扣 (旧口径), 第 1 道也被扣")
        void unknownStepHoldsWholePlan() {
            QcDeviationHold.HoldResult r = hold.holdForDeviation(F, "CLK-W-20", 20L, PLAN, null);
            assertThat(wipBatchesLocked()).containsExactlyInAnyOrder("1", "10", "20", "21", "30");
            assertThat(upstreamAnchor.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
            assertThat(r.semiRows()).isEqualTo(2);
        }
    }

    /** 挡放行 / 入账扣待检: 与扣留同一判据; 条数按记录去重。 */
    @Nested
    @DisplayName("CcpOpenDeviationLookup: 放行只被管得着的偏离挡")
    class Lookup {

        @Mock private HaccpMonitoringRecordRepository recordRepo;
        @Mock private SemiFinishedInventoryRepository semiRepo;

        private CcpOpenDeviationLookup lookup;

        @BeforeEach
        void wire() {
            f006Chain();
            HaccpMonitoringRecord dev = HaccpMonitoringRecord.builder().id(1L).factoryId(F)
                    .batchNumber("CLK-W-20").productionBatchId(20L).productionPlanId(PLAN).processOrder(2)
                    .isDeviation(true).build();
            // 同一条既按批号又按计划命中 (prod 放行页显示「2 条未处理」而实际 1 条的成因)
            when(recordRepo.findOpenDeviationsByBatchNumbers(eq(F), anyCollection())).thenAnswer(inv ->
                    ((Collection<?>) inv.getArgument(1)).contains("CLK-W-20") ? List.of(dev) : List.of());
            when(recordRepo.findOpenDeviationsByPlanIds(eq(F), anyCollection())).thenReturn(List.of(dev));
            when(recordRepo.findOpenDeviationsByPlanIdPrefix(eq(F), anyString())).thenReturn(List.of(dev));
            when(productionBatchRepo.findByIdAndFactoryId(anyLong(), eq(F))).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                return java.util.Optional.of(pb(id, "CLK-W-" + id));
            });
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 10L)).thenReturn(List.of(sheetRow(10L, 1)));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 20L)).thenReturn(List.of(sheetRow(20L, 2)));
            when(sheetRowRepo.findByFactoryIdAndBatchId(F, 30L)).thenReturn(List.of(sheetRow(30L, 3)));
            when(semiRepo.findProductionBatchIdsPostedToSemi(eq(F), any())).thenReturn(List.of());
            lookup = new CcpOpenDeviationLookup(recordRepo, productionBatchRepo);
            lookup.setSemiRepo(semiRepo);
            lookup.setScope(scope);
        }

        private MaterialBatch wip(long pbId) {
            MaterialBatch mb = new MaterialBatch();
            mb.setBatchNumber("WIP-CLK-W-" + pbId);
            mb.setSourceDocId(String.valueOf(pbId));
            return mb;
        }

        private SemiFinishedInventory anchor(int order) {
            return SemiFinishedInventory.builder().id(380L + order).factoryId(F)
                    .intermediateBatchNo("CLK-SEMI-58c806d3-P" + order).processOrder(order).build();
        }

        @Test
        @DisplayName("第 1 道的在制 / 半成品放行不被第 2 道的偏离挡; 出事批次与第 3 道被挡 (各 1 条, 不重复数)")
        void releaseBlockedOnlyInScope() {
            long up = lookup.openDeviationsForWipBatch(F, wip(10L));
            long own = lookup.openDeviationsForWipBatch(F, wip(20L));
            long down = lookup.openDeviationsForWipBatch(F, wip(30L));
            long upSemi = lookup.openDeviationsForSemi(F, anchor(1));
            long downSemi = lookup.openDeviationsForSemi(F, anchor(3));
            System.out.println("LOOKUP up=" + up + " own=" + own + " down=" + down
                    + " upSemi=" + upSemi + " downSemi=" + downSemi);
            assertThat(up).isZero();
            assertThat(upSemi).isZero();
            assertThat(own).isEqualTo(1);
            assertThat(down).isEqualTo(1);
            assertThat(downSemi).isEqualTo(1);
        }

        @Test
        @DisplayName("入账扣待检: 第 1 道新产出不扣, 第 3 道新产出扣")
        void newOutputHeldOnlyDownstream() {
            assertThat(lookup.hasOpen(F, List.of("CLK-W-10"), PLAN, 10L, null)).isFalse();
            assertThat(lookup.hasOpen(F, List.of("CLK-W-30"), PLAN, 30L, null)).isTrue();
            assertThat(lookup.hasOpen(F, List.of(), PLAN, 1L, 1)).isFalse();
            assertThat(lookup.hasOpen(F, List.of(), PLAN, 1L, 4)).isTrue();
        }
    }
}
