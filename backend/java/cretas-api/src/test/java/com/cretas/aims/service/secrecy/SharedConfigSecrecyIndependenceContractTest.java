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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四 / 五轮复审: 写给<b>所有工厂</b>的共享内容 (平台级意图、意图晋升 / 回滚、全局人工说法 / 种子、共享短语表 / 全局知识)
 * 之前的保密名检查是跨厂保护, Steve 的规矩是任何保密开关都不能削弱它。
 *
 * <ol>
 *   <li><b>枚举</b>: 主代码里调用共享内容检查 ({@code …ForSharedContent} / {@code checkSharedContent} /
 *       {@code factoriesWithPolicyProducts}) 的每个类都必须登记在 {@link #SHARED_CONTENT_WRITERS} —— 新的共享写入方
 *       不会悄悄出现; 登记了却不再调用的也红 (清单不许过期)。</li>
 *   <li><b>按开关剔工厂的全厂并集已删</b>: 训练专用的全厂并集允许清单为空 —— 旧名字
 *       ({@code factoriesWithProtectedProducts} / {@code allFactoriesProtectedNames} / {@code allFactoriesTrainingTerms})
 *       在主代码里一个都不许再出现。d 只按样本所属工厂判 ({@code trainingFilterOff})。</li>
 *   <li><b>共享并集本身不读开关</b>: 访问服务 / 过滤器里构造共享并集的方法体不引用开关配置 / d。</li>
 *   <li><b>纯共享配置的调用方</b> (平台级意图三处) 连按本厂开关变的方法都不许调。</li>
 * </ol>
 * 挂靠闸 / 隔离闸只认直接引用 {@code FactorySecrecyConfig}, 认不出这种经方法间接依赖开关的情况, 所以在这里钉。
 * 通用的「隔离闸类不许调结果随开关变的方法」做不了: 隔离闸里的 {@code LearningContentRules} 合法地依赖本厂的 d。
 */
class SharedConfigSecrecyIndependenceContractTest {

    static final Path JAVA_MAIN = Path.of("src/main/java");

    /** 调用共享内容检查的类 (相对 src/main/java)。新增一个写共享内容的入口 → 加进来, 并接上共享内容检查。 */
    static final Map<String, String> SHARED_CONTENT_WRITERS = Map.of(
            "com/cretas/aims/service/intent/IntentConfigWriteGuard.java", "平台级意图写入 / 批量回滚的名单",
            "com/cretas/aims/service/impl/IntentConfigRollbackService.java", "平台级意图批量回滚 (经 guard)",
            "com/cretas/aims/controller/IntentAnalysisController.java", "意图晋升为平台级",
            "com/cretas/aims/service/impl/ExpressionLearningServiceImpl.java", "全局人工说法 / 种子",
            "com/cretas/aims/service/impl/ActiveLearningServiceImpl.java", "共享短语表 (学习建议) / 全局知识晋升");

    /** 只写共享配置、没有本厂训练路径的调用方: 连随本厂开关变的方法都不许调。 */
    static final List<String> PURE_SHARED_CONFIG_CALLERS = List.of(
            "com/cretas/aims/service/intent/IntentConfigWriteGuard.java",
            "com/cretas/aims/service/impl/IntentConfigRollbackService.java",
            "com/cretas/aims/controller/IntentAnalysisController.java");

    /** 共享内容检查 (不看开关)。定义它们的两个类自身不算调用方。 */
    static final Pattern SHARED_CONTENT_CALL = Pattern.compile(
            "\\.(checkSharedContent|allFactoriesPolicyTermsForSharedContent(?:OrNull)?"
                    + "|allFactoriesPolicyProductNamesForSharedContent|mentionsAnyFactoryPolicyProductForSharedContent"
                    + "|factoriesWithPolicyProducts)\\s*\\(");
    static final Set<String> DEFINERS = Set.of(
            "com/cretas/aims/service/consent/ProcessSecretSampleFilter.java",
            "com/cretas/aims/service/processsecret/ProcessSecretAccessService.java");

    /** 训练专用的全厂并集 (按 d 剔工厂) —— 已删, 允许清单为空。 */
    static final Pattern REMOVED_D_FILTERED_UNION = Pattern.compile(
            "\\b(factoriesWithProtectedProducts|allFactoriesProtectedNames|allFactoriesTrainingTerms\\w*)\\b");

    /** 结果随某家工厂的开关变的方法 (过滤器 / 访问服务)。 */
    static final Pattern SWITCH_DEPENDENT = Pattern.compile(
            "\\.(mentionsProtectedProduct|trainingFilterOff|protectedProductTypeIds|switchedOffPolicyProductTypeIds"
                    + "|protectedProductNames|hiddenFromPlatform|checkTrainingSample|checkProtectedTerms)\\s*\\(");

    static String code(String rel) throws IOException {
        return FactorySecrecyGatesContractTest.stripJavaComments(
                Files.readString(JAVA_MAIN.resolve(rel), StandardCharsets.UTF_8));
    }

    @Test
    void everyCallerOfTheSharedContentCheckIsRegistered() throws IOException {
        Set<String> found = new TreeSet<>();
        List<Path> files;
        try (var walk = Files.walk(JAVA_MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path p : files) {
            String rel = JAVA_MAIN.relativize(p).toString().replace('\\', '/');
            if (DEFINERS.contains(rel)) {
                continue;
            }
            String code = FactorySecrecyGatesContractTest.stripJavaComments(Files.readString(p, StandardCharsets.UTF_8));
            if (SHARED_CONTENT_CALL.matcher(code).find()) {
                found.add(rel);
            }
            Matcher removed = REMOVED_D_FILTERED_UNION.matcher(code);
            String hit = removed.find() ? removed.group() : null;
            assertThat(hit).as(rel + " 用了已删的按开关剔工厂的全厂并集").isNull();
        }
        assertThat(found).as("调用共享内容检查的类 (新增的要登记; 不再调用的要删掉)")
                .isEqualTo(new TreeSet<>(SHARED_CONTENT_WRITERS.keySet()));
    }

    @Test
    void theDefinersNoLongerOfferASwitchFilteredAllFactoriesUnion() throws IOException {
        for (String rel : DEFINERS) {
            Matcher m = REMOVED_D_FILTERED_UNION.matcher(code(rel));
            assertThat(m.find()).as(rel + " 又定义 / 使用了按开关剔工厂的全厂并集").isFalse();
        }
    }

    @Test
    void theSharedUnionsReadNoSwitch() throws IOException {
        String access = code("com/cretas/aims/service/processsecret/ProcessSecretAccessService.java");
        assertThat(body(access, "public Set<String> factoriesWithPolicyProducts()"))
                .doesNotContain("secrecy").doesNotContain("SecrecyItem");
        String filter = code("com/cretas/aims/service/consent/ProcessSecretSampleFilter.java");
        for (String signature : List.of("public Set<String> allFactoriesPolicyTermsForSharedContent()",
                "public Set<String> allFactoriesPolicyProductNamesForSharedContent()",
                "public SampleVerdict checkSharedContent(")) {
            assertThat(body(filter, signature)).as(signature)
                    .doesNotContain("trainingFilterOff").doesNotContain("secrecy").doesNotContain("factoriesWithProtected");
        }
    }

    @Test
    void pureSharedConfigCallersCallNoSwitchDependentMethod() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String rel : PURE_SHARED_CONFIG_CALLERS) {
            Matcher m = SWITCH_DEPENDENT.matcher(code(rel));
            while (m.find()) {
                violations.add(rel + " calls " + m.group(1));
            }
        }
        assertThat(violations).as("共享配置不许经随开关变的方法取保密名").isEmpty();
    }

    @Test
    void thePinsCanGoRed() {
        // 阳性对照: 认得出共享检查调用、已删并集与随开关变的方法; 注释里的不算
        assertThat(SHARED_CONTENT_CALL.matcher("filter.checkSharedContent(\"k\", t)").find()).isTrue();
        assertThat(REMOVED_D_FILTERED_UNION.matcher(FactorySecrecyGatesContractTest.stripJavaComments(
                "x = access.factoriesWithProtectedProducts();")).find()).isTrue();
        assertThat(REMOVED_D_FILTERED_UNION.matcher(FactorySecrecyGatesContractTest.stripJavaComments(
                "// access.factoriesWithProtectedProducts()\n x = 1;")).find()).isFalse();
        assertThat(SWITCH_DEPENDENT.matcher("filter.trainingFilterOff(f)").find()).isTrue();
        assertThat(body("class X {\n    public Set<String> a() {\n        return secrecy.x();\n    }\n}",
                "public Set<String> a()")).contains("secrecy");
    }

    /** 方法体 (从签名到同一缩进的右花括号)。 */
    static String body(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("找不到 " + signature).isGreaterThanOrEqualTo(0);
        int end = code.indexOf("\n    }", start);
        return code.substring(start, end < 0 ? code.length() : end);
    }
}
