package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
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
    private final ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
    private final WorkflowProcessParamMaterializer materializer =
            new WorkflowProcessParamMaterializer(repository, workflowRepository, productTypeRepository);

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

        assertEquals(1, materializer.materialize("F006", "PT-1", def, null, 7L));

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
        confirm(confirmed);
        materializer.materialize("F006", "PT-1", definition(List.of(confirmed)), null, 7L);

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
                () -> materializer.materialize("F006", "PT-1", definition(List.of(oilTemp())), null, 7L));
        assertEquals("WORKFLOW_PROCESS_PARAM_TYPE_CONFLICT", e.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void nodeWithoutParamsOrWithUnknownProcessWritesNothing() {
        assertEquals(0, materializer.materialize("F006", "PT-1", definition(null), null, 7L));
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO")).thenReturn(Optional.empty());
        assertEquals(0, materializer.materialize("F006", "PT-1", definition(List.of(oilTemp())), null, 7L));
        verify(repository, never()).save(any());
        assertFalse(WorkflowPendingConfirmations.blockers(definition(null)).stream().anyMatch(b -> b.contains("工艺参数")));
    }

    /** 模拟人在画布上勾选「确认覆盖」: 记下勾选时的数值快照 */
    private void confirm(Map<String, Object> param) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String k : List.of("unit", "standardValue", "min", "max")) snapshot.put(k, param.get(k));
        param.put("overwriteConfirmed", true);
        param.put("confirmedBase", 0); // 服务端存草稿时盖的已发布版本号; 这些用例没有上一版 = 0
        param.put("confirmedFor", snapshot);
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
        materializer.materialize("F006", "PT-1", definition(List.of(onlyMax)), null, 7L);

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
                () -> materializer.materialize("F006", "PT-B", definition(List.of(hotter)), null, 7L));
        assertEquals(WorkflowProcessParamMaterializer.OVERWRITE_UNCONFIRMED, e.getErrorCode());
        assertTrue(e.getMessage().contains("max 160→170"), e.getMessage());
        assertTrue(e.getMessage().contains("PT-A"), "要列出共用该工序的其他已发布产品: " + e.getMessage());
        verify(repository, never()).save(any());

        confirm(hotter);
        assertEquals(1, materializer.materialize("F006", "PT-B", definition(List.of(hotter)), null, 7L));
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
        confirm(sameAsLast); // 过期的确认标记也不能让它写回
        ProductProcessWorkflowDTO now = definition(List.of(sameAsLast));
        ProductProcessWorkflowDTO last = definition(List.of(oilTemp()));

        assertEquals(0, materializer.materialize("F006", "PT-1", now, last, 7L));
        verify(repository, never()).save(any());
    }

    @Test
    void aStaleConfirmationDoesNotSurviveAnEditOfTheValues() {
        // 先确认 150-160→150-170 并发布; 工序页被人改成 max=180; 画布再把 max 改成 175 而旧勾还在 → 不得静默覆盖
        Map<String, Object> processNow = manualOil();
        processNow.put("max", 180);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(processNow)))));
        Map<String, Object> edited = oilTemp();
        edited.put("max", 170);
        confirm(edited);          // 勾选时看到的是 170
        edited.put("max", 175);   // 之后改了数值, 旧勾(confirmedFor 仍是 170)还留着

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", "PT-1", definition(List.of(edited)), null, 7L));
        assertEquals(WorkflowProcessParamMaterializer.OVERWRITE_UNCONFIRMED, e.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void aConfirmationWithoutASnapshotIsNotAConfirmation() {
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(manualOil())))));
        Map<String, Object> hotter = oilTemp();
        hotter.put("max", 170);
        hotter.put("overwriteConfirmed", true); // 只有布尔、没有 confirmedFor(例如旧数据/被人为塞入)

        assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", "PT-1", definition(List.of(hotter)), null, 7L));
        verify(repository, never()).save(any());
    }

    @Test
    void aConfirmationRestoredFromAnOlderVersionIsNotANewConfirmation() {
        // v1 当年确认 160→170 发布(章=0)。之后又发布了 v2; 工序页被改成 150; 恢复 v1 为草稿再发布:
        // 画布 170 与上一版(v2 的 165)不同, 快照 170 与画布一致, 但章(0)≠上一版已发布版本(2) → 不得静默改成 170
        Map<String, Object> processNow = manualOil();
        processNow.put("max", 150);
        processNow.put("min", 140);
        when(repository.findByFactoryIdAndId("F006", "WP-CHAO"))
                .thenReturn(Optional.of(process(new ArrayList<>(List.of(processNow)))));
        Map<String, Object> restored = oilTemp();
        restored.put("max", 170);
        confirm(restored); // confirmedBase=0, 快照 = 170

        Map<String, Object> v2Param = oilTemp();
        v2Param.put("max", 165);
        ProductProcessWorkflowDTO previous = definition(List.of(v2Param));
        previous.setVersion(2);

        BusinessException e = assertThrows(BusinessException.class,
                () -> materializer.materialize("F006", "PT-1", definition(List.of(restored)), previous, 7L));
        assertEquals(WorkflowProcessParamMaterializer.OVERWRITE_UNCONFIRMED, e.getErrorCode());
        verify(repository, never()).save(any());

        // 阳性对照: 章与上一版版本一致(同一次发布周期内勾的)才生效
        restored.put("confirmedBase", 2);
        assertEquals(1, materializer.materialize("F006", "PT-1", definition(List.of(restored)), previous, 7L));
    }
}
