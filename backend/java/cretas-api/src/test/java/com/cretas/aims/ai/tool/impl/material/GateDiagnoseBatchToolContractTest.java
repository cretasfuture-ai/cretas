package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.service.gate.GateDiagnosis;
import com.cretas.aims.service.gate.GateDiagnosisService;
import com.cretas.aims.service.gate.GateId;
import com.cretas.aims.service.gate.GateRoutes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI 只读诊断 Tool: 读同一份诊断, 一处涉及多道闸时全部列出, 且只说怎么解开、不代替用户执行。 */
class GateDiagnoseBatchToolContractTest {

    @Test
    void declaresReadOnlySoTheWriteConfirmGateDoesNotBlockIt() {
        GateDiagnoseBatchTool tool = new GateDiagnoseBatchTool(mock(GateDiagnosisService.class));
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    void listsEveryGateWithQuantitiesAndNeverOffersToExecute() throws Exception {
        GateDiagnosisService service = mock(GateDiagnosisService.class);
        GateDiagnosis pending = GateDiagnosis.of(GateId.SEMI_QC_PENDING, "QC_SEMI_PENDING_INSPECTION", "SEMI_BATCH", "SF-1")
                .qty(new BigDecimal("50"), new BigDecimal("40")).unit("kg")
                .resolve("去「质检放行」页放行半成品批次 SF-1", GateRoutes.QC_RELEASE).build();
        GateDiagnosis expired = GateDiagnosis.of(GateId.FG_EXPIRED, "FINISHED_GOODS_BATCH_EXPIRED", "FG_BATCH", "SF-1")
                .cause("到期日 2026-11-01").resolve("该批已过期需处置 (报损)", null).build();
        when(service.diagnoseBatch("F006", "SF-1")).thenReturn(List.of(pending, expired));

        Map<String, Object> out = new GateDiagnoseBatchTool(service)
                .doExecute("F006", Map.of("batchNumber", "SF-1"), Map.of());

        assertEquals(true, out.get("blocked"));
        String msg = (String) out.get("message");
        assertTrue(msg.contains("2 道闸"));
        assertTrue(msg.contains("半成品待检") && msg.contains("缺 10kg"));
        assertTrue(msg.contains("成品已过期") && msg.contains("到期日 2026-11-01"));
        assertTrue(msg.contains("去「质检放行」页放行半成品批次 SF-1"));
        assertTrue(msg.contains("我不会代替您执行"));
        assertEquals(List.of(pending, expired), out.get("gateDiagnoses"));
    }

    @Test
    void notBlockedSaysSoAndAsksForPlanContext() throws Exception {
        GateDiagnosisService service = mock(GateDiagnosisService.class);
        when(service.diagnoseBatch("F006", "RM-OK")).thenReturn(List.of());
        Map<String, Object> out = new GateDiagnoseBatchTool(service)
                .doExecute("F006", Map.of("batchNumber", "RM-OK"), Map.of());
        assertEquals(false, out.get("blocked"));
        assertTrue(((String) out.get("message")).contains("计划"));
    }
}
