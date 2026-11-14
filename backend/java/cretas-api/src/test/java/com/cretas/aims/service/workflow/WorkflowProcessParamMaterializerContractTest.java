package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.WorkProcessRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布上的工艺参数(「油温 150–160℃」)按产品存: 标准值/上下限只在各产品画布节点里。
 * 发布时共享的工序 schema 里只补一个「只有名字和单位的可选数字字段」, 让报工页有输入框、录入白名单认得它;
 * 说不清(缺范围/缺单位/自相矛盾)时发布闸(blockers)照旧拦住。
 */
class WorkflowProcessParamMaterializerContractTest {

    private final WorkProcessRepository repository = mock(WorkProcessRepository.class);
    private final WorkflowProcessParamMaterializer materializer = new WorkflowProcessParamMaterializer(repository);

    private ProductProcessWorkflowDTO definition(List<Map<String, Object>> params) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-CHAO");
        data.put("processName", "炒制");
        if (params != null) data.put("processParams", params);
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.getNodes().add(new ProductProcessWorkflowDTO.Node(
                "process:1", "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data));
        return dto;
    }

    private Map<String, Object> oilTemp() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "油温");
        p.put("unit", "℃");
        p.put("min", 150);
        p.put("max", 160);
        return p;
    }

    private WorkProcess process(List<Map<String, Object>> schema) {
        return WorkProcess.builder().id("WP-CHAO").factoryId("F006").processName("炒制")
                .customFieldSchema(schema).build();
    }

    @Test
    void newCanvasParamAddsAnOptionalNameAndUnitFieldWithoutAnyStandard() {
        ProductProcessWorkflowDTO def = definition(List.of(oilTemp()));
        assertTrue(WorkflowPendingConfirmations.blockers(def).isEmpty(), "齐全的参数不应拦发布");
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.of(process(null)));

        assertEquals(1, materializer.materialize("F006", def));

        ArgumentCaptor<WorkProcess> saved = ArgumentCaptor.forClass(WorkProcess.class);
        verify(repository).save(saved.capture());
        List<Map<String, Object>> schema = saved.getValue().getCustomFieldSchema();
        assertEquals(1, schema.size());
        Map<String, Object> field = schema.get(0);
        assertEquals("油温", field.get("label"));
        assertEquals("number", field.get("type"));
        assertEquals("℃", field.get("unit"));
        assertTrue(String.valueOf(field.get("key")).startsWith("param_"));
        // 标准只在各产品画布里: 共享字段上没有标准值和上下限
        assertFalse(field.containsKey("standardValue"), "共享 schema 不得带标准值: " + field);
        assertNull(field.get("min"), "共享 schema 不得带下限: " + field);
        assertNull(field.get("max"), "共享 schema 不得带上限: " + field);
        assertEquals(Boolean.TRUE, field.get("enabled"));
    }

    @Test
    void publishingAgainDoesNotAddTheFieldTwice() {
        List<Map<String, Object>> existing = new ArrayList<>();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", "param_x");
        field.put("label", "油温");
        field.put("type", "number");
        field.put("enabled", true);
        field.put("unit", "℃");
        existing.add(field);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.of(process(existing)));

        assertEquals(0, materializer.materialize("F006", definition(List.of(oilTemp()))));
        verify(repository, never()).save(any());
    }

    @Test
    void anExistingSameNameNumberFieldIsLeftAloneIncludingItsOwnStandard() {
        List<Map<String, Object>> existing = new ArrayList<>();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", "oil");
        field.put("label", "油温");
        field.put("type", "number");
        field.put("enabled", true);
        field.put("unit", "度");
        field.put("min", 100);
        field.put("max", 120);
        existing.add(field);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.of(process(existing)));

        materializer.materialize("F006", definition(List.of(oilTemp())));

        verify(repository, never()).save(any());
        assertEquals(100, field.get("min"), "别人配好的字段不被改");
        assertEquals("度", field.get("unit"));
    }

    @Test
    void aDisabledSameNameFieldBlocksPublishInsteadOfBeingSilentlySkipped() {
        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("key", "oil");
        disabled.put("label", "油温");
        disabled.put("type", "number");
        disabled.put("enabled", false);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(disabled)))));

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", definition(List.of(oilTemp()))));
        assertEquals(409, e.getCode());
        assertEquals(WorkflowProcessParamMaterializer.FIELD_DISABLED, e.getErrorCode());
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
        verify(repository, never()).save(any());
        assertEquals(false, disabled.get("enabled"), "不替人重新启用");
    }

    @Test
    void sameNameNonNumberFieldIsRejectedWithAHintAndNothingIsWritten() {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("key", "oil");
        text.put("label", "油温");
        text.put("type", "text");
        text.put("enabled", true);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(text)))));

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", definition(List.of(oilTemp()))));
        assertEquals(409, e.getCode());
        assertEquals(WorkflowProcessParamMaterializer.TYPE_CONFLICT, e.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void unclearParamBlocksPublishSoNothingIsWritten() {
        Map<String, Object> noRange = new LinkedHashMap<>();
        noRange.put("label", "油温");
        noRange.put("unit", "℃");
        noRange.put("min", null);
        noRange.put("max", null);
        List<String> blockers = WorkflowPendingConfirmations.blockers(definition(List.of(noRange)));
        assertTrue(blockers.stream().anyMatch(b -> b.contains("油温") && b.contains("待确认")), blockers.toString());

        Map<String, Object> noUnit = oilTemp();
        noUnit.put("unit", null);
        assertTrue(WorkflowPendingConfirmations.blockers(definition(List.of(noUnit)))
                .stream().anyMatch(b -> b.contains("单位待确认")));

        Map<String, Object> reversed = oilTemp();
        reversed.put("min", 170);
        assertTrue(WorkflowPendingConfirmations.blockers(definition(List.of(reversed)))
                .stream().anyMatch(b -> b.contains("下限大于上限")));

        Map<String, Object> standardOut = oilTemp();
        standardOut.put("standardValue", 200);
        assertTrue(WorkflowPendingConfirmations.blockers(definition(List.of(standardOut)))
                .stream().anyMatch(b -> b.contains("标准值不在上下限范围内")));
    }

    @Test
    void noParamsOrUnknownProcessWritesNothing() {
        assertEquals(0, materializer.materialize("F006", definition(null)));
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.empty());
        assertEquals(0, materializer.materialize("F006", definition(List.of(oilTemp()))));
        verify(repository, never()).save(any());
    }
}
