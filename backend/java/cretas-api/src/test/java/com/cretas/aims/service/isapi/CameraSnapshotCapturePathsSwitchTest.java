package com.cretas.aims.service.isapi;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.dto.isapi.IsapiCaptureDTO;
import com.cretas.aims.entity.ProcessingStageRecord;
import com.cretas.aims.entity.isapi.LabelRecognitionConfig;
import com.cretas.aims.entity.isapi.LabelRecognitionRecord;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProcessingStageRecordRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionConfigRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionRecordRepository;
import com.cretas.aims.service.IotDataService;
import com.cretas.aims.service.workstation.WorkstationCountingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工位计数的定时抓帧、标签识别的抓帧, 与告警抓拍同一个开关 (AI 设置 cameraSnapshotAnalysis, 默认关闭):
 * 本厂没开就不抓帧、不发给 AI。走各自的真实入口 (工位初始化 + 送帧; ISAPI 事件 / 手动触发)。
 */
class CameraSnapshotCapturePathsSwitchTest {

    private static final String FACTORY = "F006";
    private static final String ON = "{\"cameraSnapshotAnalysis\":true}";

    private final ObjectMapper mapper = new ObjectMapper();
    private FactorySettingsRepository settings;
    private DashScopeVisionClient vision;
    private CameraSnapshotAiConsent consent;

    @BeforeEach
    void setUp() {
        settings = mock(FactorySettingsRepository.class);
        vision = mock(DashScopeVisionClient.class);
        when(vision.analyzeCompletionGesture(anyString(), any()))
                .thenReturn(DashScopeVisionClient.CompletionGestureResult.error("stub: 模型已调用"));
        consent = new CameraSnapshotAiConsent(settings, mapper);
    }

    // ---------------------------------------------------------------- 工位计数

    private WorkstationCountingService workstation() {
        ProcessingStageRecordRepository stages = mock(ProcessingStageRecordRepository.class);
        when(stages.findTopByProductionBatchIdAndStageTypeOrderByStartTimeDesc(any(), any()))
                .thenReturn(Optional.of(ProcessingStageRecord.builder().passCount(0).build()));
        return new WorkstationCountingService(vision, consent, mock(IotDataService.class), stages,
                mock(BatchWorkSessionRepository.class), mapper);
    }

    private String start(WorkstationCountingService service) {
        return service.initWorkstation(WorkstationCountingService.WorkstationConfig.builder()
                .workstationId("ws-1").factoryId(FACTORY).productionBatchId(9L).build()).getWorkstationId();
    }

    @Test
    void workstationFramesAreNotSentUnlessTheFactoryTurnedItOn() {
        WorkstationCountingService service = workstation();
        String ws = start(service);

        WorkstationCountingService.CountingResult r = service.processFrame(ws, "aW1n");

        verify(vision, never()).analyzeCompletionGesture(anyString(), any());
        assertThat(r.isCounted()).isFalse();
        assertThat(r.getMessage()).isEqualTo(CameraSnapshotAiConsent.DISABLED_MESSAGE);
    }

    @Test
    void workstationFramesAreSentOnceTheFactoryTurnedItOn() {
        when(settings.findAiSettingsByFactoryId(FACTORY)).thenReturn(ON);
        WorkstationCountingService service = workstation();

        service.processFrame(start(service), "aW1n");

        verify(vision).analyzeCompletionGesture(anyString(), any());
    }

    // ---------------------------------------------------------------- 标签识别抓帧

    private LabelRecognitionConfigRepository configs;
    private IsapiDeviceService devices;
    private LabelRecognitionRecordRepository records;

    private AutoLabelRecognitionService labels() {
        configs = mock(LabelRecognitionConfigRepository.class);
        devices = mock(IsapiDeviceService.class);
        records = mock(LabelRecognitionRecordRepository.class);
        when(records.save(any(LabelRecognitionRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        LabelRecognitionConfig config = LabelRecognitionConfig.builder().id(5L).factoryId(FACTORY)
                .deviceId("cam-1").channelId(1).enabled(true).triggerOnVmd(true).build();
        when(configs.findById(5L)).thenReturn(Optional.of(config));
        when(configs.findEnabledConfigsByDeviceId("cam-1")).thenReturn(List.of(config));
        when(devices.capturePicture(anyString(), anyInt()))
                .thenReturn(IsapiCaptureDTO.builder().success(false).error("stub: 已抓帧").build());
        return new AutoLabelRecognitionService(configs, records, devices, vision, consent);
    }

    @Test
    void cameraEventsDoNotCaptureOrSendUnlessTheFactoryTurnedItOn() {
        AutoLabelRecognitionService service = labels();

        service.onIsapiEvent(FACTORY, "VMD", "cam-1", "evt-1");

        verify(devices, never()).capturePicture(anyString(), anyInt());
        verify(records, never()).save(any());
    }

    @Test
    void manualTriggerLeavesAFailedRecordSayingWhyWithoutCapturing() {
        AutoLabelRecognitionService service = labels();

        LabelRecognitionRecord record = service.manualRecognize(5L);

        verify(devices, never()).capturePicture(anyString(), anyInt());
        assertThat(record.getErrorMessage()).isEqualTo(CameraSnapshotAiConsent.DISABLED_MESSAGE);
    }

    @Test
    void factoryThatTurnedItOnGetsCaptureOnEventsAndManualTrigger() {
        when(settings.findAiSettingsByFactoryId(FACTORY)).thenReturn(ON);
        AutoLabelRecognitionService service = labels();

        service.onIsapiEvent(FACTORY, "VMD", "cam-1", "evt-1");
        service.manualRecognize(5L);

        verify(devices, org.mockito.Mockito.times(2)).capturePicture(anyString(), anyInt());
    }
}
