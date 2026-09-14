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

                返回 JSON:
                {"cut": "分割部位名称, 判断不出填 null", \
                "marblingScore": 1到5的整数(脂肪分布均匀细密为高分), 判断不出填 null, \
                "foreignObjectSuspected": true/false(是否疑似有异物, 如毛发/金属/塑料/骨渣), \
                "contaminationSuspected": true/false(是否疑似污染, 如变色/渗液/霉斑), \
                "confidence": 0.0, "notes": "补充说明; 没有就填 null"}
                """ + JSON_DISCIPLINE,
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
