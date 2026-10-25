package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G08 发布端: 产品真实入口 {@code deleteRow} 删掉一条已计入任务产量的行之后, 必须发一条
 * 「重算这道工序」的事件 (不置 COMPLETED), 并带上主产出端口; 删草稿行不发。
 * 事件 → 监听器 → {@link WorkflowTaskProgressWriter} 的聚合由 {@code WorkflowTaskProgressWriterTest} 钉住。
 */
class ProcessSheetWorkflowProgressRecomputeTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-WF-G08";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private ProcessSheetRowRepository rowRepo;
    private ApplicationEventPublisher publisher;
    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        rowRepo = mock(ProcessSheetRowRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        WorkflowClerkSheetService sheetService = mock(WorkflowClerkSheetService.class);
        when(sheetService.getWorkflowSheetConfig(FACTORY, PLAN)).thenReturn(config());
        service = new ProcessSheetServiceImpl(
                null, rowRepo, null, null, null, null, null,
                mock(ProcessSheetRowChangeLogRepository.class), mapper,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "workflowClerkSheetService", sheetService);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", publisher);
    }

    @Test
    void deletingACountedRowRequestsARecomputeOfThatProcess() throws Exception {
        ProcessSheetRow counted = row("row-32", "SAVED", ProcessSheetRow.SUBMISSION_LEGACY);
        when(rowRepo.findByFactoryIdAndPlanIdAndClientRowId(FACTORY, PLAN, "row-32")).thenReturn(List.of(counted));

        service.deleteRow(FACTORY, PLAN, "row-32", 7L);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        WorkflowTaskProgressRequestedEvent event = (WorkflowTaskProgressRequestedEvent) published.getValue();
        assertThat(event.workflowNodeId()).isEqualTo("node-2");
        assertThat(event.processOrder()).isEqualTo(2);
        assertThat(event.primaryOutputPortId()).isEqualTo("out-main");
        assertThat(event.markCompleted()).as("删除后的重算不动任务状态").isFalse();
        assertThat(event.actualQuantity()).as("删除不携带「本行产量」").isNull();
        assertThat(counted.getDeletedAt()).as("行确实被软删").isNotNull();
    }

    @Test
    void deletingADraftRowDoesNotTouchTaskProgress() throws Exception {
        ProcessSheetRow draft = row("row-draft", "DRAFT", ProcessSheetRow.SUBMISSION_DRAFT);
        when(rowRepo.findByFactoryIdAndPlanIdAndClientRowId(FACTORY, PLAN, "row-draft")).thenReturn(List.of(draft));

        service.deleteRow(FACTORY, PLAN, "row-draft", 7L);

        assertThat(draft.getDeletedAt()).isNotNull();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private ProcessSheetRow row(String clientRowId, String rowStatus, String submission) throws Exception {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("chaoshui");
        req.setProcessOrder(2);
        req.setOutputQuantity(new BigDecimal("32"));
        req.setUnit("kg");
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN);
        row.setProcessCode("chaoshui");
        row.setProcessOrder(2);
        row.setClientRowId(clientRowId);
        row.setRowStatus(rowStatus);
        row.setSubmissionStatus(submission);
        row.setRowPayload(mapper.writeValueAsString(req));
        return row;
    }

    private WorkflowClerkSheetConfigDTO config() {
        PortDescriptor byproduct = PortDescriptor.builder().workflowPortId("out-byproduct").byproduct(true)
                .unit("kg").build();
        PortDescriptor main = PortDescriptor.builder().workflowPortId("out-main").unit("kg").build();
        ProcessDescriptor desc = ProcessDescriptor.builder()
                .workflowNodeId("node-2")
                .processOrder(2)
                .plannedUnit("kg")
                .output(main)
                .outputs(List.of(byproduct, main))
                .inputs(List.of())
                .build();
        return WorkflowClerkSheetConfigDTO.builder()
                .workflowBatchId(901L)
                .workflowInstanceId(31L)
                .processes(List.of(desc))
                .build();
    }
}
