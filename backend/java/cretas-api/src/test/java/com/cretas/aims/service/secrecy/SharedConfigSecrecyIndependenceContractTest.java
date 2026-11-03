package com.cretas.aims.service.secrecy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四轮复审第 1 条: 写<b>共享配置</b> (平台级意图、意图晋升 / 回滚: 所有工厂都看得到) 前的保密产品检查是跨厂保护,
 * Steve 的规矩是任何保密开关都不能削弱它。这些调用方只许用不看开关的并集
 * ({@code ...ForSharedContent}), ⛔ 不许经任何<b>结果随开关变</b>的方法 (按 d 剔除的训练并集、按 a / e 生效的产品集合…)
 * 间接依赖开关 —— 挂靠闸 / 隔离闸只认直接引用 {@code FactorySecrecyConfig}, 认不出这种间接依赖, 所以这里钉住。
 *
 * <p>为什么不做成「隔离闸类不许调用任何随开关变的方法」的通用闸: 隔离闸清单里的 {@code LearningContentRules} 合法地
 * 依赖 d (它管的是训练), 通用规则要么误报要么要逐类开例外; 调用图也不是正则能算出来的。所以按调用方钉。
 */
class SharedConfigSecrecyIndependenceContractTest {

    static final Path JAVA_MAIN = Path.of("src/main/java");

    /** 写共享配置的调用方 (相对 src/main/java)。新增一个写共享配置的入口 → 加进来。 */
    static final List<String> SHARED_CONFIG_CALLERS = List.of(
            "com/cretas/aims/service/intent/IntentConfigWriteGuard.java",
            "com/cretas/aims/service/impl/IntentConfigRollbackService.java",
            "com/cretas/aims/controller/IntentAnalysisController.java");

    /** 结果随保密开关变的方法 (过滤器 / 访问服务)。 */
    static final Pattern SWITCH_DEPENDENT = Pattern.compile(
            "\\.(allFactoriesProtectedNames|allFactoriesTrainingTerms|allFactoriesTrainingTermsOrNull|checkGlobalContent"
                    + "|mentionsProtectedProduct|trainingFilterOff|factoriesWithProtectedProducts|protectedProductTypeIds"
                    + "|switchedOffPolicyProductTypeIds|protectedProductNames|hiddenFromPlatform)\\s*\\(");

    static final Pattern SHARED_CONTENT_CALL = Pattern.compile("\\.\\w+ForSharedContent\\s*\\(");

    @Test
    void sharedConfigCallersUseTheSwitchIndependentUnionOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String rel : SHARED_CONFIG_CALLERS) {
            String code = FactorySecrecyGatesContractTest.stripJavaComments(
                    Files.readString(JAVA_MAIN.resolve(rel), StandardCharsets.UTF_8));
            Matcher m = SWITCH_DEPENDENT.matcher(code);
            while (m.find()) {
                violations.add(rel + " calls " + m.group(1));
            }
            assertThat(SHARED_CONTENT_CALL.matcher(code).find())
                    .as(rel + " checks shared config against the switch-independent union").isTrue();
        }
        assertThat(violations).as("共享配置不许经随开关变的方法取保密名").isEmpty();
    }

    @Test
    void thePinCanGoRed() {
        // 阳性对照: 认得出经 d 剔除的并集; 注释里的不算
        assertThat(SWITCH_DEPENDENT.matcher(FactorySecrecyGatesContractTest.stripJavaComments(
                "x = processSecretSampleFilter.allFactoriesProtectedNames();")).find()).isTrue();
        assertThat(SWITCH_DEPENDENT.matcher(FactorySecrecyGatesContractTest.stripJavaComments(
                "// processSecretSampleFilter.allFactoriesProtectedNames()\n x = 1;")).find()).isFalse();
        assertThat(SWITCH_DEPENDENT.matcher("filter.allFactoriesPolicyProductNamesForSharedContent()").find()).isFalse();
    }
}
