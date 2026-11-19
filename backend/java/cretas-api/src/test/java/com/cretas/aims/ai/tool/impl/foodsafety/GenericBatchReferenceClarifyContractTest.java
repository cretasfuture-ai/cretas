package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.BatchReferenceClarifier;
import com.cretas.aims.ai.tool.impl.shipment.TraceBatchTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.trace.SalesOrderTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LSMDEMO 2026-11-18: 「从成品批次往回追」把字面「成品批次」当批次号去查, 回「暂时没查到」。
 * 走工具真实入口 execute(ToolCall, context): 泛称 → 不查批次、回追问 + 最近批次; 真批次号 → 照常查且原样出现。
 */
@ExtendWith(MockitoExtension.class)
class GenericBatchReferenceClarifyContractTest {

    private static final String FACTORY_ID = "LSMDEMO";
    private static final String REAL_BATCH = "DEMO-20261118-PB-013";

    @InjectMocks
    private BatchFullTraceTool fullTrace;

    @Mock
    private TraceabilityService traceabilityService;
    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;
    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private SalesOrderTraceService salesOrderTraceService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Field f = com.cretas.aims.ai.tool.AbstractTool.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(fullTrace, objectMapper);
    }

    private static ProductionBatch batch(String no, String product, LocalDateTime end) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(no);
        b.setProductName(product);
        b.setEndTime(end);
        return b;
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    private JsonNode run(BatchFullTraceTool tool, String batchArg) throws Exception {
        String args = objectMapper.writeValueAsString(Map.of("batchNumber", batchArg));
        return objectMapper.readTree(tool.execute(ToolCall.of("c1", "batch_full_trace", args), ctx()));
    }

    @Test
    void genericWordAsksForBatchNumberAndListsRecentBatchesWithoutQuerying() throws Exception {
        when(productionBatchRepository.findByFactoryId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        batch(REAL_BATCH, "卤猪蹄", LocalDateTime.of(2026, 11, 18, 10, 0)),
                        batch("DEMO-20261117-PB-012", "酱牛肉", LocalDateTime.of(2026, 11, 17, 10, 0)))));

        for (String generic : List.of("成品批次", "这个批次", "某批次")) {
            JsonNode out = run(fullTrace, generic);
            String text = out.toString();
            assertThat(text).as(generic).contains("请告诉我具体的批次号")
                    .contains(REAL_BATCH).contains("卤猪蹄").contains("2026-11-18")
                    .contains("DEMO-20261117-PB-012").doesNotContain("暂时没查到");
        }
        // 修前: 字面「成品批次」被拿去查
        verify(traceabilityService, never()).getFullTrace(anyString(), anyString());
        verify(shipmentRecordRepository, never()).findByFactoryIdAndBatchNumber(anyString(), anyString());
    }

    private static final String LONG_BATCH = "PB-PLAN-1794990356144-60B63614-56643";

    @Test
    void generatedLongBatchNumberIsShownAsShortLabelButCandidateKeepsFullNumber() throws Exception {
        when(productionBatchRepository.findByFactoryId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        batch(LONG_BATCH, "藤椒掌中宝200g", LocalDateTime.of(2026, 11, 18, 10, 0)),
                        batch(REAL_BATCH, "卤猪蹄", LocalDateTime.of(2026, 11, 18, 9, 0)))));

        JsonNode out = run(fullTrace, "成品批次");
        String message = out.path("data").path("message").asText();
        if (message.isEmpty()) message = out.toString();
        assertThat(message).doesNotContain("1794990356144");
        assertThat(out.path("data").path("data").path("candidates").get(0).path("batchNumber").asText())
                .isEqualTo(LONG_BATCH);
        assertThat(message).contains("PB-PLAN-…56643").contains("藤椒掌中宝200g").contains("2026-11-18")
                .contains(REAL_BATCH);
        // 回传给后续工具的参数仍是完整号
        assertThat(out.toString()).contains("\"label\":\"PB-PLAN-…56643\"");
        assertThat(BatchReferenceClarifier.displayLabel(REAL_BATCH)).isEqualTo(REAL_BATCH);
    }

    @Test
    void pickingByShortLabelStillTracesByFullBatchNumber() throws Exception {
        when(productionBatchRepository.findByFactoryId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(batch(LONG_BATCH, "藤椒掌中宝200g", LocalDateTime.of(2026, 11, 18, 10, 0)))));
        TraceabilityDTO.FullTraceResponse trace = new TraceabilityDTO.FullTraceResponse();
        trace.setMaterials(List.of());
        when(traceabilityService.getFullTrace(FACTORY_ID, LONG_BATCH)).thenReturn(trace);
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, LONG_BATCH)).thenReturn(List.of());

        run(fullTrace, "PB-PLAN-…56643");
        run(fullTrace, "…56643");

        verify(traceabilityService, org.mockito.Mockito.times(2)).getFullTrace(FACTORY_ID, LONG_BATCH);
    }

    @Test
    void realBatchNumberStillQueriesAndShowsFullNumberVerbatim() throws Exception {
        TraceabilityDTO.MaterialInfo m = new TraceabilityDTO.MaterialInfo();
        m.setBatchNumber("MB-9");
        m.setMaterialName("牛腱");
        m.setSupplierName("甲供应商");
        TraceabilityDTO.FullTraceResponse trace = new TraceabilityDTO.FullTraceResponse();
        trace.setMaterials(List.of(m));
        when(traceabilityService.getFullTrace(FACTORY_ID, REAL_BATCH)).thenReturn(trace);
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, REAL_BATCH)).thenReturn(List.of());

        String text = run(fullTrace, REAL_BATCH).toString();

        verify(traceabilityService).getFullTrace(FACTORY_ID, REAL_BATCH);
        verify(productionBatchRepository, never()).findByFactoryId(anyString(), any(Pageable.class));
        assertThat(text).contains("批次 " + REAL_BATCH + " 用了 1 个原料批次");
    }

    @Test
    void traceBatchToolAlsoClarifiesInsteadOfQueryingGenericWord() throws Exception {
        TraceBatchTool tool = new TraceBatchTool();
        inject(tool, "traceabilityService", traceabilityService);
        inject(tool, "salesOrderTraceService", salesOrderTraceService);
        inject(tool, "productionBatchRepository", productionBatchRepository);
        inject(tool, "objectMapper", objectMapper);
        when(productionBatchRepository.findByFactoryId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(batch(REAL_BATCH, "卤猪蹄", LocalDateTime.of(2026, 11, 18, 10, 0)))));

        String args = objectMapper.writeValueAsString(Map.of("batchNumber", "成品批次"));
        String text = tool.execute(ToolCall.of("c2", "trace_batch", args), ctx());

        assertThat(text).contains("请告诉我具体的批次号").contains(REAL_BATCH);
        verify(traceabilityService, never()).getBatchTrace(anyString(), anyString());
    }

    @Test
    void genericDetectionIsOnlyForStringsWithoutAsciiLettersOrDigits() {
        assertThat(BatchReferenceClarifier.isGenericReference("成品批次")).isTrue();
        assertThat(BatchReferenceClarifier.isGenericReference(" ")).isTrue();
        assertThat(BatchReferenceClarifier.isGenericReference(REAL_BATCH)).isFalse();
        assertThat(BatchReferenceClarifier.isGenericReference("SL-DEMO-BATCH-V1")).isFalse();
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalArgumentException(name);
    }
}
