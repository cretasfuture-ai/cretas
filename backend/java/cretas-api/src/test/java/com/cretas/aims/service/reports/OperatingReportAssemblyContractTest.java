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
    @Test void salespersonComparisonMatchesActualIdOrDistinctNameAndNeverInventsMissingBases(){
        Map<String,Object> jane=new HashMap<>(Map.of("salespersonName","Jane","totalAmountWithTax",150));jane.put("salespersonId",null);
        Map<String,Object> kate=new HashMap<>(Map.of("salespersonName","Kate","totalAmountWithTax",30));kate.put("salespersonId",null);
        Map<String,Object> named=Map.of("salespersonName","Jane","totalAmountWithTax",100);
        Map<String,Object> identified=Map.of("salespersonId",7,"salespersonName","Recorded seller","totalAmountWithTax",50);
        var snapshot=new OperatingReportAssembler().assemble(new OperatingReportAssembler.Input("daily",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"synthetic",List.of(),
            Map.of("rank",List.of(jane,kate,identified)),Map.of("rank",List.of(named)),
            Map.of("rank",List.of(Map.of("salespersonName","Jane","totalAmountWithTax",0),Map.of("salespersonId",7,"totalAmountWithTax",25))),
            Map.of(),Map.of(),Map.of(),List.of(),Map.of(),List.of(),Map.of()));
        var people=snapshot.sections().stream().filter(section->section.id().equals("salespeople")).findFirst().orElseThrow().metrics();
        assertEquals(people.size(),people.stream().map(OperatingReportSnapshot.Metric::id).distinct().count(),"Name-only sellers require distinct metric identities");
        assertEquals(new BigDecimal("50.00000000"),people.stream().filter(m->m.label().equals("Jane 环比")).findFirst().orElseThrow().value());
        assertNull(people.stream().filter(m->m.label().equals("Jane 同比")).findFirst().orElseThrow().value());
        assertNull(people.stream().filter(m->m.label().equals("Kate 同比")).findFirst().orElseThrow().value());
        assertEquals(new BigDecimal("100.00000000"),people.stream().filter(m->m.label().equals("Recorded seller 同比")).findFirst().orElseThrow().value());
        assertNull(people.stream().filter(m->m.label().equals("Recorded seller 环比")).findFirst().orElseThrow().value());
    }
    @Test void ratioRejectsMissingZeroAndNegativeDenominators(){
        assertNull(OperatingReportAssembler.ratio(BigDecimal.ONE,null));
        assertNull(OperatingReportAssembler.ratio(BigDecimal.ONE,BigDecimal.ZERO));
        assertNull(OperatingReportAssembler.growth(BigDecimal.TEN,BigDecimal.valueOf(-1)));
    }
    private OperatingReportSnapshot.Metric metric(OperatingReportSnapshot snapshot,String id){return snapshot.sections().stream().flatMap(s->s.metrics().stream()).filter(m->m.id().equals(id)).findFirst().orElseThrow();}
}
