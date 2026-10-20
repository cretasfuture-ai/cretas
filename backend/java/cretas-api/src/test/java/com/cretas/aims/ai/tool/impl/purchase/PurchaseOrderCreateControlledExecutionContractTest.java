package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PurchaseOrderCreateControlledExecutionContractTest {
    private final ControlledPurchaseTaskExecutionService controlled = mock(ControlledPurchaseTaskExecutionService.class);
    private final ExposedTool tool = new ExposedTool(controlled);
    private final Map<String, Object> context = Map.of("factoryId", "F001", "userId", 7L);
    private final Map<String, Object> input = Map.of(
            "supplierId", "SUP-1", "materialTypeId", "MAT-1", "quantity", 2,
            "unit", "kg", "unitPrice", 10, "currency", "CNY", "taxRate", 13,
            "amountCap", 22.60);

    @Test
    void previewUsesCanonicalFacadeAndNeverCreatesGrant() throws Exception {
        Map<String, Object> canonical = Map.of("purchaseOrderRequest", Map.of("items", 1), "currency", "CNY");
        when(controlled.previewParameters("F001", input)).thenReturn(canonical);
        when(controlled.preview("F001", canonical)).thenReturn(Map.of("status", "PREVIEW"));

        String response = tool.preview(ToolCall.of("call-1", "purchase_order_create",
                new ObjectMapper().writeValueAsString(input)), context);

        assertThat(response).contains("\"status\":\"PREVIEW\"");
        verify(controlled).previewParameters("F001", input);
        verify(controlled).preview("F001", canonical);
        verifyNoMoreInteractions(controlled);
    }

    @Test
    void alreadyCanonicalPreviewIsPassedToFacadeWithoutRebinding() {
        Map<String, Object> canonical = Map.of("purchaseOrderRequest", Map.of("items", 1), "currency", "CNY");
        when(controlled.preview("F001", canonical)).thenReturn(Map.of("status", "PREVIEW"));

        assertThat(tool.previewDirect("F001", canonical, context)).containsEntry("status", "PREVIEW");
        verify(controlled).preview("F001", canonical);
        verify(controlled, never()).previewParameters(anyString(), anyMap());
    }

    @Test
    void ordinaryToolExecuteRefusesEvenWhenCallerSpoofsConfirmed() {
        assertThatThrownBy(() -> tool.executeDirect("F001", Map.of("confirmed", true), context))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("服务端预览确认入口");
        verifyNoInteractions(controlled);
    }

    @Test
    void previewValidationFailureIsReturnedAndDoesNotFallBackToLegacyWrite() throws Exception {
        when(controlled.previewParameters("F001", input)).thenThrow(new IllegalArgumentException("税率不支持"));

        String response = tool.preview(ToolCall.of("call-2", "purchase_order_create",
                new ObjectMapper().writeValueAsString(input)), context);

        assertThat(response).contains("success").contains("税率不支持");
        verify(controlled).previewParameters("F001", input);
        verify(controlled, never()).preview(anyString(), anyMap());
    }

    private static final class ExposedTool extends PurchaseOrderCreateTool {
        private ExposedTool(ControlledPurchaseTaskExecutionService service) {
            super(service);
            this.objectMapper = new ObjectMapper().findAndRegisterModules();
        }
        private Map<String, Object> previewDirect(String factory, Map<String, Object> params, Map<String, Object> ctx) {
            try { return doPreview(factory, params, ctx); } catch (Exception e) { throw new RuntimeException(e); }
        }
        private Map<String, Object> executeDirect(String factory, Map<String, Object> params, Map<String, Object> ctx) throws Exception {
            return doExecute(factory, params, ctx);
        }
    }
}
