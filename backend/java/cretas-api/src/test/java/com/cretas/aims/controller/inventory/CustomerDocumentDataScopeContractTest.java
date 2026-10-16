package com.cretas.aims.controller.inventory;

import com.cretas.aims.aspect.DataScopeAspect;
import com.cretas.aims.security.*;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.repository.inventory.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerDocumentDataScopeContractTest {
    @ParameterizedTest @EnumSource(value=DataScope.class,names={"ALL","SELF","SELF_AND_BELOW","DEPT_AND_BELOW"})
    void actualControllersBindTypedScopeAndChooseFilteredChildQueries(DataScope scope) {
        DataScopeResolver resolver=mock(DataScopeResolver.class);
        var context=DataScopeContext.builder().factoryId("F1").userId(3L).scope(scope).build();
        when(resolver.resolve(3L)).thenReturn(context);
        when(resolver.resolveCreatedByChain(any())).thenReturn(scope==DataScope.SELF?List.of(3L):List.of(3L,4L));
        SalesController sales=mock(SalesController.class,withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ReturnOrderController returns=mock(ReturnOrderController.class,withSettings().defaultAnswer(CALLS_REAL_METHODS));
        SalesOrderRepository sr=mock(SalesOrderRepository.class,withSettings().defaultAnswer(inv->
                Page.class.isAssignableFrom(inv.getMethod().getReturnType())?Page.empty():RETURNS_DEFAULTS.answer(inv)));
        ReturnOrderRepository rr=mock(ReturnOrderRepository.class,withSettings().defaultAnswer(inv->
                Page.class.isAssignableFrom(inv.getMethod().getReturnType())?Page.empty():RETURNS_DEFAULTS.answer(inv)));
        ReflectionTestUtils.setField(sales,"salesOrderRepository",sr);
        ReflectionTestUtils.setField(returns,"returnOrderRepository",rr);
        ReflectionTestUtils.setField(sales,"dataScopeResolver",resolver);
        ReflectionTestUtils.setField(returns,"dataScopeResolver",resolver);
        var request=new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,3L,"F1","sales_manager"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var sp=new AspectJProxyFactory(sales); sp.addAspect(new DataScopeAspect(resolver));
            var rp=new AspectJProxyFactory(returns); rp.addAspect(new DataScopeAspect(resolver));
            ((SalesController)sp.getProxy()).listOrdersByCustomer("F1","C1",1,20);
            ((ReturnOrderController)rp.getProxy()).listReturnsByCustomer("F1","C1",1,20);
            if(scope==DataScope.ALL) {
                verify(sr).findByFactoryIdAndCustomerIdOrderByCreatedAtDesc(eq("F1"),eq("C1"),any());
                verify(rr).findByFactoryIdAndCounterpartyIdAndReturnTypeOrderByCreatedAtDesc(eq("F1"),eq("C1"),eq(ReturnType.SALES_RETURN),any());
            } else {
                List<Long> ids=scope==DataScope.SELF?List.of(3L):List.of(3L,4L);
                verify(sr).findByFactoryIdAndCustomerIdAndCreatedByInOrderByCreatedAtDesc(eq("F1"),eq("C1"),eq(ids),any());
                verify(rr).findByFactoryIdAndCounterpartyIdAndReturnTypeAndCreatedByInOrderByCreatedAtDesc(eq("F1"),eq("C1"),eq(ReturnType.SALES_RETURN),eq(ids),any());
                verify(sr,never()).findByFactoryIdAndCustomerIdOrderByCreatedAtDesc(any(),any(),any());
                verify(rr,never()).findByFactoryIdAndCounterpartyIdAndReturnTypeOrderByCreatedAtDesc(any(),any(),any(),any());
            }
            assertThat(DataScopeContext.current()).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
