package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守: owner 的「填 30 吨 ⇒ 自动跳 70 吨」+ 份额从 nodesJson 端口读得出来。
 * 阴性对照: 无份额 / 份额 0 不推; OUTPUT 端口上的份额不算; 非法 JSON 不抛。
 */
class InputShareRatiosTest {

    private static final String NODES = "[{\"id\":\"p1\",\"kind\":\"PROCESS\",\"data\":{\"ports\":["
            + "{\"id\":\"in:a\",\"direction\":\"INPUT\",\"inputShareRatio\":30},"
            + "{\"id\":\"in:b\",\"direction\":\"INPUT\",\"inputShareRatio\":70},"
            + "{\"id\":\"in:c\",\"direction\":\"INPUT\"},"
            + "{\"id\":\"out:x\",\"direction\":\"OUTPUT\",\"inputShareRatio\":50}]}},"
            + "{\"id\":\"m1\",\"kind\":\"RAW_MATERIAL\",\"data\":{\"name\":\"x\"}}]";

    @Test
    @DisplayName("漂移闸: parsePercent 是编译器与领料唯一的份额解析 —— 合法值两边同收, 越界/非数字两边同拒")
    void parsePercentIsTheSingleJudge() {
        assertThat(InputShareRatios.parsePercent(null)).isNull();
        assertThat(InputShareRatios.parsePercent(0)).isEqualByComparingTo("0");
        assertThat(InputShareRatios.parsePercent(30)).isEqualByComparingTo("30");
        assertThat(InputShareRatios.parsePercent(70.5)).isEqualByComparingTo("70.5");
        assertThat(InputShareRatios.parsePercent(new BigDecimal("100"))).isEqualByComparingTo("100");
        for (Object bad : new Object[] {150, -1, "x", Double.NaN}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> InputShareRatios.parsePercent(bad))
                    .as("编译器会拒的值, 这里也必须拒: " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 同一批值经 fromNodesJson: 合法的进表, 编译器会拒的一个都不进 (⛔ 不再是「任意 Number 照收」)
        String nodes = "[{\"id\":\"p\",\"kind\":\"PROCESS\",\"data\":{\"ports\":["
                + "{\"id\":\"ok\",\"direction\":\"INPUT\",\"inputShareRatio\":70.5},"
                + "{\"id\":\"over\",\"direction\":\"INPUT\",\"inputShareRatio\":150},"
                + "{\"id\":\"neg\",\"direction\":\"INPUT\",\"inputShareRatio\":-1},"
                + "{\"id\":\"str\",\"direction\":\"INPUT\",\"inputShareRatio\":\"x\"}]}}]";
        Map<String, BigDecimal> s = InputShareRatios.fromNodesJson(nodes);
        assertThat(s).containsOnlyKeys("ok");
        assertThat(s.get("ok")).isEqualByComparingTo("70.5");
    }

    @Test
    @DisplayName("从 nodesJson 读份额: 只收 INPUT 且非 null; OUTPUT 上的份额不算")
    void readsInputSharesOnly() {
        Map<String, BigDecimal> s = InputShareRatios.fromNodesJson(NODES);
        assertThat(s).containsOnlyKeys("in:a", "in:b");
        assertThat(s.get("in:b")).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("owner 原话: 30/70, 填 a=30 吨 ⇒ b=70 吨")
    void thirtyToSeventy() {
        Map<String, BigDecimal> d = InputShareRatios.derive(
                Map.of("in:a", new BigDecimal("30"), "in:b", new BigDecimal("70")),
                "in:a", new BigDecimal("30"));
        assertThat(d).containsOnlyKeys("in:b");
        assertThat(d.get("in:b")).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("份额之和不必是 100: 3/7 也推出 30 ⇒ 70 (相对量)")
    void relativeSharesNormalize() {
        Map<String, BigDecimal> d = InputShareRatios.derive(
                Map.of("in:a", new BigDecimal("3"), "in:b", new BigDecimal("7")),
                "in:a", new BigDecimal("30"));
        assertThat(d.get("in:b")).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("阴性对照: 填的那个端口没有份额 → 不推 (⛔ 不猜)")
    void filledWithoutShareDerivesNothing() {
        assertThat(InputShareRatios.derive(Map.of("in:b", new BigDecimal("70")),
                "in:a", new BigDecimal("30"))).isEmpty();
    }

    @Test
    @DisplayName("阴性对照: 份额 0 的端口不参与 —— 不被推成 0 kg (organizer 自查项)")
    void zeroShareIsNotDerived() {
        Map<String, BigDecimal> d = InputShareRatios.derive(
                Map.of("in:a", new BigDecimal("30"), "in:b", new BigDecimal("70"), "in:z", BigDecimal.ZERO),
                "in:a", new BigDecimal("30"));
        assertThat(d).containsOnlyKeys("in:b");
    }

    @Test
    @DisplayName("阴性对照: 非法 JSON → 空表, 不抛 (建单不能因它拦)")
    void invalidJsonIsEmpty() {
        assertThat(InputShareRatios.fromNodesJson("not json")).isEmpty();
    }
}
