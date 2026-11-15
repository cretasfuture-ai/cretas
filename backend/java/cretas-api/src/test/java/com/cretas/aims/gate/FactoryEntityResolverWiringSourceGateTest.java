package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体解析只有一份实现 (INTENT-DISTILL-20261111 第③步 PR-5): 填参数、同名列出、飞轮脱敏三处都调
 * {@code FactoryEntityResolver}, 没有第二份「按名字找本厂对象」的匹配逻辑, 也没有第二份单位后缀表 (形态 D)。
 *
 * <p>源码文本闸 (去掉注释再数, 否则闸会把说明文字数进去)。每条「不许有」都配一条「一定有」的读数 (硬约束 9):
 * 先断言被扫的文件真读到了、解析器调用真在, 再断言旧写法不在。</p>
 */
@DisplayName("实体解析: 三处接同一实现, 没有第二份匹配")
class FactoryEntityResolverWiringSourceGateTest {

    private static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");
    private static final String RESOLVER = "service/entity/FactoryEntityResolver.java";

    /**
     * 注入 FactoryEntityResolver 的类 —— 登记表。它能按名字解析出本厂客户 / 员工, 新的使用方要在这里登记,
     * 并自己答清「谁有权看解析出来的对象」(考勤按 hr:read, CRM 按 TOOL_PERMISSION_MAP 的客户四码)。
     */
    private static final Set<String> REGISTERED_CONSUMERS = Set.of(
            "service/execution/ToolDispatchService.java",
            "service/skill/impl/SkillExecutorImpl.java",
            "service/intent/flywheel/FlywheelTextProtector.java",
            "ai/tool/impl/crm/CustomerCrmStatusQueryTool.java",
            "ai/tool/impl/hr/AttendanceHistoryTool.java",
            "ai/tool/impl/hr/AttendanceStatusTool.java",
            "ai/tool/impl/hr/AttendanceTodayTool.java");

    private static String code(String relative) throws IOException {
        Path p = MAIN.resolve(relative);
        assertThat(p).as("被扫的文件必须存在, 否则下面的「不许有」恒真").exists();
        return stripComments(Files.readString(p, StandardCharsets.UTF_8));
    }

    static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }

    private static int count(String code, String needle) {
        int n = 0;
        for (int i = code.indexOf(needle); i >= 0; i = code.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("填参数: 规则 3 调解析器; ToolDispatchService / SkillExecutorImpl 每个 ground 调用点都带 EntityScope")
    void parameterGroundingUsesTheResolver() throws IOException {
        String grounding = code("service/execution/LlmParameterGrounding.java");
        assertThat(grounding).contains("entities.resolver().resolve(").contains("EntityKind.forParameter(");

        for (String caller : new String[]{"service/execution/ToolDispatchService.java",
                "service/skill/impl/SkillExecutorImpl.java"}) {
            String src = code(caller);
            int grounds = count(src, "LlmParameterGrounding.ground(");
            int scoped = count(src, "LlmParameterGrounding.EntityScope.of(");
            System.out.println("[entity-gate] " + caller + ": ground 调用 " + grounds + ", 带 EntityScope " + scoped);
            assertThat(grounds).as("%s 的 ground 调用点 (一定有)", caller).isEqualTo(2);
            assertThat(scoped).as("%s 每个 ground 调用点都要带实体范围", caller).isEqualTo(grounds);
        }
    }

    @Test
    @DisplayName("同名列出: 考勤 / CRM 只经解析器找人找客户, 不再按名字查库 (精确名查询 / 包含匹配搜索都不在)")
    void sameNameListingUsesTheResolver() throws IOException {
        String attendance = code("ai/tool/impl/hr/AttendanceSubject.java");
        assertThat(attendance).contains("entities.resolve(factoryId, EntityKind.EMPLOYEE, name)")
                .contains("SameNameMatches.listedCandidates(resolution)")
                .doesNotContain("findActiveByFactoryIdAndExactFullName")
                .doesNotContain("searchUsers");
        for (String tool : new String[]{"AttendanceHistoryTool", "AttendanceStatusTool", "AttendanceTodayTool"}) {
            assertThat(code("ai/tool/impl/hr/" + tool + ".java")).as(tool)
                    .contains("rbacGuard, entityResolver, userRepository,");
        }

        String crm = code("ai/tool/impl/crm/CustomerCrmStatusQueryTool.java");
        assertThat(crm).contains("entityResolver.resolve(factoryId, EntityKind.CUSTOMER, customerName)")
                .contains("SameNameMatches.listedCandidates(resolution)")
                .doesNotContain("searchCustomersByName");

        assertThat(code("repository/UserRepository.java"))
                .as("按姓名精确找人的第二份实现已并进解析器").doesNotContain("ExactFullName");
    }

    @Test
    @DisplayName("脱敏: 名单与称呼核心来自解析器, 飞轮脱敏不再自己查主数据名单、不自己维护后缀表")
    void redactionDictionaryUsesTheResolver() throws IOException {
        String protector = code("service/intent/flywheel/FlywheelTextProtector.java");
        assertThat(count(protector, "entities.redactionNames(factoryId, EntityKind."))
                .as("客户 / 供应商 / 产品 / 原料四份名单").isEqualTo(4);
        assertThat(protector).contains("FactoryEntityResolver.surfaceForms(")
                .doesNotContain("masterData.")
                .doesNotContain("FlywheelMasterDataRepository")
                .doesNotContain("\"有限公司\"")
                .doesNotContain("endsWith(suffix");
    }

    @Test
    @DisplayName("单位后缀表只有一份 (在解析器里); 注入解析器的类都在登记表里")
    void oneSuffixListAndRegisteredConsumersOnly() throws IOException {
        Set<String> suffixLists = new TreeSet<>();
        Set<String> consumers = new TreeSet<>();
        Pattern injection = Pattern.compile("(?:private|final)\\s+(?:final\\s+)?(?:[\\w.]+\\.)?FactoryEntityResolver\\s+\\w+\\s*;");
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (src.contains("\"有限责任公司\"")) {
                    suffixLists.add(rel);
                }
                Matcher m = injection.matcher(src);
                if (m.find() && !rel.equals(RESOLVER)) {
                    consumers.add(rel);
                }
            }
        }
        System.out.println("[entity-gate] 扫描 " + scanned + " 个源文件; 后缀表 " + suffixLists + "; 注入方 " + consumers);
        assertThat(scanned).as("扫描到的源文件 (一定有)").isGreaterThan(1000);
        assertThat(suffixLists).containsExactly(RESOLVER);
        assertThat(consumers).as("注入 FactoryEntityResolver 的类必须登记 (并答清权限)")
                .containsExactlyInAnyOrderElementsOf(REGISTERED_CONSUMERS);
    }
}
