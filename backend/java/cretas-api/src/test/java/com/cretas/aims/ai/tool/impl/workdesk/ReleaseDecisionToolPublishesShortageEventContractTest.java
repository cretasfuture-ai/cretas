package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 放行决策工具把原料放成「可用」时通知缺料实例; 判退 (不良品) 不发。 */
class ReleaseDecisionToolPublishesShortageEventContractTest {

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private ReleaseDecisionTool tool;
    private MaterialBatch batch;

    @BeforeEach
    void setUp() {
        tool = new ReleaseDecisionTool();
        ReflectionTestUtils.setField(tool, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(tool, "eventPublisher", publisher);
        // 纪律排查 A3: release_decision 须有质检权限 (quality:read_write) 并写放行台账
        com.cretas.aims.ai.tool.ToolRbacGuard qcRbac = org.mockito.Mockito.mock(com.cretas.aims.ai.tool.ToolRbacGuard.class);
        org.mockito.Mockito.lenient().when(qcRbac.hasAnyPermission(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(true);
        ReflectionTestUtils.setField(tool, "rbacGuard", qcRbac);
        ReflectionTestUtils.setField(tool, "releaseGuard",
                org.mockito.Mockito.mock(com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.class));
        ReflectionTestUtils.setField(tool, "qcReleaseRecordRepository",
                mock(com.cretas.aims.repository.quality.QcReleaseRecordRepository.class));   // 没有质检放行记录
        batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId("F1");
        batch.setMaterialTypeId("M1");
        batch.setBatchNumber("B-1");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(batches.findByFactoryIdAndBatchNumber("F1", "B-1")).thenReturn(Optional.of(batch));
        when(batches.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Map<String, Object> run(String decision, String notes) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("batchNumber", "B-1");
        params.put("decision", decision);
        params.put("notes", notes);
        Map<String, Object> context = new HashMap<>();
        context.put("userId", 9L);
        return tool.doExecute("F1", params, context);
    }

    @Test
    void releasingPublishesTheEvent() throws Exception {
        run("RELEASED", "");

        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class,
                e -> assertThat(e.getMaterialTypeId()).isEqualTo("M1"));
    }

    /**
     * 原名 rejectingDoesNotPublish (什么都不发)。复审 R1: 判退把质检中改成不良品, 「质检中数量」不会再来了 ——
     * 不发放行事件, 但要发离开质检中事件, 缺料实例才会重算 (与质检放行判不合格同一个发布函数)。
     */
    @Test
    void rejectingPublishesInspectionClosedNotARelease() throws Exception {
        run("REJECTED", "外观不合格");

        assertThat(batch.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(batches).save(batch);
        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(event.getValue()).isNotInstanceOf(MaterialBatchReleasedEvent.class)
                .isInstanceOfSatisfying(com.cretas.aims.event.MaterialBatchInspectionClosedEvent.class, e -> {
                    assertThat(e.getMaterialTypeId()).isEqualTo("M1");
                    assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
                    assertThat(e.getNewStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
                });
    }
}
