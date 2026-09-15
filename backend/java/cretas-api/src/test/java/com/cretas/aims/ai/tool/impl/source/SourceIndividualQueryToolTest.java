package com.cretas.aims.ai.tool.impl.source;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualDetailDTO;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.source.SourceIndividualDetailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SourceIndividualQueryTool} —— 数据形状照演示那 7 头:
 * <pre>
 *   SL-BULL-001 (公) ─┬─ SL-CATTLE-003 (已采样)
 *   SL-COW-002  (母) ─┤  SL-CATTLE-004 (未采样)
 *                      │  SL-CATTLE-005 (DNA 不匹配, 只有父)
 *                      │  SL-CATTLE-006 (只有母)
 *                      └─ SL-CATTLE-007 (已采样, 只有父)
 * </pre>
 *
 * <h3>变异对照 (2026-09-15 动手做过)</h3>
 * 把「编码查不到就报 INDIVIDUAL_NOT_FOUND」改成「查不到就 searchByKeyword 取第一条」⇒
 * {@link #unknownCodeIsReportedNotFuzzyMatched} 红 (status 变了, 且 searchByKeyword 被调了)。
 * 做之前先打印过 data.status 确认变异到达。
 */
@ExtendWith(MockitoExtension.class)
class SourceIndividualQueryToolTest {

    private static final String FACTORY = "F-SI-TOOL";

    @InjectMocks
    private SourceIndividualQueryTool tool;

    @Mock
    private SourceIndividualRepository repository;

    @Mock
    private SourceIndividualDetailService detailService;

    // ── 元数据 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("metadata — READ + LOW + tool_name=source_individual_query, 无单个必填")
    void metadata() {
        assertEquals("source_individual_query", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.getParametersSchema().get("required");
        assertTrue(required.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.getParametersSchema().get("properties");
        assertTrue(props.keySet().containsAll(List.of("individual_code", "tag_number", "dna_status", "ask")), props.keySet().toString());
    }

    // ── ① 编码 → 后代 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("① individual_code + ask=offspring → 4 头后代摘要, 每头带 DNA 状态")
    @SuppressWarnings("unchecked")
    void offspringByCode() throws Exception {
        SourceIndividual bull = entity("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-BULL-001")).thenReturn(Optional.of(bull));
        when(detailService.getDetail(FACTORY, "bull-id")).thenReturn(bullDetail());

        Map<String, Object> result = invoke(Map.of("individual_code", "SL-BULL-001", "ask", "offspring"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String summary = (String) data.get("summary");

        assertEquals("offspring", data.get("ask"));
        assertEquals(4, ((List<?>) data.get("offspring")).size());
        assertEquals(summary, result.get("message"));
        assertTrue(summary.startsWith("SL-BULL-001 有 4 头后代: "), summary);
        assertTrue(summary.contains("SL-CATTLE-003(DNA 已采样)"), summary);
        assertTrue(summary.contains("SL-CATTLE-004(DNA 未采样)"), summary);
        assertTrue(summary.contains("SL-CATTLE-005(DNA 不匹配)"), summary);
        assertTrue(summary.contains("SL-CATTLE-007(DNA 已采样)"), summary);
        assertFalse(summary.contains("SL-CATTLE-006"), "006 只有母本, 不是公牛的后代: " + summary);
        // 编码给了就不去耳标 / 模糊查
        verify(repository, never()).findByFactoryIdAndTagNumberOrderByCreatedAtDesc(anyString(), anyString());
        verify(repository, never()).searchByKeyword(anyString(), anyString(), any());
    }

    // ── ② 耳标多条 → 取最新并说明 ────────────────────────────────────────────

    @Test
    @DisplayName("② tag_number 命中 2 份档案 → 取最新的一份, 摘要里说「同耳标有 2 份档案, 取最新」")
    @SuppressWarnings("unchecked")
    void tagWithDuplicatesTakesNewestAndSaysSo() throws Exception {
        SourceIndividual newest = entity("new-id", "SL-CATTLE-005B", "1100000000005", DnaVerificationStatus.MISMATCH);
        SourceIndividual older = entity("old-id", "SL-CATTLE-005", "1100000000005", DnaVerificationStatus.SAMPLED);
        // 仓库按 createdAt 倒序: 最新在前
        when(repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "1100000000005"))
                .thenReturn(List.of(newest, older));
        when(detailService.getDetail(FACTORY, "new-id")).thenReturn(simpleDetail(newest));

        Map<String, Object> result = invoke(Map.of("tag_number", "1100000000005"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String summary = (String) data.get("summary");

        verify(detailService).getDetail(FACTORY, "new-id");
        verify(detailService, never()).getDetail(FACTORY, "old-id");
        assertEquals("profile", data.get("ask"), "ask 缺省 profile");
        assertTrue(summary.contains("同耳标 1100000000005 有 2 份档案, 取最新的一份 (SL-CATTLE-005B)"), summary);
        assertEquals("同耳标 1100000000005 有 2 份档案, 取最新的一份 (SL-CATTLE-005B)", data.get("lookupNote"));
        assertTrue(summary.contains("SL-CATTLE-005B"), summary);
        assertTrue(summary.contains("DNA 不匹配"), summary);
    }

    @Test
    @DisplayName("②b tag_number 只命中 1 份 → 没有「同耳标」那句 (阴性对照)")
    @SuppressWarnings("unchecked")
    void singleTagHasNoDuplicateNote() throws Exception {
        SourceIndividual only = entity("only-id", "SL-CATTLE-003", "1100000000003", DnaVerificationStatus.SAMPLED);
        when(repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "1100000000003"))
                .thenReturn(List.of(only));
        when(detailService.getDetail(FACTORY, "only-id")).thenReturn(simpleDetail(only));

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("tag_number", "1100000000003")).get("data");
        assertFalse(((String) data.get("summary")).contains("同耳标"));
        assertFalse(data.containsKey("lookupNote"));
    }

    // ── ③④ 只给 DNA 状态 → 列表 ─────────────────────────────────────────────

    @Test
    @DisplayName("③ dna_status=MISMATCH → 只列 005, 不去查任何一头的详情")
    @SuppressWarnings("unchecked")
    void listByDnaStatusEnum() throws Exception {
        SourceIndividual c005 = entity("c5", "SL-CATTLE-005", "1100000000005", DnaVerificationStatus.MISMATCH);
        when(repository.findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(
                eq(FACTORY), eq(DnaVerificationStatus.MISMATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c005), PageRequest.of(0, 50), 1));

        Map<String, Object> result = invoke(Map.of("dna_status", "MISMATCH"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String summary = (String) data.get("summary");

        assertEquals("list", data.get("ask"));
        assertEquals("MISMATCH", data.get("dnaStatus"));
        assertEquals(1, data.get("listedCount"));
        assertEquals(1L, data.get("totalCount"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertEquals(1, items.size());
        assertEquals("SL-CATTLE-005", items.get(0).get("individualCode"));
        assertTrue(summary.contains("DNA 不匹配 的个体共 1 头: SL-CATTLE-005(耳标 1100000000005)"), summary);
        assertFalse(summary.contains("还有"), summary);
        verifyNoInteractions(detailService);
    }

    @Test
    @DisplayName("④ 中文「不匹配」→ 映射成 MISMATCH 去查 (⛔ 不是 MATCHED)")
    void chineseDnaStatusIsMapped() throws Exception {
        when(repository.findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(
                eq(FACTORY), any(DnaVerificationStatus.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        invoke(Map.of("dna_status", "不匹配"));

        ArgumentCaptor<DnaVerificationStatus> captor = ArgumentCaptor.forClass(DnaVerificationStatus.class);
        verify(repository).findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(eq(FACTORY), captor.capture(), any(Pageable.class));
        assertEquals(DnaVerificationStatus.MISMATCH, captor.getValue());
    }

    @Test
    @DisplayName("④b parseDnaStatus: 中文 / 枚举名 / 带 DNA 前缀 各自落到对的枚举; 「不匹配」不能被「匹配」吃掉")
    void parseDnaStatusTable() {
        assertEquals(DnaVerificationStatus.MISMATCH, SourceIndividualQueryTool.parseDnaStatus("不匹配"));
        assertEquals(DnaVerificationStatus.MISMATCH, SourceIndividualQueryTool.parseDnaStatus("DNA 不匹配"));
        assertEquals(DnaVerificationStatus.MISMATCH, SourceIndividualQueryTool.parseDnaStatus("比对不匹配"));
        assertEquals(DnaVerificationStatus.MISMATCH, SourceIndividualQueryTool.parseDnaStatus("mismatch"));
        assertEquals(DnaVerificationStatus.MATCHED, SourceIndividualQueryTool.parseDnaStatus("已比中"));
        assertEquals(DnaVerificationStatus.MATCHED, SourceIndividualQueryTool.parseDnaStatus("匹配"));
        assertEquals(DnaVerificationStatus.SAMPLED, SourceIndividualQueryTool.parseDnaStatus("已采样"));
        assertEquals(DnaVerificationStatus.UNSAMPLED, SourceIndividualQueryTool.parseDnaStatus("未采样"));
        assertEquals(DnaVerificationStatus.UNSAMPLED, SourceIndividualQueryTool.parseDnaStatus(" UNSAMPLED "));
        assertNull(SourceIndividualQueryTool.parseDnaStatus("随便"));
        assertNull(SourceIndividualQueryTool.parseDnaStatus(null));
    }

    @Test
    @DisplayName("④c 认不出的 dna_status → VALIDATION_ERROR, 不查库")
    @SuppressWarnings("unchecked")
    void unknownDnaStatusIsValidationError() throws Exception {
        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("dna_status", "随便")).get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("dna_status", data.get("field"));
        verifyNoInteractions(repository, detailService);
    }

    @Test
    @DisplayName("③b 超过 50 头 → 只列 50, 摘要说「还有 N 头未列出」")
    @SuppressWarnings("unchecked")
    void listBeyondLimitSaysHowManyMore() throws Exception {
        List<SourceIndividual> fifty = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            fifty.add(entity("u" + i, String.format("SL-U-%03d", i), null, DnaVerificationStatus.UNSAMPLED));
        }
        when(repository.findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(
                eq(FACTORY), eq(DnaVerificationStatus.UNSAMPLED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(fifty, PageRequest.of(0, 50), 57));

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("dna_status", "未采样")).get("data");
        String summary = (String) data.get("summary");

        assertEquals(50, data.get("listedCount"));
        assertEquals(57L, data.get("totalCount"));
        assertTrue(summary.contains("DNA 未采样 的个体共 57 头"), summary);
        assertTrue(summary.contains("还有 7 头未列出"), summary);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByFactoryIdAndDnaVerificationStatusOrderByIndividualCodeAsc(eq(FACTORY), eq(DnaVerificationStatus.UNSAMPLED), page.capture());
        assertEquals(50, page.getValue().getPageSize());
    }

    // ── ⑤ 空参 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⑤ 什么都没给 → 真入口 execute() 返回 NEED_MORE_INFO, 追问要编码或耳标 (不要 ID)")
    void nothingGivenIsClarificationOnRealEntry() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ReflectionTestUtils.setField(tool, "objectMapper", om);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY);
        ctx.put("userId", 7L);

        String json = tool.execute(ToolCall.of("c1", "source_individual_query", "{\"ask\":\"offspring\"}"), ctx);
        JsonNode root = om.readTree(json);

        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("needMoreInfo").asBoolean(), json);
        assertEquals("NEED_MORE_INFO", root.get("status").asText());
        String question = root.get("clarificationQuestions").get(0).asText();
        assertTrue(question.contains("档案编码"), question);
        assertTrue(question.contains("耳标"), question);
        assertFalse(question.contains("ID"), "追问不许管操作员要 ID: " + question);
        verifyNoInteractions(repository, detailService);

        // doExecute 直调 (绕过基类校验) 也不往下走
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("ask", "profile")).get("data");
        assertEquals("NEED_MORE_INFO", data.get("status"));
        verifyNoInteractions(repository, detailService);
    }

    // ── ⑥ 摘要不含基因库 ID / DNA 样本号 ─────────────────────────────────────

    @Test
    @DisplayName("⑥ profile: 结构化段有 externalGeneBankId / dnaSampleCode (阳性), 摘要里没有 (阴性)")
    @SuppressWarnings("unchecked")
    void summaryOmitsGeneBankAndSampleCodes() throws Exception {
        SourceIndividual bull = entity("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-BULL-001")).thenReturn(Optional.of(bull));
        SourceIndividualDetailDTO detail = bullDetail();
        detail.getIndividual().setExternalGeneBankId("GB-SECRET-77");
        detail.getIndividual().setDnaSampleCode("DNA-SAMPLE-99");
        detail.getIndividual().setBirthDate(LocalDate.of(2023, 3, 1));
        detail.getIndividual().setOriginFarm("山东胜龙自有育肥场");
        when(detailService.getDetail(FACTORY, "bull-id")).thenReturn(detail);

        Map<String, Object> result = invoke(Map.of("individual_code", "SL-BULL-001"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> individual = (Map<String, Object>) data.get("individual");
        String summary = (String) data.get("summary");

        // 阳性: 结构化段确实带着这两个值 —— 否则下面的「不含」是恒真式
        assertEquals("GB-SECRET-77", individual.get("externalGeneBankId"));
        assertEquals("DNA-SAMPLE-99", individual.get("dnaSampleCode"));
        // 阴性: 摘要一个字都不提
        assertFalse(summary.contains("GB-SECRET-77"), summary);
        assertFalse(summary.contains("DNA-SAMPLE-99"), summary);
        // 摘要该有的都有
        assertTrue(summary.contains("SL-BULL-001"), summary);
        assertTrue(summary.contains("耳标 1100000000001"), summary);
        assertTrue(summary.contains("出生 2023-03-01"), summary);
        assertTrue(summary.contains("来自 山东胜龙自有育肥场"), summary);
        assertTrue(summary.contains("DNA 已比中"), summary);
        assertTrue(summary.contains("父本 未登记 / 母本 未登记"), summary);
        assertTrue(summary.contains("后代 4 头"), summary);
    }

    // ── ⑦ 找不到就说找不到 ─────────────────────────────────────────────────

    @Test
    @DisplayName("⑦ 编码查不到 → 「没有编码为 X 的个体」, ⛔ 不模糊匹配成别的牛")
    @SuppressWarnings("unchecked")
    void unknownCodeIsReportedNotFuzzyMatched() throws Exception {
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-BULL-00")).thenReturn(Optional.empty());
        // lenient: 正确实现不会碰模糊查询; 留着是为了让「查不到就 searchByKeyword 兜底」的变异体真能拿到
        // 另一头牛 (SL-BULL-001) 并往下走, 于是红在 status / never() 断言上, 而不是红在 mock 返回 null 的 NPE 上。
        SourceIndividual anotherCow = entity("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED);
        lenient().when(repository.searchByKeyword(eq(FACTORY), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(anotherCow), PageRequest.of(0, 1), 1));
        lenient().when(detailService.getDetail(FACTORY, "bull-id")).thenReturn(bullDetail());

        Map<String, Object> result = invoke(Map.of("individual_code", "SL-BULL-00", "ask", "offspring"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("INDIVIDUAL_NOT_FOUND", data.get("status"));
        assertEquals("SL-BULL-00", data.get("value"));
        assertEquals("没有编码为 SL-BULL-00 的个体", result.get("message"));
        verify(repository, never()).searchByKeyword(anyString(), anyString(), any());
        verifyNoInteractions(detailService);
    }

    @Test
    @DisplayName("⑦b 耳标查不到 → 「没有耳标为 X 的个体」")
    @SuppressWarnings("unchecked")
    void unknownTagIsReported() throws Exception {
        when(repository.findByFactoryIdAndTagNumberOrderByCreatedAtDesc(FACTORY, "999")).thenReturn(List.of());

        Map<String, Object> result = invoke(Map.of("tag_number", "999"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("INDIVIDUAL_NOT_FOUND", data.get("status"));
        assertEquals("没有耳标为 999 的个体", result.get("message"));
        verifyNoInteractions(detailService);
    }

    // ── ⑧ 去向 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⑧ ask=downstream → 原料批次 → 生产批次 → 成品 → 出货 一条链")
    @SuppressWarnings("unchecked")
    void downstreamChain() throws Exception {
        SourceIndividual c005 = entity("c5", "SL-CATTLE-005", "1100000000005", DnaVerificationStatus.MISMATCH);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-CATTLE-005")).thenReturn(Optional.of(c005));
        SourceIndividualDetailDTO detail = simpleDetail(c005);
        detail.setBatches(List.of(
                batch("MB-NEW", "80", LocalDate.of(2026, 9, 10)),
                batch("MB-OLD", "120", LocalDate.of(2026, 9, 1))));
        detail.setDownstream(SourceIndividualDetailDTO.Downstream.builder()
                .productionBatches(List.of(node("PRODUCTION_BATCH", "8001", "PB-8001", "酱牛肉")))
                .finishedBatches(List.of(node("FINISHED_BATCH", "fg-1", "FG-0001", "酱牛肉")))
                .shipments(List.of(node("SHIPMENT_RECORD", "sh-1", "DN-2026-001", "盒马")))
                .build());
        when(detailService.getDetail(FACTORY, "c5")).thenReturn(detail);

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("individual_code", "SL-CATTLE-005", "ask", "downstream")).get("data");
        String summary = (String) data.get("summary");

        assertEquals("SL-CATTLE-005 的去向: 原料批次 MB-NEW(80kg)、MB-OLD(120kg) → 生产批次 PB-8001(酱牛肉) → 成品 FG-0001(酱牛肉) → 出货 DN-2026-001(盒马)", summary);
        assertEquals(2, ((List<?>) data.get("batches")).size());
        assertNotNull(data.get("downstream"));
    }

    @Test
    @DisplayName("⑧b 没挂批次 → 「还没有入库成原料批次, 没有去向」")
    @SuppressWarnings("unchecked")
    void downstreamWithoutBatches() throws Exception {
        SourceIndividual c006 = entity("c6", "SL-CATTLE-006", "1100000000006", DnaVerificationStatus.UNSAMPLED);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-CATTLE-006")).thenReturn(Optional.of(c006));
        when(detailService.getDetail(FACTORY, "c6")).thenReturn(simpleDetail(c006));

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("individual_code", "SL-CATTLE-006", "ask", "DOWNSTREAM")).get("data");
        assertEquals("SL-CATTLE-006 还没有入库成原料批次, 没有去向", data.get("summary"));
    }

    // ── 其它 ask ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ask=pedigree → 父本/母本/后代/同父兄弟 一句话")
    @SuppressWarnings("unchecked")
    void pedigree() throws Exception {
        SourceIndividual c005 = entity("c5", "SL-CATTLE-005", "1100000000005", DnaVerificationStatus.MISMATCH);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-CATTLE-005")).thenReturn(Optional.of(c005));
        SourceIndividualDetailDTO detail = simpleDetail(c005);
        detail.getIndividual().setSireIndividualId("bull-id");
        detail.setSire(summary("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED));
        detail.setSiblingsBySire(List.of(
                summary("c3", "SL-CATTLE-003", "1100000000003", DnaVerificationStatus.SAMPLED),
                summary("c4", "SL-CATTLE-004", "1100000000004", DnaVerificationStatus.UNSAMPLED),
                summary("c7", "SL-CATTLE-007", "1100000000007", DnaVerificationStatus.SAMPLED)));
        when(detailService.getDetail(FACTORY, "c5")).thenReturn(detail);

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("individual_code", "SL-CATTLE-005", "ask", "pedigree")).get("data");
        String summary = (String) data.get("summary");

        assertTrue(summary.startsWith("SL-CATTLE-005: 父本 SL-BULL-001(耳标 1100000000001), 母本 未登记; 没有登记后代; 同父兄弟 3 头: "), summary);
        assertTrue(summary.contains("SL-CATTLE-003(DNA 已采样)、SL-CATTLE-004(DNA 未采样)、SL-CATTLE-007(DNA 已采样)"), summary);
        assertNotNull(data.get("sire"));
        assertNull(data.get("dam"));
    }

    @Test
    @DisplayName("ask=siblings 而父本未登记 → 如实说算不出, 不给空列表冒充「没有兄弟」")
    @SuppressWarnings("unchecked")
    void siblingsWithoutSire() throws Exception {
        SourceIndividual bull = entity("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED);
        when(repository.findByFactoryIdAndIndividualCode(FACTORY, "SL-BULL-001")).thenReturn(Optional.of(bull));
        when(detailService.getDetail(FACTORY, "bull-id")).thenReturn(bullDetail());

        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("individual_code", "SL-BULL-001", "ask", "siblings")).get("data");
        assertEquals("SL-BULL-001 未登记父本, 算不出同父兄弟", data.get("summary"));
    }

    @Test
    @DisplayName("ask 不合法 → VALIDATION_ERROR, 不查库")
    @SuppressWarnings("unchecked")
    void invalidAsk() throws Exception {
        Map<String, Object> data = (Map<String, Object>) invoke(Map.of("individual_code", "SL-BULL-001", "ask", "everything")).get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("ask", data.get("field"));
        verifyNoInteractions(repository, detailService);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static SourceIndividual entity(String id, String code, String tag, DnaVerificationStatus dna) {
        return SourceIndividual.builder()
                .id(id).factoryId(FACTORY).individualCode(code).tagNumber(tag)
                .species("CATTLE").breed("西门塔尔").sex("公").originFarm("胜龙一号牧场")
                .dnaVerificationStatus(dna)
                .build();
    }

    private static SourceIndividualDTO dto(SourceIndividual e) {
        return SourceIndividualDTO.builder()
                .id(e.getId()).factoryId(e.getFactoryId())
                .individualCode(e.getIndividualCode()).tagNumber(e.getTagNumber())
                .species(e.getSpecies()).breed(e.getBreed()).sex(e.getSex())
                .originFarm(e.getOriginFarm())
                .dnaVerificationStatus(e.getDnaVerificationStatus())
                .sireIndividualId(e.getSireIndividualId()).damIndividualId(e.getDamIndividualId())
                .build();
    }

    private static SourceIndividualDetailDTO.Summary summary(String id, String code, String tag, DnaVerificationStatus dna) {
        return SourceIndividualDetailDTO.Summary.builder()
                .id(id).individualCode(code).tagNumber(tag).species("CATTLE").breed("西门塔尔").sex("公")
                .dnaVerificationStatus(dna)
                .build();
    }

    /** 种公牛的详情: 4 头后代 (003 已采样 / 004 未采样 / 005 不匹配 / 007 已采样), 无父母, 无批次。 */
    private static SourceIndividualDetailDTO bullDetail() {
        SourceIndividual bull = entity("bull-id", "SL-BULL-001", "1100000000001", DnaVerificationStatus.MATCHED);
        return SourceIndividualDetailDTO.builder()
                .individual(dto(bull))
                .sire(null).dam(null)
                .offspring(List.of(
                        summary("c3", "SL-CATTLE-003", "1100000000003", DnaVerificationStatus.SAMPLED),
                        summary("c4", "SL-CATTLE-004", "1100000000004", DnaVerificationStatus.UNSAMPLED),
                        summary("c5", "SL-CATTLE-005", "1100000000005", DnaVerificationStatus.MISMATCH),
                        summary("c7", "SL-CATTLE-007", "1100000000007", DnaVerificationStatus.SAMPLED)))
                .siblingsBySire(List.of())
                .batches(List.of())
                .downstream(SourceIndividualDetailDTO.Downstream.builder()
                        .productionBatches(List.of()).finishedBatches(List.of()).shipments(List.of()).build())
                .build();
    }

    private static SourceIndividualDetailDTO simpleDetail(SourceIndividual e) {
        return SourceIndividualDetailDTO.builder()
                .individual(dto(e))
                .offspring(List.of())
                .siblingsBySire(List.of())
                .batches(List.of())
                .downstream(SourceIndividualDetailDTO.Downstream.builder()
                        .productionBatches(List.of()).finishedBatches(List.of()).shipments(List.of()).build())
                .build();
    }

    private static SourceIndividualDetailDTO.BatchSummary batch(String number, String qty, LocalDate date) {
        return SourceIndividualDetailDTO.BatchSummary.builder()
                .id("id-" + number).batchNumber(number).materialName("牛肉")
                .quantity(new BigDecimal(qty)).unit("kg").receiptDate(date)
                .build();
    }

    private static TraceabilityDTO.LineageNode node(String type, String id, String batchNumber, String name) {
        return TraceabilityDTO.LineageNode.builder()
                .nodeType(type).nodeId(id).batchNumber(batchNumber).name(name).resolved(true).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        Method m = SourceIndividualQueryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY);
        ctx.put("userId", 7L);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY, params, ctx);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }
}
