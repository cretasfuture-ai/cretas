package com.cretas.aims.service.scale.sim;

import com.cretas.aims.dto.scale.ScaleDataParseResult;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import com.cretas.aims.service.scale.sim.ScaleSimSupport.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秤模拟器每个场景走线上解析入口: 好帧读出对的值, 坏帧必须拒绝 (success=false 且没有重量), 绝不能读成 0。
 */
class ScaleSimulatorFixtureParseTest {

    private final ScaleProtocolAdapterServiceImpl adapter = ScaleSimSupport.simAdapter();

    static List<Fixture> all() { return ScaleSimSupport.fixtures(); }

    @Test
    void fixturesCoverTheRequiredSituations() {
        List<String> names = all().stream().map(Fixture::toString).toList();
        // 稳定 / 不稳定 / 皮重(去皮后负数) / 负数 / 超量程 / 帧损坏: 每种协议各自覆盖
        assertThat(names).contains(
                "KELI_D2008_ASCII/stable", "KELI_D2008_ASCII/unstable", "KELI_D2008_ASCII/negative_after_tare",
                "KELI_D2008_ASCII/overrange", "KELI_D2008_ASCII/truncated", "KELI_D2008_ASCII/binary_garbage",
                "YAOHUA_XK3190_ASCII/stable", "YAOHUA_XK3190_ASCII/motion", "YAOHUA_XK3190_ASCII/negative",
                "MODBUS_RTU_STANDARD/normal", "MODBUS_RTU_STANDARD/negative", "MODBUS_RTU_STANDARD/bad_crc");
        assertThat(all().stream().filter(f -> !f.ok()).count()).isGreaterThanOrEqualTo(12);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void frameIsParsedOrRejectedAsTheSimulatorExpects(Fixture f) {
        ScaleDataParseResult r = adapter.parseScaleDataByCode(f.protocol(), f.bytes());

        if (f.ok()) {
            assertThat(r.getSuccess()).as("好帧应解析成功: %s -> %s", f, r.getErrorMessage()).isTrue();
            assertThat(r.getWeight()).isEqualByComparingTo(new BigDecimal(f.expect().get("weight").asText()));
            assertThat(r.getUnit()).isEqualTo(f.expect().get("unit").asText());
            assertThat(r.getStable()).isEqualTo(f.expect().get("stable").asBoolean());
        } else {
            assertThat(r.getSuccess()).as("坏帧必须被拒绝: %s, 却读成了 weight=%s", f, r.getWeight()).isFalse();
            assertThat(r.getWeight()).as("被拒绝的帧不能带重量 (更不能是 0)").isNull();
            assertThat(r.getErrorCode()).isNotBlank();
            if ("OVERRANGE".equals(f.expect().get("reject").asText())) {
                assertThat(r.getErrorCode()).isEqualTo("OVERRANGE");
            }
        }
    }

    @Test
    void unknownProtocolIsRejected() {
        ScaleDataParseResult r = adapter.parseScaleDataByCode("NO_SUCH", new byte[]{1, 2, 3});
        assertThat(r.getSuccess()).isFalse();
        assertThat(r.getWeight()).isNull();
    }
}
