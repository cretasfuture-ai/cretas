package com.cretas.aims.controller;

import com.cretas.aims.client.dahua.DahuaClient;
import com.cretas.aims.client.dahua.DahuaDiscoveryClient;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.dahua.DahuaDeviceDTO;
import com.cretas.aims.entity.dahua.DahuaDevice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.dahua.DahuaDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 大华设备 8 个按 id 的接口: 只认 URL 里本厂的设备。以前直接 findById, 别厂账号知道设备 id 就能改 IP / 账号密码、
 * 删除、取带密码的取流地址、抓拍。别厂的设备一律 404, 且不连设备、不写库。
 */
class DahuaDeviceTenantIsolationTest {

    private static final String DEVICE = "dh-1";

    private DahuaDeviceRepository repository;
    private DahuaClient client;
    private DahuaDevice own;
    private DahuaDeviceController controller;

    @BeforeEach
    void setUp() {
        repository = mock(DahuaDeviceRepository.class);
        client = mock(DahuaClient.class);
        own = DahuaDevice.builder().id(DEVICE).factoryId("F006").deviceName("门口").ipAddress("10.0.0.8")
                .port(80).username("admin").passwordEncrypted("ENC(secret)").build();
        when(repository.findById(DEVICE)).thenReturn(Optional.of(own));
        when(repository.save(any(DahuaDevice.class))).thenAnswer(inv -> inv.getArgument(0));
        controller = new DahuaDeviceController(repository, client, mock(DahuaDiscoveryClient.class));
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("PUT /{deviceId}", (BiFunction<DahuaDeviceController, String, Object>)
                        (c, f) -> c.updateDevice(f, DEVICE, DahuaDeviceDTO.builder().ipAddress("10.9.9.9").build())),
                Arguments.of("DELETE /{deviceId}", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.deleteDevice(f, DEVICE)),
                Arguments.of("GET /{deviceId}", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.getDevice(f, DEVICE)),
                Arguments.of("POST /test-connection", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.testConnection(f, DEVICE)),
                Arguments.of("POST /sync", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.syncDeviceInfo(f, DEVICE)),
                Arguments.of("GET /streams", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.getStreamUrls(f, DEVICE, 1)),
                Arguments.of("POST /capture", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.capturePicture(f, DEVICE, 1)),
                Arguments.of("GET /capture/image", (BiFunction<DahuaDeviceController, String, Object>) (c, f) -> c.capturePictureAsImage(f, DEVICE, 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void anotherFactorysDeviceIsNotFoundAndNothingIsTouched(String name,
                                                           BiFunction<DahuaDeviceController, String, Object> call) {
        Throwable thrown = catchThrowable(() -> {
            Object r = call.apply(controller, "F007");
            if (r instanceof ResponseEntity<?> resp) {
                assertThat(resp.getStatusCode().value()).isEqualTo(404);
            } else {
                throw new AssertionError("应当 404, 实际返回: " + r);
            }
        });
        if (thrown != null) {
            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getCode()).isEqualTo(404);
        }
        verifyNoInteractions(client);
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
        assertThat(own.getIpAddress()).isEqualTo("10.0.0.8");
    }

    @Test
    void ownFactoryReadsItsDeviceWithoutTheEncryptedPassword() throws Exception {
        ApiResponse<DahuaDevice> resp = controller.getDevice("F006", DEVICE);

        assertThat(resp.getData()).isSameAs(own);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(resp.getData());
        assertThat(json).contains("\"username\":\"admin\"").doesNotContain("passwordEncrypted").doesNotContain("ENC(secret)");
    }

    @Test
    void ownFactoryCanStillUpdateAndDelete() {
        controller.updateDevice("F006", DEVICE, DahuaDeviceDTO.builder().deviceName("后门").build());
        controller.deleteDevice("F006", DEVICE);

        assertThat(own.getDeviceName()).isEqualTo("后门");
        verify(repository).save(own);
        verify(repository).deleteById(DEVICE);
    }
}
