package com.cretas.aims.service.secrecy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工厂级保密配置的两道源码闸 (注释与字符串里的说明不算, 扫之前先去掉注释)。
 *
 * <ol>
 *   <li><b>挂靠闸</b>: 直接读保密数据源 (保密策略 / 授权表与仓库、出外脱敏器、保密配置表) 的每个类 / 模块
 *       都必须登记在 {@link #JAVA_ATTACHMENTS} / {@link #PYTHON_ATTACHMENTS}, 并写明归属哪一项
 *       ({@link SecrecyItem} 的代码; {@code CONFIG} = 配置本身)。新出现一个没登记的读取点 → 红;
 *       登记了却已经不读了 → 也红 (清单不许过期)。</li>
 *   <li><b>隔离闸不读配置</b>: 跨厂隔离闸 (路径工厂 = 令牌工厂、行范围、AI 工具身份、导出按工厂查…) 一律不引用
 *       {@code FactorySecrecyConfig} / {@code SecrecyItem} / {@code factory_secrecy} —— 关掉任何一项都不能打开跨厂读取。</li>
 * </ol>
 */
class FactorySecrecyGatesContractTest {

    static final Path JAVA_MAIN = Path.of("src/main/java");
    static final Path PYTHON_ROOT = Path.of("../../python");

    /** Java 里直接碰保密数据源的写法。 */
    static final Pattern JAVA_SOURCE = Pattern.compile(
            "ProcessSecretPolicyRepository|ProcessSecretGrantRepository"
                    // 表名 (原生 SQL, 不分大小写)
                    + "|(?i:process_secret_policies|process_secret_grants)"
                    // 实体类名本身: JPQL FROM (不分大小写)、em.find(X.class)、Criteria / Specification / Root<X>
                    // (按整词认, ProcessSecretPolicyService 之类的长名字不算这一条; 审查跟进 2d)
                    + "|\\bProcessSecret(?:Policy|Grant)\\b|(?i:\\bfrom\\s+processsecret(?:policy|grant)\\b)"
                    + "|new\\s+LlmEgressRedactor\\s*\\(|LlmEgressRedactor\\s*::\\s*new|LlmEgressRedactor\\s*\\.\\s*passThrough"
                    // 经保密策略服务读 (审查 #6: 绕开仓库直接拿策略)
                    + "|\\bProcessSecretPolicyService\\b"
                    + "|FactorySecrecySettingRepository|factory_secrecy_settings|FROM\\s+FactorySecrecySetting\\b");

    /** 类 (相对 src/main/java 的路径) → 归属的项。 */
    static final Map<String, Set<String>> JAVA_ATTACHMENTS = new TreeMap<>(Map.ofEntries(
            // 数据源本身 (实体 / 仓库)
            Map.entry("com/cretas/aims/entity/processsecret/ProcessSecretPolicy.java", Set.of("PROCESS_SECRET", "CUSTOMER_HELD")),
            Map.entry("com/cretas/aims/entity/processsecret/ProcessSecretGrant.java", Set.of("PROCESS_SECRET")),
            Map.entry("com/cretas/aims/entity/secrecy/FactorySecrecySetting.java", Set.of("CONFIG")),
            Map.entry("com/cretas/aims/repository/processsecret/ProcessSecretPolicyRepository.java",
                    Set.of("PROCESS_SECRET", "CUSTOMER_HELD")),
            Map.entry("com/cretas/aims/repository/processsecret/ProcessSecretGrantRepository.java", Set.of("PROCESS_SECRET")),
            Map.entry("com/cretas/aims/repository/secrecy/FactorySecrecySettingRepository.java", Set.of("CONFIG")),
            // 唯一咽喉: a / b / e, 以及 d 的全厂并集
            Map.entry("com/cretas/aims/service/processsecret/ProcessSecretAccessService.java",
                    Set.of("PROCESS_SECRET", "PLATFORM_RECIPE_BLOCK", "CUSTOMER_HELD", "TRAINING_FILTER")),
            // 策略管理 (13 个接口经 ProcessSecretController 的开关闸)
            Map.entry("com/cretas/aims/service/processsecret/ProcessSecretPolicyService.java", Set.of("PROCESS_SECRET")),
            // 保密策略管理接口 (开关闸: a 或 e 开才放行)
            Map.entry("com/cretas/aims/controller/ProcessSecretController.java", Set.of("PROCESS_SECRET", "CUSTOMER_HELD")),
            // B 档客户侧工艺包
            Map.entry("com/cretas/aims/service/processsecret/ProcessSecretExternalService.java", Set.of("CUSTOMER_HELD")),
            // 平台统计 (JPQL 直接连保密策略与配置表)
            Map.entry("com/cretas/aims/repository/ProductionBatchRepository.java",
                    Set.of("PROCESS_SECRET", "PLATFORM_RECIPE_BLOCK", "CUSTOMER_HELD")),
            // 出外脱敏的唯一入口
            Map.entry("com/cretas/aims/service/consent/LlmEgressGuard.java", Set.of("EGRESS_REDACTION")),
            // 训练数据过滤 (d) 的名单 (#1377): 保密产品 (含已软删除) / 专属别名 / 人员 / 客户的原生查询;
            // 是否生效由 ProcessSecretSampleFilter 按样本所属工厂的 d 判 (经 FactorySecrecyConfig)
            Map.entry("com/cretas/aims/repository/learning/FactoryNameDirectoryRepository.java", Set.of("TRAINING_FILTER")),
            // 训练名单的词规则: 解析保密策略上登记的专属别名 (#1377, Steve 2026-11-03 Q1)
            Map.entry("com/cretas/aims/service/consent/ProcessSecretTerms.java", Set.of("TRAINING_FILTER")),
            // 配置本身
            Map.entry("com/cretas/aims/service/secrecy/FactorySecrecyConfig.java", Set.of("CONFIG")),
            Map.entry("com/cretas/aims/service/secrecy/FactorySecrecySettingsService.java", Set.of("CONFIG"))));

    /** Python 里直接碰保密数据源的写法 (SQL 表名 / 出外脱敏器 / 保密名查询)。 */
    static final Pattern PYTHON_SOURCE = Pattern.compile(
            "process_secret_policies|process_secret_grants|factory_secrecy_settings"
                    // common/llm_redactor.py 与 common/llm_egress_text.py 里的每一个脱敏函数 (按名字认, 不要求后面跟括号:
                    // import / 别名 / getattr 字符串都算; 审查 #6)
                    + "|\\bredact_(?:text|texts|dict|payload|payload_for_egress)\\b|_protected_names_for_egress"
                    // 保密产品名 SQL 常量 (common/process_secret_sql.py; 审查跟进 2d)
                    + "|\\b(?:ALL_FACTORIES_|ALL_)?PROTECTED_NAMES_SQL\\b");

    static final Map<String, Set<String>> PYTHON_ATTACHMENTS = new TreeMap<>(Map.ofEntries(
            Map.entry("common/factory_secrecy.py", Set.of("CONFIG")),
            Map.entry("common/llm_client.py", Set.of("EGRESS_REDACTION", "PROCESS_SECRET")),
            Map.entry("common/llm_redactor.py", Set.of("EGRESS_REDACTION")),
            Map.entry("common/llm_egress_text.py", Set.of("EGRESS_REDACTION")),
            Map.entry("food_kb/services/reranker.py", Set.of("EGRESS_REDACTION")),
            Map.entry("efficiency_recognition/services/video_analyzer.py", Set.of("EGRESS_REDACTION")),
            // 以下三份在 #1377 里 (训练数据过滤 d 的 Python 接线留到 PR-2): 名单 SQL 只在 process_secret_sql 一处
            // (process_secret_names 经它取, 不再直接写 SQL); 别名解析; 离线重放工具的 --live-lists
            Map.entry("common/process_secret_sql.py", Set.of("TRAINING_FILTER", "EGRESS_REDACTION")),
            Map.entry("common/process_secret_terms.py", Set.of("TRAINING_FILTER")),
            Map.entry("scripts/training_filter_replay.py", Set.of("TRAINING_FILTER"))));

    /** 跨厂隔离闸: 一律不读任何一项。 */
    static final List<String> JAVA_ISOLATION_GATES = List.of(
            "com/cretas/aims/config/JwtAuthInterceptor.java",
            "com/cretas/aims/security/DataScopeRouteInterceptor.java",
            "com/cretas/aims/security/DataScopeResolver.java",
            "com/cretas/aims/aspect/DataScopeAspect.java",
            "com/cretas/aims/utils/FactoryAccessValidator.java",
            "com/cretas/aims/ai/tool/gateway/ToolPrincipalPolicy.java",
            "com/cretas/aims/ai/tool/gateway/ToolEgressPermit.java",
            "com/cretas/aims/service/execution/TrustedExecutionContext.java",
            "com/cretas/aims/service/intent/IntentConfigWriteGuard.java",
            "com/cretas/aims/security/PlatformFactoryViewReadPolicy.java",
            // 平台账号能否碰某家工厂的身份闸
            "com/cretas/aims/service/platform/PlatformFactoryControlAuthority.java",
            // 平台 AI 分析入口不读 a / b (Steve 2026-11-04): 配置怎么设都能用, 放行只看授权
            "com/cretas/aims/service/platform/PlatformFactoryAiAnalysisService.java",
            "com/cretas/aims/service/platform/PlatformAiAnalysisGrantService.java",
            "com/cretas/aims/security/BusinessRowScope.java",
            "com/cretas/aims/security/RoleDataScopeService.java",
            "com/cretas/aims/service/datacenter/impl/ExportExecutor.java",
            "com/cretas/aims/service/consent/LearningContentRules.java");
    static final List<String> PYTHON_ISOLATION_GATES = List.of("auth_middleware.py", "smartbi/tenant_ctx.py");
    static final Pattern CONFIG_REFERENCE = Pattern.compile("FactorySecrecyConfig|SecrecyItem|factory_secrecy");

    // ------------------------------------------------------------------ 挂靠闸

    /**
     * 已知会由在途 PR 新增的读取点: 文件还不在代码树里时预先登记归属项。<b>有效期到文件落地为止</b> ——
     * 路径一旦存在, 闸就红, 直到把它挪进上面的正式清单 (挪过去之后「登记了却不读」的检查才对它生效)。
     * PR #1377 (训练数据过滤 d) 的四条已随它落地并挪进正式清单, 目前没有在途登记。
     */
    static final Map<String, Set<String>> JAVA_PENDING = new TreeMap<>();
    static final Map<String, Set<String>> PYTHON_PENDING = new TreeMap<>();

    @Test
    void everyJavaClassReadingASecrecySourceIsAttachedToAnItem() throws IOException {
        Map<String, Set<String>> found = scan(JAVA_MAIN, ".java", JAVA_SOURCE, FactorySecrecyGatesContractTest::stripJavaComments);
        assertAttached(JAVA_MAIN, found, JAVA_ATTACHMENTS, JAVA_PENDING, "直接读保密数据源的 Java 类");
    }

    @Test
    void everyPythonModuleReadingASecrecySourceIsAttachedToAnItem() throws IOException {
        Map<String, Set<String>> found = scan(PYTHON_ROOT, ".py", PYTHON_SOURCE, FactorySecrecyGatesContractTest::stripPythonComments);
        assertAttached(PYTHON_ROOT, found, PYTHON_ATTACHMENTS, PYTHON_PENDING, "直接读保密数据源的 Python 模块 (测试除外)");
    }

    /**
     * 读到的必须都在正式登记里; 正式登记必须都还在读 (清单不许过期); 在途登记的路径一旦出现在代码树里就红,
     * 直到挪进正式登记 (在途豁免只到文件落地为止)。
     */
    static void assertAttached(Path root, Map<String, Set<String>> found, Map<String, Set<String>> registered,
                               Map<String, Set<String>> pending, String what) {
        assertThat(landedPending(root, pending))
                .as(what + ": 在途登记的路径已经进了代码树 —— move this path into the registered list (挪进正式登记清单)")
                .isEmpty();
        Set<String> unregistered = new TreeSet<>(found.keySet());
        unregistered.removeAll(registered.keySet());
        assertThat(unregistered).as(what + ": 新出现、没登记归属项的").isEmpty();
        Set<String> stale = new TreeSet<>(registered.keySet());
        stale.removeAll(found.keySet());
        assertThat(stale).as(what + ": 登记了却已经不读的 (请删掉)").isEmpty();
        assertItemsAreKnown(registered);
        assertItemsAreKnown(pending);
    }

    /** 在途登记里已经存在于代码树的路径。 */
    static Set<String> landedPending(Path root, Map<String, Set<String>> pending) {
        Set<String> landed = new TreeSet<>();
        for (String rel : pending.keySet()) {
            if (Files.exists(root.resolve(rel))) landed.add(rel);
        }
        return landed;
    }

    /** 在途豁免会过期: 路径落地后闸红, 提示挪进正式清单; 挪进去之后转绿。 */
    @Test
    void aPendingPathThatLandedMustBeMovedIntoTheRegisteredList(@org.junit.jupiter.api.io.TempDir Path tree)
            throws IOException {
        Map<String, Set<String>> pending = Map.of("common/landed.py", Set.of("TRAINING_FILTER"),
                "common/not_yet.py", Set.of("TRAINING_FILTER"));
        Map<String, Set<String>> registered = Map.of("common/old.py", Set.of("PROCESS_SECRET"));
        Map<String, Set<String>> found = new TreeMap<>(Map.of("common/old.py", Set.of("process_secret_policies")));
        Files.createDirectories(tree.resolve("common"));
        Files.writeString(tree.resolve("common/old.py"), "SQL = 'select 1 from process_secret_policies'");
        // 还没落地: 豁免有效
        assertAttached(tree, found, registered, pending, "t");

        Files.writeString(tree.resolve("common/landed.py"), "SQL = 'select 1 from process_secret_policies'");
        found.put("common/landed.py", Set.of("process_secret_policies"));
        assertThat(landedPending(tree, pending)).containsExactly("common/landed.py");
        assertThatThrownBy(() -> assertAttached(tree, found, registered, pending, "t"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("move this path into the registered list")
                .hasMessageContaining("common/landed.py");

        // 挪进正式清单 (并从在途里删掉) 后转绿
        Map<String, Set<String>> moved = new TreeMap<>(registered);
        moved.put("common/landed.py", Set.of("TRAINING_FILTER"));
        assertAttached(tree, found, moved, Map.of("common/not_yet.py", Set.of("TRAINING_FILTER")), "t");
    }

    @Test
    void theAttachmentGateCanGoRed() {
        // 阳性对照: 闸自己能认出一个新读取点, 也认得出注释里的不算
        assertThat(JAVA_SOURCE.matcher(stripJavaComments(
                "class X { ProcessSecretPolicyRepository repo; }")).find()).isTrue();
        assertThat(JAVA_SOURCE.matcher(stripJavaComments(
                "class X { /* ProcessSecretPolicyRepository */ // process_secret_policies\n }")).find()).isFalse();
        assertThat(JAVA_SOURCE.matcher("var r = new LlmEgressRedactor (mapper);").find()).isTrue();
        // 审查 #6 补的写法: 方法引用构造、经保密策略服务读; 每一种都要认得出, 注释里的不算
        assertThat(JAVA_SOURCE.matcher("Function<ObjectMapper, LlmEgressRedactor> f = LlmEgressRedactor :: new;").find()).isTrue();
        assertThat(JAVA_SOURCE.matcher("private final ProcessSecretPolicyService policies;").find()).isTrue();
        assertThat(JAVA_SOURCE.matcher(stripJavaComments("// LlmEgressRedactor::new\n/* ProcessSecretPolicyService */")).find()).isFalse();
        assertThat(JAVA_SOURCE.matcher("ProcessSecretPolicyServiceHelper x;").find()).as("名字的一部分不算").isFalse();
        for (String python : List.of("from common.llm_redactor import redact_text", "out = redact_dict(row, alloc)",
                "redacted, meta = redact_payload(p, allocator=a)", "from common.llm_egress_text import redact_texts as rt",
                "fn = getattr(llm_egress_text, 'redact_texts')", "x = redact_payload_for_egress(p, names)")) {
            assertThat(PYTHON_SOURCE.matcher(stripPythonComments(python)).find()).as(python).isTrue();
        }
        assertThat(PYTHON_SOURCE.matcher(stripPythonComments("# redact_text(x)")).find()).isFalse();
        assertThat(PYTHON_SOURCE.matcher("unredact_textual = 1").find()).as("名字的一部分不算").isFalse();
        assertThat(PYTHON_SOURCE.matcher(stripPythonComments("rows = await conn.fetch('SELECT * FROM process_secret_policies')")).find()).isTrue();
        assertThat(PYTHON_SOURCE.matcher(stripPythonComments("# process_secret_policies")).find()).isFalse();
        // 审查跟进 2d: 实体类名 (JPQL / em.find / Criteria / Specification)、大写表名、保密名 SQL 常量
        for (String java : List.of("@Query(\"select p from ProcessSecretPolicy p\")",
                "@Query(\"SELECT g FROM processsecretgrant g\")",
                "ProcessSecretPolicy p = em.find(ProcessSecretPolicy.class, id);",
                "CriteriaQuery<ProcessSecretGrant> q = cb.createQuery(ProcessSecretGrant.class);",
                "Specification<ProcessSecretPolicy> spec = (root, q, cb) -> cb.equal(root.get(\"factoryId\"), f);",
                "jdbc.queryForList(\"SELECT * FROM PROCESS_SECRET_POLICIES\")")) {
            assertThat(JAVA_SOURCE.matcher(stripJavaComments(java)).find()).as(java).isTrue();
        }
        for (String java : List.of("ProcessSecretPolicyView view;", "class ProcessSecretGrantsPanel {}",
                "// em.find(ProcessSecretPolicy.class, id)", "/* FROM ProcessSecretGrant g */")) {
            assertThat(JAVA_SOURCE.matcher(stripJavaComments(java)).find()).as(java).isFalse();
        }
        for (String python : List.of("from common.process_secret_sql import PROTECTED_NAMES_SQL",
                "rows = await conn.fetch(ALL_FACTORIES_PROTECTED_NAMES_SQL)", "q = sql.ALL_PROTECTED_NAMES_SQL")) {
            assertThat(PYTHON_SOURCE.matcher(stripPythonComments(python)).find()).as(python).isTrue();
        }
        for (String python : List.of("# PROTECTED_NAMES_SQL", "MY_PROTECTED_NAMES_SQL_V2 = 1")) {
            assertThat(PYTHON_SOURCE.matcher(stripPythonComments(python)).find()).as(python).isFalse();
        }
        assertThatThrownBy(() -> assertItemsAreKnown(Map.of("x", Set.of("NOT_AN_ITEM")))).isInstanceOf(AssertionError.class);
    }

    // ------------------------------------------------------------------ 隔离闸不读配置

    @Test
    void crossFactoryIsolationGatesDoNotReadAnySecrecySetting() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String gate : JAVA_ISOLATION_GATES) {
            Path file = JAVA_MAIN.resolve(gate);
            assertThat(file).as(gate + " 还在 (改名了要同步本清单)").exists();
            if (CONFIG_REFERENCE.matcher(stripJavaComments(Files.readString(file, StandardCharsets.UTF_8))).find()) {
                offenders.add(gate);
            }
        }
        for (String gate : PYTHON_ISOLATION_GATES) {
            Path file = PYTHON_ROOT.resolve(gate);
            assertThat(file).as(gate).exists();
            if (CONFIG_REFERENCE.matcher(stripPythonComments(Files.readString(file, StandardCharsets.UTF_8))).find()) {
                offenders.add(gate);
            }
        }
        assertThat(offenders).as("跨厂隔离闸引用了保密配置").isEmpty();
        // 阳性对照
        assertThat(CONFIG_REFERENCE.matcher(stripJavaComments("if (!config.isOn(f, SecrecyItem.PROCESS_SECRET))")).find()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    static void assertItemsAreKnown(Map<String, Set<String>> attachments) {
        Set<String> known = new TreeSet<>();
        for (SecrecyItem item : SecrecyItem.values()) known.add(item.name());
        known.add("CONFIG");
        attachments.forEach((file, items) -> assertThat(known).as(file).containsAll(items));
    }

    static Map<String, Set<String>> scan(Path root, String suffix, Pattern source,
                                         java.util.function.UnaryOperator<String> strip) throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(suffix)).toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (isTest(rel)) continue;
                String code = strip.apply(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = source.matcher(code);
                Set<String> hits = new TreeSet<>();
                while (m.find()) hits.add(m.group());
                if (!hits.isEmpty()) found.put(rel, hits);
            }
        }
        return found;
    }

    static boolean isTest(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return rel.startsWith("tests/") || rel.contains("/tests/") || name.startsWith("test_") || name.equals("conftest.py")
                || rel.startsWith("venv") || rel.contains("/.venv/") || rel.contains("site-packages/");
    }

    /** 去掉 Java 注释 (块注释 / 行注释); 字符串字面量保留 (SQL 写在字符串里)。 */
    static String stripJavaComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        boolean inString = false;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < code.length()) {
                    out.append(code.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') inString = false;
                i++;
            } else if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 去掉 Python 的 # 注释; 字符串 (含三引号 —— SQL 常写在三引号里) 保留。
     * 只在文档字符串里提到数据源的模块会被认出来, 那种情况改措辞或登记都行, 宁可多报。
     */
    static String stripPythonComments(String code) {
        StringBuilder out = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            out.append(stripPythonLineComment(line)).append('\n');
        }
        return out.toString();
    }

    private static String stripPythonLineComment(String line) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return line.substring(0, i);
        }
        return line;
    }
}
