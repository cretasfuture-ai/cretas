package com.cretas.aims.ai.catalog;

import com.cretas.aims.dto.skill.*;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.smartbi.SmartBiSkill;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.skill.*;
import com.cretas.aims.service.skill.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactoryCatalogSkillActivationContractTest {
    @Test
    void disabledDatabaseRowIsATombstoneForDefaultSkill() {
        var repository = mock(SmartBiSkillRepository.class);
        var disabled = new SmartBiSkill(); disabled.setName("food-safety-recall"); disabled.setEnabled(false);
        when(repository.findAll()).thenReturn(List.of(disabled));
        var registry = new SkillRegistryImpl(repository, new ObjectMapper());
        ReflectionTestUtils.setField(registry, "loadDefaults", true);
        ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
        registry.loadSkills();
        assertThat(registry.getSkill("food-safety-recall")).isEmpty();
        assertThat(registry.getSkill("food-sample-lifecycle")).isPresent();
    }

    @Test
    void disabledCatalogIntentBlocksKeywordAndExplicitSkillEvenWhenToolsAreActive() {
        var registry = mock(SkillRegistry.class);
        var executor = mock(SkillExecutor.class);
        var intents = mock(AIIntentService.class);
        var skill = SkillDefinition.builder().name("food-safety-recall").enabled(true)
                .triggers(List.of("召回评估")).tools(FactoryIntentCatalog.requiredSkillTools("food-safety-recall")).build();
        when(registry.getSkillsForFactory("A")).thenReturn(List.of(skill));
        when(registry.getSkillForFactory("A", skill.getName())).thenReturn(skill);
        when(intents.getIntentByCode("A", "FOOD_SAFETY_RECALL"))
                .thenReturn(Optional.of(AIIntentConfig.builder().intentCode("FOOD_SAFETY_RECALL")
                        .isActive(false).factoryId("A").build()));
        var router = new SkillRouterServiceImpl(registry, executor, intents, mock(ToolRouterService.class));
        ReflectionTestUtils.setField(router, "minSkillConfidence", .1);
        assertThat(router.findMatchingSkills("召回评估", "A")).isEmpty();
        assertThat(router.executeSkill(skill.getName(), SkillContext.builder().factoryId("A").build()).isSuccess()).isFalse();
        verifyNoInteractions(executor);
    }

    @Test
    void factoryDisabledSkillCannotExecuteThroughGlobalLookup() {
        var registry = mock(SkillRegistry.class);
        var executor = mock(SkillExecutor.class);
        var global = SkillDefinition.builder().name("food-safety-recall").enabled(true).build();
        when(registry.getSkill(global.getName())).thenReturn(Optional.of(global));
        when(registry.getSkillsForFactory("A")).thenReturn(List.of());
        when(registry.getSkillForFactory("A", global.getName())).thenReturn(null);
        var router = new SkillRouterServiceImpl(registry, executor, mock(AIIntentService.class), mock(ToolRouterService.class));
        assertThat(router.findMatchingSkills("召回评估", "A")).isEmpty();
        assertThat(router.executeSkill(global.getName(), SkillContext.builder().factoryId("A").build()).isSuccess()).isFalse();
        verifyNoInteractions(executor);
    }
}
