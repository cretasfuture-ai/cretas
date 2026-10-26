package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.decoration.FactoryHomeLayoutToolStore;
import com.cretas.aims.ai.tool.impl.decoration.HomeLayoutGenerateTool;
import com.cretas.aims.ai.tool.impl.decoration.HomeLayoutUpdateTool;
import com.cretas.aims.ai.tool.impl.pagedesign.PageComponentAddTool;
import com.cretas.aims.ai.tool.impl.pagedesign.PageDataBindTool;
import com.cretas.aims.ai.tool.impl.pagedesign.PageGenerateTool;
import com.cretas.aims.ai.tool.impl.pagedesign.PageStyleUpdateTool;
import com.cretas.aims.ai.tool.impl.print.PrintTemplateCreateFromAITool;
import com.cretas.aims.entity.LowcodeComponentDefinition;
import com.cretas.aims.entity.LowcodePageConfig;
import com.cretas.aims.repository.LowcodeComponentDefinitionRepository;
import com.cretas.aims.repository.LowcodePageConfigRepository;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用一句话生成首页 / 页面 / 打印模板的工具: 描述里的电话、岗位 + 人名、公司名换代号再发, 脱敏不了就不生成。
 * 走真实入口 {@code tool.execute}, 只把仓库与模型打桩; 七个工具各跑一遍 (参数化维度是工具本身)。
 */
class ToolPromptEgressRedactionTest {

    private static final String FACTORY = "F006";
    private static final String SAID = "首页加一块卡片, 写上质检员赵铁柱 13912345678 和华东冷链商贸有限公司的复检进度";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        when(llm.chatLowTemp(anyString(), anyString())).thenReturn("{}");
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
    }

    static Stream<Arguments> tools() {
        return Stream.of(
                Arguments.of("home_layout_generate", (Supplier<AbstractBusinessTool>) HomeLayoutGenerateTool::new, "userInput"),
                Arguments.of("home_layout_update", (Supplier<AbstractBusinessTool>) HomeLayoutUpdateTool::new, "userInput"),
                Arguments.of("page_component_add", (Supplier<AbstractBusinessTool>) PageComponentAddTool::new, "userInput"),
                Arguments.of("page_data_bind", (Supplier<AbstractBusinessTool>) PageDataBindTool::new, "userInput"),
                Arguments.of("page_generate", (Supplier<AbstractBusinessTool>) PageGenerateTool::new, "userInput"),
                Arguments.of("page_style_update", (Supplier<AbstractBusinessTool>) PageStyleUpdateTool::new, "userInput"),
                Arguments.of("print_template_create_from_ai",
                        (Supplier<AbstractBusinessTool>) PrintTemplateCreateFromAITool::new, "designPrompt"));
    }

    private AbstractBusinessTool wired(Supplier<AbstractBusinessTool> factory) {
        AbstractBusinessTool tool = factory.get();
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "dashScopeClient", llm);
        ReflectionTestUtils.setField(tool, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
        setIfPresent(tool, "layoutStore", layoutStore());
        setIfPresent(tool, "pageConfigRepository", pages());
        setIfPresent(tool, "componentDefinitionRepository", components());
        return tool;
    }

    private static void setIfPresent(Object target, String field, Object value) {
        try {
            target.getClass().getDeclaredField(field);
        } catch (NoSuchFieldException e) {
            return;
        }
        ReflectionTestUtils.setField(target, field, value);
    }

    private static FactoryHomeLayoutToolStore layoutStore() {
        FactoryHomeLayoutToolStore store = mock(FactoryHomeLayoutToolStore.class);
        when(store.loadFlatLayout(anyString(), any())).thenReturn(List.of(Map.of("id", "welcome", "visible", true)));
        return store;
    }

    private static LowcodePageConfigRepository pages() {
        LowcodePageConfigRepository repo = mock(LowcodePageConfigRepository.class);
        when(repo.findByFactoryIdAndPageId(anyString(), anyString())).thenReturn(Optional.of(new LowcodePageConfig()));
        return repo;
    }

    private static LowcodeComponentDefinitionRepository components() {
        LowcodeComponentDefinitionRepository repo = mock(LowcodeComponentDefinitionRepository.class);
        LowcodeComponentDefinition card = new LowcodeComponentDefinition();
        card.setStatus(1);
        when(repo.findByIsSystemOrFactoryId(anyInt(), anyString())).thenReturn(List.of(card));
        return repo;
    }

    private static ToolCall call(AbstractBusinessTool tool, String argName) throws Exception {
        Map<String, Object> args = argName.equals("designPrompt")
                ? Map.of("designPrompt", SAID, "entityType", "PRINT_SALES_ORDER")
                : Map.of("userInput", SAID);
        return ToolCall.builder().id("c1").function(ToolCall.FunctionCall.builder().name(tool.getToolName())
                .arguments(new ObjectMapper().writeValueAsString(args)).build()).build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tools")
    void descriptionIsRedactedBeforeItReachesTheModel(String name, Supplier<AbstractBusinessTool> factory,
                                                     String argName) throws Exception {
        AbstractBusinessTool tool = wired(factory);
        assertThat(tool.getToolName()).isEqualTo(name);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), user.capture())).thenReturn("{}");

        tool.execute(call(tool, argName), Map.of("factoryId", FACTORY, "userId", 1L));

        assertThat(user.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检进度");
        assertThat(system.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tools")
    void nothingIsSentWhenRedactionFails(String name, Supplier<AbstractBusinessTool> factory,
                                         String argName) throws Exception {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        AbstractBusinessTool tool = wired(factory);

        String out = tool.execute(call(tool, argName), Map.of("factoryId", FACTORY, "userId", 1L));

        verify(llm, never()).chatLowTemp(anyString(), anyString());
        assertThat(out).contains("未发给 AI");
    }
}
