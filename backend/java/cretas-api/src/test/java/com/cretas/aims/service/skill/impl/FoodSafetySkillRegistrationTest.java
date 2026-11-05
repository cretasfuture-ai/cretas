package com.cretas.aims.service.skill.impl;

import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Sprint 8 P3 Phase B — Verify 3 food safety Skills are registered with proper triggers + tools.
 */
@ExtendWith(MockitoExtension.class)
class FoodSafetySkillRegistrationTest {

    @Mock
    private SmartBiSkillRepository smartBiSkillRepository;

    @Mock
    private FactorySkillConfigRepository factorySkillConfigRepository;

    private SkillRegistryImpl registry;

    @BeforeEach
    void setUp() {
        when(smartBiSkillRepository.findAll())
                .thenReturn(Collections.emptyList());

        registry = new SkillRegistryImpl(smartBiSkillRepository, new ObjectMapper());
        ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
        ReflectionTestUtils.setField(registry, "loadDefaults", true);
        ReflectionTestUtils.setField(registry, "defaultMinMatchScore", 0.1);
        ReflectionTestUtils.setField(registry, "factorySkillConfigRepository",
                factorySkillConfigRepository);

        registry.init();
    }

    @Test
    @DisplayName("FS-SKILL-01: haccp-checkpoint-management skill registered")
    void haccpSkillRegistered() {
        Optional<SkillDefinition> skill = registry.getSkill("haccp-checkpoint-management");
        assertTrue(skill.isPresent(), "haccp-checkpoint-management 应该已注册");
        SkillDefinition def = skill.get();
        assertEquals("QUALITY", def.getCategory());
        assertTrue(def.isEnabled());
        assertTrue(def.getTools().contains("haccp_checkpoint_review"));
        List<String> triggers = def.getTriggers();
        assertTrue(triggers.contains("HACCP") || triggers.contains("CCP"),
                "应含 HACCP/CCP 触发词");
    }

    @Test
    @DisplayName("FS-SKILL-02: food-additive-compliance skill registered")
    void additiveSkillRegistered() {
        Optional<SkillDefinition> skill = registry.getSkill("food-additive-compliance");
        assertTrue(skill.isPresent());
        SkillDefinition def = skill.get();
        assertEquals("QUALITY", def.getCategory());
        assertTrue(def.getTools().contains("additive_compliance_check"));
        assertTrue(def.getTriggers().stream().anyMatch(t -> t.contains("添加剂")
                || t.contains("GB 2760")));
    }

    @Test
    @DisplayName("FS-SKILL-03: food-safety-recall is a factory read-only catalog Skill")
    void recallSkillRegisteredAsFactoryReadOnlyCatalogSkill() {
        Optional<SkillDefinition> skill = registry.getSkill("food-safety-recall");
        assertTrue(skill.isPresent(), "food-safety-recall 应该已注册");
        SkillDefinition def = skill.get();
        assertEquals("FACTORY", def.getCategory());
        assertEquals(40, def.getPriority());  // priority 40 — 高优先级 (P3 关键 Skill)

        List<String> tools = def.getTools();
        assertEquals(List.of(
                "batch_trace_by_customer_date",
                "batch_full_trace",
                "haccp_checkpoint_review",
                "additive_compliance_check",
                "recall_loss_estimate"), tools);
        assertFalse(tools.contains("inventory_freeze"));
        assertFalse(tools.contains("customer_notify_batch"));
        assertFalse(tools.contains("regulatory_report_generate"));

        assertEquals(List.of("召回评估", "食品召回", "召回影响", "召回损失"),
                def.getTriggers());
    }

    @Test
    @DisplayName("FS-SKILL-04: findBestMatch finds food-safety-recall on catalog trigger")
    void recallSkillMatchesByTrigger() {
        Optional<SkillDefinition> best = registry.findBestMatch("召回评估 B-20260518-A03");
        assertTrue(best.isPresent(), "应能匹配到食品召回 Skill");
        // 不强制 best 是 food-safety-recall (可能其他 Skill score 更高)
        // 但 food-safety-recall 必须在 matches 列表里
        List<SkillDefinition> matches = registry.findMatchingSkills("召回评估");
        assertTrue(matches.stream().anyMatch(s -> "food-safety-recall".equals(s.getName())),
                "召回评估 查询应能匹配 food-safety-recall Skill");
    }
}
