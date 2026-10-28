package com.cretas.aims.ai.tool.impl.camera;

import com.cretas.aims.client.isapi.IsapiClient;
import com.cretas.aims.config.IsapiConfig;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import okhttp3.Call;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * camera_subscribe / camera_status: 订阅表全进程共用。以前「订阅全部」回给对话的订阅数和设备 id 是所有工厂的,
 * 状态查询的订阅数也是。现在只数本厂设备。
 */
class CameraSubscriptionToolsTenantIsolationTest {

    private IsapiEventSubscriptionService subscriptions;
    private IsapiDeviceRepository repository;

    private static IsapiDevice device(String id, String factoryId) {
        IsapiDevice d = new IsapiDevice();
        d.setId(id);
        d.setFactoryId(factoryId);
        return d;
    }

    @BeforeEach
    void setUp() {
        repository = mock(IsapiDeviceRepository.class);
        when(repository.findById("cam-1")).thenReturn(Optional.of(device("cam-1", "F006")));
        when(repository.findById("cam-9")).thenReturn(Optional.of(device("cam-9", "F009")));
        subscriptions = spy(new IsapiEventSubscriptionService(mock(IsapiClient.class), repository,
                mock(IsapiEventLogRepository.class), mock(IsapiConfig.class)));
        doNothing().when(subscriptions).subscribeAllDevices(anyString());
        @SuppressWarnings("unchecked")
        Map<String, Call> active = (Map<String, Call>) ReflectionTestUtils.getField(subscriptions, "activeSubscriptions");
        active.put("cam-1", mock(Call.class));
        active.put("cam-9", mock(Call.class));
    }

    @Test
    void subscribeAllReportsOnlyOwnDevices() throws Exception {
        CameraSubscribeTool tool = new CameraSubscribeTool();
        ReflectionTestUtils.setField(tool, "subscriptionService", subscriptions);
        ReflectionTestUtils.setField(tool, "deviceService", mock(IsapiDeviceService.class));

        Map<String, Object> f6 = tool.doExecute("F006", Map.of(), Map.of());
        Map<String, Object> f7 = tool.doExecute("F007", Map.of(), Map.of());

        assertThat(f6).containsEntry("subscribedCount", 1).containsEntry("subscribedDeviceIds", List.of("cam-1"));
        assertThat(f7).containsEntry("subscribedCount", 0).containsEntry("subscribedDeviceIds", List.of());
    }

    @Test
    void statusCountsOnlyOwnSubscriptions() throws Exception {
        IsapiDeviceService devices = mock(IsapiDeviceService.class);
        when(devices.getStatusStatistics(anyString())).thenReturn(Map.of("ONLINE", 1L));
        when(repository.findByFactoryIdAndStatus(anyString(), any())).thenReturn(List.of());
        CameraStatusTool tool = new CameraStatusTool();
        ReflectionTestUtils.setField(tool, "deviceService", devices);
        ReflectionTestUtils.setField(tool, "deviceRepository", repository);
        ReflectionTestUtils.setField(tool, "subscriptionService", subscriptions);

        assertThat(tool.doExecute("F006", Map.of(), Map.of())).containsEntry("activeSubscriptions", 1);
        assertThat(tool.doExecute("F007", Map.of(), Map.of())).containsEntry("activeSubscriptions", 0);
    }
}
