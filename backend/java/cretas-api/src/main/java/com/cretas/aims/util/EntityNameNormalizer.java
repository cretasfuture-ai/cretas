package com.cretas.aims.util;

/**
 * 实体名(产品/物料/工序)落库前的空白归一 —— 域内<b>唯一</b>承载点。
 *
 * <h2>被治的那个缺陷</h2>
 *
 * 2026-08-12 实测(25 个真实产品, prod F006 真值): LLM 抽取时会在
 * <b>连字符两侧</b>与<b>数字/中文交界处</b>插空格, 25 条里有 17 条被改写:
 *
 * <pre>
 *   CP-普冷-战斧牛排         -&gt; CP-普冷 - 战斧牛排
 *   468礼盒                  -&gt; 468 礼盒
 *   CP-普冷-牛肉卷加工300g   -&gt; CP-普冷 - 牛肉卷加工 300g
 * </pre>
 *
 * 这是<b>格式化</b>不是理解失败 —— 召回其实是 100%(一个没漏)。
 *
 * <h2>⛔ 为什么必须治</h2>
 *
 * {@code ProductTypeRepository#findByFactoryIdAndName} 的 javadoc 写着
 * 「<b>用于 Excel 导入时按名称解析产品类型</b>」。建厂存进
 * {@code CP-普冷 - 战斧牛排}, 而用户 Excel 里写 {@code CP-普冷-战斧牛排} ——
 * <b>查不到, 而且静默失败</b>(返回 {@code Optional.empty()}, 没有任何一层说过话)。
 *
 * <p>⚠️ 不影响唯一约束: {@code product_types} 约束在 {@code (factory_id, code)} 上,
 * 名字被改写<b>不会</b>造重复行 —— 它坏的是<b>下游按名字的匹配</b>。
 *
 * <h2>⛔ 刻意不做的事</h2>
 *
 * <b>不去掉所有空白。</b> 合法的名字里有空格(英文品名如 {@code New York Strip}、
 * 规格串里的空格)。只治<b>实测见过的两种插入</b>, 其余空白只做 trim 与折叠。
 * 「拿不出数据就不编规则」—— 本仓的判据。
 *
 * <h2>用法</h2>
 *
 * 建厂链路(以及任何新的实体写入口)在落库前调 {@link #normalize(String)}。
 * 已登记进 {@code scripts/tests/authority-bypass-registry.txt} 的第②级,
 * 新写入口绕过去会被闸看见。
 */
public final class EntityNameNormalizer {

    private EntityNameNormalizer() {
    }

    /** 连字符(半角/全角)两侧的空白 —— 实测 {@code CP-普冷 - 战斧牛排}。 */
    private static final String AROUND_HYPHEN = "[\\s\\u3000]*([-－—])[\\s\\u3000]*";

    /** 数字与中日韩字符之间的空白 —— 实测 {@code 468 礼盒} / {@code 牛肉卷加工 300g}。 */
    private static final String DIGIT_CJK = "(?<=[0-9])[\\s\\u3000]+(?=[\\u4e00-\\u9fff])";
    private static final String CJK_DIGIT = "(?<=[\\u4e00-\\u9fff])[\\s\\u3000]+(?=[0-9])";

    /**
     * 归一实体名。{@code null} 原样返回(空值语义由调用方决定, 这里不替它做主)。
     *
     * <p>做四件事, 每一件都有实测出处:
     * <ol>
     *   <li>trim</li>
     *   <li>连续空白折叠成一个半角空格</li>
     *   <li>去掉连字符两侧的空白</li>
     *   <li>去掉数字与中文之间的空白(两个方向)</li>
     * </ol>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return s;
        }
        s = s.replaceAll("[\\s\\u3000]+", " ");
        s = s.replaceAll(AROUND_HYPHEN, "$1");
        s = s.replaceAll(DIGIT_CJK, "");
        s = s.replaceAll(CJK_DIGIT, "");
        return s;
    }
}
