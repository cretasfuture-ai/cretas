package com.cretas.aims.service.workstation;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.controller.WorkstationCountingController;
import com.cretas.aims.entity.ProcessingStageRecord;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.IotDeviceDataRepository;
import com.cretas.aims.repository.ProcessingStageRecordRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.IotDataService;
import com.cretas.aims.service.impl.IotDataServiceImpl;
import com.cretas.aims.service.isapi.CameraSnapshotAiConsent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工位计数: 工位会话表全进程共用、只按工位 id 存。以前别厂账号知道工位 id 就能停掉别厂工位 (计数写进别厂的加工记录)、
 * 送帧、手动计数、看状态; 初始化还能覆盖别厂正在用的工位、挂到别厂批次上。现在只认本厂的工位与批次。
 */
class WorkstationCountingTenantIsolationTest {

    private static final String WS = "ws-1";

    private DashScopeVisionClient vision;
    private ProcessingStageRecordRepository stages;
    private ProductionBatchRepository batches;
    private WorkstationCountingService service;
    private WorkstationCountingController controller;

    @BeforeEach
    void setUp() {
        vision = mock(DashScopeVisionClient.class);
        stages = mock(ProcessingStageRecordRepository.class);
        batches = mock(ProductionBatchRepository.class);
        FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
        when(settings.findAiSettingsByFactoryId(anyString())).thenReturn("{\"cameraSnapshotAnalysis\":true}");
        when(stages.findTopByProductionBatchIdAndStageTypeOrderByStartTimeDesc(any(), any()))
                .thenReturn(Optional.of(ProcessingStageRecord.builder().id(77L).factoryId("F006").passCount(3).build()));
        when(batches.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(new ProductionBatch()));
        service = new WorkstationCountingService(vision, new CameraSnapshotAiConsent(settings, new ObjectMapper()),
                mock(IotDataService.class), stages, mock(BatchWorkSessionRepository.class), batches, new ObjectMapper());
        controller = new WorkstationCountingController(service);
        service.initWorkstation(WorkstationCountingService.WorkstationConfig.builder()
                .workstationId(WS).factoryId("F006").productionBatchId(9L).build());
    }

    @Test
    void anotherFactoryCannotStopFeedCountVerifyOrReadThisWorkstation() {
        assertThat(service.stopWorkstation("F007", WS).isSuccess()).isFalse();
        assertThat(service.processFrame("F007", WS, "aW1n").isSuccess()).isFalse();
        assertThat(service.manualCount("F007", WS, BigDecimal.ONE).isCounted()).isFalse();
        assertThat(service.verifyLabel("F007", WS, "aW1n").isSuccess()).isFalse();
        assertThat(service.getWorkstationStatus("F007", WS).getStatus())
                .isEqualTo(WorkstationCountingService.WorkstationStatus.INACTIVE);

        verify(vision, never()).analyzeCompletionGesture(anyString(), any());
        verify(vision, never()).recognizeLabel(anyString(), any(), any());
        verify(stages, never()).save(any());
        // 本厂工位仍在跑、计数没动
        WorkstationCountingService.WorkstationStatusInfo own = service.getWorkstationStatus("F006", WS);
        assertThat(own.getStatus()).isEqualTo(WorkstationCountingService.WorkstationStatus.ACTIVE);
        assertThat(own.getProductCount()).isEqualTo(3);
    }

    @Test
    void controllerPassesThePathFactory() {
        ResponseEntity<Map<String, Object>> resp = controller.stopWorkstation("F007", WS);

        assertThat(resp.getBody()).containsEntry("success", false);
        assertThat(service.getWorkstationStatus("F006", WS).getStatus())
                .isEqualTo(WorkstationCountingService.WorkstationStatus.ACTIVE);
    }

    @Test
    void ownFactoryCountsAndStops() {
        assertThat(service.manualCount("F006", WS, BigDecimal.ONE).isCounted()).isTrue();
        assertThat(service.stopWorkstation("F006", WS).isSuccess()).isTrue();
        assertThat(service.getWorkstationStatus("F006", WS).getStatus())
                .isEqualTo(WorkstationCountingService.WorkstationStatus.INACTIVE);
    }

    @Test
    void anotherFactoryCannotTakeOverARunningWorkstationId() {
        when(batches.findByIdAndFactoryId(4L, "F007")).thenReturn(Optional.of(new ProductionBatch()));

        assertThatThrownBy(() -> service.initWorkstation(WorkstationCountingService.WorkstationConfig.builder()
                .workstationId(WS).factoryId("F007").productionBatchId(4L).build()))
                .hasMessageContaining("已被占用");
        assertThat(service.getWorkstationStatus("F006", WS).getStatus())
                .isEqualTo(WorkstationCountingService.WorkstationStatus.ACTIVE);
    }

    @Test
    void aWorkstationCannotBeAttachedToAnotherFactorysBatch() {
        assertThatThrownBy(() -> service.initWorkstation(WorkstationCountingService.WorkstationConfig.builder()
                .workstationId("ws-2").factoryId("F007").productionBatchId(9L).build()))
                .hasMessageContaining("生产批次不存在");
        assertThat(service.getWorkstationStatus("F007", "ws-2").getStatus())
                .isEqualTo(WorkstationCountingService.WorkstationStatus.INACTIVE);
    }

    @Test
    void scaleReadingsAreScopedToTheFactory() {
        IotDeviceDataRepository data = mock(IotDeviceDataRepository.class);
        when(data.findByFactoryIdAndDeviceIdOrderByReceivedAtDesc(eq("F006"), eq("scale-1"), any(Pageable.class)))
                .thenReturn(List.of());
        IotDataServiceImpl iot = mock(IotDataServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(iot, "iotDeviceDataRepository", data);

        iot.getRecentData("F006", "scale-1", 1);

        verify(data).findByFactoryIdAndDeviceIdOrderByReceivedAtDesc(eq("F006"), eq("scale-1"), any(Pageable.class));
    }
}
