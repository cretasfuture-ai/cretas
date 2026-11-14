package com.cretas.aims.service.scale.sim;

import com.cretas.aims.dto.scale.ScaleDataParseResult;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「以前合法的真实写法, 加严后仍然能读」的阳性测试 (独立终审 #1705 提出)。
 * 坏帧明确报错的原则不变, 但不能把合法变体当坏帧。
 */
class ScaleParserVariantContractTest {

    private static ScaleProtocolAdapterServiceImpl adapterFor(String frameFormat) {
        return ScaleSimSupport.adapter(ScaleSimSupport.config("VARIANT", frameFormat));
    }

    private static ScaleDataParseResult parse(String frameFormat, byte[] frame) {
        return adapterFor(frameFormat).parseScaleDataByCode("VARIANT", frame);
    }

    // ---------------------------------------------------------------- 1. STX / ETX 标记

    private static final String STX_ETX_FORMAT = """
            {"frameType":"ASCII_FIXED","startMarker":"STX","endMarker":"ETX","fields":[
              {"name":"sign","start":1,"length":1},
              {"name":"weight","start":2,"length":6,"decimalPlaces":1},
              {"name":"unit","start":9,"length":2},
              {"name":"stable","start":12,"length":1,"mapping":{"S":true,"U":false}}]}""";

    private static byte[] stxFrame(String body, boolean withEtx) {
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[1 + b.length + (withEtx ? 1 : 0)];
        out[0] = 0x02;
        System.arraycopy(b, 0, out, 1, b.length);
        if (withEtx) out[out.length - 1] = 0x03;
        return out;
    }

    @Test
    void stxEtxFramedFrameIsReadWhenTheConfigDeclaresTheMarkers() {
        ScaleDataParseResult r = parse(STX_ETX_FORMAT, stxFrame("+001240 kg S", true));
        assertThat(r.getSuccess()).as("STX/ETX 帧: %s", r.getErrorMessage()).isTrue();
        assertThat(r.getWeight()).isEqualByComparingTo(new BigDecimal("124.0"));
        assertThat(r.getUnit()).isEqualTo("kg");
        assertThat(r.getStable()).isTrue();
    }

    @Test
    void stxEtxNegativeControls() {
        // 缺 ETX (其它结束标记仍然必须有)
        assertThat(parse(STX_ETX_FORMAT, stxFrame("+001240 kg S", false)).getSuccess()).isFalse();
        // 帧中间混进配置没声明的控制字符 0x01
        byte[] dirty = stxFrame("+0012\u000140 kg S", true);
        assertThat(parse(STX_ETX_FORMAT, dirty).getSuccess()).isFalse();
    }

    // ---------------------------------------------------------------- 2. 换行类结尾可选 (模拟器 fixtures 另覆盖 CR / LF / 无结尾)

    @Test
    void newlineTerminatorIsOptionalForNewlineEndMarkers() {
        String fmt = ScaleSimSupport.seedFrameFormat("proto-keli-d2008-ascii");
        for (String frame : new String[]{"+001240 kg S\r\n", "+001240 kg S\r", "+001240 kg S\n", "+001240 kg S"}) {
            ScaleDataParseResult r = parse(fmt, frame.getBytes(StandardCharsets.US_ASCII));
            assertThat(r.getSuccess()).as("结尾变体 %s: %s", frame.replace("\r", "\\r").replace("\n", "\\n"), r.getErrorMessage()).isTrue();
            assertThat(r.getWeight()).isEqualByComparingTo(new BigDecimal("124.0"));
        }
        // 真正的半帧 (稳定标志没到) 仍然被拒
        assertThat(parse(fmt, "+001240 kg ".getBytes(StandardCharsets.US_ASCII)).getSuccess()).isFalse();
    }

    // ---------------------------------------------------------------- 3. 重量域自带符号 / "12." 写法

    private static final String SIGN_IN_WEIGHT_FORMAT = """
            {"frameType":"ASCII_FIXED","fields":[
              {"name":"weight","start":0,"length":7},
              {"name":"unit","start":8,"length":2}]}""";

    @Test
    void signInsideTheWeightFieldIsRead() {
        ScaleDataParseResult neg = parse(SIGN_IN_WEIGHT_FORMAT, "  -12.5 kg".getBytes(StandardCharsets.US_ASCII));
        assertThat(neg.getSuccess()).as(neg.getErrorMessage()).isTrue();
        assertThat(neg.getWeight()).isEqualByComparingTo(new BigDecimal("-12.5"));

        ScaleDataParseResult pos = parse(SIGN_IN_WEIGHT_FORMAT, "  +12.5 kg".getBytes(StandardCharsets.US_ASCII));
        assertThat(pos.getWeight()).isEqualByComparingTo(new BigDecimal("12.5"));
    }

    @Test
    void trailingDotAndLeadingDotWeightsAreRead() {
        assertThat(parse(SIGN_IN_WEIGHT_FORMAT, "    12. kg".getBytes(StandardCharsets.US_ASCII)).getWeight())
                .isEqualByComparingTo(new BigDecimal("12"));
        assertThat(parse(SIGN_IN_WEIGHT_FORMAT, "     .5 kg".getBytes(StandardCharsets.US_ASCII)).getWeight())
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void separateSignFieldStillNegatesAsBefore() {
        String fmt = ScaleSimSupport.seedFrameFormat("proto-keli-d2008-ascii");
        ScaleDataParseResult r = parse(fmt, "-000520 kg S\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(r.getWeight()).isEqualByComparingTo(new BigDecimal("-52.0"));
    }

    @Test
    void malformedWeightsAreStillRejected() {
        for (String w : new String[]{"      -", "   --5.0", "  1.2.3", "    1e3", "   12-5"}) {
            ScaleDataParseResult r = parse(SIGN_IN_WEIGHT_FORMAT, (w + " kg").getBytes(StandardCharsets.US_ASCII));
            assertThat(r.getSuccess()).as("'%s' 必须被拒", w).isFalse();
            assertThat(r.getWeight()).isNull();
        }
    }
}
