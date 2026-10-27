package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.edge.EdgeUploadRequest;
import com.cretas.aims.dto.edge.EdgeUploadResponse;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.cretas.aims.service.edge.EdgeGatewayService;
import com.cretas.aims.service.isapi.IsapiAlertAnalysisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 边缘上传 / 心跳 / 海康事件推送: 以前按设备 id (或全局 MAC / IP) 找设备, 不看登录账号的工厂 ——
 * 别厂账号能往本厂设备上写心跳、事件和图片 (推到本厂的实时通知、按本厂开关触发 AI 分析)。
 * 现在只认登录账号本厂的设备; 网关列表只给本厂的。
 */
class EdgeGatewayTenantIsolationTest {

    private IsapiDeviceRepository devices;
    private IsapiEventLogRepository events;
    private IsapiAlertAnalysisService analysis;
    private EdgeGatewayService service;
    private EdgeGatewayController controller;
    private IsapiDevice cam;

    @BeforeEach
    void setUp() {
        devices = mock(IsapiDeviceRepository.class);
        events = mock(IsapiEventLogRepository.class);
        analysis = mock(IsapiAlertAnalysisService.class);
        cam = new IsapiDevice();
        cam.setId("cam-1");
        cam.setFactoryId("F006");
        cam.setDeviceName("仓库门口");
        when(devices.findById("cam-1")).thenReturn(Optional.of(cam));
        when(devices.findByMacAddressIgnoreCase("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(cam));
        when(devices.findByIpAddress("10.0.0.5")).thenReturn(Optional.of(cam));
        service = new EdgeGatewayService(devices, events, analysis, null);
        controller = new EdgeGatewayController(service);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loggedInAs(String factoryId) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("factoryId", factoryId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
    }

    private static EdgeUploadRequest upload(EdgeUploadRequest.UploadType type) {
        EdgeUploadRequest r = new EdgeUploadRequest();
        r.setGatewayId("gw-1");
        r.setDeviceId("cam-1");
        r.setChannelId(1);
        r.setUploadType(type);
        r.setPictureBase64("aW1n");
        r.setEventType("VMD");
        return r;
    }

    @Test
    void anotherFactorysAccountCannotUploadOrHeartbeatOnThisDevice() {
        loggedInAs("F007");

        Throwable upload = catchThrowable(() -> controller.upload(upload(EdgeUploadRequest.UploadType.CAPTURE)));
        ApiResponse<EdgeUploadResponse> heartbeat = controller.heartbeat("gw-1", "cam-1");

        assertThat(upload).isInstanceOf(BusinessException.class).hasMessageContaining("设备不存在");
        assertThat(heartbeat.getData().isSuccess()).isFalse();
        verify(devices, never()).save(any());
        verifyNoInteractions(events, analysis);
        assertThat(controller.getGateways().getData()).isEmpty();
    }

    @Test
    void eventsPushedByMacOrIpOnlyMatchTheAccountsOwnDevices() {
        assertThat(service.findDeviceIdByMacOrIp("F007", "aa:bb:cc:dd:ee:ff", "10.0.0.5")).isNull();
        assertThat(service.findDeviceIdByMacOrIp(null, "aa:bb:cc:dd:ee:ff", "10.0.0.5")).isNull();
        assertThat(service.findDeviceIdByMacOrIp("F006", "AA-BB-CC-DD-EE-FF", null)).isEqualTo("cam-1");
        assertThat(service.findDeviceIdByMacOrIp("F006", null, "10.0.0.5")).isEqualTo("cam-1");
    }

    @Test
    void ownFactoryHeartbeatIsRecordedAndOnlyItsGatewaysAreListed() {
        loggedInAs("F006");

        ApiResponse<EdgeUploadResponse> heartbeat = controller.heartbeat("gw-1", "cam-1");

        assertThat(heartbeat.getData().isSuccess()).isTrue();
        verify(devices).save(cam);
        Map<String, EdgeGatewayService.EdgeGatewayInfo> own = controller.getGateways().getData();
        assertThat(own).containsOnlyKeys("gw-1");
        loggedInAs("F007");
        assertThat(controller.getGateways().getData()).isEmpty();
    }

    @Test
    void withoutALoggedInFactoryNothingIsAccepted() {
        assertThat(service.processUpload(null, upload(EdgeUploadRequest.UploadType.HEARTBEAT)).isSuccess()).isFalse();
        verify(devices, never()).save(any());
    }
}
