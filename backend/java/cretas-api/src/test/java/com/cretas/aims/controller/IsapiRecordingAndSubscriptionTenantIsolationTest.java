package com.cretas.aims.controller;

import com.cretas.aims.client.isapi.IsapiClient;
import com.cretas.aims.config.IsapiConfig;
import com.cretas.aims.controller.isapi.IsapiRecordingController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.isapi.RecordingSearchRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.cretas.aims.service.isapi.IsapiAlertAnalysisService;
import com.cretas.aims.service.isapi.IsapiDeviceDiscoveryService;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import com.cretas.aims.service.isapi.IsapiRecordingService;
import okhttp3.Call;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 录像检索 / 回放地址 (回放地址可带设备密码) 只认本厂设备; 订阅状态 / 状态汇总不再把全进程的订阅设备 id
 * (含别厂的) 返回给前端。
 */
class IsapiRecordingAndSubscriptionTenantIsolationTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 27, 8, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 27, 9, 0);

    private IsapiDeviceRepository repository;
    private IsapiClient client;
    private IsapiRecordingController recordings;

    private static IsapiDevice device(String id, String factoryId) {
        IsapiDevice d = new IsapiDevice();
        d.setId(id);
        d.setFactoryId(factoryId);
        d.setIpAddress("10.0.0.9");
        d.setUsername("admin");
        d.setPasswordEncrypted("ENC(pw)");
        return d;
    }

    @BeforeEach
    void setUp() {
        repository = mock(IsapiDeviceRepository.class);
        client = mock(IsapiClient.class);
        when(repository.findById("cam-1")).thenReturn(Optional.of(device("cam-1", "F006")));
        when(repository.findById("cam-9")).thenReturn(Optional.of(device("cam-9", "F009")));
        recordings = new IsapiRecordingController(new IsapiRecordingService(client, repository));
    }

    @Test
    void recordingSearchesOfAnotherFactorysCameraFindNoDeviceAndNeverReachTheCamera() {
        RecordingSearchRequest req = RecordingSearchRequest.builder().deviceId("cam-1").startTime(FROM).endTime(TO).build();

        List<Throwable> refusals = List.of(
                catchThrowable(() -> recordings.searchRecordings("F007", req)),
                catchThrowable(() -> recordings.searchRecentRecordings("F007", "cam-1", 1, 1, 50)),
                catchThrowable(() -> recordings.searchRecordingsByDate("F007", "cam-1", 1, LocalDate.of(2026, 10, 27), 100)));

        assertThat(refusals).allSatisfy(t -> assertThat(t).isInstanceOf(BusinessException.class)
                .hasMessageContaining("设备不存在"));
        verifyNoInteractions(client);
    }

    @Test
    void playbackUrlOfAnotherFactorysCameraIsRefused() {
        Throwable t = catchThrowable(() -> recordings.getPlaybackUrl("F007", "cam-1", 1, FROM, TO, false));

        assertThat(t).hasMessageContaining("设备不存在");
        verifyNoInteractions(client);
    }

    @Test
    void ownFactoryGetsThePlaybackUrl() {
        ApiResponse<Map<String, Object>> resp = recordings.getPlaybackUrl("F006", "cam-1", 1, FROM, TO, false);

        assertThat(String.valueOf(resp.getData().get("playbackUrl"))).startsWith("rtsp://10.0.0.9:554/");
    }

    @Test
    void subscriptionStatusAndSummaryOnlyCountOwnDevices() {
        IsapiEventSubscriptionService subscriptions = new IsapiEventSubscriptionService(
                client, repository, mock(IsapiEventLogRepository.class), mock(IsapiConfig.class));
        @SuppressWarnings("unchecked")
        Map<String, Call> active = (Map<String, Call>) ReflectionTestUtils.getField(subscriptions, "activeSubscriptions");
        active.put("cam-1", mock(Call.class));
        active.put("cam-9", mock(Call.class));
        IsapiDeviceService devices = mock(IsapiDeviceService.class);
        when(devices.getStatusStatistics(anyString())).thenReturn(Map.of());
        IsapiDeviceController controller = new IsapiDeviceController(devices, mock(IsapiDeviceDiscoveryService.class),
                subscriptions, mock(IsapiEventLogRepository.class), mock(IsapiAlertAnalysisService.class));

        Map<String, Object> own = controller.getSubscriptionStatus("F006").getData();
        Map<String, Object> summary = controller.getStatusSummary("F006").getData();
        Map<String, Object> none = controller.getSubscriptionStatus("F007").getData();

        assertThat(own).containsEntry("activeDevices", List.of("cam-1")).containsEntry("activeCount", 1);
        assertThat(summary).containsEntry("subscriptionCount", 1);
        assertThat(none).containsEntry("activeDevices", List.of()).containsEntry("activeCount", 0);
        assertThat(subscriptions.getActiveSubscriptionCount()).isEqualTo(2);
    }
}
