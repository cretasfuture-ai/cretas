package com.cretas.aims.security;

import com.cretas.aims.controller.warehouse.WarehouseReceivingController;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceivingTaskRouteContractTest {
    private final DataScopeResolver resolver=mock(DataScopeResolver.class);
    private final DataScopeRouteInterceptor interceptor=new DataScopeRouteInterceptor(resolver,mock(JdbcTemplate.class));
    private final WarehouseReceivingController controller=new WarehouseReceivingController(null,null,null,null,null,null);

    @Test void canonicalFilteredReadIsAllowedForSelfSubordinatesAndDepartment() throws Exception {
        for (DataScope scope:ListScopes.VALUES) {
            when(resolver.resolve(11L)).thenReturn(DataScopeContext.builder().userId(11L).factoryId("F").scope(scope).build());
            assertThat(interceptor.preHandle(request("GET","F","warehouse/receiving/tasks"),
                    new MockHttpServletResponse(),handler("getTasks"))).isTrue();
        }
    }

    @Test void customerReceiptWritesRemainDeniedAndCrossFactoryFailsBeforeListQuery() throws Exception {
        when(resolver.resolve(11L)).thenReturn(DataScopeContext.builder().userId(11L).factoryId("F").scope(DataScope.SELF).build());
        assertThatThrownBy(()->interceptor.preHandle(request("POST","F","warehouse/receiving/tasks/{taskId}/receipts"),
                new MockHttpServletResponse(),handler("receiveCustomerSuppliedMaterial")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(()->interceptor.preHandle(request("GET","OTHER","warehouse/receiving/tasks"),
                new MockHttpServletResponse(),handler("getTasks")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
    }

    private HandlerMethod handler(String name) {
        var method=java.util.Arrays.stream(WarehouseReceivingController.class.getDeclaredMethods())
                .filter(m->m.getName().equals(name)).findFirst().orElseThrow();
        return new HandlerMethod(controller,method);
    }
    private MockHttpServletRequest request(String method,String factory,String path) {
        var request=new MockHttpServletRequest(method,"/api/mobile/"+factory+"/warehouse/receiving/tasks");
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,11L,"F","production_manager"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("factoryId",factory,"taskId","hidden"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/mobile/{factoryId}/"+path);
        return request;
    }
    private static class ListScopes {
        static final DataScope[] VALUES={DataScope.SELF,DataScope.SELF_AND_BELOW,DataScope.DEPT_AND_BELOW};
    }
}
