package com.cretas.aims.service.indicator;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only calculator for completed plans with multiple formal output lines. */
@Service
public class MultiOutputActualMetricsService {
    public enum Metric { YIELD_RATE, GOOD_OUTPUT_RATE, UNIT_COST, TOTAL_COST }
    public enum QualityStatus { NONE_CONFIRMED, COUNTED }

    public record Output(String planId, String batchNumber, String productTypeId,
                         BigDecimal outputKg, BigDecimal goodOutputKg, BigDecimal defectKg,
                         QualityStatus qualityStatus, BigDecimal allocatedCost) {
        public Output { outputKg=clean(outputKg); goodOutputKg=clean(goodOutputKg); defectKg=clean(defectKg); allocatedCost=clean(allocatedCost); }
    }
    public record Plan(String planId, BigDecimal inputKg, BigDecimal authoritativeCost) {
        public Plan { inputKg=clean(inputKg); authoritativeCost=clean(authoritativeCost); }
    }

    public BigDecimal aggregate(Collection<Plan> plans, Collection<Output> outputs, Metric metric) {
        if (plans == null || outputs == null || plans.isEmpty() || outputs.isEmpty() || metric == null) return null;
        Map<String,Plan> unique=uniquePlans(plans, metric); if (unique==null) return null;
        Totals totals=totals(unique, outputs, true, metric); return totals==null?null:value(totals,metric);
    }
    public BigDecimal byProduct(Collection<Plan> plans, Collection<Output> outputs, String productTypeId, Metric metric) {
        if (productTypeId==null || productTypeId.isBlank() || outputs==null || plans==null || metric==null) return null;
        var selected=outputs.stream().filter(o->o!=null && productTypeId.equals(o.productTypeId())).toList();
        if (selected.isEmpty()) return null; Map<String,Plan> unique=uniquePlans(plans, metric); if(unique==null)return null;
        if (totals(unique, outputs, true, metric) == null) return null;
        var selectedPlanIds = selected.stream().map(Output::planId).collect(java.util.stream.Collectors.toSet());
        var selectedPlans = unique.entrySet().stream().filter(e -> selectedPlanIds.contains(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a,b) -> a, LinkedHashMap::new));
        Totals totals=totals(selectedPlans,selected,false,metric); return totals==null?null:value(totals,metric);
    }
    private Map<String,Plan> uniquePlans(Collection<Plan> plans, Metric metric) {
        Map<String,Plan> result=new LinkedHashMap<>();
        for(Plan p:plans){ if(p==null||blank(p.planId())||!positive(p.inputKg())||(needsCost(metric)&&(p.authoritativeCost()==null||p.authoritativeCost().signum()<0)))return null;
            Plan old=result.putIfAbsent(p.planId(),p); if(old!=null&&(!same(old.inputKg(),p.inputKg())||(needsCost(metric)&&!same(old.authoritativeCost(),p.authoritativeCost()))))return null; }
        return result;
    }
    private Totals totals(Map<String,Plan> plans,Collection<Output> outputs,boolean requirePlanCost,Metric metric){
        BigDecimal input=plans.values().stream().map(Plan::inputKg).reduce(BigDecimal.ZERO,BigDecimal::add), output=BigDecimal.ZERO,good=BigDecimal.ZERO,inspected=BigDecimal.ZERO,cost=BigDecimal.ZERO;
        var seen=new java.util.HashSet<String>();
        Map<String,BigDecimal> costsByPlan=new LinkedHashMap<>();
        var representedPlans=new java.util.HashSet<String>();
        for(Output row:outputs){ if(row==null||blank(row.planId())||blank(row.batchNumber())||blank(row.productTypeId())||!plans.containsKey(row.planId())||!positive(row.outputKg())||(needsCost(metric)&&(row.allocatedCost()==null||row.allocatedCost().signum()<0))||!seen.add(row.planId()+"\0"+row.batchNumber()+"\0"+row.productTypeId()))return null;
            representedPlans.add(row.planId());
            if(metric!=Metric.YIELD_RATE&&row.qualityStatus()==null)return null; BigDecimal rowGood,rowDefect;
            if(metric==Metric.YIELD_RATE){rowGood=row.outputKg();rowDefect=BigDecimal.ZERO;}
            else if(row.qualityStatus()==QualityStatus.NONE_CONFIRMED){if(row.defectKg()==null||row.defectKg().signum()!=0)return null;rowGood=row.outputKg();rowDefect=BigDecimal.ZERO;}
            else {if(row.goodOutputKg()==null||row.defectKg()==null||row.goodOutputKg().signum()<0||row.defectKg().signum()<0||!same(row.outputKg(),row.goodOutputKg()))return null;rowGood=row.outputKg();rowDefect=row.defectKg();}
            output=output.add(row.outputKg());good=good.add(rowGood);inspected=inspected.add(rowGood).add(rowDefect);
            if(needsCost(metric)){cost=cost.add(row.allocatedCost());costsByPlan.merge(row.planId(),row.allocatedCost(),BigDecimal::add);} }
        if(!representedPlans.equals(plans.keySet()))return null;
        if(requirePlanCost&&needsCost(metric)&&plans.values().stream().anyMatch(p->!same(p.authoritativeCost(),costsByPlan.get(p.planId()))))return null;
        return new Totals(input,output,good,inspected,cost);
    }
    private BigDecimal value(Totals t,Metric metric){return switch(metric){case YIELD_RATE->divide(t.output.multiply(BigDecimal.valueOf(100)),t.input);case GOOD_OUTPUT_RATE->divide(t.good.multiply(BigDecimal.valueOf(100)),t.inspected);case UNIT_COST->divide(t.cost,t.output);case TOTAL_COST->t.cost.setScale(4,RoundingMode.HALF_UP);};}
    private record Totals(BigDecimal input,BigDecimal output,BigDecimal good,BigDecimal inspected,BigDecimal cost){}
    private static boolean needsCost(Metric metric){return metric==Metric.UNIT_COST||metric==Metric.TOTAL_COST;}
    private static BigDecimal clean(BigDecimal v){return v==null?null:v.stripTrailingZeros();} private static boolean blank(String s){return s==null||s.isBlank();} private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
    private static boolean same(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.subtract(b).abs().compareTo(new BigDecimal("0.000001"))<=0;} private static BigDecimal divide(BigDecimal n,BigDecimal d){return d.signum()>0?n.divide(d,4,RoundingMode.HALF_UP):null;}
}
