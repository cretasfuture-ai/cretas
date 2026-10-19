package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.service.SupplierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SupplierPriceComparisonContractTest {
    @Mock private SupplierService supplierService;
    @InjectMocks private SupplierPriceComparisonTool tool;

    @Test
    void supplierLookupDoesNotClaimPriceComparisonOrSortWithoutQuotes() throws Exception {
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        String result = tool.execute(ToolCall.of("call-1", "supplier_price_comparison", "{}"),
                Map.of("factoryId", "F-SUP", "userId", 1L));

        assertThat(result).contains("\"success\":false")
                .contains("供应商价格比较暂不可用")
                .contains("未接入真实报价");
        verifyNoInteractions(supplierService);
    }
}
