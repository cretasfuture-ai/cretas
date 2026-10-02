package com.cretas.aims.service.reports;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperatingReportAssemblyContractTest {
    @Test
    void missingCostsAndZeroBasesRemainNullAndUnassignedOrdersRemainVisible() {
        Map<String,Object> summary=new HashMap<>();
        summary.put("revenue",new BigDecimal("120")); summary.put("costedRevenue",BigDecimal.ZERO);
        summary.put("cost",null); summary.put("grossProfit",null); summary.put("marginRate",null); summary.put("costCoverage",BigDecimal.ZERO);
        Map<String,Object> sales=Map.of("summary",Map.of("orderCount",3,"assignedOrderCount",1,"totalAmountWithTax",120),
                "unassigned",Map.of("orderCount",2,"taxableAmount",80,"taxAmount",0,"totalAmountWithTax",80),
                "rank",List.of(Map.of("salespersonId",7,"salespersonName","Synthetic seller","orderCount",1,"taxableAmount",40,"taxAmount",0,"totalAmountWithTax",40)));
        var snapshot=new OperatingReportAssembler().assemble(new OperatingReportAssembler.Input("daily",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"2026-01-01T20:00:00",List.of(),sales,
                Map.of("summary",Map.of("totalAmountWithTax",0)),Map.of(),
                Map.of("summary",Map.of("recognizedShare",new BigDecimal("0.25")),"noAddress",Map.of("totalAmountWithTax",70),"unrecognized",Map.of("totalAmountWithTax",20)),
                Map.of("summary",summary),Map.of("summary",summary),List.of(),Map.of("costDataAvailable",false,"netProfit",100),List.of(),Map.of()));
        assertNull(metric(snapshot,"margin.cost").value());
        assertNull(metric(snapshot,"margin.profit").value());
        assertNull(metric(snapshot,"margin.rate").value());
        assertEquals(BigDecimal.ZERO,metric(snapshot,"margin.profit").coverage());
        assertNull(metric(snapshot,"sales.mom").value());
        assertNull(metric(snapshot,"sales.yoy").value());
        assertNull(metric(snapshot,"income.netProfit").value(),"Missing finance costs cannot mean zero costs");
        assertEquals(new BigDecimal("2"),metric(snapshot,"sales.unassigned").value());
        assertEquals(new BigDecimal("0.33333333"),metric(snapshot,"sales.assigned").coverage());
        assertEquals(new BigDecimal("70"),metric(snapshot,"province.noAddress").value());
        assertEquals(new BigDecimal("0.25"),metric(snapshot,"province.unrecognized").coverage());
        assertEquals(new BigDecimal("120"),metric(snapshot,"sales.untaxed").value());
    }
    @Test void ratioRejectsMissingZeroAndNegativeDenominators(){
        assertNull(OperatingReportAssembler.ratio(BigDecimal.ONE,null));
        assertNull(OperatingReportAssembler.ratio(BigDecimal.ONE,BigDecimal.ZERO));
        assertNull(OperatingReportAssembler.growth(BigDecimal.TEN,BigDecimal.valueOf(-1)));
    }
    private OperatingReportSnapshot.Metric metric(OperatingReportSnapshot snapshot,String id){return snapshot.sections().stream().flatMap(s->s.metrics().stream()).filter(m->m.id().equals(id)).findFirst().orElseThrow();}
}
