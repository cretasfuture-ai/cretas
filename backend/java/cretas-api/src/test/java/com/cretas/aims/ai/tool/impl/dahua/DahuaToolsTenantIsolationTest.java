package com.cretas.aims.ai.tool.impl.dahua;

import com.cretas.aims.client.dahua.DahuaClient;
import com.cretas.aims.client.dahua.DahuaDiscoveryClient;
import com.cretas.aims.config.DahuaConfig;
import com.cretas.aims.entity.dahua.DahuaDevice;
import com.cretas.aims.repository.dahua.DahuaDeviceChannelRepository;
import com.cretas.aims.repository.dahua.DahuaDeviceRepository;
import com.cretas.aims.service.dahua.DahuaDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 大华 AI 工具: 设备 id 来自模型从对话里抽出的参数, 以前不看工厂 —— 别厂账号在对话里报出设备 id,
 * 就能测连接、取带密码的取流地址、抓拍、读写智能分析配置。现在按 id 的操作只认本厂设备。
 */
class DahuaToolsTenantIsolationTest {

    private static final String DEVICE = "dh-1";

    private DahuaClient client;
    private DahuaDeviceManageTool manage;
    private DahuaSmartConfigTool smart;

    @BeforeEach
    void setUp() {
        DahuaDeviceRepository repository = mock(DahuaDeviceRepository.class);
        client = mock(DahuaClient.class);
        DahuaDevice own = DahuaDevice.builder().id(DEVICE).factoryId("F006").ipAddress("10.0.0.8")
                .username("admin").passwordEncrypted("ENC(secret)").build();
        when(repository.findById(DEVICE)).thenReturn(Optional.of(own));
        DahuaDeviceService service = new DahuaDeviceService(repository, mock(DahuaDeviceChannelRepository.class),
                client, mock(DahuaDiscoveryClient.class), mock(DahuaConfig.class));
        manage = new DahuaDeviceManageTool();
        ReflectionTestUtils.setField(manage, "dahuaDeviceService", service);
        smart = new DahuaSmartConfigTool();
        ReflectionTestUtils.setField(smart, "dahuaDeviceService", service);
    }

    private static Map<String, Object> params(String... kv) {
        Map<String, Object> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void deviceManageRefusesAnotherFactorysDeviceForEveryByIdAction() {
        for (String action : new String[]{"test", "streams", "capture", "capabilities", "info"}) {
            assertThatThrownBy(() -> manage.doExecute("F007", params("action", action, "deviceId", DEVICE), Map.of()))
                    .as(action).hasMessageContaining("设备不存在");
        }
        verifyNoInteractions(client);
    }

    @Test
    void smartConfigRefusesAnotherFactorysDevice() {
        for (String feature : new String[]{"line", "intrusion", "face"}) {
            assertThatThrownBy(() -> smart.doExecute("F007",
                    params("deviceId", DEVICE, "feature", feature, "action", "query"), Map.of()))
                    .as(feature).hasMessageContaining("设备不存在");
        }
        verifyNoInteractions(client);
    }

    @Test
    void ownFactoryGetsTheStreamUrls() throws Exception {
        Map<String, Object> out = manage.doExecute("F006", params("action", "streams", "deviceId", DEVICE), Map.of());

        assertThat(out).containsKey("deviceId");
    }
}
