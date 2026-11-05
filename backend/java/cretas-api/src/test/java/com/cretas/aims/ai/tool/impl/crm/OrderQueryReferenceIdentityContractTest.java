package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderQueryReferenceIdentityContractTest {
    @Test void deletedCanonicalReferenceCannotResolveToAnotherOrdersNumber() {
        var service = mock(SalesService.class);
        var repository = mock(SalesOrderRepository.class);
        when(service.getSalesOrderById("F006","SO-REAL-ID")).thenThrow(new ResourceNotFoundException("missing"));
        assertThatThrownBy(() -> new OrderQueryTool(service,repository).doExecute("F006",
                Map.of("orderId","SO-REAL-ID",OrderQueryTool.SERVER_RESULT_ID,"SO-REAL-ID"),Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @Test void directUserSuppliedOrderNumberRetainsExistingFactoryScopedResolution() {
        var service = mock(SalesService.class);
        var repository = mock(SalesOrderRepository.class);
        when(service.getSalesOrderById("F006","SO-001")).thenThrow(new ResourceNotFoundException("missing"));
        var order = new SalesOrder(); order.setId("SO-REAL-ID"); order.setFactoryId("F006");
        when(repository.findByFactoryIdAndOrderNumber("F006","SO-001")).thenReturn(Optional.of(order));
        var result = new OrderQueryTool(service,repository).doExecute("F006",Map.of("orderId","SO-001"),Map.of());
        assertThat(result.get("order")).isSameAs(order);
        verify(repository).findByFactoryIdAndOrderNumber("F006","SO-001");
    }
}
