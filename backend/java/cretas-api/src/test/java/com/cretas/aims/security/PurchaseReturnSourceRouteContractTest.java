package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PurchaseReturnSourceRouteContractTest {
    @Test
    void restrictedSourceAvailabilityUsesPurchaseOwnershipInsteadOfReturnOrderId() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE purchase_orders(id varchar PRIMARY KEY,factory_id varchar,created_by bigint)");
        jdbc.execute("INSERT INTO purchase_orders VALUES('OWN','F1',56),('HIDDEN','F1',99),('FOREIGN','F2',56)");
        var resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(56L)).thenReturn(DataScopeContext.builder().userId(56L).factoryId("F1").scope(DataScope.SELF).build());
        doThrow(new BusinessException(403,"hidden source")).when(resolver).assertCanAccess("F1",99L);
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc))
                .setControllerAdvice(new Errors()).build();
        var actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","purchasing");
        mvc.perform(get("/api/mobile/F1/return-orders/purchase-source/OWN/availability").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resource").value("purchase_orders"));
        for (String id : new String[]{"HIDDEN","FOREIGN","MISSING"}) {
            mvc.perform(get("/api/mobile/F1/return-orders/purchase-source/"+id+"/availability").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/mobile/F2/return-orders/purchase-source/FOREIGN/availability").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor))
                .andExpect(status().isForbidden());
        verify(resolver).assertCanAccess("F1",56L);
        verify(resolver).assertCanAccess("F1",99L);
        jdbc.execute("DROP ALL OBJECTS");
    }

    @RestController
    static class Probe {
        @GetMapping("/api/mobile/{factoryId}/return-orders/purchase-source/{purchaseOrderId}/availability")
        Map<String,Object> get(HttpServletRequest request) {
            return Map.of("resource",request.getAttribute(DataScopeRouteInterceptor.RESOURCE_ATTRIBUTE));
        }
    }
    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        org.springframework.http.ResponseEntity<String> error(BusinessException e) {
            return org.springframework.http.ResponseEntity.status(e.getCode()).body(e.getMessage());
        }
    }
}
