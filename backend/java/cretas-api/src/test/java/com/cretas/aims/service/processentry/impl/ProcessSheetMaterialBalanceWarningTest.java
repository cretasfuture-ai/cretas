package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** web 正式报工的物料平衡提示 (能力核查缺口「web 路无物料平衡校验」)。规则与 App 报工同为 15%, 只提示不拦。 */
class ProcessSheetMaterialBalanceWarningTest {

    private static ProcessSheetRowRequest req(String in, String out) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setInputQuantity(in == null ? null : new BigDecimal(in));
        r.setOutputQuantity(out == null ? null : new BigDecimal(out));
        r.setUnit("kg");
        return r;
    }

    @Test
    @DisplayName("偏差超过 15% 提示, 带具体数字")
    void overThreshold_warns() {
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(req("100", "70")))
                .singleElement().asString().startsWith("物料平衡偏差 30% (投入 100kg, 产出 70kg");
    }

    @Test
    @DisplayName("15% 以内 (含副产与留样) 不提示")
    void withinThreshold_silent() {
        ProcessSheetRowRequest r = req("100", "70");
        ProcessChainEntryRequest.Byproduct b = new ProcessChainEntryRequest.Byproduct();
        b.setQuantity(new BigDecimal("18"));
        b.setUnit("kg");
        r.setByproducts(List.of(b));
        r.setSampleRetainQuantity(2);
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).isEmpty();
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(req("100", "86"))).isEmpty();
    }

    @Test
    @DisplayName("g 与 kg 换算后比较")
    void gramsConverted() {
        ProcessSheetRowRequest r = req("2", "1900");
        r.setInputUnit("kg");
        r.setOutputUnit("g");
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).isEmpty();
        r.setOutputQuantity(new BigDecimal("1000"));
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).singleElement().asString()
                .startsWith("物料平衡偏差 50%");
    }

    @Test
    @DisplayName("单位不可比 (kg 投入 / 个 产出) 不算偏差")
    void incomparableUnits_silent() {
        ProcessSheetRowRequest r = req("100", "10");
        r.setInputUnit("kg");
        r.setOutputUnit("个");
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).isEmpty();
    }

    @Test
    @DisplayName("有产出但一点投入都没登记 → 提示追溯会断; 登记了原料或上游就不提示")
    void noInputAtAll_warns() {
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(req(null, "20")))
                .singleElement().asString().contains("没有登记任何投入");

        ProcessSheetRowRequest withRaw = req(null, "20");
        ProcessSheetRowRequest.RawInput raw = new ProcessSheetRowRequest.RawInput();
        raw.setMaterialBatchId("MB-1");
        raw.setQuantity(new BigDecimal("21"));
        withRaw.setRawMaterialInputs(List.of(raw));
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(withRaw)).isEmpty();

        ProcessSheetRowRequest withUpstream = req(null, "20");
        withUpstream.setUpstreamSources(List.of(new ProcessSheetRowRequest.UpstreamRef()));
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(withUpstream)).isEmpty();
    }

    @Test
    @DisplayName("多产出: 同单位合计后比较; 单位不一致不比")
    void multiOutput() {
        ProcessSheetRowRequest r = req("100", null);
        ProcessSheetRowRequest.OutputLine a = new ProcessSheetRowRequest.OutputLine();
        a.setQuantity(new BigDecimal("50"));
        a.setUnit("kg");
        ProcessSheetRowRequest.OutputLine b = new ProcessSheetRowRequest.OutputLine();
        b.setQuantity(new BigDecimal("40"));
        b.setUnit("kg");
        r.setOutputs(List.of(a, b));
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).isEmpty();
        b.setQuantity(new BigDecimal("10"));
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).singleElement().asString()
                .startsWith("物料平衡偏差 40%");
        b.setUnit("个");
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(r)).isEmpty();
    }
}
