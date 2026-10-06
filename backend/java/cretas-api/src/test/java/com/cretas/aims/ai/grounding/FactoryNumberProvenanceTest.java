package com.cretas.aims.ai.grounding;

import com.cretas.aims.ai.grounding.FactoryNumberProvenance.Decision;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance.NumberToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数字出处闸的规则表。每条「不算数 / 算有出处」的规则都配一条能让它红的对照:
 * 只证明「不报」的断言, 在一个什么都不报的闸上也是绿的。
 */
class FactoryNumberProvenanceTest {

    private final FactoryNumberProvenance gate = new FactoryNumberProvenance();

    private List<String> raws(String text) {
        return gate.extract(text).stream().map(NumberToken::raw).toList();
    }

    private List<String> unsourced(String answer, Object... sources) {
        return gate.audit(answer, sources).unsourced().stream().map(v -> v.token().raw()).toList();
    }

    @Nested
    @DisplayName("(c) 不算「数字」的东西")
    class NotNumbers {

        @Test
        @DisplayName("日期 / 时刻 / 年月 / markdown 里的日期都不抽")
        void datesAreNotNumbers() {
            assertThat(raws("截至 2026-10-03 08:30, 2026/9/1, 2026年10月3日, 10月3日, 2026-09, 9月份"))
                    .isEmpty();
            assertThat(raws("2026 年 **8** 月营收")).as("** 不能把日期切断").isEmpty();
            // 红对照: 同一句里放一个真数, 它必须被抽出来 —— 证明上面的空不是「抽取器瞎了」
            assertThat(raws("截至 2026-10-03 库存 38.5kg")).containsExactly("38.5");
        }

        @Test
        @DisplayName("编码 / 标识符 / 省略号截断的编码尾巴 / 长纯数字 不抽")
        void codesAndIdsAreNotNumbers() {
            assertThat(raws("工厂 F006 批次 SL-CATTLE-005 原料 MT-20260903-4578-WKS-03b44cd6 Q3 Top5 B2026001"))
                    .isEmpty();
            assertThat(raws("批次 PB-PLAN-...-29206 与 PB_X_17")).isEmpty();
            assertThat(raws("耳标 110370281000005, 手机 13800138000")).isEmpty();
            // 红对照: 编码后面跟着真正的数量, 数量要被抽到
            assertThat(raws("SL-CATTLE-005(523kg)")).containsExactly("523");
            // 10 位以内的整数不当标识符: 金额要能被核对
            assertThat(raws("营收 1234567890 元")).containsExactly("1234567890");
        }

        @Test
        @DisplayName("列表序号 / 第N步 / 时间窗口 / TopN 口径 不抽")
        void ordinalsAndWindowsAreNotNumbers() {
            String text = "## 1. 概览\n2、风险\n(3) 建议\n建议：1. 先核对\n第2步 复核\n"
                    + "近7天 最近 30 天 过去3个月 7天内 前5名 Top 10";
            assertThat(raws(text)).isEmpty();
            // 红对照: 1.5kg 不是序号, 「提前 30% 预警」不是 TopN
            assertThat(raws("1.5kg 原料")).containsExactly("1.5");
            assertThat(raws("建议提前 30% 时间预警")).containsExactly("30%");
        }

        @Test
        @DisplayName("区间: 50-60 与 98%-99% 的后一个数不是负数")
        void rangesAreNotNegative() {
            List<NumberToken> t = gate.extract("原料占比 50-60%, 良品率 98%-99%");
            assertThat(t).extracting(NumberToken::mantissa)
                    .containsExactly(new BigDecimal("50"), new BigDecimal("60"),
                            new BigDecimal("98"), new BigDecimal("99"));
            // 红对照: 真正的负数仍是负数
            assertThat(gate.extract("净利润 -12.5 万").get(0).mantissa()).isEqualByComparingTo("-12.5");
        }
    }

    @Nested
    @DisplayName("(d) 算有出处的四条形式改写 —— 每条都配一条红对照")
    class SourcedForms {

        @Test
        void sameValueIgnoresTrailingZerosAndThousandsSeparators() {
            assertThat(unsourced("营收 ¥1,234,567.00, 库存 38.0kg", Map.of("revenue", 1234567, "stock", 38)))
                    .isEmpty();
            assertThat(unsourced("营收 ¥1,234,568", Map.of("revenue", 1234567))).containsExactly("1,234,568");
        }

        @Test
        void roundingToShownPrecision() {
            assertThat(unsourced("单位成本 12.35 元, 约 12.3 元", Map.of("unitCost", new BigDecimal("12.3456"))))
                    .isEmpty();
            assertThat(unsourced("合格率 97%", Map.of("passRate", new BigDecimal("96.7")))).isEmpty();
            // 红对照: 整数舍入窗口不能宽到吞掉另一个量 (6.4 不是「6 个」的出处)
            assertThat(unsourced("共 6 个批次", Map.of("avgCost", new BigDecimal("6.4")))).containsExactly("6");
            assertThat(unsourced("余额 0 元", Map.of("balance", new BigDecimal("0.3")))).containsExactly("0");
        }

        @Test
        void ratioAndPercentAreTheSameValue() {
            assertThat(unsourced("良品率 85.3%", Map.of("yield", new BigDecimal("0.853")))).isEmpty();
            assertThat(unsourced("良品率 85.3%", Map.of("yield", new BigDecimal("85.3")))).isEmpty();
            assertThat(unsourced("占比 0.85", "占比 85%")).isEmpty();
            // 红对照
            assertThat(unsourced("良品率 86.3%", Map.of("yield", new BigDecimal("0.853")))).containsExactly("86.3%");
        }

        @Test
        void wanAndYiScaling() {
            assertThat(unsourced("营收 12.35万元", Map.of("revenue", 123456))).isEmpty();
            assertThat(unsourced("营收 12.35万元", "营收 12.35万")).isEmpty();
            assertThat(unsourced("资产 1.2亿", Map.of("assets", 120_400_000))).isEmpty();
            // 2 位有效数字只认 1% 以内: 1.23 亿写成「1.2亿」(差 2.5%) 不认 —— 严格是有意的
            assertThat(unsourced("资产 1.2亿", Map.of("assets", 123_000_000))).containsExactly("1.2亿");
            // 千克的「千」不是量级
            assertThat(gate.extract("5千克").get(0).multiplier()).isEqualByComparingTo("1");
            // 红对照: 粗到 1 位有效数字的「2 万」不认 15000~29999 里随便一个数
            assertThat(unsourced("欠款超过 2 万元", Map.of("balance", 23000))).containsExactly("2 万");
        }

        @Test
        void signMayBeExpressedInWords() {
            assertThat(unsourced("净利润下降 12%", Map.of("change", -12))).isEmpty();
            assertThat(unsourced("净利润下降 13%", Map.of("change", -12))).containsExactly("13%");
        }

        @Test
        void listSizeIsInTheSource() {
            assertThat(unsourced("共 3 个客户", Map.of("customers", List.of("a", "b", "c")))).isEmpty();
            assertThat(unsourced("共 4 个客户", Map.of("customers", List.of("a", "b", "c")))).containsExactly("4");
        }

        @Test
        @DisplayName("⛔ 不认算术派生: 合计 / 占比 / 倍数 —— 这是故意的")
        void arithmeticDerivationIsNotAccepted() {
            Map<String, Object> src = Map.of("a", new BigDecimal("101.41"), "b", new BigDecimal("5.0"));
            assertThat(unsourced("合计 106.41 元, 是后者的 20.28 倍", src))
                    .containsExactly("106.41", "20.28");
        }
    }

    @Nested
    @DisplayName("放行判定")
    class Decisions {

        private static final String SITE = "unit-test";

        @Test
        @DisplayName("(a) 只有工具数 → 原样放行, 模板不被求值")
        void passesUnchangedWhenAllSourced() {
            AtomicInteger fallbackCalls = new AtomicInteger();
            String answer = "本月营收 ¥12,345.60, 共 3 个客户。";
            FactoryNumberProvenance.Result r = gate.enforce(SITE, answer,
                    () -> { fallbackCalls.incrementAndGet(); return "模板"; },
                    Map.of("revenue", new BigDecimal("12345.6"), "customers", List.of(1, 2, 3)));
            assertThat(r.decision()).isEqualTo(Decision.PASS);
            assertThat(r.text()).isEqualTo(answer);
            assertThat(fallbackCalls).hasValue(0);
        }

        @Test
        @DisplayName("(b) 有编造的数 + 有模板 → 换成模板")
        void fallsBackToTemplate() {
            FactoryNumberProvenance.Result r = gate.enforce(SITE, "本月营收 ¥12,345.60, 环比增长 43568.21%。",
                    () -> "营收: 12345.6", Map.of("revenue", new BigDecimal("12345.6")));
            assertThat(r.decision()).isEqualTo(Decision.FALLBACK_TEMPLATE);
            assertThat(r.text()).isEqualTo("营收: 12345.6");
        }

        @Test
        @DisplayName("(b) 有编造的数 + 没有模板 → 删掉那一句并加注, 有出处的句子保留")
        void redactsSentenceWithoutTemplate() {
            String answer = "本月营收 ¥12,345.60，客户 3 家。\n预计下月可节省 8,600 元。\n建议优先跟进重点客户的回款进度。";
            FactoryNumberProvenance.Result r = gate.enforce(SITE, answer, null,
                    Map.of("revenue", new BigDecimal("12345.6"), "customers", List.of(1, 2, 3)));
            assertThat(r.decision()).isEqualTo(Decision.REDACTED);
            assertThat(r.text()).doesNotContain("8,600").doesNotContain("节省")
                    .contains("本月营收 ¥12,345.60").contains("建议优先跟进重点客户的回款进度")
                    .contains(String.format(FactoryNumberProvenance.REDACTION_NOTE_TEMPLATE, 1));
        }

        @Test
        @DisplayName("删完几乎什么都不剩 → 整段换成一句说明")
        void nothingLeft() {
            String r = gate.enforceText(SITE, "环比增长 37.4%。", Map.of("revenue", 1));
            assertThat(r).isEqualTo(FactoryNumberProvenance.NOTHING_LEFT_MESSAGE);
        }

        @Test
        @DisplayName("每次判定必计一次数 (「它确实跑了」的对照), 计到正确的判定上")
        void everyDecisionIsCounted() {
            String site = "count-test";
            long before = gate.total(site);
            gate.enforceText(site, "营收 1 元", Map.of("r", 1));
            gate.enforceText(site, "营收 2 元", Map.of("r", 1));
            gate.enforceText(site, "没有数字", Map.of("r", 1));
            assertThat(gate.total(site) - before).isEqualTo(3);
            assertThat(gate.count(site, Decision.PASS)).isEqualTo(1);
            assertThat(gate.count(site, Decision.REDACTED)).isEqualTo(1);
            assertThat(gate.count(site, Decision.NO_NUMBERS)).isEqualTo(1);
        }

        @Test
        @DisplayName("源池为空 → 正文里的数全部无出处 (不是全部放行)")
        void emptySourcePoolFlagsEverything() {
            FactoryNumberProvenance.Audit a = gate.audit("良品率 95%, 产量 120kg");
            assertThat(a.sourcePoolSize()).isZero();
            assertThat(a.unsourced()).hasSize(2);
            assertThat(a.hitRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("POJO / 嵌套结构 / JSON 字符串里的数都进源池")
        void walksNestedSources() {
            record Row(String name, BigDecimal amount, List<Integer> ids) { }
            Object src = Map.of("rows", List.of(new Row("x", new BigDecimal("88.8"), List.of(7, 8))),
                    "raw", "{\"total\": 1024}");
            assertThat(unsourced("金额 88.8, 总计 1024, 共 2 个编号", src)).isEmpty();
        }
    }
}
