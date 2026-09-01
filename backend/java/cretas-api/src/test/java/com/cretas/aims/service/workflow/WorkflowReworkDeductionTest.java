package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回料自动扣减的算式。owner 2026-09-01 两次订正过语义，这个文件钉的是**最终那一版**。
 */
class WorkflowReworkDeductionTest {

    private static final String NODES = """
        [
          {"id":"material:rework","kind":"SEMI_FINISHED",
           "data":{"name":"金检剔出回料","skuId":"PT-REWORK","isRework":true}},
          {"id":"process:cure","kind":"PROCESS",
           "data":{"processName":"腌制",
                   "reworkPolicy":{"sources":[{"reworkNodeId":"material:rework","inputRatioPercent":8.5}]}}},
          {"id":"process:cook","kind":"PROCESS",
           "data":{"processName":"熟制",
                   "reworkPolicy":{"sources":[{"reworkNodeId":"material:rework","inputRatioPercent":10}]}}},
          {"id":"process:pack","kind":"PROCESS","data":{"processName":"装盒"}}
        ]
        """;

    @Test
    void 分母是主料而不是工序总投入() {
        // 🔴 本类最要紧的一条。owner 原话「比例的话是按照投入的原料或者半成品」:
        //    投 100kg 主料 + 8.5% ⇒ 回料投 8.5kg, 本工序共投 108.5kg。
        //    ⛔ 另一种读法(「总投入里 8.5% 是回料」)会算出 8.5kg 回料 + 91.5kg 主料 ——
        //    同一个百分比、两个差一个量级的结果, 而它直接进核算。
        assertThat(WorkflowReworkDeduction.deduct(new BigDecimal("100"), new BigDecimal("8.5")))
                .isEqualByComparingTo("8.5");
        // 阴性对照: 如果实现改成了「占总投入的比例」, 这个数会是 100*8.5/108.5 ≈ 7.834
        assertThat(WorkflowReworkDeduction.deduct(new BigDecimal("100"), new BigDecimal("8.5")))
                .isNotEqualByComparingTo("7.834101");
    }

    @Test
    void 逐工序算出扣减量() {
        List<WorkflowReworkDeduction.Line> lines = WorkflowReworkDeduction.compute(
                NODES, Map.of(
                        "process:cure", new BigDecimal("100"),
                        "process:cook", new BigDecimal("250")));
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.outcome()).isEqualTo(WorkflowReworkDeduction.Outcome.RESOLVED);
            assertThat(line.reworkSkuId()).isEqualTo("PT-REWORK");
        });
        assertThat(lines.stream().filter(l -> l.processNodeId().equals("process:cure")).findFirst())
                .get().extracting(WorkflowReworkDeduction.Line::deductQuantity)
                .satisfies(q -> assertThat((BigDecimal) q).isEqualByComparingTo("8.5"));
        assertThat(lines.stream().filter(l -> l.processNodeId().equals("process:cook")).findFirst())
                .get().extracting(WorkflowReworkDeduction.Line::deductQuantity)
                .satisfies(q -> assertThat((BigDecimal) q).isEqualByComparingTo("25"));
    }

    @Test
    void 多道工序各以自己的主料为分母_不要求合计一百() {
        // 8.5% + 10% = 18.5%，⛔ 不是一个需要凑成 100% 的分配比例。
        // 少了这条，有人会「顺手」加一道合计校验，把一张合法的图判成非法。
        List<WorkflowReworkDeduction.Line> lines = WorkflowReworkDeduction.compute(
                NODES, Map.of(
                        "process:cure", new BigDecimal("100"),
                        "process:cook", new BigDecimal("100")));
        BigDecimal total = lines.stream()
                .map(WorkflowReworkDeduction.Line::inputRatioPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("18.5");
        assertThat(lines).allSatisfy(line ->
                assertThat(line.outcome()).isEqualTo(WorkflowReworkDeduction.Outcome.RESOLVED));
    }

    @Test
    void 主料投入量拿不到时产出_算不出_而不是零() {
        // 🔴 「我不知道」和「该扣 0」对下游完全不同。兜底成 0 会把前者翻译成一个
        //    很有说服力的真实读数 —— 本仓为这个形状付过代价(COALESCE(food_cost,0))。
        List<WorkflowReworkDeduction.Line> lines = WorkflowReworkDeduction.compute(
                NODES, Map.of("process:cure", new BigDecimal("100")));   // cook 没给
        WorkflowReworkDeduction.Line cook = lines.stream()
                .filter(l -> l.processNodeId().equals("process:cook")).findFirst().orElseThrow();
        assertThat(cook.outcome()).isEqualTo(WorkflowReworkDeduction.Outcome.UNRESOLVED_MAIN_INPUT);
        assertThat(cook.deductQuantity()).isNull();
        assertThat(cook.mainInputQuantity()).isNull();
        // 阳性对照: 同一次调用里给了主料的那条**确实算出来了** —— 证明红不是因为整体没跑
        WorkflowReworkDeduction.Line cure = lines.stream()
                .filter(l -> l.processNodeId().equals("process:cure")).findFirst().orElseThrow();
        assertThat(cure.outcome()).isEqualTo(WorkflowReworkDeduction.Outcome.RESOLVED);
    }

    @Test
    void 没配回料规则的工序不产出任何扣减() {
        // 阴性对照：证明上面的绿不是「给每道工序都编一条」。
        List<WorkflowReworkDeduction.Line> lines = WorkflowReworkDeduction.compute(
                NODES, Map.of("process:pack", new BigDecimal("999")));
        assertThat(lines.stream().map(WorkflowReworkDeduction.Line::processNodeId))
                .doesNotContain("process:pack");
    }

    @Test
    void 比例超域视为没配而不是夹逼成边界() {
        // 夹逼成 100 会让一个明显写错的配置**静默生效**并扣掉全部主料量的回料。
        String bad = """
            [
              {"id":"material:rework","data":{"skuId":"PT-REWORK","isRework":true}},
              {"id":"process:x","data":{"reworkPolicy":{"sources":[
                 {"reworkNodeId":"material:rework","inputRatioPercent":150}]}}}
            ]
            """;
        assertThat(WorkflowReworkDeduction.compute(bad, Map.of("process:x", BigDecimal.TEN)))
                .isEmpty();
    }

    @Test
    void 比例为零是合法的_它不等于没配() {
        // 「登记了这条去向, 比例待定」是抽屉里明确允许的状态, 扣减量就该是 0。
        String zero = """
            [
              {"id":"material:rework","data":{"skuId":"PT-REWORK","isRework":true}},
              {"id":"process:x","data":{"reworkPolicy":{"sources":[
                 {"reworkNodeId":"material:rework","inputRatioPercent":0}]}}}
            ]
            """;
        List<WorkflowReworkDeduction.Line> lines =
                WorkflowReworkDeduction.compute(zero, Map.of("process:x", new BigDecimal("100")));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().outcome())
                .isEqualTo(WorkflowReworkDeduction.Outcome.RESOLVED);
        assertThat(lines.getFirst().deductQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void 空与坏输入一律空列表而不是抛异常() {
        assertThat(WorkflowReworkDeduction.compute(null, Map.of())).isEmpty();
        assertThat(WorkflowReworkDeduction.compute("", Map.of())).isEmpty();
        assertThat(WorkflowReworkDeduction.compute("{not json", Map.of())).isEmpty();
        // mainInput 传 null 不该崩 —— 只是全部算不出
        assertThat(WorkflowReworkDeduction.compute(NODES, null))
                .allSatisfy(line -> assertThat(line.outcome())
                        .isEqualTo(WorkflowReworkDeduction.Outcome.UNRESOLVED_MAIN_INPUT));
    }
}
