package com.cretas.aims.service.reports;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class OperatingReportCollector {
    private final PythonSmartBIClient python;
    private final OperatingReportGrossMarginQuery margin;
    private final ArApService aging;
    private final IncomeStatementService income;
    private final MaterialBatchService materials;
    private final MaterialBatchRepository batches;
    private final ObjectMapper mapper;
    public OperatingReportCollector(PythonSmartBIClient python,OperatingReportGrossMarginQuery margin,ArApService aging,
                                    IncomeStatementService income,MaterialBatchService materials,MaterialBatchRepository batches,ObjectMapper mapper){
        this.python=python;this.margin=margin;this.aging=aging;this.income=income;this.materials=materials;this.batches=batches;this.mapper=mapper;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
    public OperatingReportAssembler.Input collect(OperatingReportContext context,String type,LocalDate start,LocalDate end)throws Exception{
        List<OperatingReportSnapshot.SourceReceipt> receipts=new ArrayList<>();
        long days=ChronoUnit.DAYS.between(start,end)+1;
        LocalDate previousStart="monthly".equals(type)?start.minusMonths(1):start.minusDays(days);
        LocalDate previousEnd="monthly".equals(type)?previousStart.withDayOfMonth(previousStart.lengthOfMonth()):end.minusDays(days);
        Map<String,Object> sales=preset(context,"salesperson-performance",start,end,receipts);
        Map<String,Object> previous=preset(context,"salesperson-performance",previousStart,previousEnd,receipts);
        Map<String,Object> lastYear=preset(context,"salesperson-performance",start.minusYears(1),end.minusYears(1),receipts);
        Map<String,Object> region=preset(context,"regional-comparison",start,end,receipts);
        Map<String,Object> high=margin.fetch(context.factoryId(),start,end);
        receipt(receipts,"sales-preset.gross-margin original SQL (all product groups)",start+".."+end);
        @SuppressWarnings("unchecked") List<Map<String,Object>> rank=new ArrayList<>((List<Map<String,Object>>)high.get("rank"));
        rank.sort(Comparator.comparing((Map<String,Object> row)->OperatingReportAssembler.number(row.get("marginRate")),Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder())));
        Map<String,Object> low=Map.of("rank",rank,"summary",high.get("summary"));
        var agingRows=aging.getAgingAnalysis(context.factoryId(),CounterpartyType.CUSTOMER);
        receipt(receipts,"ar-ap/aging?counterpartyType=CUSTOMER","as of "+LocalDate.now()+"; outstanding balance by due date");
        Map<String,Object> incomeData=mapper.convertValue(income.generate(context.factoryId(),start.getYear(),start.getMonthValue(),end.getYear(),end.getMonthValue()),new TypeReference<>(){});
        receipt(receipts,"finance/report/income-statement",start.withDayOfMonth(1)+".."+end.withDayOfMonth(end.lengthOfMonth())+" POSTED-only");
        List<Map<String,Object>> stock=mapper.convertValue(materials.listStockSummary(context.factoryId()),new TypeReference<>(){});
        List<MaterialBatch> raw=batches.findByFactoryId(context.factoryId(),Pageable.unpaged()).getContent();
        Map<String,BigDecimal> coverage=costCoverage(stock,raw);
        receipt(receipts,"material-batches/stock-summary","current company-owned available remainder after reservations; Java repeatable-read");
        return new OperatingReportAssembler.Input(type,start,end,OffsetDateTime.now().toString(),List.copyOf(receipts),sales,previous,lastYear,region,high,low,agingRows,incomeData,stock,coverage);
    }
    private Map<String,Object> preset(OperatingReportContext context,String endpoint,LocalDate start,LocalDate end,List<OperatingReportSnapshot.SourceReceipt> receipts)throws Exception{
        Map<String,String> parameters=new LinkedHashMap<>(Map.of("startDate",start.toString(),"endDate",end.toString()));
        if(endpoint.equals("regional-comparison"))parameters.put("level","province");
        Map<String,Object> result=python.getOperatingSalesPreset(context.factoryId(),context.userId()==null?null:context.userId().toString(),context.userRole(),endpoint,parameters);
        receipt(receipts,"sales-preset/"+endpoint,start+".."+end+(endpoint.equals("regional-comparison")?" province":""));
        return result;
    }
    private void receipt(List<OperatingReportSnapshot.SourceReceipt> receipts,String endpoint,String basis){receipts.add(new OperatingReportSnapshot.SourceReceipt(endpoint,OffsetDateTime.now().toString(),basis));}
    /** Match every stock-summary predicate; denominator comes from the actual grouped query, never a first page. */
    static Map<String,BigDecimal> costCoverage(List<Map<String,Object>> stock,List<MaterialBatch> raw){
        Map<String,Long> included=new HashMap<>(),priced=new HashMap<>();
        for(MaterialBatch batch:raw){
            if(batch.getStatus()==null||Set.of("DEPLETED","USED_UP","EXPIRED","SCRAPPED","DEFECTIVE").contains(batch.getStatus().name()))continue;
            if(batch.getOwnership()!=null&&!batch.getOwnership().name().equals("COMPANY_OWNED"))continue;
            if(batch.getReceiptQuantity()==null||batch.getUsedQuantity()==null||batch.getReservedQuantity()==null)continue;
            if(batch.getReceiptQuantity().subtract(batch.getUsedQuantity()).subtract(batch.getReservedQuantity()).signum()<=0)continue;
            String key=batch.getMaterialTypeId()+":"+batch.getQuantityUnit();
            included.merge(key,1L,Long::sum);if(batch.getUnitPrice()!=null)priced.merge(key,1L,Long::sum);
        }
        Map<String,BigDecimal> result=new HashMap<>();
        for(Map<String,Object> row:stock){
            String key=row.get("materialTypeId")+":"+row.get("unit");BigDecimal count=OperatingReportAssembler.number(row.get("batchCount"));
            BigDecimal observed=BigDecimal.valueOf(included.getOrDefault(key,0L));
            result.put(key,count==null||count.compareTo(observed)!=0?null:OperatingReportAssembler.ratio(BigDecimal.valueOf(priced.getOrDefault(key,0L)),count));
        }
        return result;
    }
}
