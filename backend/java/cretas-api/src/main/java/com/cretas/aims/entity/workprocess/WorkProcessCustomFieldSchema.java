package com.cretas.aims.entity.workprocess;

import com.cretas.aims.exception.BusinessException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逐工序自定义字段 schema 的形状校验 —— <b>唯一一份</b>。
 *
 * <h2>为什么放在 cretas-model 而不是某个 service 里</h2>
 *
 * 这套规则原先只活在 web-admin 的前端里
 * ({@code product-processes/index.vue#saveCustomFieldConfig} 的「防呆」注释写得很清楚:
 * key/label 必填 + key 唯一)。后端从 DTO 到实体是直通的, 于是同一条规则
 * <b>在 Web 路径成立、在其它任何写入口都不成立</b>。
 * 补进 {@code WorkProcessServiceImpl} 之后, 又多了两个写入口(AI 工具、对话建厂落库),
 * 它们在别的模块 —— 抄一份就必然漂。
 *
 * cretas-model 是这几处唯一共同能看见的模块, 所以规则放这里。
 *
 * <h2>不校验会怎样(不是理论风险, 是下游代码的读法决定的)</h2>
 *
 * {@code ProcessCustomFieldValidation#checkKeys} 用 {@code String.valueOf(f.get("key"))}
 * 取白名单。缺 {@code key} 的条目会变成一个<b>名字叫 "null" 的字段</b>, 于是:
 * 报工时提交任何真实字段都被 400 拒绝, 而提交字面量 {@code "null"} 反倒放行。
 * 两个同 key 的条目则让「这个字段到底是数字还是文本」取决于前端遍历顺序。
 */
public final class WorkProcessCustomFieldSchema {

    private WorkProcessCustomFieldSchema() {
    }

    /** 自定义字段允许的值类型 —— 与前端 {@code ProcessSheetCustomFieldDef.type} 逐字一致。 */
    public static final Set<String> TYPES = Set.of("number", "text", "date");

    /** 一条字段定义允许出现的键 —— 多一个键就是拼错, 而拼错的键会静默地什么都不做。 */
    public static final Set<String> ENTRY_KEYS = Set.of("key", "label", "type", "enabled");

    /** 一条不合规, 带上给前端定位用的 hintTarget。 */
    public record Problem(String hintTarget, String message) {
    }

    /**
     * 校验并原样返回。
     *
     * @param schema {@code null} 原样返回(= 未开启自定义字段, 向后兼容)
     * @return 原样的 schema —— 本方法<b>只校验不改写</b>, 改写会让「存进去的」和「传进来的」不一致
     * @throws BusinessException 形状不合规
     */
    public static List<Map<String, Object>> validated(List<Map<String, Object>> schema) {
        Problem problem = firstProblem(schema);
        if (problem != null) {
            throw new BusinessException(400, problem.message())
                    .withCode("WORK_PROCESS_CUSTOM_FIELD_SCHEMA_INVALID")
                    .withHintTarget(problem.hintTarget());
        }
        return schema;
    }

    /**
     * 批量场景用: 只报问题不抛异常。
     *
     * <p>对话建厂那条路要<b>把所有问题一次列全再拒</b>(而不是抛第一个) ——
     * 逐个抛会让客户改一条撞一条, 一次访谈的落库要来回十几趟。
     *
     * @return {@code null} 表示合规; 否则是人能看懂的一句话
     */
    public static String firstProblemMessage(List<Map<String, Object>> schema) {
        Problem p = firstProblem(schema);
        return p == null ? null : p.message();
    }

    private static Problem firstProblem(List<Map<String, Object>> schema) {
        if (schema == null) {
            return null;
        }
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < schema.size(); i++) {
            Map<String, Object> field = schema.get(i);
            String at = "第 " + (i + 1) + " 个自定义字段";
            if (field == null) {
                return new Problem("customFieldSchema", at + "是空的");
            }
            String key = trimmedOrNull(field.get("key"));
            if (key == null) {
                return new Problem("key", at + "缺少 key(字段标识, 报工时按它匹配)");
            }
            if (trimmedOrNull(field.get("label")) == null) {
                return new Problem("label", at + "「" + key + "」缺少 label(录入表格里显示的列名)");
            }
            String type = trimmedOrNull(field.get("type"));
            if (type == null || !TYPES.contains(type)) {
                return new Problem("type", at + "「" + key + "」的 type 是「" + field.get("type")
                        + "」, 只能是 " + TYPES.stream().sorted().toList());
            }
            if (!seenKeys.add(key)) {
                return new Problem("key", "自定义字段 key「" + key + "」重复了 —— "
                        + "报工校验按 key 建白名单, 重复会让这个字段的类型取决于遍历顺序");
            }
            List<String> unknown = field.keySet().stream()
                    .map(String::valueOf)
                    .filter(k -> !ENTRY_KEYS.contains(k))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                return new Problem("customFieldSchema", at + "「" + key + "」有无法识别的属性 " + unknown
                        + " —— 可用的只有 " + ENTRY_KEYS.stream().sorted().toList()
                        + "(拼错的属性会被静默忽略, 所以这里直接拒绝)");
            }
        }
        return null;
    }

    private static String trimmedOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
