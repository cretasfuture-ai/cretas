package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「默认开放给一线的写操作」背后的工具<b>必须</b>支持预览。
 *
 * <p>为什么: 每一次 AI 写操作都是「预览 → 确认」两段 (W0)。写工具 {@code supportsPreview()=false} 时,
 * 用户拿到的是「预览模式暂不支持此意图类型 … 请到对应功能页面手工操作」—— 也就是<b>开放了却办不了</b>。
 * 2026-10-06 prod 复测一次性抓到 8 个这样的工具 (打卡 / 入库 / 领用 / 周转框 / 质检 …)。
 * 本测试让「下一次往 {@code FrontlineAiWriteGrantService.DEFAULT_OPEN} 里加意图、却忘了给工具补预览」直接红。
 *
 * <p>⚠️ 判据里的代理 (形态 E, 必须写明): 意图 → 工具的真实绑定在库表 {@code ai_intent_configs.tool_name}, 单测里读不到;
 * 这里用<b>约定</b>「意图码小写 == 工具名」去找工具, 当前 14 个默认开放意图全部命中。若将来某个意图的工具名与意图码不同,
 * 「找不到同名工具」这一条会红并点名 —— 那时请把映射显式补进 {@link #EXPLICIT_TOOL_OF_INTENT}, 不要删本断言。
 */
class FrontlineDefaultOpenToolsHavePreviewTest {

    /** 意图码 → 工具名, 仅登记「与意图码小写不一致」的例外 (当前为空)。 */
    private static final Map<String, String> EXPLICIT_TOOL_OF_INTENT = Map.of();

    @Test
    @DisplayName("DEFAULT_OPEN 里的每个意图都能找到同名工具, 且该工具 supportsPreview()=true")
    void everyDefaultOpenWriteToolSupportsPreview() throws Exception {
        Map<String, ToolExecutor> toolsByName = scanTools();
        assertThat(toolsByName.size()).as("扫到的工具数 (扫描器活着)").isGreaterThan(100);

        Set<String> intents = new TreeSet<>();
        FrontlineAiWriteGrantService.DEFAULT_OPEN.values().forEach(intents::addAll);
        assertThat(intents).as("默认开放的意图数").hasSize(14);

        List<String> missingTool = new ArrayList<>();
        List<String> noPreview = new ArrayList<>();
        for (String intent : intents) {
            String toolName = EXPLICIT_TOOL_OF_INTENT.getOrDefault(intent, intent.toLowerCase());
            ToolExecutor tool = toolsByName.get(toolName);
            if (tool == null) {
                missingTool.add(intent + " -> " + toolName);
            } else if (!tool.supportsPreview()) {
                noPreview.add(intent + " -> " + tool.getClass().getSimpleName());
            }
        }
        assertThat(missingTool).as("找不到同名工具的默认开放意图 (请补 EXPLICIT_TOOL_OF_INTENT)").isEmpty();
        assertThat(noPreview).as("默认开放却不支持预览的工具 —— 一线点了只会看到「暂不支持」").isEmpty();
    }

    @Test
    @DisplayName("对照: 扫描器认得出「不支持预览」的工具 (阴性样本), 所以上面的空列表不是扫描器瞎了")
    void scannerCanSeeToolsWithoutPreview() throws Exception {
        long without = scanTools().values().stream().filter(t -> !t.supportsPreview()).count();
        assertThat(without).as("全仓仍有大量不支持预览的工具 (它们不在一线默认开放名单里)").isGreaterThan(50);
    }

    private static Map<String, ToolExecutor> scanTools() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        ObjenesisStd objenesis = new ObjenesisStd();
        Map<String, ToolExecutor> byName = new TreeMap<>();
        for (BeanDefinition def : scanner.findCandidateComponents("com.cretas.aims.ai.tool.impl")) {
            Class<?> cls = Class.forName(def.getBeanClassName());
            if (cls.isInterface() || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
                continue;
            }
            try {
                ToolExecutor tool = (ToolExecutor) objenesis.newInstance(cls);
                String name = tool.getToolName();
                if (name != null) {
                    byName.put(name, tool);
                }
            } catch (RuntimeException ignoredBecauseNameNeedsInjectedState) {
                // 极少数工具的名字依赖注入态, 取不到就当不在这张表里; 默认开放的 14 个都是字面常量
            }
        }
        return byName;
    }
}
