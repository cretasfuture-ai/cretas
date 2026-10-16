package com.cretas.aims.controller.finance;

import com.cretas.aims.aspect.DataScopeAspect;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.service.finance.PaymentRecordService;
import com.cretas.aims.security.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerPaymentDataScopeContractTest {
    @ParameterizedTest @EnumSource(value=DataScope.class,names={"ALL","SELF","SELF_AND_BELOW","DEPT_AND_BELOW"})
    void actualControllerBindsTypedScopeAndFiltersSourceOrderOwners(DataScope scope) {
        var resolver=mock(DataScopeResolver.class);
        when(resolver.resolve(3L)).thenReturn(DataScopeContext.builder().factoryId("F1").userId(3L).scope(scope).build());
        when(resolver.resolveCreatedByChain(any())).thenAnswer(i->{
            assertThat(((DataScopeContext)i.getArgument(0)).getColumn()).isEqualTo("created_by");
            return List.of(3L,4L);
        });
        var repo=mock(PaymentRecordRepository.class,withSettings().defaultAnswer(i->Page.class.isAssignableFrom(i.getMethod().getReturnType())?Page.empty():RETURNS_DEFAULTS.answer(i)));
        var controller=new PaymentRecordController(mock(PaymentRecordService.class),repo,resolver);
        var proxy=new AspectJProxyFactory(controller); proxy.addAspect(new DataScopeAspect(resolver));
        var request=new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,3L,"F1","sales_manager"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            ((PaymentRecordController)proxy.getProxy()).listByCustomer("F1","C1",0,999);
            var page=org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            if(scope==DataScope.ALL) {
                verify(repo).findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(eq("F1"),eq("C1"),page.capture());
            } else {
                verify(repo).findCustomerPaymentsInOrderScope(eq("F1"),eq("C1"),eq(List.of(3L,4L)),page.capture());
                verify(repo,never()).findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(any(),any(),any());
            }
            assertThat(page.getValue().getPageNumber()).isZero();
            assertThat(page.getValue().getPageSize()).isEqualTo(200);
            assertThat(DataScopeContext.current()).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
