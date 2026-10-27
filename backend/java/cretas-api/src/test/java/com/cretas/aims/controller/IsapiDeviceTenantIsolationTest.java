package com.cretas.aims.controller;

import com.cretas.aims.dto.isapi.HttpHostConfigRequest;
import com.cretas.aims.dto.isapi.IsapiDeviceDTO;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.isapi.IsapiEventLogRepository;
import com.cretas.aims.service.isapi.IsapiAlertAnalysisService;
import com.cretas.aims.service.isapi.IsapiDeviceDiscoveryService;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 按设备 id 的 15 个摄像头接口: 只认 URL 里本厂的设备。以前它们不看工厂, 别厂有设备权限的账号
 * 知道设备 id 就能读明文密码、重启、恢复出厂、删除、抓拍。别厂的设备一律 404, 且除了「查归属」什么都不做。
 */
class IsapiDeviceTenantIsolationTest {

    private static final String DEVICE = "cam-1";
    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/cretas/aims/controller/IsapiDeviceController.java");

    private IsapiDeviceService devices;
    private IsapiEventSubscriptionService subscriptions;
    private IsapiDeviceController controller;

    @BeforeEach
    void setUp() {
        devices = mock(IsapiDeviceService.class);
        subscriptions = mock(IsapiEventSubscriptionService.class);
        IsapiDevice own = new IsapiDevice();
        own.setId(DEVICE);
        own.setFactoryId("F006");
        when(devices.getDevice("F006", DEVICE)).thenReturn(own);
        when(devices.getDevice("F007", DEVICE)).thenThrow(new IllegalArgumentException("设备不存在或无权访问: " + DEVICE));
        when(devices.toDTO(own)).thenReturn(IsapiDeviceDTO.builder().id(DEVICE).build());
        when(devices.getDecryptedPassword(DEVICE)).thenReturn("pw-123");
        controller = new IsapiDeviceController(devices, mock(IsapiDeviceDiscoveryService.class), subscriptions,
                mock(IsapiEventLogRepository.class), mock(IsapiAlertAnalysisService.class));
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("PUT /{deviceId}", (BiConsumer<IsapiDeviceController, String>)
                        (c, f) -> c.updateDevice(f, DEVICE, IsapiDeviceDTO.builder().build())),
                Arguments.of("DELETE /{deviceId}", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.deleteDevice(f, DEVICE)),
                Arguments.of("GET /{deviceId}", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.getDevice(f, DEVICE)),
                Arguments.of("POST /test-connection", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.testConnection(f, DEVICE)),
                Arguments.of("POST /sync", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.syncDevice(f, DEVICE)),
                Arguments.of("POST /configure-http-host", (BiConsumer<IsapiDeviceController, String>)
                        (c, f) -> c.configureHttpHost(f, DEVICE, new HttpHostConfigRequest())),
                Arguments.of("GET /password", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.getDevicePassword(f, DEVICE)),
                Arguments.of("PUT /password", (BiConsumer<IsapiDeviceController, String>)
                        (c, f) -> c.changeDevicePassword(f, DEVICE, Map.of("newPassword", "Abcd1234!"))),
                Arguments.of("POST /reboot", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.rebootDevice(f, DEVICE)),
                Arguments.of("POST /factory-reset", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.factoryResetDevice(f, DEVICE)),
                Arguments.of("GET /streams", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.getStreamUrls(f, DEVICE)),
                Arguments.of("POST /capture", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.capturePicture(f, DEVICE, 1)),
                Arguments.of("GET /capture/image", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.capturePictureAsImage(f, DEVICE, 1)),
                Arguments.of("POST /subscribe", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.subscribeDevice(f, DEVICE)),
                Arguments.of("POST /unsubscribe", (BiConsumer<IsapiDeviceController, String>) (c, f) -> c.unsubscribeDevice(f, DEVICE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void anotherFactorysDeviceIsNotFoundAndNothingElseRuns(String name, BiConsumer<IsapiDeviceController, String> call) {
        Throwable thrown = catchThrowable(() -> call.accept(controller, "F007"));

        assertThat(thrown).as(name).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).as(name).isEqualTo(404);
        verify(devices).getDevice("F007", DEVICE);
        verifyNoMoreInteractions(devices);
        verifyNoInteractions(subscriptions);
    }

    @Test
    void ownFactoryStillWorks() {
        assertThat(controller.getDevice("F006", DEVICE).getData().getId()).isEqualTo(DEVICE);
        assertThat(controller.getDevicePassword("F006", DEVICE).getData()).containsEntry("password", "pw-123");
        controller.rebootDevice("F006", DEVICE);
        verify(devices).rebootDevice(DEVICE);
    }

    /** 新加一个 /{deviceId} 接口而忘了查归属, 这里会红: 接口数 = 方法体里查归属的次数。 */
    @Test
    void everyDeviceIdEndpointChecksOwnershipFirst() throws Exception {
        String src = Files.readString(CONTROLLER, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher m = Pattern.compile("@(Get|Post|Put|Delete)Mapping\\(\"/\\{deviceId\\}[^\\n]*\\n(?:(?!\\n    }\\n)[\\s\\S])*?\\) \\{\\n"
                + "(?:        try \\{\\n)?\\s*(?:IsapiDevice device = )?ownDevice\\(factoryId, deviceId\\);").matcher(src);
        int guarded = 0;
        while (m.find()) {
            guarded++;
        }
        long mappings = Pattern.compile("@(Get|Post|Put|Delete)Mapping\\(\"/\\{deviceId\\}").matcher(src).results().count();
        assertThat(mappings).isEqualTo(15);
        assertThat(guarded).isEqualTo(mappings);
        assertThat(src).doesNotContain("deviceService.getDevice(deviceId)");
    }
}
