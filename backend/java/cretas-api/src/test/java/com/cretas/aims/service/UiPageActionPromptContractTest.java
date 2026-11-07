package com.cretas.aims.service;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.config.DashScopeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UI_PAGE_ACTION 的资源、输出动作边界和纯解析职责契约。
 *
 * <p>这个实体只能经 FormAssistantService 产出 field_values；它不接入 Tool、Skill 或业务写服务。
 */
class UiPageActionPromptContractTest {

    private static final List<String> EXISTING_FACTORY_ENTITIES = List.of(
            "PRODUCTION_PLAN", "PRODUCT", "PURCHASE_ORDER", "SALES_ORDER",
            "STOCKTAKING", "WH_INBOUND", "PROCESS_TASK");

    @Test
    void registryLoadsDedicatedPromptWithoutChangingExistingFactoryPrompts() {
        FormPromptRegistry registry = new FormPromptRegistry();

        String prompt = registry.promptFor("factory", "UI_PAGE_ACTION").orElseThrow();
        assertThat(prompt).contains("goal", "page", "controls", "routes", "history")
                .contains("click", "fill", "navigate", "wait", "ask", "done")
                .contains("一次且仅一次", "只能是", "不得凭空创造 ref")
                .contains("不执行或模拟任何业务写入")
                .doesNotContain("FILL_FORM");

        for (String entity : EXISTING_FACTORY_ENTITIES) {
            assertThat(registry.promptFor("factory", entity))
                    .as("既有实体 prompt 不得因新增页面动作 prompt 丢失: %s", entity)
                    .isPresent();
        }
    }

    @Test
    void formAssistantInjectsPromptAndReturnsOneStructuredActionOnly() {
        DashScopeClient llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatLowTemp(anyString(), anyString())).thenReturn(
                "{\"field_values\":{\"kind\":\"fill\","
                        + "\"target\":\"control:quantity\",\"value\":\"100\","
                        + "\"message\":\"填写数量 100\"},\"confidence\":0.96}");

        FormAssistantService service = new FormAssistantService(
                llm, mock(DashScopeVisionClient.class), mock(DashScopeConfig.class),
                new ObjectMapper(), new FormPromptRegistry());

        FormAssistantService.FormParseResult result = service.parseFormInput(
                "采购牛腱 100 公斤", "UI_PAGE_ACTION", List.of(
                        Map.of("name", "kind", "type", "string", "required", true),
                        Map.of("name", "target", "type", "string", "required", true),
                        Map.of("name", "value", "type", "string"),
                        Map.of("name", "message", "type", "string", "required", true)), "F006");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chatLowTemp(systemPrompt.capture(), anyString());
        assertThat(systemPrompt.getValue()).contains("UI_PAGE_ACTION", "field_values")
                .contains("不执行或模拟任何业务写入");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldValues())
                .containsEntry("kind", "fill")
                .containsEntry("target", "control:quantity")
                .containsEntry("value", "100")
                .containsEntry("message", "填写数量 100")
                .hasSize(4);
    }

    @Test
    void actionSafetyContractNamesOnlySnapshotTargetsAndNoBusinessExecutionAuthority() {
        String prompt = new FormPromptRegistry().promptFor("factory", "UI_PAGE_ACTION").orElseThrow();

        assertThat(prompt).contains("controls[].ref", "routes[].ref", "navigate")
                .contains("密码、密钥、验证码、付款、删除、作废、权限配置、登出")
                .contains("history", "真实成功结果", "done")
                .contains("实际点击、填写、提交和结果判断由外部浏览器控制器逐步完成");
        assertThat(prompt).doesNotContain("confirmToken", "X-Cretas-Confirmation-Token", "ToolCall")
                .doesNotContain("executeWithTool", "/ai-intents/confirm");
    }
}
