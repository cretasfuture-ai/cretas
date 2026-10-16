package com.cretas.aims.integration;

import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.aspect.DataScopeAspect;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CRETAS_WORKFLOW_PG_VERIFY",matches="true")
class RoleDataScopePostgresContractTest {
    @Test void savedScopeIsFactoryLocalAndReadBackWithoutChangingLegacyDefaults() throws Exception {
        fixture((jdbc,resolver,service) -> {
            assertThat(service.read("F1","warehouse_manager",true).scope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.ALL);
            var saved=service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            assertThat(saved.version()).isEqualTo(1);
            assertThat(saved.configured()).isTrue();
            assertThat(service.read("F1","warehouse_manager",true).scope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            assertThat(service.read("F2","warehouse_manager",true).scope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.ALL);
            assertThat(resolver.resolve(56L).getScope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            assertThat(resolver.resolve(66L).getScope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.ALL);
            assertThat(jdbc.queryForObject("SELECT data_scope FROM role_definitions WHERE role_code='warehouse_manager'",String.class)).isEqualTo("ALL");
        });
    }
    @Test void staleVersionCannotOverwriteCommittedPolicy() throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.ALL,1),56L);
            assertThatThrownBy(() -> service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,1),56L))
                .isInstanceOfSatisfying(BusinessException.class,e->{
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getActionHint()).contains("刷新角色权限页面");
                });
            assertThat(service.read("F1","warehouse_manager",true).scope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.ALL);
            assertThat(service.read("F1","warehouse_manager",true).version()).isEqualTo(2);
        });
    }
    @ParameterizedTest @EnumSource(value=com.cretas.aims.entity.enums.DataScope.class,names={"ALL","SELF","SELF_AND_BELOW","DEPT_AND_BELOW"})
    void supportedPolicyIsPersistedAndResolved(com.cretas.aims.entity.enums.DataScope scope) throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(scope,0),56L);
            assertThat(resolver.resolve(56L).getScope()).isEqualTo(scope);
            assertThat(jdbc.queryForObject("SELECT updated_by FROM factory_role_data_scopes WHERE factory_id='F1'",Long.class)).isEqualTo(56L);
        });
    }
    @Test void customUnknownAndLockedRolesCannotWritePolicy() throws Exception {
        fixture((jdbc,resolver,service) -> {
            assertThatThrownBy(() -> service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.CUSTOM,0),56L)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.update("F1","unknown",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.ALL,0),56L)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.update("F1","factory_super_admin",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L)).isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM factory_role_data_scopes",Integer.class)).isZero();
        });
    }
    @Test void realAspectUsesTypedFactoryIdentityWithoutLegacySecurityPrincipal() throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager"));
            var proxy=proxy(resolver);
            assertThat(proxy.currentOwner()).isEqualTo(56L);
            assertThat(proxy.currentScope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            assertThat(DataScopeContext.current()).isNull();
            resolver.assertCanAccess("F1",56L);
            assertThatThrownBy(() -> resolver.assertCanAccess("F1",66L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
        });
    }
    @Test void unavailableOverrideStorageStopsAccessInsteadOfFallingBackToAll() throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.execute("DROP TABLE factory_role_data_scopes");
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager"));
            assertThatThrownBy(() -> proxy(resolver).currentOwner()).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
        });
    }
    @Test void platformCollisionDoesNotBorrowFactoryPolicyOrUser() throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            actor(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,56L,null,"super_admin"));
            assertThat(proxy(resolver).currentOwner()).isNull();
            resolver.assertCanAccess("F1",66L);
        });
    }
    @Test void typedCrossFactoryAndMissingIdentityAreRejected() throws Exception {
        fixture((jdbc,resolver,service) -> {
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F2","warehouse_manager"));
            assertThatThrownBy(() -> proxy(resolver).currentOwner()).isInstanceOf(BusinessException.class);
            actor(null);
            assertThatThrownBy(() -> proxy(resolver).currentOwner()).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(401));
        });
    }
    @DataScope("scope_test_owner")
    public static class ClassProbe {
        public Long currentOwner() { return DataScopeContext.current().getUserId(); }
        public String currentColumn() { return DataScopeContext.current().getColumn(); }
    }
    @Test void classLevelScopeBindingUsesTypedIdentityAndCleansThreadLocal() throws Exception {
        fixture((jdbc,resolver,service) -> {
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager"));
            var factory=new AspectJProxyFactory(new ClassProbe());factory.addAspect(new DataScopeAspect(resolver));
            ClassProbe probe=factory.getProxy();assertThat(probe.currentOwner()).isEqualTo(56L);assertThat(probe.currentColumn()).isEqualTo("scope_test_owner");assertThat(DataScopeContext.current()).isNull();
        });
    }
    @Test void legacyCustomPolicyCannotSilentlyExposeAllRows() throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.update("UPDATE role_definitions SET data_scope='CUSTOM'");
            assertThatThrownBy(() -> resolver.resolve(56L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"sales/orders","purchase/orders","customers","material-batches","return-orders","quality-return-orders","reference-data/customers","reference-data/sales-orders","reference-data/purchase-orders","reference-data/batches","markers/sales-order","markers/purchase-order"})
    void hiddenExternalIdReadAndWriteAreBlockedBeforeBusinessHandler(String resource) throws Exception {
        fixture((jdbc,resolver,service) -> {
            for (String table : List.of("sales_orders","purchase_orders","customers","material_batches","return_orders","quality_return_orders")) {
                jdbc.execute("CREATE TABLE "+table+"(id varchar(40) PRIMARY KEY,factory_id varchar(50),created_by bigint,remark text)");
                jdbc.update("INSERT INTO "+table+" VALUES ('OWN','F1',56,NULL),('HIDDEN','F1',66,NULL)");
            }
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var probe=new RouteProbe(jdbc);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(probe)
                .addInterceptors(jwtGuard(resolver),new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var actor=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/"+resource+"/HIDDEN")
                .header("Authorization","Bearer "+factoryToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mobile/F1/"+resource+"/HIDDEN/change")
                .header("Authorization","Bearer "+factoryToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            assertThat(probe.writes).isZero();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/"+resource+"/OWN")
                .header("Authorization","Bearer "+factoryToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+RouteProbe.table(resource)+" WHERE remark IS NOT NULL",Integer.class)).isZero();
        });
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"quotes/by-customer","finance/invoices/by-customer","finance/payments/by-customer","sales/orders/by-status","sales/statistics","reference-data/customers","reference-data/sales-orders","reference-data/purchase-orders","reference-data/batches","inventory/by-warehouse"}) void unsafeAlternateReadIsRejectedButAllScopePreservesIt(String alternate) throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var probe=new RouteProbe(jdbc);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(probe)
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var actor=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/"+alternate)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            assertThat(probe.reads).isZero();
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.ALL,1),56L);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/"+alternate)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(probe.reads).isEqualTo(1);
        });
    }
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/api/mobile/{factoryId}")
    static class RouteProbe {
        final JdbcTemplate jdbc; int writes=0,reads=0;
        RouteProbe(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        static String table(String resource) { return switch(resource) {
            case "sales/orders","reference-data/sales-orders","markers/sales-order" -> "sales_orders";case "purchase/orders","reference-data/purchase-orders","markers/purchase-order" -> "purchase_orders";case "customers","reference-data/customers" -> "customers";
            case "material-batches","reference-data/batches" -> "material_batches";case "return-orders" -> "return_orders";default -> "quality_return_orders";}; }
        @org.springframework.web.bind.annotation.GetMapping({"/sales/deliveries/{deliveryId}","/sales/orders/{orderId}","/purchase/orders/{orderId}","/customers/{customerId}","/material-batches/{batchId}","/return-orders/{returnOrderId}","/quality-return-orders/{id}","/reference-data/customers/{id}","/reference-data/sales-orders/{id}","/reference-data/purchase-orders/{id}","/reference-data/batches/{id}","/markers/sales-order/{id}","/markers/purchase-order/{id}"})
        public String read() { return "allowed"; }
        @org.springframework.web.bind.annotation.PostMapping({"/sales/orders/{orderId}/change","/purchase/orders/{orderId}/change","/customers/{customerId}/change","/material-batches/{batchId}/change","/return-orders/{returnOrderId}/change","/quality-return-orders/{id}/change","/reference-data/customers/{id}/change","/reference-data/sales-orders/{id}/change","/reference-data/purchase-orders/{id}/change","/reference-data/batches/{id}/change","/markers/sales-order/{id}/change","/markers/purchase-order/{id}/change"})
        public String change(jakarta.servlet.http.HttpServletRequest request) {
            writes++;String path=request.getRequestURI().substring("/api/mobile/F1/".length());
            String resource=path.substring(0,path.indexOf("/HIDDEN"));jdbc.update("UPDATE "+table(resource)+" SET remark='changed' WHERE id='HIDDEN'");return "changed";
        }
        @org.springframework.web.bind.annotation.GetMapping({"/sales/deliveries/by-order/{orderId}","/finance/invoices/by-sales-order/{salesOrderId}","/finance/payments/by-sales-order/{salesOrderId}","/production-plans/by-sales-order/{salesOrderId}"})
        public String linkedTab() { reads++;return "allowed linked data"; }
        @org.springframework.web.bind.annotation.GetMapping({"/quotes/by-customer","/finance/invoices/by-customer","/finance/payments/by-customer","/sales/orders/by-status","/sales/statistics","/reference-data/customers","/reference-data/sales-orders","/reference-data/purchase-orders","/reference-data/batches","/inventory/by-warehouse"}) public String shortcut() { reads++;return "unsafe legacy shortcut"; }
    }
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class RouteErrors {
        @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
        org.springframework.http.ResponseEntity<Map<String,Object>> handle(BusinessException error) {
            return org.springframework.http.ResponseEntity.status(error.getCode()).body(Map.of("message",error.getMessage()));
        }
    }


    @Test void missingLegacyDefaultStorageDoesNotInventAllAccess() throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.execute("DROP TABLE role_definitions");
            assertThat(resolver.resolve(56L).getScope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager"));
            assertThatThrownBy(() -> resolver.assertCanAccess("F1",66L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(403));
        });
    }
    @Test void trustedInternalStockCallIsNotMistakenForExternalRecordAccess() throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            actor(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager"));
            DataScopeResolver.requireRecordAccess(resolver,"F1",66L,"material_batches");
            assertThatThrownBy(() -> resolver.assertCanAccess("F1",66L)).isInstanceOf(BusinessException.class);
        });
    }


    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"read","by-order","confirm","close-short"})
    void canonicalWarehouseReceiptUsesParentOwnershipBeforeHandlerWrites(String action) throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.execute("CREATE TABLE purchase_orders(id varchar PRIMARY KEY,factory_id varchar,created_by bigint); CREATE TABLE purchase_receive_records(id varchar PRIMARY KEY,factory_id varchar,purchase_order_id varchar)");
            jdbc.update("INSERT INTO purchase_orders VALUES ('OWN','F1',56),('HIDDEN','F1',66)");
            jdbc.update("INSERT INTO purchase_receive_records VALUES ('OWN','F1','OWN'),('HIDDEN','F1','HIDDEN')");
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var purchase=mock(com.cretas.aims.service.inventory.PurchaseService.class);
            when(purchase.getReceiveRecordById("F1","OWN")).thenReturn(new com.cretas.aims.entity.inventory.PurchaseReceiveRecord());
            when(purchase.confirmReceive("F1","OWN",56L)).thenReturn(new com.cretas.aims.entity.inventory.PurchaseReceiveRecord());
            when(purchase.closeReceivingTask(eq("F1"),anyString(),any(),eq(56L))).thenReturn(com.cretas.aims.entity.enums.PurchaseOrderStatus.CLOSED);
            var controller=new com.cretas.aims.controller.warehouse.WarehouseReceivingController(purchase,
                mobileUser(),mock(com.cretas.aims.service.factory.WarehouseResolver.class),mock(com.cretas.aims.repository.factory.FactoryWarehouseRepository.class),
                mock(com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService.class),mock(com.cretas.aims.service.inventory.CustomerMaterialArrivalNoticeService.class));
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var identity=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            for(String id:List.of("HIDDEN","OWN")) {
                String suffix=switch(action) {case "by-order"->"receipts/by-order/"+id;case "confirm"->"receipts/"+id+"/confirm";case "close-short"->"tasks/"+id+"/close-short";default->"receipts/"+id;};
                var req=(action.equals("confirm")||action.equals("close-short"))?
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mobile/F1/warehouse/receiving/"+suffix):
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/warehouse/receiving/"+suffix);
                req.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity).header("Authorization","Bearer local-fixture").requestAttr("userId",56L).contentType("application/json").content("{\"reasonCode\":\"OTHER\"}");
                mvc.perform(req).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(id.equals("HIDDEN")?403:200));
                if(id.equals("HIDDEN")) verifyNoInteractions(purchase);
            }
            verify(purchase,never()).confirmReceive("F1","HIDDEN",56L);
            verify(purchase,never()).closeReceivingTask(eq("F1"),eq("HIDDEN"),any(),anyLong());
        });
    }
    @Test void standaloneDeliveryInheritsCustomerOwnershipAndCanBeReadBack() throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.execute("CREATE TABLE sales_orders(id varchar,factory_id varchar,created_by bigint); CREATE TABLE customers(id varchar,factory_id varchar,created_by bigint); CREATE TABLE sales_delivery_records(id varchar,factory_id varchar,sales_order_id varchar,customer_id varchar)");
            jdbc.update("INSERT INTO customers VALUES('C1','F1',56),('C2','F1',66)");
            jdbc.update("INSERT INTO sales_delivery_records VALUES('OWN','F1',NULL,'C1'),('HIDDEN','F1',NULL,'C2')");
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new RouteProbe(jdbc))
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var identity=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/sales/deliveries/OWN").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/sales/deliveries/HIDDEN").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        });
    }


    @Test void canonicalReceiptCreationChecksParentThroughRealServiceBeforeSave() throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var purchase=mock(com.cretas.aims.service.inventory.impl.PurchaseServiceImpl.class,CALLS_REAL_METHODS);
            var orders=mock(com.cretas.aims.repository.inventory.PurchaseOrderRepository.class);
            var suppliers=mock(com.cretas.aims.repository.SupplierRepository.class);
            var receipts=mock(com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository.class);
            for(String id:List.of("OWN","HIDDEN")) {
                var order=new com.cretas.aims.entity.inventory.PurchaseOrder();order.setCreatedBy(id.equals("OWN")?56L:66L);
                order.setStatus(com.cretas.aims.entity.enums.PurchaseOrderStatus.DRAFT);
                when(orders.findByIdAndFactoryIdForUpdate(id,"F1")).thenReturn(Optional.of(order));
            }
            when(suppliers.findByIdAndFactoryId("S1","F1")).thenReturn(Optional.of(new com.cretas.aims.entity.Supplier()));
            org.springframework.test.util.ReflectionTestUtils.setField(purchase,"purchaseOrderRepository",orders);
            org.springframework.test.util.ReflectionTestUtils.setField(purchase,"supplierRepository",suppliers);
            org.springframework.test.util.ReflectionTestUtils.setField(purchase,"receiveRecordRepository",receipts);
            org.springframework.test.util.ReflectionTestUtils.setField(purchase,"dataScopeResolver",resolver);
            var controller=new com.cretas.aims.controller.warehouse.WarehouseReceivingController(purchase,
                mobileUser(),mock(com.cretas.aims.service.factory.WarehouseResolver.class),mock(com.cretas.aims.repository.factory.FactoryWarehouseRepository.class),
                mock(com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService.class),mock(com.cretas.aims.service.inventory.CustomerMaterialArrivalNoticeService.class));
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var identity=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            for(String id:List.of("HIDDEN","OWN")) {
                String body="{\"purchaseOrderId\":\""+id+"\",\"supplierId\":\"S1\",\"receiveDate\":\"2026-10-13\",\"items\":[{\"materialTypeId\":\"M1\",\"receivedQuantity\":1,\"unit\":\"个\"}]}";
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mobile/F1/warehouse/receiving/receipts")
                    .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity).header("Authorization","Bearer local-fixture").requestAttr("userId",56L).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(id.equals("HIDDEN")?403:409));
                verify(receipts,never()).save(any());
            }
        });
    }


    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"summary","workflow"})
    void realUiAggregatesCannotRevealHiddenRowsThroughStatistics(String kind) throws Exception {
        fixture((jdbc,resolver,service) -> {
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var summaries=mock(com.cretas.aims.service.listsummary.ListSummaryService.class);
            var workflows=mock(com.cretas.aims.service.workflow.WorkflowStatsService.class);
            var aggregateUsers=mock(UserRepository.class);when(aggregateUsers.findById(56L)).thenReturn(Optional.of(user(56,"F1")));
            var aggregatePermissions=mock(com.cretas.aims.service.PermissionService.class);
            when(aggregatePermissions.hasAnyPermission(any(),any(String[].class))).thenReturn(true);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.cretas.aims.controller.ListSummaryController(summaries,aggregatePermissions,aggregateUsers),new com.cretas.aims.controller.WorkflowStatsController(workflows))
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var identity=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            for(int version=1;version<=2;version++) {
                var request=kind.equals("summary")?org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mobile/F1/list-summary/salesOrder"):
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/workflow-stats/sales");
                mvc.perform(request.header("Authorization","Bearer local-fixture").requestAttr("userId",56L).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(version==1?403:200));
                if(version==1) {verifyNoInteractions(summaries,workflows);service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.ALL,1),56L);}
            }
            if(kind.equals("summary"))verify(summaries).computeSummary(eq("F1"),eq("salesOrder"),any());else verify(workflows).getSalesStats("F1");
        });
    }


    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"sales/deliveries/by-order","finance/invoices/by-sales-order","finance/payments/by-sales-order","production-plans/by-sales-order"})
    void salesDetailLinkedTabsUseTheSourceOrdersScope(String prefix) throws Exception {
        fixture((jdbc,resolver,service) -> {
            jdbc.execute("CREATE TABLE sales_orders(id varchar,factory_id varchar,created_by bigint)");
            jdbc.update("INSERT INTO sales_orders VALUES('OWN','F1',56),('HIDDEN','F1',66)");
            service.update("F1","warehouse_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            var probe=new RouteProbe(jdbc);var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(probe)
                .addInterceptors(new DataScopeRouteInterceptor(resolver,jdbc)).setControllerAdvice(new RouteErrors()).build();
            var identity=new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","warehouse_manager");
            for(String id:List.of("HIDDEN","OWN")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/"+prefix+"/"+id).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,identity))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(id.equals("HIDDEN")?403:200));
                if(id.equals("HIDDEN"))assertThat(probe.reads).isZero();
            }
            assertThat(probe.reads).isEqualTo(1);
        });
    }

    public static class Probe {
        @DataScope("created_by") public com.cretas.aims.entity.enums.DataScope currentScope() { return DataScopeContext.current().getScope(); }
        @DataScope("created_by") public Long currentOwner() {
            return DataScopeContext.current()==null ? null : DataScopeContext.current().getUserId();
        }
    }
    private static Probe proxy(DataScopeResolver resolver) {
        var factory=new AspectJProxyFactory(new Probe()); factory.addAspect(new DataScopeAspect(resolver)); return factory.getProxy();
    }
    private static void actor(AuthenticatedActor actor) {
        var request=new MockHttpServletRequest(); request.setAttribute("userId",100007L);
        if(actor!=null) request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @Test void activeProductionManagerCanConfigureItsFactoryPolicy() throws Exception {
        fixture((jdbc,resolver,service)->{
            var saved=service.update("F1","production_manager",new RoleDataScopeService.Update(com.cretas.aims.entity.enums.DataScope.SELF,0),56L);
            assertThat(saved.scope()).isEqualTo(com.cretas.aims.entity.enums.DataScope.SELF);
            assertThat(service.read("F1","production_manager",true).version()).isEqualTo(1);
        });
    }
    @Test void nativeMigrationRejectsMalformedExistingScopeTableWithoutChangingIt() throws Exception {
        String url=System.getenv("CRETAS_WORKFLOW_PG_URL");DisposablePostgresTargetGuard.requireSafeUrl(url);
        String schema="scope_collision_"+UUID.randomUUID().toString().replace("-","");
        String login=System.getenv("CRETAS_WORKFLOW_PG_USER"),secret=System.getenv("CRETAS_WORKFLOW_PG_PASSWORD");
        try(var connection=DriverManager.getConnection(url,login,secret);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            try {
                statement.execute("CREATE TABLE "+schema+".factory_role_data_scopes(bad_column text)");
                assertThatThrownBy(()->Flyway.configure().dataSource(url,login,secret).schemas(schema).defaultSchema(schema)
                        .locations("classpath:db/flyway").baselineOnMigrate(true).baselineVersion("20261103.63")
                        .target("20261103.64").outOfOrder(false).load().migrate())
                        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                try(var columns=statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='factory_role_data_scopes'")) {
                    assertThat(columns.next()).isTrue();assertThat(columns.getString(1)).isEqualTo("bad_column");assertThat(columns.next()).isFalse();
                }
            } finally { statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    private static com.cretas.aims.service.MobileService mobileUser() {
        var service=mock(com.cretas.aims.service.MobileService.class);
        var user=new com.cretas.aims.dto.user.UserDTO();user.setId(56L);
        when(service.getUserFromToken(anyString())).thenReturn(user);return service;
    }
    private static com.cretas.aims.utils.JwtUtil jwtTokens() {
        var jwt=new com.cretas.aims.utils.JwtUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"secret","nonproduction-contract-secret-for-typed-identity-20261016");
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"expiration",3600000L);return jwt;
    }
    private static String factoryToken(){return jwtTokens().generateToken(56L,"F1","scope_user","warehouse_manager");}
    private static com.cretas.aims.config.JwtAuthInterceptor jwtGuard(DataScopeResolver resolver) {
        var guard=new com.cretas.aims.config.JwtAuthInterceptor();
        org.springframework.test.util.ReflectionTestUtils.setField(guard,"jwtUtil",jwtTokens());
        org.springframework.test.util.ReflectionTestUtils.setField(guard,"userRepository",org.springframework.test.util.ReflectionTestUtils.getField(resolver,"userRepository"));
        org.springframework.test.util.ReflectionTestUtils.setField(guard,"factoryRepository",mock(com.cretas.aims.repository.FactoryRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(guard,"tokenBlacklistService",mock(com.cretas.aims.service.TokenBlacklistService.class));return guard;
    }
    interface Check { void run(JdbcTemplate jdbc,DataScopeResolver resolver,RoleDataScopeService service) throws Exception; }
    private static User user(long id,String factory) {
        var u=new User(); u.setId(id);u.setFactoryId(factory);u.setRoleCode("warehouse_manager");u.setIsActive(true);u.setDepartment("warehouse");return u;
    }
    private static void fixture(Check check) throws Exception {
        String url=System.getenv("CRETAS_WORKFLOW_PG_URL");
        DisposablePostgresTargetGuard.requireSafeUrl(url);
        String schema="role_scope_verify_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,System.getenv("CRETAS_WORKFLOW_PG_USER"),System.getenv("CRETAS_WORKFLOW_PG_PASSWORD"));var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema); connection.setSchema(schema);
            try {
                statement.execute("CREATE TABLE factories(id varchar(50) PRIMARY KEY); CREATE TABLE users(id bigint PRIMARY KEY); CREATE TABLE role_definitions(role_code varchar(50),data_scope varchar(32)); INSERT INTO factories VALUES('F1'),('F2'); INSERT INTO users VALUES(56),(66); INSERT INTO role_definitions VALUES('warehouse_manager','ALL')");
                Flyway.configure().dataSource(url,System.getenv("CRETAS_WORKFLOW_PG_USER"),System.getenv("CRETAS_WORKFLOW_PG_PASSWORD"))
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/flyway").baselineOnMigrate(true).baselineVersion("20261103.63").target("20261103.64").outOfOrder(false).load().migrate();
                JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                var users=mock(UserRepository.class);
                when(users.findById(56L)).thenReturn(Optional.of(user(56,"F1")));
                when(users.findById(66L)).thenReturn(Optional.of(user(66,"F2")));
                var defaults=mock(com.cretas.aims.repository.RoleDefinitionRepository.class);
                when(defaults.findByRoleCode(anyString())).thenAnswer(i -> jdbc.query("SELECT data_scope FROM role_definitions WHERE role_code=?", (rs,n)-> { var d=new com.cretas.aims.entity.config.RoleDefinition();d.setDataScope(rs.getString(1));return d; },(String)i.getArgument(0)).stream().findFirst());
                var resolver=new DataScopeResolver(users,defaults,jdbc);
                check.run(jdbc,resolver,new RoleDataScopeService(jdbc,resolver));
            } finally { RequestContextHolder.resetRequestAttributes(); connection.setSchema("public");statement.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
}
