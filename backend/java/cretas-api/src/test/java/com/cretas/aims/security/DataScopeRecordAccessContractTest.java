package com.cretas.aims.security;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.impl.*;
import com.cretas.aims.service.impl.CustomerServiceImpl;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.impl.QualityReturnOrderServiceImpl;
import com.cretas.aims.service.inventory.impl.ReturnOrderServiceImpl;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.mapper.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DataScopeRecordAccessContractTest {
    @ParameterizedTest @ValueSource(strings={"sales","purchase","material","customer","returns","quality"})
    void hiddenRecordCannotBeReadByDirectId(String resource) {
        var resolver=mock(DataScopeResolver.class);
        doThrow(new BusinessException(403,"scope deny-control")).when(resolver).assertCanAccess("F1",100L);
        assertThatThrownBy(() -> read(resource,resolver)).isInstanceOf(BusinessException.class).hasMessage("scope deny-control");
        verify(resolver).assertCanAccess("F1",100L);
    }
    @ParameterizedTest @ValueSource(strings={"sales","purchase","material","customer","returns","quality"})
    void allowedRecordUsesTheSameScopeGuard(String resource) {
        var resolver=mock(DataScopeResolver.class);
        assertThatCode(() -> read(resource,resolver)).doesNotThrowAnyException();
        verify(resolver).assertCanAccess("F1",100L);
    }

    @org.junit.jupiter.api.Test void mvcRegistersTheRequiredRouteGuardAfterAuthenticationAndModuleChecks() {
        var guard=mock(DataScopeRouteInterceptor.class);
        var processSecretGuard=mock(ProcessSecretGuardInterceptor.class);
        var config=new com.cretas.aims.config.WebMvcConfig(mock(com.cretas.aims.config.JwtAuthInterceptor.class),
            mock(com.cretas.aims.config.DemoReadOnlyInterceptor.class),mock(com.cretas.aims.config.PermissionInterceptor.class),
            mock(com.cretas.aims.config.RequireRoleInterceptor.class),mock(com.cretas.aims.config.ModuleEnabledInterceptor.class),guard,
            processSecretGuard);
        var registry=new Registered();config.addInterceptors(registry);
        assertThat(registry.all().stream().filter(x->x instanceof org.springframework.web.servlet.handler.MappedInterceptor)
            .map(x->((org.springframework.web.servlet.handler.MappedInterceptor)x).getInterceptor()).toList()).contains(guard, processSecretGuard);
    }
    static class Registered extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
        java.util.List<Object> all() { return getInterceptors(); }
    }


    @ParameterizedTest @ValueSource(strings={"sales","purchase"})
    void childCreationChecksLockedParentBeforeAnyWrite(String resource) {
        var resolver=mock(DataScopeResolver.class);
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(DataScopeRouteInterceptor.RESOURCE_ATTRIBUTE,resource.equals("sales")?"sales_orders":"purchase_orders");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            doThrow(new BusinessException(403,"scope deny-control")).when(resolver).assertCanAccess("F1",100L);
            assertThatThrownBy(()->createChild(resource,resolver)).isInstanceOf(BusinessException.class).hasMessage("scope deny-control");
            verify(resolver).assertCanAccess("F1",100L);
            reset(resolver);
            // An owned DRAFT parent passes row access and reaches the real status guard.
            assertThatThrownBy(()->createChild(resource,resolver)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(409));
            verify(resolver).assertCanAccess("F1",100L);
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @org.junit.jupiter.api.Test void standaloneDeliveryCannotBeCreatedForHiddenCustomer() {
        var resolver=mock(DataScopeResolver.class);doThrow(new BusinessException(403,"scope deny-control")).when(resolver).assertCanAccess("F1",100L);
        var svc=mock(SalesServiceImpl.class,CALLS_REAL_METHODS);var customers=mock(CustomerRepository.class);var deliveries=mock(SalesDeliveryRecordRepository.class);
        var customer=new Customer();customer.setCreatedBy(100L);when(customers.findByIdAndFactoryId("C1","F1")).thenReturn(Optional.of(customer));
        ReflectionTestUtils.setField(svc,"customerRepository",customers);ReflectionTestUtils.setField(svc,"deliveryRecordRepository",deliveries);ReflectionTestUtils.setField(svc,"dataScopeResolver",resolver);
        var req=new com.cretas.aims.dto.inventory.CreateDeliveryRequest();req.setCustomerId("C1");req.setItems(java.util.List.of());
        assertThatThrownBy(()->svc.createDeliveryRecord("F1",req,56L)).isInstanceOf(BusinessException.class).hasMessage("scope deny-control");
        verify(deliveries,never()).save(any());verify(resolver).assertCanAccess("F1",100L);
    }

    private void createChild(String type,DataScopeResolver resolver) {
        if(type.equals("sales")) {
            var svc=mock(SalesServiceImpl.class,CALLS_REAL_METHODS);var orders=mock(SalesOrderRepository.class);var customers=mock(CustomerRepository.class);var deliveries=mock(SalesDeliveryRecordRepository.class);
            var order=new SalesOrder();order.setCreatedBy(100L);order.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.DRAFT);
            when(orders.findByIdAndFactoryIdForUpdate("PARENT","F1")).thenReturn(Optional.of(order));
            when(customers.findByIdAndFactoryId("C1","F1")).thenReturn(Optional.of(new Customer()));
            ReflectionTestUtils.setField(svc,"salesOrderRepository",orders);ReflectionTestUtils.setField(svc,"customerRepository",customers);ReflectionTestUtils.setField(svc,"deliveryRecordRepository",deliveries);ReflectionTestUtils.setField(svc,"dataScopeResolver",resolver);
            var dto=new com.cretas.aims.dto.inventory.CreateDeliveryRequest();dto.setSalesOrderId("PARENT");dto.setCustomerId("C1");dto.setItems(java.util.List.of());
            try { svc.createDeliveryRecord("F1",dto,56L); } finally { verify(deliveries,never()).save(any()); }
        } else {
            var svc=mock(PurchaseServiceImpl.class,CALLS_REAL_METHODS);var orders=mock(PurchaseOrderRepository.class);var suppliers=mock(SupplierRepository.class);var receipts=mock(PurchaseReceiveRecordRepository.class);
            var order=new PurchaseOrder();order.setCreatedBy(100L);order.setStatus(com.cretas.aims.entity.enums.PurchaseOrderStatus.DRAFT);
            when(orders.findByIdAndFactoryIdForUpdate("PARENT","F1")).thenReturn(Optional.of(order));
            when(suppliers.findByIdAndFactoryId("S1","F1")).thenReturn(Optional.of(new Supplier()));
            ReflectionTestUtils.setField(svc,"purchaseOrderRepository",orders);ReflectionTestUtils.setField(svc,"supplierRepository",suppliers);ReflectionTestUtils.setField(svc,"receiveRecordRepository",receipts);ReflectionTestUtils.setField(svc,"dataScopeResolver",resolver);
            var dto=new com.cretas.aims.dto.inventory.CreateReceiveRecordRequest();dto.setPurchaseOrderId("PARENT");dto.setSupplierId("S1");dto.setItems(java.util.List.of());
            try { svc.createReceiveRecord("F1",dto,56L); } finally { verify(receipts,never()).save(any()); }
        }
    }

    private void read(String type,DataScopeResolver resolver) {
        if(type.equals("sales")) {
            var service=mock(SalesServiceImpl.class,CALLS_REAL_METHODS); var repo=mock(SalesOrderRepository.class);
            var row=new SalesOrder();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findById("R1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"salesOrderRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            service.getSalesOrderById("F1","R1");
        } else if(type.equals("purchase")) {
            var service=mock(PurchaseServiceImpl.class,CALLS_REAL_METHODS);var repo=mock(PurchaseOrderRepository.class);
            var row=new PurchaseOrder();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findById("R1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"purchaseOrderRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            service.getPurchaseOrderById("F1","R1");
        } else if(type.equals("material")) {
            var service=mock(MaterialBatchServiceImpl.class,CALLS_REAL_METHODS);var repo=mock(MaterialBatchRepository.class);
            var row=new MaterialBatch();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findById("R1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"materialBatchRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            ReflectionTestUtils.setField(service,"materialBatchMapper",mock(MaterialBatchMapper.class));service.getMaterialBatchById("F1","R1");
        } else if(type.equals("customer")) {
            var service=mock(CustomerServiceImpl.class,CALLS_REAL_METHODS);var repo=mock(CustomerRepository.class);
            var row=new Customer();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findByIdAndFactoryId("R1","F1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"customerRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            ReflectionTestUtils.setField(service,"customerMapper",mock(CustomerMapper.class));service.getCustomerById("F1","R1");
        } else if(type.equals("returns")) {
            var service=mock(ReturnOrderServiceImpl.class,CALLS_REAL_METHODS);var repo=mock(ReturnOrderRepository.class);
            var row=new ReturnOrder();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findById("R1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"returnOrderRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            service.getReturnOrderById("F1","R1");
        } else {
            var service=mock(QualityReturnOrderServiceImpl.class,CALLS_REAL_METHODS);var repo=mock(QualityReturnOrderRepository.class);
            var row=new QualityReturnOrder();row.setFactoryId("F1");row.setCreatedBy(100L);when(repo.findByIdAndFactoryId("R1","F1")).thenReturn(Optional.of(row));
            ReflectionTestUtils.setField(service,"returnOrderRepository",repo);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
            service.get("F1","R1");
        }
    }
}
