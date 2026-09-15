package com.cretas.aims.ai.tool.impl.lineage;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LineageQueryTool} (Sprint 11 D4; 2026-09-15 按批次号 + 来自哪几头牛).
 *
 * <h3>变异对照 (2026-09-15 动手做过)</h3>
 * 把「批次号查不到就报 BATCH_NOT_FOUND」改成「查不到就拿批次号当主键继续查」⇒
 * {@link #unknownBatchNumberIsReportedNotGuessed} 红 (status 变成 NO_LINEAGE_FOUND, 且 assembler 被调了)。
 * 做之前先打印过 data.status 确认变异到达。
 */
@ExtendWith(MockitoExtension.class)
class LineageQueryToolTest {

    private static final String FACTORY_ID = "F999_MOCK";
    private static final String BATCH_TYPE = "FINISHED_BATCH";
    private static final String BATCH_ID = "RES_3101_009";

    private static final String DEMO_BATCH_NUMBER = "SL-DEMO-BATCH-V1";
    private static final long DEMO_BATCH_PK = 8001L;

    @InjectMocks
    private LineageQueryTool tool;

    @Mock
    private LineageGraphAssembler lineageGraphAssembler;

    @Mock
    private BatchLineageEdgeRepository edgeRepository;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Mock
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Mock
    private SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    // ============================================================
    // 既有用例 (字段名 / 状态码一个不动)
    // ============================================================

    @Test
    @DisplayName("UT-LQT-01: metadata — READ + LOW + tool_name=lineage_query; batch_number 进 schema, 无单个必填")
    void metadata() {
        assertEquals("lineage_query", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        Map<String, Object> schema = tool.getParametersSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.isEmpty(), "batch_number / batch_id 二选一, 不该有单个必填项: " + required);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("batch_number"), "schema 必须暴露 batch_number");
        @SuppressWarnings("unchecked")
        Map<String, Object> batchType = (Map<String, Object>) props.get("batch_type");
        assertEquals("PRODUCTION_BATCH", batchType.get("default"));
        assertTrue(tool.getDescription().contains("批次号"), "描述要告诉 LLM 可以直接给批次号");
    }

    @Test
    @DisplayName("UT-LQT-02: happy path (老用法 batch_type+batch_id) — 1 上游 + 2 下游 + 3 边, 既有字段名不变")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        TraceabilityDTO.LineageSection section = section(
                List.of(group(1, node("MATERIAL_BATCH", "MB-rice-001", "MB-rice-001", "大米", null))),
                List.of(group(1, node("SHIPMENT_RECORD", "SHP-001", "DN-001", "客户A", null)),
                        group(2, node("SHIPMENT_RECORD", "SHP-002", "DN-002", "客户B", null))),
                0);
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq(BATCH_TYPE), eq(BATCH_ID), anyInt(), eq(true), eq(false)))
                .thenReturn(section);

        BatchLineageEdge upstream = edge("RAW_TO_PRODUCTION", "MATERIAL_BATCH", "MB-rice-001",
                BATCH_TYPE, BATCH_ID, new BigDecimal("50.0"), "kg");
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(FACTORY_ID, BATCH_ID, BATCH_TYPE))
                .thenReturn(List.of(upstream));
        BatchLineageEdge downstream = edge("FINISHED_TO_SHIPMENT", BATCH_TYPE, BATCH_ID,
                "SHIPMENT_RECORD", "SHP-001", new BigDecimal("30.0"), "kg");
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(FACTORY_ID, BATCH_ID, BATCH_TYPE))
                .thenReturn(List.of(downstream));

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("ancestorCount"));
        assertEquals(2, data.get("descendantCount"));
        assertEquals(2, data.get("directEdgeCount"));
        assertEquals(BATCH_ID, data.get("batchId"));
        assertEquals("BOTH", data.get("direction"));

        List<Map<String, Object>> ancestors = (List<Map<String, Object>>) data.get("ancestors");
        assertEquals("MATERIAL_BATCH", ancestors.get(0).get("nodeType"));
        assertEquals("MB-rice-001", ancestors.get(0).get("nodeId"));
        assertEquals(1, ancestors.get(0).get("depth"));

        List<Map<String, Object>> edges = (List<Map<String, Object>>) data.get("edges");
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(e -> "UPSTREAM".equals(e.get("relation"))));
        assertTrue(edges.stream().anyMatch(e -> "DOWNSTREAM".equals(e.get("relation"))));

        // 老用法只给主键 ⇒ 不去任何批次仓库反查批次号
        verifyNoInteractions(productionBatchRepository, materialBatchRepository,
                finishedGoodsBatchRepository, salesDeliveryRecordRepository);
    }

    @Test
    @DisplayName("UT-LQT-03: direction=ANCESTORS — 仅返上游, 且不让 assembler 算下游")
    @SuppressWarnings("unchecked")
    void ancestorsOnly() throws Exception {
        TraceabilityDTO.LineageSection section = section(
                List.of(group(1, node("MATERIAL_BATCH", "MB-rice-001", "MB-rice-001", "大米", null))),
                null, 0);
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq(BATCH_TYPE), eq(BATCH_ID), anyInt(), eq(false), eq(false)))
                .thenReturn(section);
        lenient().when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "direction", "ANCESTORS"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("ancestorCount"));
        assertEquals(0, data.get("descendantCount"),
                "direction=ANCESTORS 应过滤掉 descendants");
        verify(edgeRepository, never()).findByFactoryIdAndSourceIdAndSourceType(any(), any(), any());
    }

    @Test
    @DisplayName("UT-LQT-04: invalid batch_type → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void invalidBatchType() throws Exception {
        Map<String, Object> result = invoke(Map.of(
                "batch_type", "WHATEVER",
                "batch_id", "X"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("batch_type", data.get("field"));
    }

    @Test
    @DisplayName("UT-LQT-05: invalid direction → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void invalidDirection() throws Exception {
        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "direction", "SIDEWAYS"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("direction", data.get("field"));
    }

    @Test
    @DisplayName("UT-LQT-06: no lineage 数据 → status=NO_LINEAGE_FOUND")
    @SuppressWarnings("unchecked")
    void noLineageFound() throws Exception {
        when(lineageGraphAssembler.assemble(any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(section(List.of(), List.of(), 0));
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", "DOES-NOT-EXIST"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("NO_LINEAGE_FOUND", data.get("status"));
        assertTrue(((String) result.get("message")).contains("未找到"));
    }

    @Test
    @DisplayName("UT-LQT-07: max_depth=15 → 自动 cap 到 10, 传给 assembler 的也是 10")
    @SuppressWarnings("unchecked")
    void maxDepthCap() throws Exception {
        when(lineageGraphAssembler.assemble(any(), any(), any(), eq(10), anyBoolean(), anyBoolean()))
                .thenReturn(section(List.of(), List.of(), 0));
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", BATCH_TYPE,
                "batch_id", BATCH_ID,
                "max_depth", 15));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(10, data.get("maxDepth"), "max_depth=15 应被 cap 到 10");
    }

    // ============================================================
    // 2026-09-15: 按批次号 + 来自哪几头牛
    // ============================================================

    @Test
    @DisplayName("UT-LQT-08: batch_number + 默认类型 → 解析到生产批次主键, summary 列出 6 头牛的耳标")
    @SuppressWarnings("unchecked")
    void batchNumberResolvesToProductionBatchAndSummarizesIndividuals() throws Exception {
        ProductionBatch pb = new ProductionBatch();
        pb.setId(DEMO_BATCH_PK);
        pb.setBatchNumber(DEMO_BATCH_NUMBER);
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, DEMO_BATCH_NUMBER))
                .thenReturn(Optional.of(pb));

        // 6 个原料批次, 各挂一头牛 (演示数据的形状: 耳标 110370281000001..006)
        List<TraceabilityDTO.LineageNode> materials = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            materials.add(node("MATERIAL_BATCH", "mb-" + i, "MB-2026-00" + i, "牛肉",
                    individual("si-" + i, "SL-CATTLE-00" + i, "11037028100000" + i, "西门塔尔杂交",
                            "山东胜龙自有育肥场", "MATCHED")));
        }
        TraceabilityDTO.LineageSection section = section(List.of(group(1, materials)), List.of(), 6);
        when(lineageGraphAssembler.assemble(FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(DEMO_BATCH_PK), 5, true, false))
                .thenReturn(section);
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(FACTORY_ID, String.valueOf(DEMO_BATCH_PK), "PRODUCTION_BATCH"))
                .thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(FACTORY_ID, String.valueOf(DEMO_BATCH_PK), "PRODUCTION_BATCH"))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of("batch_number", DEMO_BATCH_NUMBER));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("PRODUCTION_BATCH", data.get("batchType"), "batch_type 缺省 PRODUCTION_BATCH");
        assertEquals(String.valueOf(DEMO_BATCH_PK), data.get("batchId"), "主键由批次号解析而来");
        assertEquals(DEMO_BATCH_NUMBER, data.get("batchNumber"));
        assertEquals(6, data.get("sourceIndividualCount"));
        assertEquals(6, data.get("ancestorCount"));

        String summary = (String) data.get("summary");
        assertNotNull(summary);
        assertEquals(summary, result.get("message"), "message 与 summary 是同一句");
        assertTrue(summary.contains("生产批次 " + DEMO_BATCH_NUMBER), summary);
        assertTrue(summary.contains("来自 6 头牛"), summary);
        for (int i = 1; i <= 6; i++) {
            assertTrue(summary.contains("耳标 11037028100000" + i), "summary 缺第 " + i + " 头: " + summary);
        }
        assertTrue(summary.contains("西门塔尔杂交 · 山东胜龙自有育肥场 · DNA 已比中"), summary);

        // 结构化侧: 每个祖先节点带 sourceIndividual, 老字段 nodeType/nodeId/depth 仍在
        List<Map<String, Object>> ancestors = (List<Map<String, Object>>) data.get("ancestors");
        assertEquals(6, ancestors.size());
        Map<String, Object> first = ancestors.get(0);
        assertEquals("MATERIAL_BATCH", first.get("nodeType"));
        assertEquals("mb-1", first.get("nodeId"));
        assertEquals(1, first.get("depth"));
        Map<String, Object> si = (Map<String, Object>) first.get("sourceIndividual");
        assertEquals("110370281000001", si.get("tagNumber"));
        assertEquals("MATCHED", si.get("dnaVerificationStatus"));
    }

    @Test
    @DisplayName("UT-LQT-09: 不存在的批次号 → 「没有批次 X」, 且不碰 assembler / 不拿批次号当主键")
    @SuppressWarnings("unchecked")
    void unknownBatchNumberIsReportedNotGuessed() throws Exception {
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "SL-NOPE-999"))
                .thenReturn(Optional.empty());
        // lenient: 正确实现根本不会走到这里; 留着是为了让「拿批次号当主键继续查」的变异体能跑完,
        // 从而红在下面的 status 断言上, 而不是红在一个 mock 返回 null 的 NPE 上 (那证明不了断言在守东西)。
        lenient().when(lineageGraphAssembler.assemble(any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(section(List.of(), List.of(), 0));
        lenient().when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any())).thenReturn(List.of());
        lenient().when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of("batch_number", "SL-NOPE-999"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("BATCH_NOT_FOUND", data.get("status"));
        assertEquals("SL-NOPE-999", data.get("batchNumber"));
        String message = (String) result.get("message");
        assertTrue(message.contains("没有批次 SL-NOPE-999"), message);
        // 阴性: 查不到就停, ⛔ 不拿批次号当主键往下查
        verifyNoInteractions(lineageGraphAssembler, edgeRepository);
    }

    @Test
    @DisplayName("UT-LQT-10: batch_type=MATERIAL_BATCH + batch_number → 到原料批次仓库解析, 不碰生产批次仓库")
    @SuppressWarnings("unchecked")
    void batchNumberHonoursBatchType() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-1");
        mb.setBatchNumber("MB-2026-001");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "MB-2026-001"))
                .thenReturn(Optional.of(mb));
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq("MATERIAL_BATCH"), eq("mb-uuid-1"), anyInt(), eq(true), eq(false)))
                .thenReturn(section(List.of(), List.of(group(1, node("PRODUCTION_BATCH", "8001", "SL-DEMO-BATCH-V1", "酱牛肉", null))), 0));
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_type", "material_batch",
                "batch_number", " MB-2026-001 "));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("mb-uuid-1", data.get("batchId"));
        assertEquals("MB-2026-001", data.get("batchNumber"), "批次号要 trim");
        assertEquals(1, data.get("descendantCount"));
        verifyNoInteractions(productionBatchRepository);
    }

    @Test
    @DisplayName("UT-LQT-11: batch_number 与 batch_id 同时给 → 批次号优先 (主键由它解析)")
    @SuppressWarnings("unchecked")
    void batchNumberWinsOverBatchId() throws Exception {
        ProductionBatch pb = new ProductionBatch();
        pb.setId(4242L);
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, DEMO_BATCH_NUMBER))
                .thenReturn(Optional.of(pb));
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq("PRODUCTION_BATCH"), eq("4242"), anyInt(), eq(true), eq(false)))
                .thenReturn(section(List.of(), List.of(), 0));
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of(
                "batch_number", DEMO_BATCH_NUMBER,
                "batch_id", "should-be-ignored"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("4242", data.get("batchId"));
    }

    @Test
    @DisplayName("UT-LQT-12: 同一头牛分成两个原料批次 → summary 按个体去重, 只念一次")
    @SuppressWarnings("unchecked")
    void summaryDedupesIndividualsAcrossMaterialBatches() throws Exception {
        TraceabilityDTO.SourceIndividualBrief sameCow =
                individual("si-1", "SL-CATTLE-001", "110370281000001", "西门塔尔杂交", "山东胜龙自有育肥场", "SAMPLED");
        TraceabilityDTO.LineageSection section = section(
                List.of(group(1, List.of(
                        node("MATERIAL_BATCH", "mb-a", "MB-A", "前腿", sameCow),
                        node("MATERIAL_BATCH", "mb-b", "MB-B", "后腿", sameCow)))),
                List.of(), 1);   // assembler 已按个体 id 去重: 1 头
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq(BATCH_TYPE), eq(BATCH_ID), anyInt(), eq(true), eq(false)))
                .thenReturn(section);
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of("batch_type", BATCH_TYPE, "batch_id", BATCH_ID));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String summary = (String) data.get("summary");

        assertEquals(2, data.get("ancestorCount"), "两个原料批次节点都在");
        assertEquals(1, data.get("sourceIndividualCount"));
        assertTrue(summary.contains("来自 1 头牛"), summary);
        int occurrences = summary.split("耳标 110370281000001", -1).length - 1;
        assertEquals(1, occurrences, "同一头牛只念一次: " + summary);
        assertTrue(summary.contains("DNA 已采样"), summary);
    }

    @Test
    @DisplayName("UT-LQT-13: 上游有原料批次但没登记个体 → summary 如实说「没登记来自哪头牛」, 不编")
    @SuppressWarnings("unchecked")
    void summarySaysWhenNoIndividualRegistered() throws Exception {
        TraceabilityDTO.LineageSection section = section(
                List.of(group(1, node("MATERIAL_BATCH", "mb-a", "MB-A", "大米", null))),
                List.of(), 0);
        when(lineageGraphAssembler.assemble(eq(FACTORY_ID), eq(BATCH_TYPE), eq(BATCH_ID), anyInt(), eq(true), eq(false)))
                .thenReturn(section);
        when(edgeRepository.findByFactoryIdAndTargetIdAndTargetType(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findByFactoryIdAndSourceIdAndSourceType(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of("batch_type", BATCH_TYPE, "batch_id", BATCH_ID));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String summary = (String) data.get("summary");

        assertEquals(0, data.get("sourceIndividualCount"));
        assertTrue(summary.contains("没登记来自哪头牛"), summary);
        assertFalse(summary.contains("来自 0 头牛"), summary);
    }

    @Test
    @DisplayName("UT-LQT-14: batch_number / batch_id 都没给 → 真入口 execute() 返回 NEED_MORE_INFO, 追问要批次号")
    void missingBothIsClarificationOnRealEntry() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ReflectionTestUtils.setField(tool, "objectMapper", om);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);

        String json = tool.execute(ToolCall.of("c1", "lineage_query", "{\"batch_type\":\"PRODUCTION_BATCH\"}"), ctx);
        JsonNode root = om.readTree(json);

        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("needMoreInfo").asBoolean(), json);
        assertEquals("NEED_MORE_INFO", root.get("status").asText());
        String question = root.get("clarificationQuestions").get(0).asText();
        assertTrue(question.contains("批次号"), question);
        assertFalse(question.contains("ID"), "追问不许管操作员要 ID: " + question);
        verifyNoInteractions(lineageGraphAssembler, productionBatchRepository);

        // doExecute 直调也不会往下走
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("direction", "BOTH")).get("data");
        assertEquals("NEED_MORE_INFO", data.get("status"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static TraceabilityDTO.LineageSection section(List<TraceabilityDTO.LineageDepthGroup> ancestors,
                                                          List<TraceabilityDTO.LineageDepthGroup> descendants,
                                                          int individualCount) {
        int nodes = 0;
        for (TraceabilityDTO.LineageDepthGroup g : ancestors) nodes += g.getNodes().size();
        if (descendants != null) for (TraceabilityDTO.LineageDepthGroup g : descendants) nodes += g.getNodes().size();
        return TraceabilityDTO.LineageSection.builder()
                .ancestors(ancestors)
                .descendants(descendants)
                .nodeCount(nodes)
                .sourceIndividualCount(individualCount)
                .build();
    }

    private static TraceabilityDTO.LineageDepthGroup group(int depth, TraceabilityDTO.LineageNode... nodes) {
        return group(depth, List.of(nodes));
    }

    private static TraceabilityDTO.LineageDepthGroup group(int depth, List<TraceabilityDTO.LineageNode> nodes) {
        return TraceabilityDTO.LineageDepthGroup.builder().depth(depth).nodes(nodes).build();
    }

    private static TraceabilityDTO.LineageNode node(String type, String id, String batchNumber, String name,
                                                    TraceabilityDTO.SourceIndividualBrief si) {
        return TraceabilityDTO.LineageNode.builder()
                .nodeType(type).nodeId(id).batchNumber(batchNumber).name(name).resolved(true)
                .sourceIndividual(si)
                .build();
    }

    private static TraceabilityDTO.SourceIndividualBrief individual(String id, String code, String tag, String breed,
                                                                    String farm, String dna) {
        return TraceabilityDTO.SourceIndividualBrief.builder()
                .id(id).individualCode(code).tagNumber(tag).tagNumberMasked(false)
                .species("CATTLE").breed(breed).originFarm(farm).dnaVerificationStatus(dna)
                .build();
    }

    private BatchLineageEdge edge(String edgeType, String srcType, String srcId,
                                  String tgtType, String tgtId, BigDecimal qty, String unit) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY_ID);
        e.setEdgeType(edgeType);
        e.setSourceType(srcType);
        e.setSourceId(srcId);
        e.setTargetType(tgtType);
        e.setTargetId(tgtId);
        e.setQuantityUsed(qty);
        e.setUnit(unit);
        e.setEventTime(LocalDateTime.now());
        return e;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        Method m = LineageQueryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, ctx);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }
}
