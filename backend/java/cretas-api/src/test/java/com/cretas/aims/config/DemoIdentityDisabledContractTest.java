package com.cretas.aims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 演示身份与"只读写闸名单"必须一致 —— 配了身份就必须上闸。
 *
 * <p>演示身份只保留工厂只读路径；已退休的餐饮演示不再拥有配置入口。
 *
 * <p>⚠️ DEMO_LOGISTICS 是<b>有意</b>不在名单内的(排线调度演示需要真实写操作),
 * 所以不变式只覆盖 rest/factory 两个身份, 并单独断言 logistics 保持豁免 ——
 * 防止有人"顺手补全"把它锁死。
 *
 * <p>配置有三个承载点(application.properties + 两处 {@code @Value} fallback),
 * 本类逐个断言, 不假设它们同步。
 */
class DemoIdentityDisabledContractTest {

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path INTERCEPTOR =
        Path.of("src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java");
    private static final Path ORCHESTRATOR =
        Path.of("src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java");

    @Test
    @DisplayName("已退休餐饮演示无登录入口但保留历史只读 deny fence")
    void retiredRestaurantDemoHasNoLoginEntryButKeepsDenyFence() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids"))
            .as("cretas.demo.factory-ids 的值")
            .contains("DEMO_REST")
            .doesNotContain("MOCK_REST");
        assertThat(propertyLines()).noneMatch(line -> line.startsWith("cretas.demo.rest."));
    }

    @Test
    @DisplayName("承重: 配了演示身份的租户, 必须在只读名单内")
    void everyConfiguredDemoIdentityIsReadOnlyLocked() throws Exception {
        String lockList = propertyLine("cretas.demo.factory-ids");
        for (String key : new String[]{"cretas.demo.factory.factory-id"}) {
            String tenant = defaultValueOf(key);
            if (tenant.isEmpty()) {
                continue;   // 该身份未配置 = 登录不了 = 无需上闸
            }
            assertThat(lockList)
                .as("%s 配成了 %s, 但它不在只读名单里 —— 公开扫码演示会拿到完整写权限",
                    key, tenant)
                .contains(tenant);
        }
    }

    @Test
    @DisplayName("工厂演示身份指向只读名单")
    void factoryDemoIdentityIsEnabled() throws Exception {
        String factoryId = defaultValueOf("cretas.demo.factory.factory-id");
        assertThat(factoryId).isEqualTo("DEMO_FACTORY2");
        assertThat(propertyLine("cretas.demo.factory-ids")).contains(factoryId);
        assertThat(defaultValueOf("cretas.demo.factory.username")).isNotEmpty();
    }

    @Test
    @DisplayName("DEMO_LOGISTICS 保持豁免 —— 排线调度演示需要真实写操作")
    void logisticsStaysWritable() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids"))
            .as("有人把物流演示也锁死了, 排线调度演示会当场不可用")
            .doesNotContain("DEMO_LOGISTICS");
    }

    @Test
    @DisplayName("两处 @Value fallback 与 application.properties 名单一致 —— 三个承载点")
    void codeFallbacksMatchTheProperties() throws Exception {
        String expected = defaultValueOf("cretas.demo.factory-ids");
        for (Path f : new Path[]{INTERCEPTOR, ORCHESTRATOR}) {
            String src = Files.readString(f);
            // fallback 允许是 properties 名单的**子集**(orchestrator 历史上就少一个
            // F_DEMO), 但**不允许漏掉任何一个已配置的演示身份** —— 漏了就是写闸
            // 在那条代码路径上对该租户失效, 而且是静默的。
            for (String key : new String[]{"cretas.demo.factory.factory-id"}) {
                String tenant = defaultValueOf(key);
                if (tenant.isEmpty()) {
                    continue;
                }
                assertThat(src)
                    .as("%s 的 @Value fallback 漏了演示租户 %s (properties 名单是 %s)",
                        f.getFileName(), tenant, expected)
                    .contains(tenant);
            }
        }
    }

    private static String propertyLine(String key) throws Exception {
        return Arrays.stream(Files.readString(PROPS).split("\\R"))
            .filter(l -> l.startsWith(key + "="))
            .findFirst().orElseThrow(() -> new AssertionError("找不到配置项: " + key));
    }

    private static java.util.List<String> propertyLines() throws Exception {
        return Arrays.asList(Files.readString(PROPS).split("\\R"));
    }

    /** 取 `key=${ENV:default}` 里的 default 部分。 */
    private static String defaultValueOf(String key) throws Exception {
        String line = propertyLine(key);
        int colon = line.indexOf(':', line.indexOf("${"));
        return line.substring(colon + 1, line.lastIndexOf('}'));
    }
}
