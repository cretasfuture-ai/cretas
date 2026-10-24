package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.event.listener.WorkflowTaskProgressEventListener;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G08: Workflow 任务产量 = 该工序节点全部有效逐道行的主产出合计 (重算, 不是用触发行覆盖)。
 */
class WorkflowTaskProgressWriterTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-WF";
    private static final String PRIMARY_PORT = "port-main";

    /** 与 Spring Boot 注入的生产 mapper 同配置: 未知字段不报错 (旧 payload 里可能留着已删字段)。 */
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private WorkProcessTaskRepository taskRepository;
    private ProcessSheetRowRepository rowRepository;
    private WorkflowTaskProgressWriter writer;
    private WorkProcessTask first;
    private WorkProcessTask target;
    private final List<ProcessSheetRow> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkProcessTaskRepository.class);
        rowRepository = mock(ProcessSheetRowRepository.class);
        writer = new WorkflowTaskProgressWriter(taskRepository, rowRepository, mapper);
        first = task(101L, "node-1");
        target = task(102L, "node-2");
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(FACTORY, 31L))
                .thenReturn(List.of(first, target));
        when(taskRepository.findByFactoryIdAndIdForUpdate(FACTORY, 102L)).thenReturn(Optional.of(target));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenAnswer(i -> List.copyOf(rows));
    }

    private WorkflowTaskProgressRequestedEvent saved(String triggerQty) {
        return new WorkflowTaskProgressRequestedEvent(
                FACTORY, PLAN, 31L, "node-2", 2, new BigDecimal(triggerQty), PRIMARY_PORT, true);
    }

    private WorkflowTaskProgressRequestedEvent recompute() {
        return new WorkflowTaskProgressRequestedEvent(
                FACTORY, PLAN, 31L, "node-2", 2, null, PRIMARY_PORT, false);
    }

    @Test
    void sameProcessTwoRowsSumInsteadOfOverwrite() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        writer.stamp(saved("48"));
        assertEquals(0, new BigDecimal("48").compareTo(target.getActualQuantity()));

        rows.add(row("r-2", 2, "32", "kg", null, false));
        writer.stamp(saved("32"));

        assertEquals(0, new BigDecimal("80").compareTo(target.getActualQuantity()),
                "同一工序第二行必须累加 (48 + 32), 旧版用触发行覆盖成 32");
        assertEquals(WorkProcessTask.Status.COMPLETED, target.getStatus());
        assertNull(first.getActualQuantity(), "同一 Workflow 实例的其它节点一行不动");
        verify(taskRepository, org.mockito.Mockito.times(2)).saveAndFlush(target);
    }

    @Test
    void deletingARowRecomputesToTheRemainingRowAndKeepsStatus() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        rows.add(row("r-2", 2, "32", "kg", null, false));
        writer.stamp(saved("32"));
        assertEquals(0, new BigDecimal("80").compareTo(target.getActualQuantity()));

        target.setStatus(WorkProcessTask.Status.IN_PROGRESS);   // 观察: 重算不许动状态
        rows.remove(1);                                          // r-2 软删 (@Where 之后查不到)
        writer.stamp(recompute());

        assertEquals(0, new BigDecimal("48").compareTo(target.getActualQuantity()));
        assertEquals(WorkProcessTask.Status.IN_PROGRESS, target.getStatus());
    }

    @Test
    void deletingTheLastRowLeavesNoActualQuantityInsteadOfZero() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        writer.stamp(saved("48"));
        rows.clear();

        writer.stamp(recompute());

        assertNull(target.getActualQuantity(), "没有有效行 = 未报, 不是 0");
    }

    @Test
    void draftsOtherProcessesAndRevertedRowsAreNotCounted() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        ProcessSheetRow draft = row("r-draft", 2, "500", "kg", null, false);
        draft.setRowStatus("DRAFT");
        rows.add(draft);
        ProcessSheetRow reverted = row("r-reverted", 2, "700", "kg", null, false);
        reverted.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);   // 撤销小结后退回草稿
        rows.add(reverted);
        rows.add(row("r-other", 3, "900", "kg", null, false));

        writer.stamp(saved("48"));

        assertEquals(0, new BigDecimal("48").compareTo(target.getActualQuantity()));
    }

    @Test
    void multiOutputCountsOnlyThePrimaryPortNotBaseZero() {
        // base#0 = 用户第一个填的产出 (这里是副产, 单位「个」), base#1 才是主产出端口
        rows.add(row("grp#0", 2, "5", "个", "port-byproduct", true));
        rows.add(row("grp#1", 2, "40", "kg", PRIMARY_PORT, true));
        rows.add(row("r-single", 2, "10", "kg", null, false));

        writer.stamp(saved("5"));

        assertEquals(0, new BigDecimal("50").compareTo(target.getActualQuantity()),
                "主产出 40 + 单产出行 10; 副产 5 个不混进 kg");
    }

    @Test
    void mixedUnitsOrMissingQuantityAreUnknownNotAPartialSum() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        rows.add(row("r-2", 2, "3", "箱", null, false));
        writer.stamp(saved("3"));
        assertNull(target.getActualQuantity(), "单位不一致不相加");

        rows.clear();
        rows.add(row("r-1", 2, "48", "kg", null, false));
        ProcessSheetRow noQty = row("r-3", 2, "1", "kg", null, false);
        noQty.setRowPayload("{\"clientRowId\":\"r-3\",\"processOrder\":2,\"unit\":\"kg\"}");
        rows.add(noQty);
        writer.stamp(saved("48"));
        assertNull(target.getActualQuantity(), "有效行缺产出量 ⇒ 未知, 不拿 48 冒充全量");
    }

    @Test
    void legacyEventWithoutPrimaryPortCountsSingleRowsOnly() {
        rows.add(row("r-1", 2, "48", "kg", null, false));
        rows.add(row("grp#0", 2, "7", "kg", PRIMARY_PORT, true));

        writer.stamp(new WorkflowTaskProgressRequestedEvent(FACTORY, PLAN, 31L, "node-2", 2, BigDecimal.ONE));

        assertEquals(0, new BigDecimal("48").compareTo(target.getActualQuantity()));
        assertEquals(WorkProcessTask.Status.COMPLETED, target.getStatus());
    }

    @Test
    void afterCommitListenerContainsProjectionFailure() {
        WorkflowTaskProgressWriter failing = mock(WorkflowTaskProgressWriter.class);
        WorkflowTaskProgressEventListener listener = new WorkflowTaskProgressEventListener(failing);
        WorkflowTaskProgressRequestedEvent event = saved("1");
        doThrow(new IllegalStateException("projection failed")).when(failing).stamp(event);

        assertDoesNotThrow(() -> listener.onWorkflowTaskProgressRequested(event));
    }

    private ProcessSheetRow row(String clientRowId, int processOrder, String qty, String unit,
                                String portId, boolean multiMember) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessOrder(processOrder);
        req.setOutputQuantity(new BigDecimal(qty));
        req.setUnit(unit);
        req.setOutputUnit(unit);
        req.setWorkflowPortId(portId);
        req.setMultiOutputMember(multiMember ? Boolean.TRUE : null);
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN);
        row.setClientRowId(clientRowId);
        row.setProcessOrder(processOrder);
        row.setRowStatus("SAVED");
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        try {
            row.setRowPayload(mapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private WorkProcessTask task(Long id, String nodeId) {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(id);
        task.setWorkflowNodeId(nodeId);
        task.setStatus(WorkProcessTask.Status.PENDING);
        return task;
    }
}
