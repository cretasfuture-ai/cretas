package com.cretas.aims.service.yield.balance;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Level;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Qty;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Result;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Thresholds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口 #07: App 报工路 / web 逐道报工路 / web 平衡表 三处必须给出同一个结论 (同一份 MaterialBalanceCalculator)。
 * web 逐道路的入口是 {@code ProcessSheetServiceImpl.materialBalanceWarnings}; 平衡表入口是 {@code MaterialBalanceService.build}。
 */
class MaterialBalanceParityContractTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    /** 同一批数字: 投入 100kg, 主产出 70, 副产 5, 损耗 3, 留样 2 → 未解释 20 = 20% > 15% 红 */
    @Test
    @DisplayName("三路: 红色案例 (未解释 20%) 都出声, 数字一致")
    void redCase_allThreePathsAgree() {
        Result calc = MaterialBalanceCalculator.compute(d("100"), "kg", d("70"), "kg",
                List.of(new Qty(d("5"), "kg")), d("3"), d("2"), Thresholds.DEFAULT);
        assertThat(calc.level()).isEqualTo(Level.RED);
        assertThat(calc.unexplained()).isEqualByComparingTo("20");
        assertThat(calc.confirmedLoss()).isEqualByComparingTo("5");

        // web 逐道路 (无损耗字段: 3kg 损耗并入留样之外不存在, 用 留样 5 对齐同一个"已确认损耗 5")
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setInputQuantity(d("100"));
        req.setOutputQuantity(d("70"));
        req.setUnit("kg");
        ProcessChainEntryRequest.Byproduct b = new ProcessChainEntryRequest.Byproduct();
        b.setQuantity(d("5"));
        b.setUnit("kg");
        req.setByproducts(List.of(b));
        req.setSampleRetainQuantity(5);
        List<String> web = com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl
                .materialBalanceWarnings(req, Thresholds.DEFAULT);
        assertThat(web).singleElement().asString().startsWith("物料平衡偏差 20%");

        // 平衡表路: 一条报工, 同样的去向
        MaterialBalanceService svc = service();
        Map<String, Object> row = reportRow(1L, "100", "kg", "70", "kg", "3", 2, "[{\"quantity\":5,\"unit\":\"kg\"}]");
        var table = svc.build(List.of(row), Thresholds.DEFAULT);
        var line = table.getPlans().get(0).getRows().get(0);
        assertThat(line.getLevel()).isEqualTo("RED");
        assertThat(line.getUnexplained()).isEqualByComparingTo("20");
        assertThat(line.getUnexplainedPct()).isEqualByComparingTo("20.0");
        assertThat(MaterialBalanceCalculator.warningText(calc, "kg")).startsWith("物料平衡偏差 20%");
    }

    @Test
    @DisplayName("阈值可配置: 同一批数字在 25% 红线下不再是红, 三路同时变")
    void thresholdIsConfigurable_everywhere() {
        Thresholds loose = new Thresholds(d("0.10"), d("0.25"));
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setInputQuantity(d("100"));
        req.setOutputQuantity(d("80"));
        req.setUnit("kg");
        assertThat(com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl
                .materialBalanceWarnings(req, Thresholds.DEFAULT)).hasSize(1);
        assertThat(com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl
                .materialBalanceWarnings(req, loose)).isEmpty();
        var line = service().build(List.of(reportRow(1L, "100", "kg", "80", "kg", null, null, null)), loose)
                .getPlans().get(0).getRows().get(0);
        assertThat(line.getLevel()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("对平 → BALANCED; 5%~15% → WARN (黄); 单位换不了 → UNIT_INCOMPARABLE 且不给数字")
    void levelsAndIncomparable() {
        assertThat(lvl("100", "kg", "98", "kg")).isEqualTo(Level.BALANCED);
        assertThat(lvl("100", "kg", "90", "kg")).isEqualTo(Level.WARN);
        assertThat(lvl("100", "kg", "70", "kg")).isEqualTo(Level.RED);
        assertThat(lvl("2", "kg", "1900", "g")).isEqualTo(Level.BALANCED);
        Result r = MaterialBalanceCalculator.compute(d("100"), "kg", d("10"), "个", null, null, null, Thresholds.DEFAULT);
        assertThat(r.level()).isEqualTo(Level.UNIT_INCOMPARABLE);
        assertThat(r.unexplained()).isNull();
        var line = service().build(List.of(reportRow(1L, "100", "kg", "10", "个", null, null, null)), Thresholds.DEFAULT)
                .getPlans().get(0).getRows().get(0);
        assertThat(line.getLevel()).isEqualTo("UNIT_INCOMPARABLE");
        assertThat(line.getUnexplained()).isNull();
        assertThat(line.getNote()).contains("单位不可比");
    }

    @Test
    @DisplayName("三阶段报工: 同一任务的 INPUT 与 OUTPUT 报工合成一行, 不各算各的")
    void staged_reportsOfOneTaskAreMerged() {
        Map<String, Object> in = reportRow(1L, "100", "kg", null, "kg", null, null, null);
        Map<String, Object> out = reportRow(2L, null, null, "97", "kg", null, null, null);
        var line = service().build(List.of(in, out), Thresholds.DEFAULT).getPlans().get(0).getRows().get(0);
        assertThat(line.getLevel()).isEqualTo("BALANCED");
        assertThat(line.getInput()).isEqualByComparingTo("100");
        assertThat(line.getOutput()).isEqualByComparingTo("97");
        assertThat(line.getUnexplained()).isEqualByComparingTo("3");
    }

    private static Level lvl(String in, String inU, String out, String outU) {
        return MaterialBalanceCalculator.compute(d(in), inU, d(out), outU, null, null, null, Thresholds.DEFAULT).level();
    }

    private static MaterialBalanceService service() {
        return new MaterialBalanceService(mock(com.cretas.aims.repository.ProductionReportRepository.class),
                mock(com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions.class),
                mock(MaterialBalanceThresholdProvider.class), new ObjectMapper());
    }

    private static Map<String, Object> reportRow(long id, String in, String inU, String out, String outU,
                                                 String waste, Integer sample, String byproductsJson) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("report_id", id);
        m.put("task_id", 10L);
        m.put("process_order", 1);
        m.put("process_id", "wp1");
        m.put("process_name", "解冻");
        m.put("plan_id", "P1");
        m.put("plan_number", "PLAN-1");
        m.put("batch_number", "B-1");
        if (in != null) { m.put("input_quantity", d(in)); m.put("input_unit", inU); }
        if (out != null) { m.put("output_quantity", d(out)); m.put("output_unit", outU); }
        if (waste != null) m.put("waste_quantity", d(waste));
        if (sample != null) m.put("sample_retain_quantity", sample);
        if (byproductsJson != null) m.put("byproducts_json", byproductsJson);
        return m;
    }
}
