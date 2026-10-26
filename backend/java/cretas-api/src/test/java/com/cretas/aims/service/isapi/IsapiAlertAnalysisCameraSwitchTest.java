package com.cretas.aims.service.isapi;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.ai.client.DashScopeVisionClient.CameraAlertAnalysisResult;
import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.entity.isapi.IsapiEventLog;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄像头告警抓拍交给 AI 识别: 默认关闭 (抓拍里可能有人, 图片识别不做名称脱敏), 本厂在 AI 设置里
 * ({@code PUT /api/mobile/{factoryId}/settings/ai} 的 {@code cameraSnapshotAnalysis}) 打开才发。
 * 走 {@link IsapiAlertAnalysisService#analyzeAlert} (订阅 / 边缘网关的异步入口都汇到这里) 与
 * {@link IsapiAlertAnalysisService#reanalyzeEvent} (设备页「重新分析」接口)。
 */
class IsapiAlertAnalysisCameraSwitchTest {

    private static final String FACTORY = "F006";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeVisionClient vision;
    private FactorySettingsRepository settings;
    private IsapiEventLogRepository events;
    private IsapiAlertAnalysisService service;

    @BeforeEach
    void setUp() {
        vision = mock(DashScopeVisionClient.class);
        when(vision.isAvailable()).thenReturn(true);
        when(vision.analyzeCameraAlert(anyString(), anyString(), any()))
                .thenReturn(CameraAlertAnalysisResult.error("stub: 模型已调用"));
        settings = mock(FactorySettingsRepository.class);
        events = mock(IsapiEventLogRepository.class);
        service = new IsapiAlertAnalysisService(vision, events, settings, mapper);
    }

    private static IsapiDevice device(String factoryId) {
        IsapiDevice d = new IsapiDevice();
        d.setFactoryId(factoryId);
        d.setDeviceName("车间 1 号摄像头");
        return d;
    }

    private static IsapiEventLog snapshot(IsapiDevice device) {
        IsapiEventLog e = new IsapiEventLog();
        e.setId(7L);
        e.setFactoryId(device.getFactoryId());
        e.setEventType("fielddetection");
        e.setPictureData(new byte[]{1, 2, 3});
        e.setDevice(device);
        return e;
    }

    @ParameterizedTest(name = "ai_settings = {0}")
    @NullSource
    @ValueSource(strings = {"", "{\"enabled\":true,\"tone\":\"professional\"}", "{\"cameraSnapshotAnalysis\":false}",
            "{\"cameraSnapshotAnalysis\":null}", "{\"cameraSnapshotAnalysis\":\"true\"}", "not json"})
    void snapshotIsNotSentUnlessTheFactoryTurnedItOn(String aiSettings) {
        when(settings.findAiSettingsByFactoryId(FACTORY)).thenReturn(aiSettings);

        CameraAlertAnalysisResult r = service.analyzeAlert(snapshot(device(FACTORY)), device(FACTORY));

        verify(vision, never()).analyzeCameraAlert(anyString(), anyString(), any());
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).isEqualTo(IsapiAlertAnalysisService.CAMERA_AI_DISABLED_MESSAGE);
    }

    @Test
    void settingsLookupFailureCountsAsOff() {
        when(settings.findAiSettingsByFactoryId(FACTORY)).thenThrow(new IllegalStateException("db down"));

        service.analyzeAlert(snapshot(device(FACTORY)), device(FACTORY));

        verify(vision, never()).analyzeCameraAlert(anyString(), anyString(), any());
    }

    @Test
    void factoryThatTurnedItOnThroughTheAiSettingsEndpointGetsAnalysis() throws Exception {
        // PUT /settings/ai 存的就是这个 DTO 的 JSON (FactorySettingsServiceImpl.updateAiSettings → toJsonString)
        String stored = mapper.writeValueAsString(FactorySettingsDTO.AISettings.builder()
                .enabled(true).tone("professional").cameraSnapshotAnalysis(true).build());
        when(settings.findAiSettingsByFactoryId(FACTORY)).thenReturn(stored);
        when(settings.findAiSettingsByFactoryId("F007")).thenReturn(null);

        service.analyzeAlert(snapshot(device(FACTORY)), device(FACTORY));
        service.analyzeAlert(snapshot(device("F007")), device("F007"));

        // 只有打开的那个厂发了一次; 另一个厂照旧不发
        verify(vision).analyzeCameraAlert(anyString(), anyString(), any());
    }

    @Test
    void reanalyzeEndpointHonoursTheSwitchToo() {
        IsapiDevice device = device(FACTORY);
        when(events.findById(7L)).thenReturn(Optional.of(snapshot(device)));

        assertThat(service.reanalyzeEvent(7L).getMessage())
                .isEqualTo(IsapiAlertAnalysisService.CAMERA_AI_DISABLED_MESSAGE);
        verify(vision, never()).analyzeCameraAlert(anyString(), anyString(), any());

        when(settings.findAiSettingsByFactoryId(FACTORY)).thenReturn("{\"cameraSnapshotAnalysis\":true}");
        service.reanalyzeEvent(7L);
        verify(vision).analyzeCameraAlert(anyString(), anyString(), any());
    }
}
