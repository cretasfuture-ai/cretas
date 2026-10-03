package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultFormatterMaterialInventoryTest {
    private final ResultFormatterServiceImpl formatter = new ResultFormatterServiceImpl();

    private String format(Map<String, Object> row) {
        return formatter.format(IntentExecuteResponse.builder()
                .intentCategory("MATERIAL").intentCode("MATERIAL_BATCH_QUERY")
                .resultData(Map.of("content", List.of(row), "totalElements", 1)).build());
    }

    private Map<String, Object> batch() {
        return new HashMap<>(Map.of("batchNumber", "B-1", "materialName", "排腿",
                "unit", "kg", "status", "AVAILABLE", "quantity", 99,
                "availableQuantity", 25));
    }

    @Test void usesActualCurrentQuantityAndChineseStatus() {
        var row = batch();
        row.put("currentQuantity", new BigDecimal("12.5"));
        var text = format(row);
        assertTrue(text.contains("账面数量 12.5kg"), text);
        assertTrue(text.contains("可用"), text);
        assertFalse(text.contains("99kg"), text);
        assertFalse(text.contains("25kg"), text);
    }

    @Test void zeroStockIsNotReplacedByLegacyQuantity() {
        var row = batch();
        row.put("currentQuantity", BigDecimal.ZERO);
        row.put("status", "USED_UP");
        var text = format(row);
        assertTrue(text.contains("账面数量 0kg"), text);
        assertTrue(text.contains("已用完"), text);
    }

    @Test void supportsLegacyQuantityAndPackagingUnits() {
        var row = batch();
        row.put("quantity", 12);
        row.put("unit", "盒");
        assertTrue(format(row).contains("账面数量 12盒"));
    }

    @Test void usesBatchUnitInsteadOfMaterialTypeDefault() {
        var row = batch();
        row.put("currentQuantity", 12);
        row.put("quantityUnit", "盒");
        var text = format(row);
        assertTrue(text.contains("账面数量 12盒"), text);
        assertFalse(text.contains("12kg"), text);
    }

    @Test void absentOrNullQuantityIsExplicitAndNotFabricated() {
        var row = batch();
        row.put("currentQuantity", null);
        assertTrue(format(row).contains("数量未提供"));
        row.remove("currentQuantity");
        row.remove("quantity");
        assertTrue(format(row).contains("数量未提供"));
        assertFalse(format(row).contains("?kg"));
    }
}
