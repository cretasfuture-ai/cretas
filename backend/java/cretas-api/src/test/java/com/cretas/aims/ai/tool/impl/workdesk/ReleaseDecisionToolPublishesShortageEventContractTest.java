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

    @Test
    void rejectingDoesNotPublish() throws Exception {
        run("REJECTED", "外观不合格");

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
