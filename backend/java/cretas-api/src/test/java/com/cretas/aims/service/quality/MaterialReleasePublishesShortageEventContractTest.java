package com.cretas.aims.service.quality;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.event.MaterialBatchInspectionClosedEvent;
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
        ReflectionTestUtils.setField(service, "qcReleaseRecordRepository",
                mock(com.cretas.aims.repository.quality.QcReleaseRecordRepository.class));   // 没有质检放行记录
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
        service.applyDisposition(inspection, DispositionAction.RELEASE, true);

        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class, e -> {
            assertThat(e.getFactoryId()).isEqualTo("F1");
            assertThat(e.getMaterialTypeId()).isEqualTo("M1");
            assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
        });
    }

    /**
     * 原名 scrapAndAlreadyAvailableDoNotPublish, 断言「什么都不发」。D-S411-04 审查 M1 之后性质改为: 报废 / 本来就可用
     * 都<b>不发放行事件</b>; 质检中直接报废 = 「质检中」那部分不会再来了, 发一条 {@link MaterialBatchInspectionClosedEvent}
     * 让缺料实例清掉质检中数量 (否则缺口被低估)。本来就可用的再放行一次: 两种事件都不发 (阴性对照)。
     */
    @Test
    void scrapAndAlreadyAvailableDoNotPublishARelease() {
        service.applyDisposition(inspection, DispositionAction.SCRAP, true);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        service.applyDisposition(inspection, DispositionAction.RELEASE, true);

        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher, atLeast(0)).publishEvent(events.capture());
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(events.getAllValues()).noneMatch(e -> e instanceof MaterialBatchReleasedEvent);
        assertThat(events.getAllValues()).singleElement().isInstanceOfSatisfying(
                MaterialBatchInspectionClosedEvent.class, e -> {
                    assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
                    assertThat(e.getNewStatus()).isEqualTo(MaterialBatchStatus.SCRAPPED);
                });
    }
}
