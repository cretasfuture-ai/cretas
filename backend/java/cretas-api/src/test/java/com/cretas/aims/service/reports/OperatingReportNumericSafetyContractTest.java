package com.cretas.aims.service.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cretas.aims.service.reports.OperatingReportSnapshot.*;

class OperatingReportNumericSafetyContractTest {
    @Test void modelMayOnlySelectExistingMetricReferencesAndEnums(){
        var codec=new OperatingReportCodec(new ObjectMapper());
        var snapshot=new OperatingReportSnapshot(SCHEMA,Status.SUCCESS,"daily","2026-01-01","2026-01-01","2026-01-01",List.of(),
                List.of(new Section("profit","毛利",true,"query",List.of(new Metric("profit","毛利",BigDecimal.TEN,"元",null,null,"query.profit")))),List.of(),null);
        String valid="{\"insights\":[{\"kind\":\"REVIEW_RECORDED_VALUE\",\"metricId\":\"profit\",\"action\":\"REVIEW_PROFIT\"}]}";
        assertEquals(1,codec.parseInsights(valid,snapshot).size());
        for(String text:List.of("毛利率提高百分之五。","销售額增加壹佰元。","增幅½","one hundred dollars","99999元")){
            String invalid="{\"insights\":[{\"kind\":\"REVIEW_RECORDED_VALUE\",\"metricId\":\"profit\",\"action\":\"REVIEW_PROFIT\",\"text\":\""+text+"\"}]}";
            assertThrows(IllegalArgumentException.class,()->codec.parseInsights(invalid,snapshot));
            assertThrows(IllegalArgumentException.class,()->codec.parseInsights(text,snapshot));
        }
        assertThrows(IllegalArgumentException.class,()->codec.parseInsights(valid.replace("profit\"","unknown\""),snapshot));
        assertThrows(IllegalArgumentException.class,()->codec.parseInsights(valid.replace("REVIEW_RECORDED_VALUE","MISSING_VALUE"),snapshot));
    }
    @Test void fullProductGroupsRetainProfitExtremesBeyondPythonDisplayLimit(){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<200;i++)rows.add(row("p"+i,"100","0"));
        rows.add(row("true-profit-leader","100000","50000"));
        Map<String,Object> payload=OperatingReportGrossMarginQuery.payload(rows,false);
        @SuppressWarnings("unchecked") var rank=(List<Map<String,Object>>)payload.get("rank");
        assertEquals(201,rank.size());
        assertTrue(rank.stream().anyMatch(p->p.get("productTypeId").equals("true-profit-leader")&&new BigDecimal("50000").equals(p.get("grossProfit"))));
    }
    @Test void reportAdapterUsesExactlyTheExistingSalesPresetAggregatePredicateAndExpressions()throws Exception{
        Path path=Path.of("../../python/smartbi/api/sales_preset.py");
        assertTrue(Files.isRegularFile(path),"Canonical Python source must be present");
        String source=Files.readString(path);
        int method=source.indexOf("async def gross_margin(");
        int start=source.indexOf("SELECT soi.product_type_id",method);
        int end=source.indexOf("\"\"\"",start);
        String canonical=source.substring(start,end).replace("$1","?").replace("$2","?").replace("$3","?");
        assertEquals(normalize(canonical),normalize(OperatingReportGrossMarginQuery.SQL));
        assertFalse(OperatingReportGrossMarginQuery.SQL.toLowerCase().contains("limit"));
    }
    private Map<String,Object> row(String id,String revenue,String cost){Map<String,Object> row=new HashMap<>();row.put("product_type_id",id);row.put("product_name",id);row.put("revenue",new BigDecimal(revenue));row.put("costed_revenue",new BigDecimal(revenue));row.put("cost",new BigDecimal(cost));row.put("costed_line_count",1);return row;}
    private String normalize(String text){return text.strip().replaceAll("\\s+"," ");}
}
