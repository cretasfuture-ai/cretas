package com.cretas.aims.service.reports;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperatingReportInventoryCoverageContractTest {
    private MaterialBatch batch(String unit,String price){var batch=new MaterialBatch();batch.setMaterialTypeId("SYNTH-M");batch.setQuantityUnit(unit);batch.setReceiptQuantity(new BigDecimal("100"));batch.setUsedQuantity(new BigDecimal("20"));batch.setReservedQuantity(new BigDecimal("30"));batch.setUnitPrice(price==null?null:new BigDecimal(price));batch.setStatus(MaterialBatchStatus.AVAILABLE);return batch;}
    @Test void coverageMatchesAvailableRemainderPredicatesAndEachUnit(){
        var priced=batch("kg","2");var missing=batch("kg",null);var grams=batch("g","3");var reserved=batch("kg",null);reserved.setReservedQuantity(new BigDecimal("80"));
        var customer=batch("kg",null);customer.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        var stock=List.<Map<String,Object>>of(Map.of("materialTypeId","SYNTH-M","unit","kg","batchCount",2),Map.of("materialTypeId","SYNTH-M","unit","g","batchCount",1));
        var coverage=OperatingReportCollector.costCoverage(stock,List.of(priced,missing,grams,reserved,customer));
        assertEquals(new BigDecimal("0.50000000"),coverage.get("SYNTH-M:kg"));assertEquals(new BigDecimal("1.00000000"),coverage.get("SYNTH-M:g"));
        assertNull(OperatingReportCollector.costCoverage(List.of(Map.of("materialTypeId","SYNTH-M","unit","kg","batchCount",3)),List.of(priced,missing)).get("SYNTH-M:kg"));
        LocalDate date=LocalDate.of(2026,1,1);
        var snapshot=new OperatingReportAssembler().assemble(new OperatingReportAssembler.Input("daily",date,date,"synthetic",List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
            List.of(Map.of("current",70,"days1_30",0,"days31_60",0,"days61_90",0,"days91_120",10,"days120plus",20,"total",100)),Map.of(),
            List.of(Map.of("materialTypeId","SYNTH-M","unit","kg","materialName","Synthetic material","totalQuantity",100,"batchCount",2,"totalValue",100)),coverage));
        var over90=snapshot.sections().stream().flatMap(s->s.metrics().stream()).filter(m->m.id().equals("aging.over90")).findFirst().orElseThrow();
        var share=snapshot.sections().stream().flatMap(s->s.metrics().stream()).filter(m->m.id().equals("aging.over90Share")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("30"),over90.value());assertNull(over90.coverage());assertEquals(new BigDecimal("30.00000000"),share.value());assertNull(share.coverage());
        var value=snapshot.sections().stream().flatMap(s->s.metrics().stream()).filter(m->m.id().equals("inventory.SYNTH-M:kg.value")).findFirst().orElseThrow();assertNull(value.value());
        String text=new OperatingReportCodec(new com.fasterxml.jackson.databind.ObjectMapper()).render(snapshot,true);
        assertTrue(text.contains("可用余量"));assertTrue(text.contains("扣预留"));assertTrue(text.contains("90天以上应收占比"));
    }
}
