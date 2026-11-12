package com.cretas.aims.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写入确认三洞 #1 的闸: 意图目录里每个 active、能预览 (会铸确认凭据) 的写工具, 必须让确认前状态核对有东西可比 ——
 * 预览给 {@code currentValues} / {@code current*} 改前值, 或声明 {@code previewCreatesNewRecordOnly()} (只新建)。
 * 两样都没有的工具在确认时只能 fail-closed, 用户在助手里永远确认不了 (审阅阻断 1)。
 *
 * <p>源码扫描 (工具文件 + 它引用的同包预览助手类); 判据是代理, 漏判的方向是「当成有」, 所以另有
 * {@code ConfirmStateRecheckContractTest} 钉规则本身。新增 active 写工具时这里会红, 按上面两条之一补齐。</p>
 */
class WriteToolPriorStateDeclarationContractTest {

    private static final Path IMPL = Path.of("src/main/java/com/cretas/aims/ai/tool/impl");
    private static final Path CATALOG = Path.of("src/main/resources/ai/factory-intent-catalog.json");
    private static final Pattern NAME = Pattern.compile(
            "getToolName\\(\\)\\s*\\{\\s*return\\s+\"([a-z0-9_]+)\"|TOOL_NAME\\s*=\\s*\"([a-z0-9_]+)\"");
    private static final Pattern PRIOR_STATE = Pattern.compile(
            // WritePreviewSupport.ready(message, current, next, …) 也可能被静态导入成 ready(…)
            "\"currentValues\"|\\bready\\(\\s*\\w+\\s*,\\s*\\w*[Cc]urrent|\"current(?:ly)?[A-Z][A-Za-z]*\"");

    @Test
    void everyActivePreviewingWriteToolGivesComparablePriorValuesOrOnlyCreates() throws IOException {
        Set<String> active = activeCatalogTools();
        Map<String, Path> classes = toolClasses();
        Map<String, String> verdicts = new TreeMap<>();
        List<String> blocked = new ArrayList<>();
        for (String tool : active) {
            Path file = classes.get(tool);
            if (file == null) {
                continue; // 目录里有、代码里没有的工具不铸凭据, 别的闸管
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!Pattern.compile("supportsPreview\\(\\)\\s*\\{\\s*return\\s+true").matcher(source).find()
                    || source.contains("AccessMode.READ")) {
                continue;
            }
            String verdict;
            if (source.contains("previewCreatesNewRecordOnly")) {
                verdict = "CREATE_ONLY";
            } else if (PRIOR_STATE.matcher(source + helperSources(file, source)).find()) {
                verdict = "PRIOR_VALUES";
            } else {
                verdict = "BLOCKED";
                blocked.add(tool);
            }
            verdicts.put(tool, verdict);
        }
        System.out.println("WRITE_TOOL_PRIOR_STATE " + verdicts);
        // 必然产出的对照: 已知的两类各至少有一个, 证明扫描真的扫到了工具 (硬约束 9)
        assertThat(verdicts).containsEntry("work_process_task_start", "PRIOR_VALUES")
                .containsEntry("requisition_create", "CREATE_ONLY");
        assertThat(blocked).as("这些 active 写工具确认时无法核对改前值, 会被永远拒绝").isEmpty();
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

    private static Map<String, Path> toolClasses() throws IOException {
        Map<String, Path> byName = new HashMap<>();
        try (Stream<Path> files = Files.walk(IMPL)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = NAME.matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (m.find()) {
                    byName.putIfAbsent(m.group(1) != null ? m.group(1) : m.group(2), file);
                }
            }
        }
        return byName;
    }

    /** 工具把预览交给同包助手类 (ClockPreview、WorkProcessTaskTransitionPreview…) 时, 一并看助手的源码。 */
    private static String helperSources(Path toolFile, String toolSource) throws IOException {
        StringBuilder helpers = new StringBuilder();
        try (Stream<Path> siblings = Files.list(toolFile.getParent())) {
            for (Path sibling : siblings.toList()) {
                String simple = sibling.getFileName().toString().replace(".java", "");
                if (!sibling.equals(toolFile) && simple.endsWith("Preview") && toolSource.contains(simple + ".")) {
                    helpers.append(Files.readString(sibling, StandardCharsets.UTF_8));
                }
            }
        }
        return helpers.toString();
    }
}
