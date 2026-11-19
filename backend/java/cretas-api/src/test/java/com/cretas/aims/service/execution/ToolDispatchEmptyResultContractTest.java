package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 工具结果解析: 「查询成功但没有数据」与「执行出错/无法解析」必须走不同文案。
 * 此前两者都可能落到「本次查询没有获得可展示的结果。请补充…」, 用户看到红条却不知道是没数据还是查坏了。
 */
@DisplayName("ToolDispatch 空结果 vs 出错: 文案分流")
class ToolDispatchEmptyResultContractTest {

    private static final String GENERIC_GUIDE = "本次查询没有获得可展示的结果";

    private ToolDispatchService dispatch;
    private AIIntentConfig intent;

    @BeforeEach
    void setUp() {
        dispatch = mock(ToolDispatchService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(dispatch, "objectMapper", new ObjectMapper());
        intent = new AIIntentConfig();
        intent.setIntentCode("SALES_ORDER_QUERY");
        intent.setIntentName("销售订单查询");
        intent.setIntentCategory("DATA_QUERY");
        intent.setToolName("sales_order_query");
    }

    @Test
    @DisplayName("success=true 且结果集为空(无 message) → 「没有找到相关记录」, 不用引导文案, 带查询对象与时间范围")
    void emptySuccessSaysNoRecords() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse(
                "{\"success\":true,\"data\":{\"orders\":[],\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-18\"}}", intent);

        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        assertThat(r.getMessage()).contains("没有找到").contains("销售订单查询")
                .contains("2026-11-01").contains("2026-11-18");
        assertThat(r.getMessage()).doesNotContain(GENERIC_GUIDE);
    }

    @Test
    @DisplayName("success=true 且 data 为空列表 → 同样是「没有找到」")
    void emptyListSaysNoRecords() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse("{\"success\":true,\"data\":[]}", intent);
        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        assertThat(r.getMessage()).contains("没有找到").doesNotContain(GENERIC_GUIDE);
    }

    @Test
    @DisplayName("success=false 且无 message/error(出错) → 保留原引导文案, 状态 FAILED")
    void failureKeepsGuidance() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse("{\"success\":false,\"data\":{\"orders\":[]}}", intent);
        assertThat(r.getStatus()).isEqualTo("FAILED");
        assertThat(r.getMessage()).contains(GENERIC_GUIDE).doesNotContain("没有找到");
    }

    @Test
    @DisplayName("无法解析的结果 → 出错文案, 不说「没有记录」")
    void unparseableIsNotEmpty() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse("这不是 JSON {{{", intent);
        assertThat(r.getStatus()).isEqualTo("FAILED");
        assertThat(r.getMessage()).doesNotContain("没有找到");
    }

    @Test
    @DisplayName("阴性对照: 有数据的成功、data 为 null 的写操作成功都不被当成空结果")
    void nonEmptyOrNullDataIsNotEmptyResult() {
        IntentExecuteResponse withData = dispatch.parseToolResultToResponse(
                "{\"success\":true,\"data\":{\"orders\":[{\"id\":1}]}}", intent);
        assertThat(withData.getMessage()).doesNotContain("没有找到");

        IntentExecuteResponse writeOk = dispatch.parseToolResultToResponse("{\"success\":true}", intent);
        assertThat(writeOk.getMessage()).isEqualTo("操作已完成。");
    }
}
