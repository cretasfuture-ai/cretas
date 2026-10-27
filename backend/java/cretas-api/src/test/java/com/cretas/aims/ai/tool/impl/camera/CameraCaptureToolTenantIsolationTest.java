package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.client.isapi.IsapiClient;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.repository.isapi.IsapiDeviceChannelRepository;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * camera_capture: 设备 id 来自模型抽取的参数, 以前直接按 id 抓拍 —— 别厂账号在对话里报出设备 id 就能抓别厂画面。
 * 现在只抓本厂摄像头。
 */
class CameraCaptureToolTenantIsolationTest {

    private IsapiClient client;
    private CameraCaptureTool tool;

    @BeforeEach
    void setUp() throws Exception {
        IsapiDeviceRepository repository = mock(IsapiDeviceRepository.class);
        client = mock(IsapiClient.class);
        IsapiDevice cam = new IsapiDevice();
        cam.setId("cam-1");
        cam.setFactoryId("F006");
        cam.setDeviceName("包装线");
        when(repository.findById("cam-1")).thenReturn(Optional.of(cam));
        when(client.capturePicture(any(IsapiDevice.class), anyInt())).thenReturn(new byte[]{1, 2, 3});
        tool = new CameraCaptureTool();
        ReflectionTestUtils.setField(tool, "deviceService",
                new IsapiDeviceService(repository, mock(IsapiDeviceChannelRepository.class), client));
    }

    @Test
    void anotherFactorysCameraIsNotCaptured() {
        assertThatThrownBy(() -> tool.doExecute("F007", Map.of("deviceId", "cam-1"), Map.of()))
                .hasMessageContaining("设备不存在");
        verifyNoInteractions(client);
    }

    @Test
    void ownCameraIsCaptured() throws Exception {
        Map<String, Object> out = tool.doExecute("F006", Map.of("deviceId", "cam-1"), Map.of());

        assertThat(String.valueOf(out.get("message"))).contains("包装线");
        verify(client).capturePicture(any(IsapiDevice.class), anyInt());
    }
}
