package com.cretas.aims.service.quality;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 质检处置把原料放成「可用」时要通知缺料实例 (否则「质检中」永远挪不成「已放行」, 缺口不会下降);
 * 报废 / 本来就可用 不发。
 */
class MaterialReleasePublishesShortageEventContractTest {

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private QualityDispositionInventoryService service;
    private MaterialBatch batch;
    private QualityInspection inspection;

    @BeforeEach
    void setUp() {
        service = new QualityDispositionInventoryService();
        ReflectionTestUtils.setField(service, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);
        batch = new MaterialBatch();
        batch.setId("MB-1");
        batch.setFactoryId("F1");
        batch.setMaterialTypeId("M1");
        batch.setBatchNumber("B-1");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(batches.findByIdAndFactoryId("MB-1", "F1")).thenReturn(Optional.of(batch));
        inspection = new QualityInspection();
        inspection.setFactoryId("F1");
        inspection.setMaterialBatchId("MB-1");
    }

    @Test
    void releasingAnInspectingBatchPublishesTheEvent() {
        service.applyDisposition(inspection, DispositionAction.RELEASE);

        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class, e -> {
            assertThat(e.getFactoryId()).isEqualTo("F1");
            assertThat(e.getMaterialTypeId()).isEqualTo("M1");
            assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
        });
    }

    @Test
    void scrapAndAlreadyAvailableDoNotPublish() {
        service.applyDisposition(inspection, DispositionAction.SCRAP);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        service.applyDisposition(inspection, DispositionAction.RELEASE);

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
