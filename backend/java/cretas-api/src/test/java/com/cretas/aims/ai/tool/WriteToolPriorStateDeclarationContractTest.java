package com.cretas.aims.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 写入确认三洞 #1 的闸: 意图目录里每个 active、能预览 (会铸确认凭据) 的写工具, 必须让确认前状态核对有东西可比 ——
 * 预览给 {@code currentValues} / {@code current*} 改前值, 或声明 {@code previewCreatesNewRecordOnly()} (只新建)。
 * 两样都没有的工具在确认时只能 fail-closed, 用户在助手里永远确认不了 (审阅阻断 1)。
 *
 * <p>「能不能预览 / 是不是写 / 是不是只新建」读<b>真实声明</b>: 扫描类路径上的工具类, 用只走真实方法的实例调
 * {@code supportsPreview()} / {@code getAccessMode()} / {@code previewCreatesNewRecordOnly()} 的返回值
 * (不看源码里有没有这些字样 —— 注释里的 {@code AccessMode.READ} 或一个返回 false 的覆盖都会骗过字符串匹配)。
 * 只有「预览里给不给改前值」要跑预览才知道 (预览要连库), 这一项看去掉注释后的预览源码, 规则本身由
 * {@code ConfirmStateRecheckContractTest} 钉住。</p>
 */
class WriteToolPriorStateDeclarationContractTest {

    private static final Path IMPL = Path.of("src/main/java/com/cretas/aims/ai/tool/impl");
    private static final Path CATALOG = Path.of("src/main/resources/ai/factory-intent-catalog.json");
    private static final Pattern PRIOR_STATE = Pattern.compile(
            // WritePreviewSupport.ready(message, current, next, …) 也可能被静态导入成 ready(…)
            "\"currentValues\"|\\bready\\(\\s*\\w+\\s*,\\s*\\w*[Cc]urrent|\"current(?:ly)?[A-Z][A-Za-z]*\"");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    void everyActivePreviewingWriteToolGivesComparablePriorValuesOrOnlyCreates() throws Exception {
        Set<String> active = activeCatalogTools();
        Map<String, ToolExecutor> tools = declaredTools();
        Map<String, String> verdicts = new TreeMap<>();
        List<String> blocked = new ArrayList<>();
        for (String name : active) {
            ToolExecutor tool = tools.get(name);
            if (tool == null) {
                continue; // 目录里有、代码里没有的工具不铸凭据, 别的闸管
            }
            if (!tool.supportsPreview() || tool.getAccessMode() == ToolExecutor.AccessMode.READ) {
                continue;
            }
            String verdict;
            if (tool.previewCreatesNewRecordOnly()) {
                verdict = "CREATE_ONLY";
            } else if (PRIOR_STATE.matcher(previewSources(tool.getClass())).find()) {
                verdict = "PRIOR_VALUES";
            } else {
                verdict = "BLOCKED";
                blocked.add(name);
            }
            verdicts.put(name, verdict);
        }
        System.out.println("WRITE_TOOL_PRIOR_STATE " + verdicts);
        // 必然产出的对照: 已知的两类各至少有一个, 证明真的读到了工具声明 (硬约束 9)
        assertThat(verdicts).containsEntry("work_process_task_start", "PRIOR_VALUES")
                .containsEntry("requisition_create", "CREATE_ONLY");
        assertThat(blocked).as("这些 active 写工具确认时无法核对改前值, 会被永远拒绝").isEmpty();
    }

    @Test
    void declarationsAreReadFromRealMethodsNotFromSourceText() {
        // 阴性对照: 一个工具在注释里写着 AccessMode.READ、却真实声明为写; 另一个覆盖了 previewCreatesNewRecordOnly 但返回 false
        ToolExecutor commentSaysRead = mock(CommentSaysReadTool.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        assertThat(commentSaysRead.getAccessMode()).isEqualTo(ToolExecutor.AccessMode.WRITE);
        ToolExecutor overridesFalse = mock(OverridesFalseTool.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        assertThat(overridesFalse.previewCreatesNewRecordOnly()).isFalse();
    }

    private static Set<String> activeCatalogTools() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CATALOG, StandardCharsets.UTF_8));
        Set<String> tools = new java.util.TreeSet<>();
        for (JsonNode intent : root.path("canonical_intents")) {
            if (intent.path("is_active").asBoolean(false) && intent.hasNonNull("tool_name")) {
                tools.add(intent.get("tool_name").asText());
            }
        }
        assertThat(tools).as("目录读到了工具").isNotEmpty();
        return tools;
    }

    /** 类路径上的工具类 → 只走真实方法的实例 (不跑构造器、不注入依赖; 只调返回常量的声明方法)。 */
    private static Map<String, ToolExecutor> declaredTools() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        Map<String, ToolExecutor> byName = new HashMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.cretas.aims.ai.tool.impl")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface() || !hasMainSource(type)) {
                continue; // 测试类路径上同包的测试替身 (TestGainTool 之类) 不是生产工具
            }
            ToolExecutor tool = (ToolExecutor) mock(type, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            String name;
            try {
                name = tool.getToolName();
            } catch (RuntimeException needsDependencies) {
                continue;
            }
            if (name != null) {
                byName.putIfAbsent(name, tool);
            }
        }
        assertThat(byName).as("扫描到了工具类").hasSizeGreaterThan(50);
        return byName;
    }

    private static boolean hasMainSource(Class<?> type) {
        return type.getEnclosingClass() == null && Files.exists(sourceFile(type));
    }

    private static Path sourceFile(Class<?> type) {
        return IMPL.resolve(type.getPackageName().replace("com.cretas.aims.ai.tool.impl.", "").replace('.', '/'))
                .resolve(type.getSimpleName() + ".java");
    }

    /** 工具类 (去掉 Mockito 子类) 及它委托的同包 *Preview 助手类的源码, 注释去掉。 */
    private static String previewSources(Class<?> mocked) throws IOException {
        Class<?> type = mocked;
        while (type.getName().contains("$MockitoMock$") || type.getSimpleName().contains("$")) {
            type = type.getSuperclass();
        }
        Path file = sourceFile(type);
        String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        StringBuilder all = new StringBuilder(source);
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            for (Path sibling : siblings.toList()) {
                String simple = sibling.getFileName().toString().replace(".java", "");
                if (!sibling.equals(file) && simple.endsWith("Preview") && source.contains(simple + ".")) {
                    all.append(stripComments(Files.readString(sibling, StandardCharsets.UTF_8)));
                }
            }
        }
        return all.toString();
    }

    private static String stripComments(String source) {
        return COMMENTS.matcher(source).replaceAll(" ");
    }

    /** 注释写着 AccessMode.READ, 真实声明却是写 (默认 WRITE)。 */
    abstract static class CommentSaysReadTool implements ToolExecutor {
        // getAccessMode() 返回 AccessMode.READ —— 这只是一句过时的注释
    }

    /** 覆盖了 previewCreatesNewRecordOnly, 但返回 false。 */
    abstract static class OverridesFalseTool implements ToolExecutor {
        @Override
        public boolean previewCreatesNewRecordOnly() {
            return false;
        }
    }
}
