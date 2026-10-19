package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShipmentSkuMarginServiceTest {
    static final String F="TEST-FACTORY";
    static final LocalDate START=LocalDate.of(2026,10,1), END=LocalDate.of(2026,10,31);
    EmbeddedDatabase db; JdbcTemplate jdbc; SalesDeliveryItemRepository repo;
    IncomeStatementService financial; SalesReturnCostService salesReturnCosts; ShipmentSkuMarginService service;
    @BeforeEach void setup() {
        db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE sales_delivery_records(id varchar PRIMARY KEY,factory_id varchar,delivery_date date,record_role varchar,status varchar,deleted_at timestamp)");
        jdbc.execute("CREATE TABLE sales_delivery_items(id bigint PRIMARY KEY,delivery_record_id varchar,product_type_id varchar,product_name varchar,unit varchar,delivered_quantity decimal(20,4),unit_price decimal(20,4),deleted_at timestamp)");
        jdbc.execute("CREATE TABLE product_types(id varchar,factory_id varchar,name varchar,deleted_at timestamp)");
        repo=mock(SalesDeliveryItemRepository.class); financial=mock(IncomeStatementService.class);
        salesReturnCosts=mock(SalesReturnCostService.class);
        EntityManager em=mock(EntityManager.class); Query lock=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        service=new ShipmentSkuMarginService(repo,jdbc,financial,em,salesReturnCosts);
        lenient().when(salesReturnCosts.returnedRevenue(anyString(),any(),any())).thenReturn(BigDecimal.ZERO);
        lenient().when(salesReturnCosts.returnedCost(anyString(),any(),any())).thenReturn(BigDecimal.ZERO);
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("720","346.12",0));
    }
    @AfterEach void close(){db.shutdown();}
    @Test void actualMultiBatchCostsRevalueWithoutDuplicatingLineRevenue() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","公斤","9","30");line(2,"s","B","盒","30","15");
        movements(m(1,"A","a","9","15.2913"),m(2,"B","b","14","6.4165"),m(2,"B","c","16","7.4165"));
        var result=service.generate(F,START,END);
        assertEquals("MATCH",result.getReconciliationStatus());money("720",result.getTotalRevenue());money("346.12",result.getTotalCost());money("373.88",result.getGrossProfit());
        money("137.62",result.getRows().get(0).getCost());money("208.50",result.getRows().get(1).getCost());
        money("241.50",result.getRows().get(1).getGrossProfit());assertEquals(2,result.getRows().get(1).getBatchCount());assertEquals(1,result.getRows().get(1).getShipmentLineCount());
        var source=result.getRows().get(1).getSources().get(0);
        assertEquals(2L,source.getShipmentLineId());assertEquals("delivery",source.getDeliveryNumber());assertEquals(START.toString(),source.getDeliveryDate());
        money("450",source.getRevenue());money("30",source.getQuantity());assertEquals("盒",source.getUnit());
        money("208.4950",source.getRawCost());money("208.50",source.getCost());assertEquals(2,source.getBatches().size());
        money("6.4165",source.getBatches().get(0).getUnitCost());money("14",source.getBatches().get(0).getQuantity());
        assertEquals("unit",source.getBatches().get(0).getUnit());
        money("208.4950",source.getBatches().stream().map(b->b.getRawCost()).reduce(BigDecimal.ZERO,BigDecimal::add));
        movements(m(1,"A","a","9","15.2913"),m(2,"B","b","14","6.4165"),m(2,"B","c","16","8.4165"));
        var corrected=service.generate(F,START,END);money("362.12",corrected.getTotalCost());assertEquals("MISMATCH",corrected.getReconciliationStatus());
        money("450",corrected.getRows().get(1).getSources().get(0).getRevenue());
        money("224.4950",corrected.getRows().get(1).getSources().get(0).getRawCost());
    }
    @Test void metadataQueryExcludesMasterDraftDeletedAndForeignRows() {
        record("valid",F,"SHIPMENT","DELIVERED");line(1,"valid","A","盒","1","15");
        record("master",F,"MASTER","SHIPPED");line(2,"master","A","盒","999","15");
        record("draft",F,"SHIPMENT","DRAFT");line(3,"draft","A","盒","999","15");
        record("foreign","OTHER","SHIPMENT","SHIPPED");line(4,"foreign","A","盒","999","15");
        record("deleted",F,"SHIPMENT","SHIPPED");line(5,"deleted","A","盒","999","15");jdbc.update("UPDATE sales_delivery_records SET deleted_at=CURRENT_TIMESTAMP WHERE id='deleted'");
        line(6,"valid","A","盒","999","15");jdbc.update("UPDATE sales_delivery_items SET deleted_at=CURRENT_TIMESTAMP WHERE id=6");
        movements(m(1,"A","a","1","7"));var result=service.generate(F,START,END);
        money("15",result.getTotalRevenue());money("1",result.getRows().get(0).getQuantity());
    }
    @Test void noSnapshotOrZeroFallbackForMissingPriceOrCost() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1",null);movements(m(1,"A","a","1","7"));
        assertTrue(assertThrows(BusinessException.class,()->service.generate(F,START,END)).getMessage().contains("售价"));
        jdbc.update("UPDATE sales_delivery_items SET unit_price=15 WHERE id=1");movements(m(1,"A","a","1",null));
        assertTrue(assertThrows(BusinessException.class,()->service.generate(F,START,END)).getMessage().contains("成本"));
    }
    @Test void incompletePhysicalSourceAndMismatchedProductAreRejected() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","15");movements();
        assertThrows(BusinessException.class,()->service.generate(F,START,END));
        movements(m(1,"B","a","1","7"));assertThrows(BusinessException.class,()->service.generate(F,START,END));
    }
    @Test void zeroIsRealAndZeroRevenueRateIsUnknown() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","0");movements(m(1,"A","a","1","0"));
        var result=service.generate(F,START,END);money("0",result.getTotalCost());money("0",result.getTotalRevenue());assertNull(result.getMarginRate());
    }
    @Test void missingShipmentNameUsesOnlyThisFactoryVisibleProductName() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","15");jdbc.update("UPDATE sales_delivery_items SET product_name=NULL WHERE id=1");
        jdbc.update("INSERT INTO product_types VALUES('A','OTHER','外厂名称',NULL)");movements(m(1,"A","a","1","7"));
        assertEquals("未命名成品",service.generate(F,START,END).getRows().get(0).getProductName());
        jdbc.update("INSERT INTO product_types VALUES('A',?,'本厂盒装',NULL)",F);
        assertEquals("本厂盒装",service.generate(F,START,END).getRows().get(0).getProductName());
    }
    @Test void partialMonthDoesNotPretendToReconcileMonthlyLedger() {
        movements();var result=service.generate(F,START,START.plusDays(1));
        assertEquals("PARTIAL_PERIOD",result.getReconciliationStatus());assertNull(result.getFinancialStatement());verifyNoInteractions(financial,salesReturnCosts);
    }
    @Test void pendingVouchersPreventGreenEvenWhenAmountsMatch() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","720");movements(m(1,"A","a","1","346.12"));
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("720","346.12",1));
        when(salesReturnCosts.returnedRevenue(F,START,END)).thenThrow(new AssertionError("pending period must not read return evidence"));
        when(salesReturnCosts.returnedCost(F,START,END)).thenThrow(new AssertionError("pending period must not read return evidence"));
        var result=service.generate(F,START,END);
        assertEquals("PENDING_VOUCHERS",result.getReconciliationStatus());
        assertNull(result.getReconciliationReturnedRevenue());assertNull(result.getReconciliationReturnedCost());
        assertNull(result.getReconciliationNetRevenue());assertNull(result.getReconciliationNetCost());
        assertNull(result.getReconciliationNetGrossProfit());verifyNoInteractions(salesReturnCosts);
    }
    @Test void fullMonthReconcilesNetFactsAfterPhysicalReturnWithRawCostRounding() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","100");
        movements(m(1,"A","a","1","60"));
        when(salesReturnCosts.returnedRevenue(F,START,END)).thenReturn(new BigDecimal("20"));
        when(salesReturnCosts.returnedCost(F,START,END)).thenReturn(new BigDecimal("12.345"));
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("80","47.66",0));
        var result=service.generate(F,START,END);
        assertEquals("MATCH",result.getReconciliationStatus());
        assertEquals("实际出货扣除本期退货后，净收入、净销售成本和毛利与同期间已过账利润表一致。",result.getReconciliationMessage());
        money("100",result.getTotalRevenue());money("60",result.getTotalCost());money("40",result.getGrossProfit());
        money("20",result.getReconciliationReturnedRevenue());money("12.35",result.getReconciliationReturnedCost());
        money("80",result.getReconciliationNetRevenue());money("47.66",result.getReconciliationNetCost());
        money("32.34",result.getReconciliationNetGrossProfit());
    }
    @Test void noPhysicalReturnCostStillSubtractsReturnedRevenue() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","100");
        movements(m(1,"A","a","1","60"));
        when(salesReturnCosts.returnedRevenue(F,START,END)).thenReturn(new BigDecimal("20"));
        when(salesReturnCosts.returnedCost(F,START,END)).thenReturn(BigDecimal.ZERO);
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("80","60",0));
        var result=service.generate(F,START,END);
        assertEquals("MATCH",result.getReconciliationStatus());
        money("20",result.getReconciliationReturnedRevenue());money("0",result.getReconciliationReturnedCost());
        money("80",result.getReconciliationNetRevenue());money("60",result.getReconciliationNetCost());
        money("20",result.getReconciliationNetGrossProfit());
    }
    @Test void committedLedgerPropagatesMissingReturnEvidence() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","100");
        movements(m(1,"A","a","1","60"));
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("80","47.66",0));
        when(salesReturnCosts.returnedRevenue(F,START,END))
                .thenThrow(new BusinessException(409,"缺少已过账退货凭证"));
        var error=assertThrows(BusinessException.class,()->service.generate(F,START,END));
        assertEquals(409,error.getCode());
        verify(salesReturnCosts).returnedRevenue(F,START,END);
    }
    @Test void moneyRoundsAtPeriodLevelAndDisclosesDisplayDifference() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","1");line(2,"s","B","盒","1","1");
        movements(m(1,"A","a","1","0.004"),m(2,"B","b","1","0.004"));
        var result=service.generate(F,START,END);money("0.01",result.getTotalCost());money("0.01",result.getRoundingAdjustment());money("1.99",result.getGrossProfit());
    }
    @Test void quantitiesWithDifferentUnitsNeverMix() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","2","1");line(2,"s","A","公斤","3","1");
        movements(m(1,"A","a","2","1"),m(2,"A","b","3","1"));
        var result=service.generate(F,START,END);assertEquals(2,result.getRows().size());money("5",result.getTotalCost());
    }
    @Test void noPricePermissionStripsNestedFinancialComparisonAsWellAsSkuAmounts() {
        var advice=new com.cretas.aims.security.PriceFieldResponseAdvice();
        var row=com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO.Row.builder()
                .productName("盒装").quantity(BigDecimal.ONE).revenue(BigDecimal.TEN).cost(BigDecimal.ONE)
                .sources(List.of(com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO.SourceLine.builder()
                    .revenue(BigDecimal.TEN).rawCost(BigDecimal.ONE).batches(List.of(
                        com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO.BatchSource.builder().unitCost(BigDecimal.ONE).build())).build())).build();
        var response=com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO.builder()
                .rows(List.of(row)).totalRevenue(BigDecimal.TEN).financialStatement(statement("720","346.12",0))
                .reconciliationReturnedRevenue(BigDecimal.ONE).reconciliationReturnedCost(BigDecimal.ONE)
                .reconciliationNetRevenue(BigDecimal.ONE).reconciliationNetCost(BigDecimal.ONE)
                .reconciliationNetGrossProfit(BigDecimal.ONE).build();
        var strip=org.springframework.util.ReflectionUtils.findMethod(advice.getClass(),"stripPriceFields",Object.class,IdentityHashMap.class,Deque.class,boolean.class,boolean.class);
        assertNotNull(strip);org.springframework.util.ReflectionUtils.makeAccessible(strip);
        org.springframework.util.ReflectionUtils.invokeMethod(strip,advice,response,new IdentityHashMap<>(),new ArrayDeque<>(),false,true);
        assertNull(response.getTotalRevenue());assertNull(row.getRevenue());assertNull(row.getCost());assertNull(response.getFinancialStatement());
        assertNull(response.getReconciliationReturnedRevenue());assertNull(response.getReconciliationReturnedCost());
        assertNull(response.getReconciliationNetRevenue());assertNull(response.getReconciliationNetCost());
        assertNull(response.getReconciliationNetGrossProfit());
        assertNull(row.getSources());
        assertEquals(BigDecimal.ONE,row.getQuantity());
    }
    @Test void netCostRoundsAfterRawReturnDeductionAndResidualMismatchRemainsVisible() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","1");
        movements(m(1,"A","a","1","0.006"));
        when(salesReturnCosts.returnedRevenue(F,START,END)).thenReturn(BigDecimal.ZERO);
        when(salesReturnCosts.returnedCost(F,START,END)).thenReturn(new BigDecimal("0.004"));
        when(financial.generate(F,2026,10,2026,10)).thenReturn(statement("1","0.01",0));
        var result=service.generate(F,START,END);
        money("0.01",result.getTotalCost());money("0",result.getReconciliationNetCost());
        assertEquals("MISMATCH",result.getReconciliationStatus());
    }
    @Test void multipleLinesShareBatchButKeepDistinctRevenueAndExactCosts() {
        record("s",F,"SHIPMENT","SHIPPED");line(1,"s","A","盒","1","10");line(2,"s","A","盒","2","10");
        movements(m(1,"A","same","0.5","0.008"),m(2,"A","same","1","0.008"));
        var row=service.generate(F,START,END).getRows().get(0);
        assertEquals(2,row.getSources().size());assertEquals(1,row.getBatchCount());money("30",row.getRevenue());
        money("30",row.getSources().stream().map(s->s.getRevenue()).reduce(BigDecimal.ZERO,BigDecimal::add));
        money("0.012",row.getSources().stream().map(s->s.getRawCost()).reduce(BigDecimal.ZERO,BigDecimal::add));
        money("0.01",row.getCost());money("3",row.getQuantity());
    }
    void record(String id,String factory,String role,String status){jdbc.update("INSERT INTO sales_delivery_records VALUES(?,?,? ,?,?,NULL)",id,factory,java.sql.Date.valueOf(START),role,status);}
    void line(long id,String record,String sku,String unit,String qty,String price){jdbc.update("INSERT INTO sales_delivery_items VALUES(?,?,?,?,?,?,?,NULL)",id,record,sku,sku,unit,new BigDecimal(qty),price==null?null:new BigDecimal(price));}
    Object[] m(long id,String sku,String batch,String qty,String cost){return new Object[]{START,new BigDecimal(qty),cost==null?null:new BigDecimal(cost),sku,batch,sku,"delivery","unit",id};}
    void movements(Object[]... rows){when(repo.findShippedMovementsForLedger(F,START,END)).thenReturn(Arrays.asList(rows));when(repo.findShippedMovementsForLedger(F,START,START.plusDays(1))).thenReturn(Arrays.asList(rows));}
    static IncomeStatementDTO statement(String revenue,String cost,long pending){BigDecimal r=new BigDecimal(revenue),c=new BigDecimal(cost);return IncomeStatementDTO.builder().totalRevenue(r).totalCost(c).grossProfit(r.subtract(c)).netProfit(r.subtract(c)).pendingDraftVoucherCount(pending).build();}
    static void money(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
