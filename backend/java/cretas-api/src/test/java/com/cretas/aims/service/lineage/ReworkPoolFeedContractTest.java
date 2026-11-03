package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 03: 回料池 (池化锚) 领用能归属到产出它的生产批次, 追溯才查得到回料来源。
 * 2026-11-03 F006 实跑: 回料是纯库存产出、挂在池化锚上, 解析器按设计不猜, 回料领用一条边都没写。
 */
@DisplayName("缺口 03: 回料池领用按先进先出归属产出批次并写 WIP_CONSUME")
class ReworkPoolFeedContractTest {

    private static final String F = "F006";
    private static final String ANCHOR = "CLK-SEMI-6ec94af9-fe2c87f5";

    private SemiFinishedInventoryRepository sfiRepo;
    private ProcessSheetRowRepository rowRepo;
    private LineageEdgeRecorder edgeRecorder;
    private SemiFeedLineageRecorder feed;

    @BeforeEach
    void setUp() {
        sfiRepo = mock(SemiFinishedInventoryRepository.class);
        rowRepo = mock(ProcessSheetRowRepository.class);
        edgeRecorder = mock(LineageEdgeRecorder.class);
        when(edgeRecorder.recordWipConsume(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(LineageRecordOutcome.INSERTED);
        feed = new SemiFeedLineageRecorder(edgeRecorder, new SemiSourceBatchResolver(sfiRepo,
                mock(ProductionBatchRepository.class), mock(WorkProcessTaskRepository.class)));
        ReflectionTestUtils.setField(feed, "reworkPoolAttribution", new ReworkPoolProducerAttribution(sfiRepo, rowRepo));
        ReflectionTestUtils.setField(feed, "sfiRepository", sfiRepo);
    }

    private void pool(String consumedAfter) {
        when(sfiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, ANCHOR))
                .thenReturn(Optional.of(SemiFinishedInventory.builder().factoryId(F).intermediateBatchNo(ANCHOR)
                        .isRework(true).unit("kg").consumedQuantity(new BigDecimal(consumedAfter)).build()));
    }

    private static ProcessSheetRow row(long id, String clientRowId, Long batchId, String status, String payload) {
        ProcessSheetRow r = new ProcessSheetRow();
        r.setId(id);
        r.setFactoryId(F);
        r.setPlanId("P1");
        r.setProcessCode("p:L5:p3");
        r.setClientRowId(clientRowId);
        r.setBatchId(batchId);
        r.setBatchNumber(batchId == null ? ANCHOR : "B" + batchId);
        r.setRowStatus(status);
        r.setRowPayload(payload);
        return r;
    }

    @Test
    @DisplayName("两笔回料入账 0.3(批100) + 0.6(批200), 领走 [0.2,0.7) => 批100 出 0.1 / 批200 出 0.4, 各一条边")
    void fifoSplitAcrossTwoProducers() {
        pool("0.7");
        ProcessSheetRow in1 = row(11, "g#1", null, ProcessSheetRow.STATUS_SAVED_SFI, "{\"outputQuantity\":0.3}");
        ProcessSheetRow in2 = row(22, "h#1", null, ProcessSheetRow.STATUS_SAVED_SFI, "{\"outputQuantity\":0.6}");
        when(rowRepo.findByFactoryIdAndBatchNumberAndRowStatus(F, ANCHOR, ProcessSheetRow.STATUS_SAVED_SFI))
                .thenReturn(List.of(in2, in1)); // 乱序给, 实现必须按 id 排
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCode(F, "P1", "p:L5:p3")).thenReturn(List.of(
                in1, in2, row(10, "g#0", 100L, "SAVED", "{}"), row(21, "h#0", 200L, "SAVED", "{}")));

        LineageRecordOutcome outcome = feed.recordSemiFeed(F, ANCHOR, 555L, new BigDecimal("0.5"), "kg", 7L,
                "reworkSubmit:row9:" + ANCHOR, "PROCESS_SHEET_REWORK_SUBMIT");

        assertThat(outcome).isEqualTo(LineageRecordOutcome.INSERTED);
        verify(edgeRecorder).recordWipConsume(eq(F), eq(100L), eq(555L),
                argThat(q -> q.compareTo(new BigDecimal("0.1")) == 0), eq("kg"), eq(7L),
                eq("reworkSubmit:row9:" + ANCHOR + ":producer100"), any());
        verify(edgeRecorder).recordWipConsume(eq(F), eq(200L), eq(555L),
                argThat(q -> q.compareTo(new BigDecimal("0.4")) == 0), eq("kg"), eq(7L),
                eq("reworkSubmit:row9:" + ANCHOR + ":producer200"), any());
    }

    @Test
    @DisplayName("入账行不在多产出组里 (找不到产出批次) => AMBIGUOUS, 不写边, 不拿目标批次顶替")
    void noProducerBatchMeansNoEdge() {
        pool("0.5");
        ProcessSheetRow lone = row(11, "solo", null, ProcessSheetRow.STATUS_SAVED_SFI, "{\"outputQuantity\":0.9}");
        when(rowRepo.findByFactoryIdAndBatchNumberAndRowStatus(F, ANCHOR, ProcessSheetRow.STATUS_SAVED_SFI))
                .thenReturn(List.of(lone));

        LineageRecordOutcome outcome = feed.recordSemiFeed(F, ANCHOR, 555L, new BigDecimal("0.5"), "kg", 7L,
                "k", "PROCESS_SHEET_REWORK_SUBMIT");

        assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
        verify(edgeRecorder, never()).recordWipConsume(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非回料的池化锚不走归属 (普通半成品仍按原规则 AMBIGUOUS)")
    void plainPooledAnchorStaysAmbiguous() {
        when(sfiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, ANCHOR))
                .thenReturn(Optional.of(SemiFinishedInventory.builder().factoryId(F).intermediateBatchNo(ANCHOR)
                        .isRework(false).unit("kg").consumedQuantity(new BigDecimal("0.5")).build()));

        LineageRecordOutcome outcome = feed.recordSemiFeed(F, ANCHOR, 555L, new BigDecimal("0.5"), "kg", 7L,
                "k", "PROCESS_SHEET_REWORK_SUBMIT");

        assertThat(outcome).isEqualTo(LineageRecordOutcome.AMBIGUOUS);
        verify(edgeRecorder, never()).recordWipConsume(anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}
