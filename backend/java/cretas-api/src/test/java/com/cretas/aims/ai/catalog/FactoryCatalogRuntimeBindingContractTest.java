package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Runtime wiring contract for the shared factory catalog.
 *
 * <p>This deliberately drives the real registry entry points with classpath
 * Tool metadata and an empty Skill database.  It does not replace runtime
 * registration with a string-only copy of the catalog.</p>
 */
class FactoryCatalogRuntimeBindingContractTest {

    private static final String TOOL_IMPL_PACKAGE = "com.cretas.aims.ai.tool.impl";

    @Test
    void everyCanonicalCatalogToolBindsToARealEnabledToolRegistryEntry() {
        CatalogSnapshot catalog = readCatalog();
        Map<String, ToolExecutor> metadata = scanToolMetadata();

        assertThat(catalog.canonicalCodes).hasSize(266);
        assertThat(catalog.reclassifiedCodes).hasSize(29);
        Set<String> canonicalReclassifiedCodes = catalog.reclassifiedCodes.stream()
                .map(FactoryIntentCatalog::canonicalCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(canonicalReclassifiedCodes).hasSize(29);
        assertThat(FactoryIntentCatalog.canonicalCodes())
                .hasSize(266)
                .containsAll(canonicalReclassifiedCodes);
        assertThat(catalog.toolNames).hasSize(252);
        assertThat(metadata.keySet()).containsAll(catalog.toolNames);

        ToolRegistry toolRegistry = register(metadata.values());
        for (String toolName : catalog.toolNames) {
            ToolExecutor executor = metadata.get(toolName);
            assertThat(executor).as("catalog tool metadata: %s", toolName).isNotNull();
            assertThat(executor.isEnabled()).as("catalog tool enabled: %s", toolName).isTrue();
            assertThat(toolRegistry.hasExecutor(toolName))
                    .as("ToolRegistry.init must register catalog tool: %s", toolName)
                    .isTrue();
            assertThat(toolRegistry.getExecutor(toolName)).containsSame(executor);
        }
    }

    @Test
    void allCatalogOwnedSkillsLoadFromDefaultsAndResolveReadOnlyAgainstRegisteredTools() {
        CatalogSnapshot catalog = readCatalog();
        Map<String, ToolExecutor> metadata = scanToolMetadata();
        ToolRegistry toolRegistry = register(metadata.values());

        assertThat(catalog.skillTools).hasSize(11);
        SkillRegistryImpl skillRegistry = loadSkillsWithEmptyDatabase();

        for (Map.Entry<String, List<String>> entry : catalog.skillTools.entrySet()) {
            SkillDefinition skill = skillRegistry.getSkill(entry.getKey())
                    .orElseThrow(() -> new AssertionError("catalog Skill was not loaded: " + entry.getKey()));
            assertThat(skill.getTools())
                    .as("catalog Skill tool binding: %s", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
            assertThat(ToolAccessModes.resolveSkill(
                    skill, name -> toolRegistry.getExecutor(name).orElse(null)))
                    .as("catalog Skill access mode: %s", entry.getKey())
                    .isEqualTo(ToolExecutor.AccessMode.READ);
            for (String toolName : entry.getValue()) {
                assertThat(toolRegistry.hasExecutor(toolName))
                        .as("Skill tool must be registered: %s", toolName)
                        .isTrue();
            }
        }
    }

    @Test
    void legacyRecallWriteOverrideIsRejectedAndNullSkillBindingClearsStaleTool() {
        List<String> required = FactoryIntentCatalog.requiredSkillTools("food-safety-recall");
        assertThat(required).isNotNull().isNotEmpty();
        assertThat(FactoryIntentCatalog.isAllowedSkill("food-safety-recall", required)).isTrue();
        assertThat(FactoryIntentCatalog.isAllowedSkill("food-safety-recall",
                List.of("batch_full_trace", "recall_loss_estimate", "food_sample_create")))
                .as("an old DB write-chain must not override the catalog-owned read-only Skill")
                .isFalse();

        AIIntentConfig source = AIIntentConfig.builder()
                .factoryId("FACTORY-A")
                .intentCode("FOOD_SAFETY_RECALL")
                .toolName("legacy_recall_write")
                .requiredRoles("[\"quality_manager\"]")
                .requiresApproval(true)
                .isActive(false)
                .build();
        AIIntentConfig projected = FactoryIntentCatalog.project(source);
        assertThat(projected).isNotNull().isNotSameAs(source);
        assertThat(projected.getToolName()).isNull();
        assertThat(projected.getFactoryId()).isEqualTo("FACTORY-A");
        assertThat(projected.getRequiredRoles()).isEqualTo(source.getRequiredRoles());
        assertThat(projected.getRequiresApproval()).isTrue();
        assertThat(projected.getIsActive()).isFalse();
        assertThat(source.getToolName()).isEqualTo("legacy_recall_write");
    }

    private static ToolRegistry register(Iterable<ToolExecutor> executors) {
        ToolRegistry registry = new ToolRegistry();
        List<ToolExecutor> list = new ArrayList<>();
        executors.forEach(list::add);
        ReflectionTestUtils.setField(registry, "toolExecutors", list);

        ToolSimilarityService similarity = mock(ToolSimilarityService.class);
        doReturn(List.of()).when(similarity).detectSimilarTools(anyCollection());
        ReflectionTestUtils.setField(registry, "toolSimilarityService", similarity);
        registry.init();
        return registry;
    }

    private static SkillRegistryImpl loadSkillsWithEmptyDatabase() {
        SmartBiSkillRepository repository = mock(SmartBiSkillRepository.class);
        doReturn(List.of()).when(repository).findByEnabledTrueOrderByPriorityAsc();
        SkillRegistryImpl registry = new SkillRegistryImpl(repository, new ObjectMapper());
        ReflectionTestUtils.setField(registry, "loadDefaults", true);
        ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
        ReflectionTestUtils.setField(registry, "defaultMinMatchScore", 0.1d);
        registry.loadSkills();
        return registry;
    }

    private static Map<String, ToolExecutor> scanToolMetadata() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));

        Map<String, ToolExecutor> tools = new LinkedHashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(TOOL_IMPL_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("tool class cannot be loaded: " + definition.getBeanClassName(), e);
            }
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()
                    || type.getEnclosingClass() != null) {
                continue;
            }
            ToolExecutor executor = mock(type.asSubclass(ToolExecutor.class),
                    withSettings().defaultAnswer(CALLS_REAL_METHODS));
            String name = executor.getToolName();
            if (name == null || name.isBlank()) {
                continue;
            }
            tools.putIfAbsent(name, executor);
        }
        return tools;
    }

    private static CatalogSnapshot readCatalog() {
        try (InputStream input = FactoryCatalogRuntimeBindingContractTest.class
                .getResourceAsStream("/ai/factory-intent-catalog.json")) {
            if (input == null) {
                throw new AssertionError("factory intent catalog resource is missing");
            }
            JsonNode document = new ObjectMapper().readTree(input);
            Set<String> canonicalCodes = new LinkedHashSet<>();
            Set<String> toolNames = new LinkedHashSet<>();
            Map<String, List<String>> skillTools = new LinkedHashMap<>();
            for (JsonNode row : document.path("canonical_intents")) {
                canonicalCodes.add(row.path("intent_code").asText());
                if (row.path("tool_name").isTextual()) {
                    toolNames.add(row.path("tool_name").asText());
                }
                if (row.path("skill_name").isTextual() && row.path("skill_tools").isArray()) {
                    List<String> tools = new ArrayList<>();
                    row.path("skill_tools").forEach(tool -> {
                        tools.add(tool.asText());
                        toolNames.add(tool.asText());
                    });
                    skillTools.put(row.path("skill_name").asText(), List.copyOf(tools));
                }
            }
            Set<String> reclassifiedCodes = new LinkedHashSet<>();
            document.path("reclassified_intents").forEach(code -> reclassifiedCodes.add(code.asText()));
            return new CatalogSnapshot(canonicalCodes, toolNames, skillTools, reclassifiedCodes);
        } catch (IOException e) {
            throw new AssertionError("factory intent catalog cannot be read", e);
        }
    }

    private record CatalogSnapshot(Set<String> canonicalCodes, Set<String> toolNames,
                                   Map<String, List<String>> skillTools,
                                   Set<String> reclassifiedCodes) {
    }
}
