package com.cretas.aims.service.config;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.canvas.CanvasApplyTemplateTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleModuleTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleSkillTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasToggleToolTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasUpdateFieldTool;
import com.cretas.aims.ai.tool.impl.canvas.CanvasUpdateTriggerChainTool;
import com.cretas.aims.entity.config.FactoryTemplate;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real Spring bean creation, including ToolRegistry's actual PostConstruct tool discovery. */
class CanvasBoundToolRegistryStartupGuardTest {
    @Test
    void mandatorySixWritersAndStateReaderStartWithoutCircularReferencesAndResolveRealRegistry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            registerMock(context, FactoryConfigurationRepository.class);
            registerMock(context, FactoryModuleConfigRepository.class);
            registerMock(context, FactoryToolConfigRepository.class);
            registerMock(context, FactorySkillConfigRepository.class);
            registerMock(context, FactoryTriggerChainRepository.class);
            registerMock(context, FactoryTemplateRepository.class);
            registerMock(context, FactoryDefaultValueRepository.class);
            registerMock(context, CanvasDynamicFieldRepository.class);
            registerMock(context, ModuleSchemaRepository.class);
            registerMock(context, FactoryConfigService.class);
            registerMock(context, FactoryFeatureMenuWriteLock.class);
            registerMock(context, EntityManager.class);
            registerMock(context, jakarta.persistence.EntityManagerFactory.class);
            registerMock(context, PlatformTransactionManager.class);
            registerMock(context, ToolSimilarityService.class);
            // Reader first exposes a constructor dependency cycle instead of hiding it
            // behind an already-built registry or a mocked ToolRegistry.
            context.register(CanvasConfigStateReader.class, CanvasConfigPlanBindingService.class,
                    CanvasConfigCommandService.class, ToolRegistry.class,
                    CanvasToggleModuleTool.class, CanvasUpdateFieldTool.class,
                    CanvasApplyTemplateTool.class, CanvasToggleToolTool.class,
                    CanvasToggleSkillTool.class, CanvasUpdateTriggerChainTool.class);
            context.refresh();

            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.getAllToolNames()).containsExactlyInAnyOrder(
                    "canvas_toggle_module", "canvas_update_field", "canvas_apply_template",
                    "canvas_toggle_tool", "canvas_toggle_skill", "canvas_update_trigger_chain");
            registry.getAllToolNames().forEach(name -> {
                Object tool = registry.getExecutor(name).orElseThrow();
                assertThat(ReflectionTestUtils.getField(tool, "commandExecutor"))
                        .isSameAs(context.getBean(CanvasConfigCommandService.class));
            });
            FactoryTemplateRepository templates = context.getBean(FactoryTemplateRepository.class);
            when(templates.findByTemplateCodeIgnoreCase("ctx")).thenReturn(Optional.of(
                    FactoryTemplate.builder().id(1L).templateCode("CTX").isActive(true)
                            .baseConfig(Map.of("defaultOverrides", Map.of("disabledTools", List.of("canvas_*"))))
                            .build()));
            CanvasConfigStateReader.Snapshot state = context.getBean(CanvasConfigStateReader.class)
                    .read("LOCAL_CTX", "canvas_apply_template", Map.of("templateCode", "ctx"));
            assertThat(state.state().get("templateImpact")).isInstanceOf(Map.class);
            // The real registry is usable after init, not replaced with an optional empty fallback.
            Map<?, ?> impact = (Map<?, ?>) state.state().get("templateImpact");
            assertThat(impact.get("disabledTools")).isEqualTo(registry.getAllToolNames().stream().sorted().toList());
            verify(context.getBean(ToolSimilarityService.class)).detectSimilarTools(argThat(tools ->
                    tools.size() == 6 && tools.stream().allMatch(tool ->
                            registry.getExecutor(tool.getToolName()).orElseThrow() == tool)));
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
