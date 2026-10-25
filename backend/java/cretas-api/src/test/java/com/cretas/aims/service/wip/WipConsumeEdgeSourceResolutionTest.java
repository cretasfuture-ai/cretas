package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.lineage.SemiSourceBatchResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIP_CONSUME 边的来源节点 —— ⛔ 来源未知时不得拿目标批次顶替。
 *
 * <p>prod（F006 备份 2026-10-24）57 条 WIP_CONSUME 里 51 条自环，其中 39 条的 SFI 行
 * {@code batch_id} 为空 —— 那是 {@code sourceWip.getBatchId() == null ? task.getProductionBatchId() : …}
 * 这一行伪造出来的「这批半成品是自己产的」。另 12 条行上 batch_id 就等于目标批次（同批工序间领用），是真的自环。
 *
 * <p>直接驱动 {@code recordWipLineageEdge}（App 报工领用半成品的唯一写边点）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WIP_CONSUME: 来源批次解析不了就不写边, 不再写伪自环")
class WipConsumeEdgeSourceResolutionTest {

    private static final String FACTORY = "F006";
    private static final Long TARGET_BATCH = 2398L;

    @Mock SemiFinishedInventoryRepository wipRepo;
    @Mock SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock ProductionReportRepository reportRepo;
    @Mock BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock WorkProcessTaskRepository taskRepo;
    @Mock WorkProcessRepository workProcessRepo;
    @Mock ProductTypeRepository productTypeRepo;
    @Mock ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock ProductFamilyResolver productFamilyResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ProductionBatchRepository productionBatchRepo;

    private WipInventoryServiceImpl service;
    private SimpleMeterRegistry meters;
    private WorkProcessTask targetTask;

    @BeforeEach
    void setUp() {
        service = new WipInventoryServiceImpl(wipRepo, txnRepo, reportRepo, lineageEdgeRepo, taskRepo,
                workProcessRepo, productTypeRepo, workflowInstanceRepo, workflowTaskPortRepo,
                productFamilyResolver, eventPublisher);
        SemiSourceBatchResolver resolver = new SemiSourceBatchResolver(wipRepo, productionBatchRepo, taskRepo);
        meters = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(resolver, "meterRegistry", meters);
        ReflectionTestUtils.setField(service, "semiSourceBatchResolver", resolver);

        when(lineageEdgeRepo.save(any(BatchLineageEdge.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepo.findByFactoryIdAndId(anyString(), any())).thenReturn(Optional.empty());
        when(productionBatchRepo.findByFactoryIdAndBatchNumber(anyString(), anyString())).thenReturn(Optional.empty());

        targetTask = new WorkProcessTask();
        targetTask.setId(1989L);
        targetTask.setFactoryId(FACTORY);
        targetTask.setProductionBatchId(TARGET_BATCH);
        targetTask.setProcessOrder(2);
    }

    @Test
    @DisplayName("池化锚 CLK-SEMI-… 且行上无 batch_id: 不写边 (原来写成 2398→2398 自环), warn + 计数 +1")
    void unresolvablePooledAnchorWritesNoEdge() {
        SemiFinishedInventory pooled = sfi("CLK-SEMI-8cf3fa56-fbd59b4d", null, null);

        record(pooled);

        verify(lineageEdgeRepo, never()).save(any(BatchLineageEdge.class));
        // 「没写」必须配一个「一定会发生」的读数: 解析器确实走到了、而且记了这一次 (不是根本没跑到)。
        double unresolved = meters.counter(SemiSourceBatchResolver.METRIC_UNRESOLVED,
                "reason", SemiSourceBatchResolver.REASON_POOLED_CLERK_ANCHOR, "origin", "APP_WIP_CONSUME").count();
        System.out.println("[lineage-test] unresolved counter=" + unresolved);
        assertThat(unresolved).isEqualTo(1.0);
    }

    @Test
    @DisplayName("App 工序批次号 …-B77-S1-5 且任务 5 属于批次 77: 来源 = 77, 不是目标批次")
    void appTaskBatchNumberResolvesRealProducer() {
        WorkProcessTask producer = new WorkProcessTask();
        producer.setId(5L);
        producer.setFactoryId(FACTORY);
        producer.setProductionBatchId(77L);
        when(taskRepo.findByFactoryIdAndId(FACTORY, 5L)).thenReturn(Optional.of(producer));

        record(sfi("8406e457-2755-41e3-a5a8-11f31504d2af-B77-S1-5", null, null));

        BatchLineageEdge edge = savedEdge();
        assertThat(edge.getSourceId()).isEqualTo("77");
        assertThat(edge.getTargetId()).isEqualTo(String.valueOf(TARGET_BATCH));
        assertThat(edge.getMeta()).containsEntry("sourceResolvedBy", SemiSourceBatchResolver.BY_TASK_BATCH_NO);
    }

    @Test
    @DisplayName("号里的批次与任务真实批次对不上: 不采纳 (两个来源一致才算可靠), 不写边")
    void appTaskBatchNumberMismatchIsRejected() {
        WorkProcessTask producer = new WorkProcessTask();
        producer.setId(5L);
        producer.setFactoryId(FACTORY);
        producer.setProductionBatchId(78L);   // 号里写的是 B77
        when(taskRepo.findByFactoryIdAndId(FACTORY, 5L)).thenReturn(Optional.of(producer));

        record(sfi("PT-B77-S1-5", null, null));

        verify(lineageEdgeRepo, never()).save(any(BatchLineageEdge.class));
    }

    @Test
    @DisplayName("库存号就是某个生产批次号: 来源 = 那个批次")
    void productionBatchNumberResolves() {
        ProductionBatch pb = new ProductionBatch();
        pb.setId(10927L);
        pb.setFactoryId(FACTORY);
        when(productionBatchRepo.findByFactoryIdAndBatchNumber(FACTORY, "CLK-W-20260903-42481"))
                .thenReturn(Optional.of(pb));

        record(sfi("CLK-W-20260903-42481", null, null));

        assertThat(savedEdge().getSourceId()).isEqualTo("10927");
    }

    @Test
    @DisplayName("阳性对照: 行上 batch_id 就是目标批次 (同批工序间领用) —— 真自环照写")
    void sameBatchInterProcessSelfLoopIsKept() {
        record(sfi("PT-B2398-S1-1988", TARGET_BATCH, null));

        BatchLineageEdge edge = savedEdge();
        assertThat(edge.getSourceId()).isEqualTo(edge.getTargetId()).isEqualTo(String.valueOf(TARGET_BATCH));
        assertThat(edge.getMeta()).containsEntry("sourceResolvedBy", SemiSourceBatchResolver.BY_ROW_BATCH);
    }

    @Test
    @DisplayName("解析器未注入 (旧单测装配) + 行上无 batch_id: 不写边, 不退回目标批次")
    void noResolverAndNoRowBatchWritesNothing() {
        ReflectionTestUtils.setField(service, "semiSourceBatchResolver", null);

        record(sfi("CLK-SEMI-x-y", null, null));

        verify(lineageEdgeRepo, never()).save(any(BatchLineageEdge.class));
    }

    // ─────────────────────────────────────────────────────────────

    private void record(SemiFinishedInventory source) {
        ReflectionTestUtils.invokeMethod(service, "recordWipLineageEdge",
                FACTORY, source, targetTask, new BigDecimal("3"), 7L);
    }

    private BatchLineageEdge savedEdge() {
        ArgumentCaptor<BatchLineageEdge> captor = ArgumentCaptor.forClass(BatchLineageEdge.class);
        verify(lineageEdgeRepo).save(captor.capture());
        return captor.getValue();
    }

    private static SemiFinishedInventory sfi(String no, Long batchId, Long sourceTaskId) {
        return SemiFinishedInventory.builder()
                .id(383L).factoryId(FACTORY).intermediateBatchNo(no)
                .batchId(batchId).sourceWorkProcessTaskId(sourceTaskId)
                .unit("kg").build();
    }
}
