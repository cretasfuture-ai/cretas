package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.dto.isapi.IsapiCaptureDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.entity.isapi.LabelRecognitionConfig;
import com.cretas.aims.entity.isapi.LabelRecognitionRecord;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionConfigRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionRecordRepository;
import com.cretas.aims.service.isapi.AutoLabelRecognitionService;
import com.cretas.aims.service.isapi.CameraSnapshotAiConsent;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签识别: 配置和识别记录只认本厂。以前按配置 id 的接口不看工厂, 新建配置也不查摄像头归属 ——
 * A 厂可以把配置挂到 B 厂摄像头上, 再手动触发, 用 A 厂的开关去抓 B 厂画面交给视觉模型。
 */
class LabelRecognitionTenantIsolationTest {

    private static final String CAM = "cam-1";
    private static final String ON = "{\"cameraSnapshotAnalysis\":true}";

    private LabelRecognitionConfigRepository configs;
    private LabelRecognitionRecordRepository records;
    private IsapiDeviceService devices;
    private FactorySettingsRepository settings;
    private LabelRecognitionConfig config;
    private AutoLabelRecognitionService service;
    private LabelRecognitionController controller;

    @BeforeEach
    void setUp() {
        configs = mock(LabelRecognitionConfigRepository.class);
        records = mock(LabelRecognitionRecordRepository.class);
        devices = mock(IsapiDeviceService.class);
        settings = mock(FactorySettingsRepository.class);
        when(settings.findAiSettingsByFactoryId(anyString())).thenReturn(ON);
        config = LabelRecognitionConfig.builder().id(5L).factoryId("F006").deviceId(CAM)
                .channelId(1).enabled(true).triggerOnVmd(true).build();
        when(configs.findById(5L)).thenReturn(Optional.of(config));
        when(configs.save(any(LabelRecognitionConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(records.save(any(LabelRecognitionRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(records.findByConfigIdOrderByRecognitionTimeDesc(eq(5L), any()))
                .thenReturn(new PageImpl<>(List.of()));
        IsapiDevice cam = new IsapiDevice();
        cam.setId(CAM);
        cam.setFactoryId("F006");
        when(devices.getDevice("F006", CAM)).thenReturn(cam);
        when(devices.getDevice("F007", CAM)).thenThrow(new IllegalArgumentException("设备不存在或无权访问: " + CAM));
        when(devices.capturePicture(anyString(), anyString(), anyInt()))
                .thenReturn(IsapiCaptureDTO.builder().success(false).error("stub: 已抓帧").build());
        service = new AutoLabelRecognitionService(configs, records, devices, mock(DashScopeVisionClient.class),
                new CameraSnapshotAiConsent(settings, new ObjectMapper()));
        controller = new LabelRecognitionController(service);
    }

    static Stream<Arguments> configEndpoints() {
        return Stream.of(
                Arguments.of("GET /configs/{id}", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.getConfig(f, 5L)),
                Arguments.of("PUT /configs/{id}", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.updateConfig(f, 5L, LabelRecognitionConfig.builder().channelId(2).build())),
                Arguments.of("DELETE /configs/{id}", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.deleteConfig(f, 5L)),
                Arguments.of("POST /configs/{id}/toggle", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.toggleConfig(f, 5L, Map.of("enabled", false))),
                Arguments.of("POST /configs/{id}/trigger", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.triggerRecognition(f, 5L)),
                Arguments.of("GET /records?configId=", (BiFunction<LabelRecognitionController, String, ResponseEntity<?>>)
                        (c, f) -> c.getRecords(f, 0, 20, 5L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configEndpoints")
    void anotherFactorysConfigIsNotFoundAndNothingHappens(String name,
                                                          BiFunction<LabelRecognitionController, String, ResponseEntity<?>> call) {
        ResponseEntity<?> resp = call.apply(controller, "F007");

        // 与「配置不存在」完全同一个回答 (不泄露别厂有这条配置)
        when(configs.findById(404L)).thenReturn(Optional.empty());
        ResponseEntity<?> missing = controller.getConfig("F007", 404L);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isEqualTo(missing.getBody());
        verify(configs, never()).save(any());
        verify(records, never()).save(any());
        verify(records, never()).findByConfigIdOrderByRecognitionTimeDesc(any(), any());
        verify(devices, never()).capturePicture(anyString(), anyString(), anyInt());
        assertThat(config.getChannelId()).isEqualTo(1);
        assertThat(config.getEnabled()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configEndpoints")
    void ownFactoryStillWorks(String name, BiFunction<LabelRecognitionController, String, ResponseEntity<?>> call) {
        ResponseEntity<?> resp = call.apply(controller, "F006");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void manualTriggerCapturesTheCameraAsTheConfigsFactory() {
        controller.triggerRecognition("F006", 5L);

        verify(devices).capturePicture("F006", CAM, 1);
    }

    @Test
    void aConfigCannotBeAttachedToAnotherFactorysCamera() {
        when(configs.existsByFactoryIdAndDeviceId("F007", CAM)).thenReturn(false);

        ResponseEntity<?> resp = controller.createConfig("F007",
                LabelRecognitionConfig.builder().deviceId(CAM).channelId(1).build());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(configs, never()).save(any());
    }

    @Test
    void aConfigOnOwnCameraIsCreated() {
        ResponseEntity<?> resp = controller.createConfig("F006",
                LabelRecognitionConfig.builder().deviceId(CAM).channelId(1).build());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(configs).save(any(LabelRecognitionConfig.class));
    }

    @Test
    void cameraEventsOnlyTriggerTheEventFactorysConfigs() {
        // 存量里可能有别厂挂在这台摄像头上的配置: 本厂摄像头的事件不触发它
        LabelRecognitionConfig foreign = LabelRecognitionConfig.builder().id(9L).factoryId("F007").deviceId(CAM)
                .channelId(1).enabled(true).triggerOnVmd(true).build();
        when(configs.findEnabledConfigsByDeviceId(CAM)).thenReturn(List.of(foreign, config));

        service.onIsapiEvent("F006", "VMD", CAM, "evt-1");

        verify(devices).capturePicture("F006", CAM, 1);
        verify(devices, never()).capturePicture(eq("F007"), anyString(), anyInt());
        assertThat(foreign.getLastTriggerTime()).isNull();
    }

    @Test
    void recordsOfAnotherFactorysConfigAreNotListed() {
        ResponseEntity<?> resp = controller.getRecords("F007", 0, 20, 5L);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }
}
