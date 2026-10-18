package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Starts the real repository proxy and executes the shipment query. Also run against local PostgreSQL. */
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
public class ShipmentCostRepositoryQueryValidationTest {
    @Autowired EntityManager em;
    @Autowired SalesDeliveryItemRepository repo;
    static final String F="P23-COST-JPA";
    static final LocalDate DATE=LocalDate.of(2026,10,17);

    // H2's JSON support lacks this PostgreSQL function. The same query runs unmodified on PostgreSQL.
    public static String jsonText(String json,String key) throws Exception {
        var mapper=new ObjectMapper();var node=mapper.readTree(json);
        if(node.isTextual())node=mapper.readTree(node.asText());
        return node.has(key)?node.get(key).asText():null;
    }

    @Test void joinsActualBatchesByFactoryDeliveryAndLineAndRevaluesOnlyChangedBatch() {
        String version=em.createNativeQuery("SELECT version()").getSingleResult().toString();
        if(version.contains("H2"))em.createNativeQuery("CREATE ALIAS IF NOT EXISTS jsonb_extract_path_text FOR 'com.cretas.aims.repository.inventory.ShipmentCostRepositoryQueryValidationTest.jsonText'").executeUpdate();
        Factory f=new Factory();f.setId(F);f.setName("成本分摊测试");em.persist(f);
        var warehouse=new com.cretas.aims.entity.factory.FactoryWarehouse();warehouse.setId("p23-test-wh");warehouse.setFactoryId(F);warehouse.setCode("WH-FG");warehouse.setName("测试成品仓");warehouse.setType(com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.FINISHED);em.persist(warehouse);
        User u=new User();u.setFactoryId(F);u.setUsername("p23-jpa");u.setPasswordHash("unused");u.setRoleCode("factory_super_admin");em.persist(u);
        ProductType p=new ProductType();p.setId("p23-sku");p.setFactoryId(F);p.setCode("p23");p.setName("盒装");p.setUnit("盒");p.setCreatedBy(u.getId());p.setGramsPerUnit(new BigDecimal("500"));em.persist(p);
        Customer c=new Customer();c.setId("p23-customer");c.setFactoryId(F);c.setCode("p23-customer");c.setCustomerCode("p23-customer");c.setName("测试客户");c.setCreatedBy(u.getId());em.persist(c);
        FinishedGoodsBatch a=batch("a","6.4165"),b=batch("b","6.9165");
        SalesOrder order=new SalesOrder();order.setId("p23-order");order.setFactoryId(F);order.setOrderNumber("p23-order");order.setCustomerId(c.getId());order.setOrderDate(DATE);order.setCreatedBy(u.getId());em.persist(order);
        SalesDeliveryRecord r=new SalesDeliveryRecord();r.setId("p23-delivery");r.setFactoryId(F);r.setSalesOrderId(order.getId());r.setDeliveryNumber("p23-delivery");r.setDeliveryDate(DATE);r.setCustomerId(c.getId());r.setShippedBy(u.getId());r.setStatus(SalesDeliveryStatus.SHIPPED);em.persist(r);
        SalesDeliveryItem item=new SalesDeliveryItem();item.setDeliveryRecordId(r.getId());item.setProductTypeId(p.getId());item.setUnit("盒");item.setDeliveredQuantity(BigDecimal.TEN);item.setFinishedGoodsBatchId(a.getId());em.persist(item);em.flush();
        edge(F,item.getId(),a,"4");edge(F,item.getId(),b,"6");
        var historical=edge(F,item.getId(),b,"999");
        historical.setMeta(Map.of("shipmentCarrier","SALES_DELIVERY_RECORD","deliveryItemId",item.getId(),"origin","BACKFILL"));
        edge("OTHER",item.getId(),b,"999"); // A forged cross-tenant edge cannot contribute.
        edge(F,item.getId()+999,b,"999"); // Another delivery line cannot contribute.
        em.flush();
        assertCost("67.1650");assertEquals(2,repo.findShippedMovementsForLedger(F,DATE,DATE).size());
        b.setUnitCost(new BigDecimal("7.4165"));em.flush();assertCost("70.1650");
        var scoped=repo.findShippedMovementsForLedgerInSourceOwnerScope(F,DATE,DATE,java.util.List.of(u.getId()));
        assertEquals(2,scoped.size());
        assertEquals(0,new BigDecimal("70.1650").compareTo((BigDecimal)ShipmentCostRows.aggregate(scoped,false)[0]));
        assertEquals(0,repo.findShippedMovementsForLedgerInSourceOwnerScope(F,DATE,DATE,java.util.List.of(u.getId()+999)).size());
        assertEquals(0,repo.findShippedMovementsForLedgerInSourceOwnerScope("OTHER",DATE,DATE,java.util.List.of(u.getId())).size());
        assertTrue(repo.findShippedMovementsForLedgerInSourceOwnerScope(F,DATE,DATE,java.util.List.of()).isEmpty());
        assertEquals(0L,repo.aggregateMissingCost(F,DATE,DATE)[1]);
        assertEquals(0L,repo.aggregateShippedCogs("OTHER",DATE,DATE)[2]);
        if (!version.contains("H2")) {
            // Non-first FIFO batch: no allocation/direct FK links this batch to the September delivery.
            b.setProductionPlanId("p23-second-plan");r.setDeliveryDate(LocalDate.of(2026,9,30));em.flush();
            var periods=org.mockito.Mockito.mock(com.cretas.aims.service.finance.AccountingPeriodService.class);
            org.mockito.Mockito.when(periods.getStatus(org.mockito.ArgumentMatchers.eq(F),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(i->i.<Integer>getArgument(2)==9?com.cretas.aims.entity.finance.AccountingPeriod.Status.CLOSED:com.cretas.aims.entity.finance.AccountingPeriod.Status.OPEN);
            var guard=new com.cretas.aims.service.finance.ProductionCostConsistency(em,periods);
            var denied=assertThrows(com.cretas.aims.exception.BusinessException.class,()->guard.assertChangeAllowed(F,"p23-second-plan"));
            assertTrue(denied.getMessage().contains("2026-09"));
            System.out.println("P23_POSTGRES_NONFIRST_BATCH_CLOSED_DELIVERY_PERIOD_PASS");
            r.setDeliveryDate(DATE);
        }
        r.setStatus(SalesDeliveryStatus.DRAFT);em.flush();assertEquals(0L,repo.aggregateShippedCogs(F,DATE,DATE)[2]);
    }
    void assertCost(String value){assertEquals(0,new BigDecimal(value).compareTo((BigDecimal)repo.aggregateShippedCogs(F,DATE,DATE)[0]));}
    FinishedGoodsBatch batch(String id,String cost){var b=new FinishedGoodsBatch();b.setId("p23-"+id);b.setFactoryId(F);b.setBatchNumber("p23-"+id);b.setProductTypeId("p23-sku");b.setUnit("盒");b.setWarehouseId("p23-test-wh");b.setProducedQuantity(BigDecimal.TEN);b.setStatus("AVAILABLE");b.setUnitCost(new BigDecimal(cost));em.persist(b);return b;}
    BatchLineageEdge edge(String factory,Long line,FinishedGoodsBatch b,String qty){var e=new BatchLineageEdge();e.setFactoryId(factory);e.setSourceType("FINISHED_BATCH");e.setSourceId(b.getId());e.setTargetType("SHIPMENT_RECORD");e.setTargetId("p23-delivery");e.setEdgeType("FINISHED_TO_SHIPMENT");e.setUnit("盒");e.setEventTime(DATE.atStartOfDay());e.setQuantityUsed(new BigDecimal(qty));e.setMeta(Map.of("shipmentCarrier","SALES_DELIVERY_RECORD","deliveryItemId",line,"origin","SALES_DELIVERY_SHIPMENT"));em.persist(e);return e;}
}
