package com.cretas.aims.ai.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「用户会问、但系统确实做不到」的能力登记表。
 *
 * <p><b>它解决的问题</b>：意图库里曾经把 {@code SPC图表} 映射到 {@code QUALITY_STATS}、
 * 把 {@code 因果分析} 映射到一个根本不存在的 {@code CORRELATION_ANALYSIS}。
 * 用户问了会拿到一个「看起来像那么回事」的质量统计，或者掉进兜底话术。
 * 两种都是把做不到的事情答应下来。</p>
 *
 * <p><b>登记的原则</b>：只登记有<b>正面证据</b>证明不存在的能力 ——
 * 即全仓找不到任何实现（Tool / 端点 / 计算代码 / 数据列）。
 * 「我没找到」不够，必须是「它不在」。</p>
 *
 * <p><b>措辞硬要求</b>：{@link UnsupportedCapability#why()} 与
 * {@link UnsupportedCapability#instead()} 是直接发给厂长/店长看的，
 * 里面<b>不许出现黑话</b>。可以用用户自己打出来的词（比如「SPC」）去指称
 * 他要的东西，但解释<b>为什么做不到</b>必须是人话。</p>
 *
 * <p>纯静态、无 Spring 依赖，方便被闸直接调用。</p>
 *
 * @author Cretas Team
 * @since 2026-08-26
 */
public final class UnsupportedCapabilityRegistry {

    private UnsupportedCapabilityRegistry() {
    }

    /**
     * 一条「做不到」的登记。
     *
     * @param id        稳定标识，用于日志/埋点
     * @param askedFor  用户要的东西，用他自己的说法
     * @param why       为什么做不到 —— 人话，不许黑话
     * @param instead   实际能给的东西 —— 人话，且必须是系统真有的
     * @param userTerms 命中这些词就算问的是这件事
     */
    public record UnsupportedCapability(
            String id,
            String askedFor,
            String why,
            List<String> instead,
            List<String> userTerms) {
    }

    /**
     * 路由到的意图码。意图本身绑定 {@code unsupported_capability} Tool
     * （见 flyway V20261031_02__unsupported_capability_intent.sql）。
     */
    public static final String INTENT_CODE = "UNSUPPORTED_CAPABILITY";

    private static final Map<String, UnsupportedCapability> BY_ID = new LinkedHashMap<>();

    private static void register(UnsupportedCapability capability) {
        BY_ID.put(capability.id(), capability);
    }

    static {
        // ── SPC ──────────────────────────────────────────────────────────
        // 证据（2026-08-26 全仓实测）：controlChart / control_chart / Cpk /
        // 统计过程控制 命中数均为 0（Cpk 的少量命中全部是 pnpm-lock 里的
        // base64 噪声）。SPC 的核心就是控制图 + 过程能力指数，两样都没有。
        // 另有旁证：tests/restaurant-full-e2e.py 的负面用例里
        // ('SPC控制图', False) —— 测试早就假定它不该命中任何意图。
        register(new UnsupportedCapability(
                "spc",
                "SPC、控制图、过程能力（Cpk）这类分析",
                "这类分析要求每道工序按固定间隔取样，并且把每一次量到的具体数值都记下来"
                        + "（比如每半小时称一次重量、测一次温度）。"
                        + "系统现在存的质检记录是「这一批合格还是不合格」，"
                        + "没有这种一次一次的实测数值，所以算不出来。",
                List.of(
                        "每批产品的质检合格率",
                        "不合格都出在哪几项、各占多少",
                        "合格率按时间的变化趋势"),
                List.of("SPC", "spc", "控制图", "管制图", "过程能力", "Cpk", "CPK", "cpk", "统计过程控制")));

        // ── 相关性 / 因果 ────────────────────────────────────────────────
        // 证据（2026-08-26 全仓实测）：意图码 CORRELATION_ANALYSIS 在整个仓库里
        // 只出现在 IntentKnowledgeBase.java 的 6 条映射上 —— 没有 Tool、
        // 没有 flyway seed、没有 Python 实现。
        // 反向旁证：Python 侧 smartbi/agent 明确<b>拒绝</b>因果归因
        //   (bounded_runtime.py: CAUSAL_ATTRIBUTION_UNSUPPORTED_BY_READ_CONTRACTS,
        //    synthesis_engine.py: 「相关≠因果」)。
        // 也就是说系统的一半在小心地拒绝因果结论，另一半却在把「因果分析」
        // 当成一个支持的说法登记着。
        register(new UnsupportedCapability(
                "correlation",
                "两个指标之间有没有关系、谁影响谁",
                "系统没有做这种计算的功能。而且就算把数据凑齐，"
                        + "也只能看出两件事是不是一起变，看不出是谁导致了谁 —— "
                        + "那需要专门做对照试验才能下结论。",
                List.of(
                        "把你关心的这几项数据放在同一段时间里列出来，你自己对着看",
                        "单独一项的变化趋势"),
                List.of("关联", "相关性", "因果", "影响因素")));
    }

    /** 全部登记项，按登记顺序。 */
    public static List<UnsupportedCapability> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<UnsupportedCapability> byId(String id) {
        return Optional.ofNullable(id).map(BY_ID::get);
    }

    /**
     * 判断一句用户输入问的是不是某个「做不到」的能力。
     *
     * @param text 用户原话（或意图库里的短语）
     * @return 命中的登记项；没命中返回 empty
     */
    public static Optional<UnsupportedCapability> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (UnsupportedCapability capability : BY_ID.values()) {
            for (String term : capability.userTerms()) {
                if (text.contains(term)) {
                    return Optional.of(capability);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 组装发给用户的答复。
     *
     * <p>形状固定成三段：<b>做不到</b> → <b>为什么</b> → <b>能给你什么</b>。
     * 第三段不能省 —— 只说「做不了」而不说替代，用户下一步无处可去，
     * 那和兜底话术一样没用。</p>
     */
    public static String answerFor(UnsupportedCapability capability) {
        StringBuilder sb = new StringBuilder();
        sb.append(capability.askedFor()).append("，系统做不了。\n\n");
        sb.append("为什么：").append(capability.why()).append("\n\n");
        sb.append("能给你的是：\n");
        for (String item : capability.instead()) {
            sb.append("· ").append(item).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
