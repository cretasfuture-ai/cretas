package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 配工艺听来的工艺参数 (「油温 150–160℃」): 说清楚并发布后, 工序 customFieldSchema 里有它;
 * 说不清 (缺范围/缺单位/自相矛盾) 时发布闸拦住, 不会走到落库。
 */
class WorkflowProcessParamMaterializerContractTest {

    private final WorkProcessRepository repository = mock(WorkProcessRepository.class);
    private final ProductProcessWorkflowRepository workflowRepository = mock(ProductProcessWorkflowRepository.class);
    private final WorkflowProcessParamMaterializer materializer =
            new WorkflowProcessParamMaterializer(repository, workflowRepository);

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
    void confirmedParamEndsUpInTheWorkProcessSchema() {
        ProductProcessWorkflowDTO def = definition(List.of(oilTemp()));
        assertTrue(WorkflowPendingConfirmations.blockers(def).isEmpty(), "齐全的参数不应拦发布");
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.of(process(null)));

        assertEquals(1, materializer.materialize("F006", "PT-1", def, null));

        ArgumentCaptor<WorkProcess> saved = ArgumentCaptor.forClass(WorkProcess.class);
        verify(repository).save(saved.capture());
        List<Map<String, Object>> schema = saved.getValue().getCustomFieldSchema();
        assertEquals(1, schema.size());
        Map<String, Object> field = schema.get(0);
        assertEquals("油温", field.get("label"));
        assertEquals("number", field.get("type"));
        assertEquals("℃", field.get("unit"));
        assertEquals(150, field.get("min"));
        assertEquals(160, field.get("max"));
        assertTrue(String.valueOf(field.get("key")).startsWith("param_"));
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
    void republishUpdatesTheSameFieldAndKeepsOtherFields() {
        List<Map<String, Object>> existing = new ArrayList<>();
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("key", "note");
        other.put("label", "备注");
        other.put("type", "text");
        other.put("enabled", true);
        existing.add(other);
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("key", "oil");
        old.put("label", "油温");
        old.put("type", "number");
        old.put("enabled", false);
        old.put("min", 100);
        old.put("max", 120);
        existing.add(old);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.of(process(existing)));

        Map<String, Object> confirmed = oilTemp();
        confirmed.put("overwriteConfirmed", true);
        materializer.materialize("F006", "PT-1", definition(List.of(confirmed)), null);

        ArgumentCaptor<WorkProcess> saved = ArgumentCaptor.forClass(WorkProcess.class);
        verify(repository).save(saved.capture());
        List<Map<String, Object>> schema = saved.getValue().getCustomFieldSchema();
        assertEquals(2, schema.size(), "同名参数是更新不是新增");
        assertEquals("备注", schema.get(0).get("label"));
        assertEquals("oil", schema.get(1).get("key"));
        assertEquals(false, schema.get(1).get("enabled"), "不改人工配好的启用状态");
        assertEquals(150, schema.get(1).get("min"));
        assertEquals(160, schema.get(1).get("max"));
    }

    @Test
    void sameLabelTextFieldIsNotSilentlyTurnedIntoNumber() {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("key", "oil");
        text.put("label", "油温");
        text.put("type", "text");
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(text)))));

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", "PT-1", definition(List.of(oilTemp())), null));
        assertEquals("WORKFLOW_PROCESS_PARAM_TYPE_CONFLICT", e.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void nodeWithoutParamsOrWithUnknownProcessWritesNothing() {
        assertEquals(0, materializer.materialize("F006", "PT-1", definition(null), null));
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.empty());
        assertEquals(0, materializer.materialize("F006", "PT-1", definition(List.of(oilTemp())), null));
        verify(repository, never()).save(any());
        assertFalse(WorkflowPendingConfirmations.blockers(definition(null)).stream().anyMatch(b -> b.contains("工艺参数")));
    }

    private Map<String, Object> manualOil() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", "oil");
        f.put("label", "油温");
        f.put("type", "number");
        f.put("enabled", true);
        f.put("unit", "℃");
        f.put("standardValue", 155);
        f.put("min", 150);
        f.put("max", 160);
        return f;
    }

    @Test
    void valuesTheCanvasDoesNotGiveAreKeptNotDeleted() {
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(manualOil())))));
        Map<String, Object> onlyMax = new LinkedHashMap<>();
        onlyMax.put("label", "油温");
        onlyMax.put("unit", "℃");
        onlyMax.put("max", 160); // 与现有相同, 只补一个键
        materializer.materialize("F006", "PT-1", definition(List.of(onlyMax)), null);

        ArgumentCaptor<WorkProcess> saved = ArgumentCaptor.forClass(WorkProcess.class);
        verify(repository).save(saved.capture());
        Map<String, Object> field = saved.getValue().getCustomFieldSchema().get(0);
        assertEquals(155, field.get("standardValue"), "画布没给 standardValue, 原值必须还在");
        assertEquals(150, field.get("min"));
        assertEquals(160, field.get("max"));
    }

    @Test
    void changingAnExistingValueWithoutConfirmationIs409AndWritesNothing() {
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(manualOil())))));
        ProductProcessWorkflow other = new ProductProcessWorkflow();
        other.setProductTypeId("PT-A");
        other.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        other.setNodesJson("[{\"data\":{\"workProcessId\":\"WP-CHAO\"}}]");
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc("F006"))
                .thenReturn(List.of(other));
        Map<String, Object> hotter = oilTemp();
        hotter.put("max", 170);

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", "PT-B", definition(List.of(hotter)), null));
        assertEquals(WorkflowProcessParamMaterializer.OVERWRITE_UNCONFIRMED, e.getErrorCode());
        assertTrue(e.getMessage().contains("max 160→170"), e.getMessage());
        assertTrue(e.getMessage().contains("PT-A"), "要列出共用该工序的其他已发布产品: " + e.getMessage());
        verify(repository, never()).save(any());

        hotter.put("overwriteConfirmed", true);
        assertEquals(1, materializer.materialize("F006", "PT-B", definition(List.of(hotter)), null));
        verify(repository).save(any());
    }

    @Test
    void republishDoesNotRewriteValuesTheCanvasDidNotChangeSinceLastPublish() {
        // 画布里留着 150-160; 期间有人在工序管理页手工改成 100-120。画布没动 → 重发布不得写回旧值
        Map<String, Object> manual = manualOil();
        manual.put("min", 100);
        manual.put("max", 120);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(manual)))));
        Map<String, Object> sameAsLast = oilTemp();
        sameAsLast.put("overwriteConfirmed", true); // 过期的确认标记也不能让它写回
        ProductProcessWorkflowDTO now = definition(List.of(sameAsLast));
        ProductProcessWorkflowDTO last = definition(List.of(oilTemp()));

        assertEquals(0, materializer.materialize("F006", "PT-1", now, last));
        verify(repository, never()).save(any());
    }
}
