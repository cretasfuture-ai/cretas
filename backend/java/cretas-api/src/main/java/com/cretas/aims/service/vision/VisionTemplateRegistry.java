package com.cretas.aims.service.vision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 拍照核验的模板登记表 —— <b>代码内</b>的一份, 不落库。
 *
 * <h3>为什么不做成配置表</h3>
 * 每个模板的价值全在「它要求 VL 返回哪几个字段」和「后处理拿这些字段去做什么」这两件事上,
 * 而后者<b>必然是代码</b>({@code EAR_TAG} 要去查个体档案, {@code MEAT_CUT} 要打 AI 初评标)。
 * 把 prompt 挪进数据库只会让两半分家: 管理员改了 prompt 让 VL 少返回一个字段,
 * 而读那个字段的 Java 代码毫不知情 —— 那正是形态 D(同一个东西两份, 一定会漂)。
 * ⇒ 要新增模板就改这个文件, 它会和它的后处理一起进同一个 PR。
 *
 * <h3>⛔ 模板里不许出现物种/客户专属词</h3>
 * {@code EAR_TAG} 说的是「牲畜标识牌」这一类物体, 不是「牛耳标」;
 * {@code MEAT_CUT} 说的是「肉品分割块」, 不是某一家的产品名。
 * 物种由个体档案的 {@code species} 字段承担, ⛔ 不要焊进 prompt。
 */
public final class VisionTemplateRegistry {

    /** 一个模板要什么、问什么。 */
    public record VisionTemplate(
            String code,
            String displayName,
            String prompt,
            /** VL 返回的 JSON 里<b>必须</b>有的键 —— 少一个就是 422, ⛔ 不补默认值。 */
            Set<String> requiredKeys) {
    }

    /**
     * 所有模板<b>共同</b>的输出纪律。写在一处, ⛔ 不在两个 prompt 里各抄一遍。
     *
     * <p>⚠️ 「只返回 JSON」这句话对任何模型都只是<b>请求</b>而不是保证 ——
     * 所以解析侧必须自己会剥 ```json 围栏、会在解析失败时诚实地报 422,
     * 而不是依赖这句话生效。
     */
    private static final String JSON_DISCIPLINE = """

            输出要求(严格遵守):
            1. 只返回一个 JSON 对象, 不要任何解释文字, 不要 markdown 代码围栏。
            2. 看不清或无法判断的字段, 值填 null —— 不要猜, 不要填"未知"这类字符串。
            3. confidence 是 0 到 1 之间的小数, 表示你对本次识别整体的把握程度。
            """;

    public static final String EAR_TAG = "EAR_TAG";
    public static final String MEAT_CUT = "MEAT_CUT";

    /**
     * 肉品初评允许的部位词表 —— 与胜龙演示租户工艺族图里「分割」工序的 20 个产出端口同名
     * (scripts/e2e/shenglong-demo/seed-cattle-demo.py 的 CUTS)。⚠️ 这是通用牛肉分割命名,
     * 不是租户配置; 猪/羊部位另起模板时再扩。清单外一律 null, ⛔ 不让模型自由发挥。
     */
    public static final java.util.List<String> MEAT_CUT_VOCABULARY = java.util.List.of(
            "牛腩", "西冷", "眼肉", "牛腱", "牛霖", "上脑", "牛柳", "前胸", "后胸", "牛肋条",
            "板腱", "嫩肩", "黄瓜条", "大米龙", "小米龙", "臀肉", "腱子", "牛尾", "碎肉", "骨");

    private static final String CUT_VOCABULARY = String.join("、", MEAT_CUT_VOCABULARY);

    private static final Map<String, VisionTemplate> TEMPLATES = new LinkedHashMap<>();

    static {
        register(new VisionTemplate(
                EAR_TAG,
                "标识牌识别",
                """
                这是一张牲畜个体标识牌(耳标/脚环/芯片标签)的照片。
                请读出标识牌上的编号。编号通常是一串数字, 也可能带字母前缀。

                返回 JSON:
                {"tagNumber": "读到的编号(只含字母和数字, 去掉空格和横杠)", \
                "confidence": 0.0, "notes": "影响识别的情况, 如遮挡/反光/模糊; 没有就填 null"}
                """ + JSON_DISCIPLINE,
                Set.of("tagNumber", "confidence")));

        register(new VisionTemplate(
                MEAT_CUT,
                "肉品分割块初评",
                """
                这是一张肉品分割块的照片。请做一次外观初评。

                部位判断的纪律(2026-09-16 演示实测: 切好的方块被说成"牛小排"且把握 0.95, 另一张写成英文 "Sirloin"):
                - cut 只能从这个清单里选一个, 用清单里的中文原词: {CUTS}; 清单外的一律填 null。
                - 只有看得到整块分割肉的形状、骨或筋膜走向时才判断部位; 已切成方块、薄片、肉粒、肉末的, cut 填 null。
                - 不要用英文, 不要中英混排, 不要写"眼肉 (Ribeye)"这种带括号的写法。
                - confidence 主要反映你对 cut 的把握: cut 为 null 或拿不准时 confidence 不高于 0.6。

                返回 JSON:
                {"cut": "上面清单里的一个中文词, 判断不出填 null", \
                "marblingScore": 1到5的整数(脂肪分布均匀细密为高分; 这是外观描述, 不是等级), 判断不出填 null, \
                "foreignObjectSuspected": true/false(是否疑似有异物, 如毛发/金属/塑料/骨渣/标签/牙签; 盘饰蔬菜和配料不算), \
                "contaminationSuspected": true/false(是否疑似污染, 如变色/渗液/霉斑), \
                "confidence": 0.0, "notes": "补充说明; 没有就填 null"}
                """.replace("{CUTS}", CUT_VOCABULARY) + JSON_DISCIPLINE,
                Set.of("cut", "marblingScore", "foreignObjectSuspected",
                        "contaminationSuspected", "confidence")));
    }

    private static void register(VisionTemplate t) {
        TEMPLATES.put(t.code(), t);
    }

    private VisionTemplateRegistry() {
    }

    public static Optional<VisionTemplate> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(TEMPLATES.get(code.trim().toUpperCase()));
    }

    /** 给前端/错误信息用 —— 「支持哪些模板」只有这一处答案。 */
    public static List<VisionTemplate> all() {
        return List.copyOf(TEMPLATES.values());
    }

    public static List<String> codes() {
        return TEMPLATES.keySet().stream().toList();
    }
}
