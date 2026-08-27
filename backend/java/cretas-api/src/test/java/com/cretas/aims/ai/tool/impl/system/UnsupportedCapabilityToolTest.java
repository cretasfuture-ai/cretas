package com.cretas.aims.ai.tool.impl.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UnsupportedCapabilityTool} 的行为闸。
 *
 * <p>重点不是「它返回了一段文字」，而是三件事：
 * <ol>
 *   <li>机器可读侧有 {@code supported=false} —— 只发 message 的话，
 *       下游埋点分不清「拒答」和「答了」；</li>
 *   <li>能从用户原话定位到是哪一项做不到；</li>
 *   <li>定位不到时<b>不编理由</b>。</li>
 * </ol>
 */
class UnsupportedCapabilityToolTest {

    private final UnsupportedCapabilityTool tool = new UnsupportedCapabilityTool();

    private Map<String, Object> execute(Map<String, Object> params, Map<String, Object> context)
            throws Exception {
        return tool.doExecute("F001", params, context);
    }

    @Test
    @DisplayName("问 SPC: 明确说做不了, 给出原因和替代, 且 supported=false")
    void spcQuestionGetsAnExplicitRefusal() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("question", "帮我看下这个月的SPC图表");

        Map<String, Object> result = execute(params, Map.of());

        assertThat(result.get("supported")).isEqualTo(false);
        assertThat(result.get("capabilityId")).isEqualTo("spc");

        String message = String.valueOf(result.get("message"));
        assertThat(message).contains("做不了");
        assertThat(message).contains("为什么");
        assertThat(message).contains("能给你的是");
        // 替代必须是系统真有的东西
        assertThat(message).contains("合格率");
        // ⛔ 阴性对照: 不许假装给了 SPC
        assertThat(message).doesNotContain("控制图已生成");
    }

    @Test
    @DisplayName("问因果/相关性: 说清相关不等于因果, 并给替代")
    void correlationQuestionGetsAnExplicitRefusal() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("question", "帮我做个因果分析");

        Map<String, Object> result = execute(params, Map.of());

        assertThat(result.get("supported")).isEqualTo(false);
        assertThat(result.get("capabilityId")).isEqualTo("correlation");
        String message = String.valueOf(result.get("message"));
        assertThat(message).contains("做不了");
        assertThat(message).contains("能给你的是");
        assertThat(message).contains("看不出是谁导致了谁");
    }

    @Test
    @DisplayName("原话在 context.request 里也能定位")
    void questionCanComeFromContext() throws Exception {
        Map<String, Object> context = new HashMap<>();
        context.put("request", "这两个指标有没有关联");

        Map<String, Object> result = execute(new HashMap<>(), context);

        assertThat(result.get("capabilityId")).isEqualTo("correlation");
    }

    @Test
    @DisplayName("显式 capabilityId 优先于从原话猜")
    void explicitCapabilityIdWins() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("capabilityId", "spc");
        params.put("question", "随便一句跟能力无关的话");

        Map<String, Object> result = execute(params, Map.of());

        assertThat(result.get("capabilityId")).isEqualTo("spc");
    }

    @Test
    @DisplayName("定位不到具体能力时: 仍然说做不了, 但不编一个理由出来")
    void unknownCapabilityStillRefusesButInventsNoReason() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("question", "帮我算一下今天的什么东西");

        Map<String, Object> result = execute(params, Map.of());

        assertThat(result.get("supported")).isEqualTo(false);
        assertThat(result.get("capabilityId")).isNull();

        String message = String.valueOf(result.get("message"));
        assertThat(message).contains("做不了");
        // ⛔ 关键阴性对照: 没定位到就不许搬 SPC / 因果 的理由来充数
        assertThat(message).doesNotContain("固定间隔取样");
        assertThat(message).doesNotContain("看不出是谁导致了谁");
    }

    @Test
    @DisplayName("只读, 无副作用")
    void toolIsReadOnly() {
        assertThat(tool.getAccessMode()).isEqualTo(UnsupportedCapabilityTool.AccessMode.READ);
        assertThat(tool.getToolName()).isEqualTo("unsupported_capability");
    }
}
