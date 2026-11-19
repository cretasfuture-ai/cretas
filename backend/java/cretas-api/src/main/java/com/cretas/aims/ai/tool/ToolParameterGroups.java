package com.cretas.aims.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具 schema 的「至少给一个」参数组 (顶层键 {@value #ANY_OF_KEY}, 如 [["batchNumber","salesOrderNo"]])。
 *
 * <p><b>为什么</b> (BP deck 11-19 问题 39 / 34): schema 的 {@code required} 只能写「某一个必填」, 表达不了
 * 「批次号或销售单号给一个」「客户编号或客户名称给一个」。于是抽参 (填槽 {@code SlotFillingServiceImpl#inferSlotsFromTool}
 * 与 {@code ToolDispatchService#extractParametersWithLLM}) 只认 {@code required} 里的那一个:
 * <ul>
 *   <li>追溯销售单: 槽位只有 batchNumber, 标签「销售单号不要填这里」, 模型老实不填 → 反问「请提供生产批次号」,
 *       销售单号从来没人去抽;</li>
 *   <li>按日期+客户追溯: 必填只有 date, date 一到手 (填槽 / 上下文) 就认为参数齐了, 不再叫模型,
 *       问句里的客户名到不了工具 → 工具反问「哪个客户」。</li>
 * </ul>
 * 声明了组, 组内每个参数都进抽参; 组里一个都没有时算「缺」。组本身不决定追问话术, 仍由工具的
 * {@code validateRequiredParams} / {@code getParameterQuestion} 判。
 */
public final class ToolParameterGroups {

    public static final String ANY_OF_KEY = "x-llm-extract-any-of";

    private ToolParameterGroups() {
    }

    /** schema 里声明的全部组 (没声明 = 空表); 只认非空字符串组成的组。 */
    public static List<List<String>> groups(Map<String, Object> schema) {
        List<List<String>> out = new ArrayList<>();
        if (schema == null || !(schema.get(ANY_OF_KEY) instanceof List<?> raw)) {
            return out;
        }
        for (Object g : raw) {
            if (!(g instanceof List<?> members)) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (Object m : members) {
                if (m instanceof String s && !s.isBlank()) {
                    names.add(s);
                }
            }
            if (!names.isEmpty()) {
                out.add(names);
            }
        }
        return out;
    }

    /** 所有组里出现过的参数名 (去重, 保持声明顺序)。 */
    public static Set<String> memberNames(Map<String, Object> schema) {
        Set<String> names = new LinkedHashSet<>();
        groups(schema).forEach(names::addAll);
        return names;
    }

    /**
     * 已有参数下仍「缺」的组成员: 一个组里只要有一个成员已给, 该组不缺 (返回空); 一个都没给, 返回整组成员。
     *
     * @param present 判断参数是否已给的谓词 (非空非空白)
     */
    public static List<String> missingMembers(Map<String, Object> schema,
                                              java.util.function.Predicate<String> present) {
        List<String> missing = new ArrayList<>();
        for (List<String> group : groups(schema)) {
            if (group.stream().noneMatch(present)) {
                for (String name : group) {
                    if (!missing.contains(name)) {
                        missing.add(name);
                    }
                }
            }
        }
        return missing;
    }
}
