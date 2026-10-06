package com.cretas.aims.service.skill.impl;

import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * monthly-financial-close 的提示词不许要求它的 Tool 给不出的数。
 *
 * <p>它串的 8 个 Tool 都只取<b>本期一个期间</b>, 没有一个返回上月数据 ——
 * 原提示词却要求「营业收入: ¥X (vs 上月 ±Y%)」「净利润: ¥X (利润率 Y%)」,
 * 等于让模型编数。守的是性质 (不要求环比 / 自算比率), 不是某一句措辞。
 */
class MonthlyFinancialCloseSkillPromptTest {

    private SkillDefinition skill;

    @BeforeEach
    void setUp() {
        SmartBiSkillRepository repo = mock(SmartBiSkillRepository.class);
        when(repo.findByEnabledTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        SkillRegistryImpl registry = new SkillRegistryImpl(repo, new ObjectMapper());
        ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
        ReflectionTestUtils.setField(registry, "loadDefaults", true);
        ReflectionTestUtils.setField(registry, "defaultMinMatchScore", 0.1);
        ReflectionTestUtils.setField(registry, "factorySkillConfigRepository",
                mock(FactorySkillConfigRepository.class));
        registry.init();
        skill = registry.getSkill("monthly-financial-close").orElseThrow();
    }

    @Test
    void promptDoesNotDemandComparisonsTheToolsCannotSupply() {
        String prompt = skill.getPromptTemplate();
        // 阳性对照: 读到的确实是那条 Skill 的输出格式, 否则下面的「不含」没有意义
        assertThat(prompt).contains("营业收入").contains("净利润");

        assertThat(prompt).doesNotContain("vs 上月").doesNotContain("±");
        assertThat(prompt).doesNotContainPattern("利润率\\s*Y%");
        // 明确告诉模型没有上月数据
        assertThat(prompt).contains("不要写环比");
        // 毛利率要用 Tool 已算好的那个字段, 不是让模型自己除
        assertThat(prompt).contains("grossMarginPercent");
    }

    @Test
    void premiseStillHolds_noToolInTheSkillIsAPeriodComparisonTool() {
        // 提示词放弃环比的前提是「没有 Tool 返回上月数据」。谁往这条 Skill 里加了一个
        // 对比 / 趋势类 Tool, 这条就该红 —— 那时应该把环比接回来, 而不是继续说「不要写」。
        assertThat(skill.getTools())
                .isNotEmpty()
                .noneMatch(t -> t.contains("trend") || t.contains("compare") || t.contains("previous")
                        || t.contains("mom") || t.contains("yoy"));
    }
}
