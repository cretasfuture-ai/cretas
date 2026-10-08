package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityCheckQueryPresentationContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    final QualityCheckQueryTool tool=new QualityCheckQueryTool();
    final QualityInspectionService service=mock(QualityInspectionService.class);
    final ProductionBatchRepository batches=mock(ProductionBatchRepository.class);
    final MaterialBatchRepository materials=mock(MaterialBatchRepository.class);

    QualityCheckQueryPresentationContractTest() {
        ReflectionTestUtils.setField(tool,"objectMapper",mapper);
        ReflectionTestUtils.setField(tool,"qualityInspectionService",service);
        // Set repositories only when available so the same test measures the original main.
        if(Arrays.stream(QualityCheckQueryTool.class.getDeclaredFields()).anyMatch(f->f.getName().equals("productionBatchRepository"))) {
            ReflectionTestUtils.setField(tool,"productionBatchRepository",batches);
            ReflectionTestUtils.setField(tool,"materialBatchRepository",materials);
        }
    }

    QualityInspection record(String result) {
        var i=new QualityInspection(); i.setId("internal-inspection-id"); i.setFactoryId("F006");
        i.setProductionBatchId(10805L); i.setResult(result); i.setNotes("[质检类型: 感官检验]");
        i.setPassRate(new BigDecimal("95.00")); return i;
    }

    Map<String,Object> run(List<QualityInspection> records,Map<String,Object> params) throws Exception {
        var page=new PageResponse<QualityInspection>(); page.setContent(records); page.setTotalElements((long)records.size()); page.setTotalPages(1);
        when(service.getInspections(eq("F006"),any(),any(PageRequest.class))).thenReturn(page);
        var call=new ToolCall(); var function=new ToolCall.FunctionCall();
        function.setName("quality_check_query"); function.setArguments(mapper.writeValueAsString(params)); call.setFunction(function);
        var response=mapper.readTree(tool.execute(call,Map.of("factoryId","F006","userId",42L)));
        assertTrue(response.path("success").asBoolean(),response.toString());
        verify(service).getInspections(eq("F006"),any(),any(PageRequest.class));
        return mapper.convertValue(response.path("data"),Map.class);
    }

    @Test void actualToolEntryAndFormatterShowBusinessBatchAndChineseResults() throws Exception {
        var b=new ProductionBatch(); b.setBatchNumber("PB-20261008-QC");
        when(batches.findByIdAndFactoryId(10805L,"F006")).thenReturn(Optional.of(b));
        var data=run(List.of(record("PASS"),record("FAIL"),record("CONDITIONAL")),Map.of());
        String message=data.get("message").toString();
        assertTrue(message.contains("PB-20261008-QC"),message);
        assertTrue(message.contains("类型: 感官检验"),message);
        assertTrue(message.contains("结果: 合格")); assertTrue(message.contains("状态: 不合格"));
        assertTrue(message.contains("状态: 条件通过")); assertTrue(message.contains("95.00%"));
        assertFalse(message.contains("10805")); assertFalse(message.contains("internal-inspection-id"));
        assertFalse(message.contains("PASS")); assertFalse(message.contains("FAIL")); assertFalse(message.contains(": -"));
        var response=new IntentExecuteResponse(); response.setIntentCode("QUALITY_CHECK_QUERY"); response.setIntentCategory("QUALITY");
        response.setResultData(data); response.setMessage(message);
        assertEquals(message,new ResultFormatterServiceImpl().format(response));
    }

    @Test void materialBatchUsesTenantScopedLookup() throws Exception {
        var i=record("PASS"); i.setMaterialBatchId("internal-material-id");
        var b=new MaterialBatch(); b.setBatchNumber("MB-20261008-QC");
        when(materials.findByIdAndFactoryId("internal-material-id","F006")).thenReturn(Optional.of(b));
        var data=run(List.of(i),Map.of());
        assertTrue(data.get("message").toString().contains("MB-20261008-QC"));
        verify(materials).findByIdAndFactoryId("internal-material-id","F006");
        verifyNoInteractions(batches);
    }

    @Test void missingMetadataStaysUnknownAndCannotLeakInternalNumbersOrCodes() throws Exception {
        var i=record("UNRECOGNIZED_INTERNAL_CODE"); i.setNotes(null); i.setPassRate(null);
        when(batches.findByIdAndFactoryId(10805L,"F006")).thenReturn(Optional.empty());
        var data=run(List.of(i),Map.of()); String message=data.get("message").toString();
        assertTrue(message.contains("关联批次信息不可用")); assertTrue(message.contains("未记录检验类型"));
        assertTrue(message.contains("检验结果待确认")); assertTrue(message.contains("未记录合格率"));
        assertFalse(message.contains("10805")); assertFalse(message.contains("UNRECOGNIZED")); assertFalse(message.contains(": -"));
    }

    @Test void emptyQueryAndFilteredStatusKeepPairedPositiveAndNegativeControls() throws Exception {
        var data=run(List.of(record("PASS")),Map.of("status","FAIL"));
        assertTrue(((List<?>)data.get("content")).isEmpty());
        String message=data.get("message").toString(); assertTrue(message.contains("当前没有匹配的质检记录"));
        assertTrue(message.contains("不合格")); assertFalse(message.contains("FAIL"));
    }

    @Test void realInspectionModeCanSupplyTypeWhenNotesHaveNoType() throws Exception {
        var i=record("PENDING"); i.setNotes(null); i.setInspectionMode(QualityInspection.InspectionMode.SAMPLING);
        var data=run(List.of(i),Map.of()); String message=data.get("message").toString();
        assertTrue(message.contains("类型: 抽检")); assertTrue(message.contains("状态: 待检验"));
    }
}
