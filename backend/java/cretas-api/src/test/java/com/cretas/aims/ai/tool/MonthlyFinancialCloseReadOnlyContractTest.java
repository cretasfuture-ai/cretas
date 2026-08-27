package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住 {@code MONTHLY_FINANCIAL_CLOSE} 这条**写闸豁免**的前提不过期。
 *
 * <h4>🔴 它为什么存在（2026-08-28 生产实测）</h4>
 *
 * <p>{@link WriteGuardService#hasWriteSuffix} 按意图码后缀判写，而 {@code _CLOSE} 在
 * {@code WRITE_SUFFIXES} 里 ⇒ {@code MONTHLY_FINANCIAL_CLOSE} 被判成写意图 ⇒
 * W0 写闸返回 {@code WRITE_CONFIRM_REQUIRED}。</p>
 *
 * <p>但这个名字里的 CLOSE 是「月度<b>复盘</b>」，不是「执行结账」：它 {@code tool_name=NULL}、
 * 走 {@code monthly-financial-close} Skill，串起 8 个只读 Tool，DB 里
 * {@code sensitivity_level='LOW'}。</p>
 *
 * <p><b>被误判的实际代价</b>：财务主管工作台的主区块「月度经营摘要」永远显示
 * 「『月度财务复盘』是写入/修改操作，执行前需要确认。」—— 那个工作台的核心功能从来没工作过。
 * 而且 {@code IntentKnowledgeBase} 把「本月营收」「今日营收」「本月业绩如何」等
 * <b>纯查询短语</b>也短路到这个意图，⇒ 任何人问这些话都拿不到数。</p>
 *
 * <h4>⚠️ 这道闸守的是「豁免的前提」，不是「豁免本身」</h4>
 *
 * <p>豁免合法的**唯一**理由是：那条 Skill 串起来的每一个 Tool 都是 READ。
 * 谁往 {@code monthly-financial-close} 里加一个写 Tool，这道闸就该红 ——
 * 因为那一刻豁免的前提没了，而写闸已经被摘掉，⛔ 没有第二道网。</p>
 *
 * <p>⛔ 这不是「把断言改绿」的地方：红了说明豁免该撤销（或那个 Tool 不该进这条 Skill）。</p>
 */
class MonthlyFinancialCloseReadOnlyContractTest {

    private static final String INTENT_CODE = "MONTHLY_FINANCIAL_CLOSE";
    private static final String SKILL_NAME = "monthly-financial-close";
    private static final Path SKILL_REGISTRY =
            Paths.get("src/main/java/com/cretas/aims/service/skill/impl/SkillRegistryImpl.java");
    private static final Path TOOL_DESCRIPTORS =
            Paths.get("src/main/resources/ai/tool/gateway/tool-descriptors.yaml");

    @Test
    @DisplayName("豁免还在：MONTHLY_FINANCIAL_CLOSE 不被后缀启发式判成写")
    void exemptionIsStillRegistered() {
        WriteGuardService guard = new WriteGuardService();
        assertThat(guard.hasWriteSuffix(INTENT_CODE))
                .as("WriteGuardService 的具名豁免不见了 —— 财务主管工作台的「月度经营摘要」"
                    + "会重新变回一句「是写入/修改操作，执行前需要确认」。")
                .isFalse();

        // 阳性对照: 这把尺子确实认得出真正的写后缀, 否则上面那个 false 是假的。
        assertThat(guard.hasWriteSuffix("SALES_ORDER_CLOSE"))
                .as("阳性对照没过 —— 后缀启发式本身失效了，本次读数作废")
                .isTrue();
    }

    @Test
    @DisplayName("豁免的前提还成立：monthly-financial-close 串的每个 Tool 都是 READ")
    void everyToolInTheSkillIsStillReadOnly() throws IOException {
        List<String> tools = toolsOfSkill(SKILL_NAME);

        // ⛔ 抽到 0 个 Tool 不许当成「通过」—— 那是解析器坏了, 不是 Skill 变干净了。
        assertThat(tools)
                .as("从 %s 里没抽到 %s 的 Tool 列表 —— 解析判据可能过期了，本次读数作废",
                        SKILL_REGISTRY, SKILL_NAME)
                .isNotEmpty();

        String descriptors = Files.readString(TOOL_DESCRIPTORS, StandardCharsets.UTF_8);
        List<String> notRead = new ArrayList<>();
        for (String tool : tools) {
            String actionType = actionTypeOf(descriptors, tool);
            if (!"READ".equals(actionType)) notRead.add(tool + " -> " + actionType);
        }

        assertThat(notRead)
                .as("monthly-financial-close 里出现了非 READ 的 Tool。"
                    + "MONTHLY_FINANCIAL_CLOSE 的写闸豁免正是靠「全是 READ」这一条支撑的，"
                    + "⛔ 前提没了就必须撤销豁免（WriteGuardService.NON_DESTRUCTIVE_SUFFIX_EXCEPTIONS），"
                    + "而不是把这条断言改绿。")
                .isEmpty();
    }

    /** 从 SkillRegistryImpl 源码里抽出某条 Skill 的 {@code .tools(...)} 列表。 */
    private static List<String> toolsOfSkill(String skillName) throws IOException {
        String source = Files.readString(SKILL_REGISTRY, StandardCharsets.UTF_8);
        int nameAt = source.indexOf(".name(\"" + skillName + "\")");
        if (nameAt < 0) return List.of();
        int toolsAt = source.indexOf(".tools(", nameAt);
        if (toolsAt < 0) return List.of();
        int end = source.indexOf(".contextNeeded(", toolsAt);
        if (end < 0) end = Math.min(source.length(), toolsAt + 2000);
        List<String> tools = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z0-9_]+)\"").matcher(source.substring(toolsAt, end));
        while (m.find()) tools.add(m.group(1));
        return tools;
    }

    /** 从权威工具清册里读某个 Tool 的 actionType；读不到返回 {@code <缺失>}（⛔ 不兜底成 READ）。 */
    private static String actionTypeOf(String descriptors, String toolName) {
        Matcher m = Pattern.compile(
                        "toolName:\\s*\"" + Pattern.quote(toolName) + "\"[\\s\\S]{0,400}?actionType:\\s*(\\w+)")
                .matcher(descriptors);
        return m.find() ? m.group(1) : "<清册里没有这个 Tool>";
    }
}
