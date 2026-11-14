package com.cretas.aims.service.scale.sim;

import com.cretas.aims.dto.scale.ScaleDataParseResult;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子 SQL (V2026_01_04_11) 里的内置协议配置对不对得上它自己的样例帧。
 *
 * <p>这是「已知缺陷的棘轮」, 不是通过证明: 柯力 / 耀华 / 托利多的种子配置现在都读不对自己的样例帧
 * (见 docs/hardware/scale-integration-current-state.md §4)。这几条断言钉住「现在仍是坏的」,
 * 哪天有人用迁移修好了种子, 这里会红, 提醒他把模拟器测试切回种子配置并删掉本类对应断言。
 * 修种子必须等真秤抓到真实帧再改, 照样例帧猜着改没有意义。
 */
class ScaleSeedConformanceTest {

    private static final String KELI_SAMPLE_HEX = "2B303031323430206B6720530D0A";      // 种子 sample_data_hex
    private static final String YAOHUA_SAMPLE_HEX = "3D2B30303132352E3430206B6720530D0A";

    private ScaleDataParseResult parseWithSeed(String code, String protocolId, String hex) {
        ScaleProtocolAdapterServiceImpl a = ScaleSimSupport.adapter(
                ScaleSimSupport.config(code, ScaleSimSupport.seedFrameFormat(protocolId)));
        return a.parseScaleDataByCode(code, HexFormat.of().parseHex(hex));
    }

    @Test
    void keliSeedCannotReadItsOwnStableSample() {
        ScaleDataParseResult r = parseWithSeed("KELI_D2008_ASCII", "proto-keli-d2008-ascii", KELI_SAMPLE_HEX);
        // 种子 stable 下标 10 指到空格 (S 在 11)。加严前: 稳定帧被静默读成「不稳定」; 现在: 明确拒绝。
        // 种子样例用例 test-keli-001 期望 stable=true, 即种子与自己的样例矛盾。
        assertThat(r.getSuccess()).as("种子仍有缺陷: 修好后请删本断言并把模拟器测试切回种子配置").isFalse();
        assertThat(r.getErrorCode()).isEqualTo("INVALID_STABLE");
        assertThat(r.getWeight()).isNull();
    }

    @Test
    void yaohuaSeedMisreadsItsOwnSampleWithoutAnyError() {
        ScaleDataParseResult r = parseWithSeed("YAOHUA_XK3190_ASCII", "proto-yaohua-xk3190-ascii", YAOHUA_SAMPLE_HEX);
        // 种子字段下标对不上样例: 要么拒绝, 要么读出不是 125.40 的值; 不得是对的 (对了说明种子被修了)
        boolean correct = Boolean.TRUE.equals(r.getSuccess()) && r.getWeight() != null
                && r.getWeight().compareTo(new BigDecimal("125.40")) == 0
                && "kg".equals(r.getUnit()) && Boolean.TRUE.equals(r.getStable());
        assertThat(correct).as("种子仍有缺陷: 修好后请删本断言并把模拟器测试切回种子配置").isFalse();
    }

    @Test
    void toledoSeedCannotProduceAReadingFromAnyFrame() {
        ScaleProtocolAdapterServiceImpl a = ScaleSimSupport.adapter(ScaleSimSupport.config(
                "TOLEDO_IND570_ASCII", ScaleSimSupport.seedFrameFormat("proto-toledo-ind570")));
        // 种子是 ASCII_VARIABLE 却用 start/length 字段 (没有 delimiter/index): 任何输入都不会有重量,
        // 此前这种情况会返回 success=true + weight=null, 现在必须是明确失败。
        byte[] stxFrame = new byte[]{0x02, 'A', 'B', '0', '0', '1', '2', '5', '.', '4', '0', ' ', 'k', 'g', 0x03};
        ScaleDataParseResult r = a.parseScaleDataByCode("TOLEDO_IND570_ASCII", stxFrame);
        assertThat(r.getSuccess()).isFalse();
        assertThat(r.getWeight()).isNull();
    }
}
