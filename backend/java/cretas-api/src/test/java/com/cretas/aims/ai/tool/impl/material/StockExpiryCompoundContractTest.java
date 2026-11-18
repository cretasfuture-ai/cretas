package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存数字专员 D: 「哪些原料库存快不够了？有没有快过期的？」两问都要答 (修前回「今日待收清单」)。
 */
class StockExpiryCompoundContractTest {

    private final MaterialBatchService service = mock(MaterialBatchService.class);

    private static Map<String, Object> warning() {
        Map<String, Object> w = new HashMap<>();
        w.put("materialName", "牛腱");
        w.put("currentStock", new BigDecimal("12"));
        w.put("safetyStock", new BigDecimal("50"));
        w.put("unit", "kg");
        return w;
    }

    private static MaterialBatchDTO expiring() {
        MaterialBatchDTO b = new MaterialBatchDTO();
        b.setBatchNumber("MB-1");
        b.setMaterialName("花椒");
        b.setExpireDate(LocalDate.parse("2026-11-20"));
        b.setRemainingDays(2);
        b.setCurrentQuantity(new BigDecimal("8"));
        b.setUnit("kg");
        return b;
    }

    private String reply(Map<String, Object> result, String code) {
        return new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode(code).intentCategory("MATERIAL").status("SUCCESS").resultData(result).build());
    }

    @Test
    void lowStockSentenceWithExpiryAlsoAnswersExpiring() throws Exception {
        MaterialLowStockAlertTool tool = new MaterialLowStockAlertTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        when(service.getLowStockWarnings("F")).thenReturn(List.of(warning()));
        when(service.getExpiringBatches("F", 7)).thenReturn(List.of(expiring()));

        Map<String, Object> result = tool.doExecute("F",
                new HashMap<>(Map.of("originalUserInput", "哪些原料库存快不够了？有没有快过期的？")), Map.of());
        String reply = reply(result, "MATERIAL_LOW_STOCK_ALERT");

        assertThat(reply).contains("牛腱").contains("安全库存 50kg").contains("花椒").contains("2026-11-20");
        assertThat(reply).doesNotContain("待收");
    }

    @Test
    void lowStockOnlySentenceDoesNotQueryExpiring() throws Exception {
        // 阴性对照: 只问库存, 不额外去查临期
        MaterialLowStockAlertTool tool = new MaterialLowStockAlertTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        when(service.getLowStockWarnings("F")).thenReturn(List.of(warning()));

        Map<String, Object> result = tool.doExecute("F", new HashMap<>(Map.of("originalUserInput", "哪些原料库存不够了")), Map.of());

        assertThat(result).doesNotContainKey("expiringBatches");
        verify(service, never()).getExpiringBatches("F", 7);
        // 修前这条回复读 alerts/minStock, 有预警也会说「库存充足」
        assertThat(reply(result, "MATERIAL_LOW_STOCK_ALERT")).contains("牛腱").doesNotContain("库存都在安全库存以上");
    }

    @Test
    void expiringSentenceWithLowStockAlsoAnswersLowStock() throws Exception {
        MaterialExpiringAlertTool tool = new MaterialExpiringAlertTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        when(service.getLowStockWarnings("F")).thenReturn(List.of(warning()));
        when(service.getExpiringBatches("F", 7)).thenReturn(List.of(expiring()));

        Map<String, Object> result = tool.doExecute("F",
                new HashMap<>(Map.of("originalUserInput", "有没有快过期的？库存够不够？")), Map.of());
        String reply = reply(result, "MATERIAL_EXPIRING_ALERT");

        assertThat(reply).contains("牛腱").contains("花椒");
    }

    @Test
    void expiringOnlySentenceDoesNotQueryLowStock() throws Exception {
        MaterialExpiringAlertTool tool = new MaterialExpiringAlertTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        when(service.getExpiringBatches("F", 7)).thenReturn(List.of(expiring()));
        Map<String, Object> result = tool.doExecute("F", new HashMap<>(Map.of("originalUserInput", "哪些原料批次快过期了")), Map.of());
        assertThat(result).doesNotContainKey("lowStockWarnings");
        assertThat(reply(result, "MATERIAL_EXPIRING_ALERT")).contains("花椒").doesNotContain("安全库存");
    }
}
