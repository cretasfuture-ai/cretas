package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 闸: 每个带<b>可选</b> status 参数的读工具, 都必须在 schema 里声明状态词表 ({@link StatusFilter#WORDS}),
 * 否则用户在对话里说的状态 (「已发货的出货单」) 到不了它 —— ERP-S6-10 的形状, 当时同形的读工具有 23 个。
 *
 * <p>读的是每个工具运行时真实返回的 schema (有的工具 status 是动态拼的), 不是源码文本。
 * 新工具要么声明词表, 要么在 {@link #ALLOWLIST} 里写明为什么它的 status 不是用户会说出口的状态。
 */
class OptionalStatusWordsContractTest {

    private static final String TOOL_IMPL_PACKAGE = "com.cretas.aims.ai.tool.impl";

    /** 工具名 → 不需要状态词表的理由。 */
    private static final Map<String, String> ALLOWLIST = Map.of();

    @Test
    void everyReadToolWithAnOptionalStatusDeclaresItsStatusWords() {
        Map<String, Map<String, Object>> optionalStatus = readToolsWithOptionalStatus();

        // 必然发生的读数: 扫到了工具, 且已知的那批都在 (扫描坏了时 "0 个违规" 没有意义)
        assertThat(optionalStatus).as("扫到的带可选 status 的读工具").hasSizeGreaterThanOrEqualTo(20)
                .containsKeys("material_batch_query", "order_list", "shipment_query", "quality_check_query",
                        "purchase_order_list", "alert_list", "reminder_query_mine");
        System.out.println("READING: read tools with optional status = " + optionalStatus.size()
                + " " + new TreeMap<>(optionalStatus).keySet());

        List<String> problems = new ArrayList<>();
        optionalStatus.forEach((tool, property) -> {
            if (ALLOWLIST.containsKey(tool)) {
                return;
            }
            problems.addAll(problemsOf(tool, property));
        });
        assertThat(problems).as("对话里说的状态会被这些工具忽略 —— 用 StatusFilter.property(...) 声明状态词表").isEmpty();
        assertThat(ALLOWLIST.keySet()).as("豁免名单里的工具必须真实存在且仍带可选 status")
                .allMatch(optionalStatus::containsKey);
    }

    /** 阴性对照: 同一判据对一个没声明词表 / 词表漏了状态 / 一个词指向两个状态的 status 必须报出来。 */
    @Test
    void theCheckFlagsMissingIncompleteAndAmbiguousTables() {
        assertThat(problemsOf("t", Map.of("type", "string", "enum", List.of("A", "B")))).hasSize(1);
        assertThat(problemsOf("t", Map.of("type", "string", "enum", List.of("A", "B"),
                StatusFilter.WORDS, Map.of("A", List.of("甲"))))).hasSize(1);
        assertThat(problemsOf("t", StatusFilter.property("x", StatusFilter.table(
                StatusFilter.words("A", "同一个词"), StatusFilter.words("B", "同一个词"))))).hasSize(1);
        assertThat(problemsOf("t", StatusFilter.property("x", StatusFilter.table(
                StatusFilter.words("A", "甲"), StatusFilter.words("B", "乙"))))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<String> problemsOf(String tool, Map<String, Object> property) {
        List<String> problems = new ArrayList<>();
        Object words = property.get(StatusFilter.WORDS);
        if (!(words instanceof Map<?, ?> table)) {
            problems.add(tool + ": status 没有声明状态词表 (" + StatusFilter.WORDS + ")");
            return problems;
        }
        if (property.get("enum") instanceof Collection<?> codes) {
            Set<String> missing = new LinkedHashSet<>();
            codes.forEach(c -> {
                if (!table.containsKey(String.valueOf(c))) {
                    missing.add(String.valueOf(c));
                }
            });
            if (!missing.isEmpty()) {
                problems.add(tool + ": 状态词表漏了 " + missing);
            }
        }
        Map<String, String> owner = new HashMap<>();
        ((Map<String, Object>) table).forEach((code, said) -> {
            for (Object w : (Collection<?>) said) {
                String prev = owner.put(String.valueOf(w), code);
                if (prev != null && !prev.equals(code)) {
                    problems.add(tool + ": 「" + w + "」同时指向 " + prev + " 和 " + code);
                }
            }
        });
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> readToolsWithOptionalStatus() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        Map<String, Map<String, Object>> found = new HashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(TOOL_IMPL_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("工具类无法加载: " + bd.getBeanClassName(), e);
            }
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface() || type.getEnclosingClass() != null) {
                continue; // 测试夹具工具都是嵌套类
            }
            ToolExecutor tool = mock(type.asSubclass(ToolExecutor.class), withSettings().defaultAnswer(CALLS_REAL_METHODS));
            Map<String, Object> schema;
            String name;
            try {
                if (tool.getAccessMode() != AccessMode.READ) {
                    continue;
                }
                name = tool.getToolName();
                schema = tool.getParametersSchema();
            } catch (Throwable t) {
                throw new AssertionError("读不到工具的 schema (getParametersSchema 不应依赖注入字段): " + type.getName(), t);
            }
            if (schema == null || !(schema.get("properties") instanceof Map<?, ?> props)
                    || !(props.get("status") instanceof Map<?, ?> status)) {
                continue;
            }
            Object required = schema.get("required");
            if (required instanceof Collection<?> req && req.contains("status")) {
                continue; // 必填的 status 会走参数追问, 不是这里的形状
            }
            found.put(name, (Map<String, Object>) status);
        }
        return found;
    }
}
