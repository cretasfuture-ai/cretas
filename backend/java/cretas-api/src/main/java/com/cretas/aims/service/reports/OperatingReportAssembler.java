package com.cretas.aims.service.reports;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import static com.cretas.aims.service.reports.OperatingReportSnapshot.*;

/** Assemble existing query payloads without replacing missing observations with zero. */
@Component
public class OperatingReportAssembler {
    public record Input(String type, LocalDate start, LocalDate end, String fetchedAt,
                        List<SourceReceipt> receipts, Map<String,Object> sales,
                        Map<String,Object> previous, Map<String,Object> lastYear,
                        Map<String,Object> region, Map<String,Object> marginHigh,
                        Map<String,Object> marginLow, List<Map<String,Object>> aging,
                        Map<String,Object> income, List<Map<String,Object>> inventory,
                        Map<String,BigDecimal> inventoryCostCoverage) { }

    public OperatingReportSnapshot assemble(Input input) {
        List<Section> sections = new ArrayList<>();
        Map<String,Object> summary = map(input.sales().get("summary"));
        Map<String,Object> unassigned = map(input.sales().get("unassigned"));
        BigDecimal orderCount = number(summary.get("orderCount"));
        BigDecimal assigned = number(summary.get("assignedOrderCount"));
        BigDecimal assignmentCoverage = ratio(assigned, orderCount);
        sections.add(section("orders", "销售订单与数据覆盖", false, "排除 DRAFT/CANCELLED 与软删除订单；销售订单期间口径", List.of(
                metric("sales.orders", "订单数", orderCount, "单", "订单数缺失", null, "salesperson-performance.summary.orderCount"),
                metric("sales.assigned", "已填业务员订单数", assigned, "单", "业务员信息缺失", assignmentCoverage, "salesperson-performance.summary.assignedOrderCount"),
                metric("sales.unassigned", "未填业务员订单数", number(unassigned.get("orderCount")), "单", "业务员归属未知", assignmentCoverage, "salesperson-performance.unassigned.orderCount"))));
        List<Metric> sales = new ArrayList<>();
        BigDecimal taxed = number(summary.get("totalAmountWithTax"));
        List<Map<String,Object>> ranks = rows(input.sales().get("rank"));
        List<Map<String,Object>> allSales = new ArrayList<>(ranks); allSales.add(unassigned);
        sales.add(metric("sales.taxed", "含税销售额", taxed, "元", "销售额缺失", null, "salesperson-performance.summary.totalAmountWithTax"));
        sales.add(metric("sales.untaxed", "未税销售额", sum(allSales, "taxableAmount"), "元", "未税销售额缺失", null, "sum(salesperson-performance.rank.taxableAmount)+unassigned.taxableAmount"));
        sales.add(metric("sales.tax", "税额", sum(allSales, "taxAmount"), "元", "税额缺失", null, "sum(salesperson-performance.rank.taxAmount)+unassigned.taxAmount"));
        BigDecimal previous = number(map(input.previous().get("summary")).get("totalAmountWithTax"));
        BigDecimal lastYear = number(map(input.lastYear().get("summary")).get("totalAmountWithTax"));
        sales.add(metric("sales.previous", "上一期间含税销售额", previous, "元", "上一期间缺失", null, "previous.salesperson-performance.summary.totalAmountWithTax"));
        sales.add(metric("sales.lastYear", "去年同期含税销售额", lastYear, "元", "去年同期缺失", null, "lastYear.salesperson-performance.summary.totalAmountWithTax"));
        sales.add(metric("sales.mom", "环比", percentage(growth(taxed, previous)), "%", "上一期间基数缺失或不大于零", null, "(current-previous)/previous*100"));
        sales.add(metric("sales.yoy", "同比", percentage(growth(taxed, lastYear)), "%", "去年同期基数缺失或不大于零", null, "(current-lastYear)/lastYear*100"));
        sections.add(section("sales", "销售额、环比与同比", true, "含税=未税+税额；比较期间见取数来源。没有订单的已查询期间可为零，零基数增长率不可计算。", sales));

        List<Metric> people = new ArrayList<>();
        for (Map<String,Object> row : ranks) {
            String id = String.valueOf(row.get("salespersonId"));
            String name = text(row.get("salespersonName"), "业务员姓名缺失");
            people.add(metric("salesperson."+id+".sales", name+" 含税销售额", number(row.get("totalAmountWithTax")), "元", "销售额缺失", assignmentCoverage, "salesperson-performance.rank["+id+"].totalAmountWithTax"));
            people.add(metric("salesperson."+id+".orders", name+" 订单数", number(row.get("orderCount")), "单", "订单数缺失", assignmentCoverage, "salesperson-performance.rank["+id+"].orderCount"));
        }
        people.add(metric("salesperson.unassigned", "未归属业务员销售额（不列入个人排行）", number(unassigned.get("totalAmountWithTax")), "元", "未填写业务员", assignmentCoverage, "salesperson-performance.unassigned.totalAmountWithTax"));
        sections.add(section("salespeople", "业务员排行", true, "按既有 salesperson-performance 含税销售额排序；覆盖率按订单归属计算。", people));

        List<Metric> regions = new ArrayList<>();
        BigDecimal regionCoverage = number(map(input.region().get("summary")).get("recognizedShare"));
        for (Map<String,Object> row : rows(input.region().get("regions"))) {
            String id=String.valueOf(row.get("region")); String name=text(row.get("label"), "地区名称缺失");
            regions.add(metric("province."+id+".sales", name+" 含税销售额", number(row.get("totalAmountWithTax")), "元", "地区销售额缺失", regionCoverage, "regional-comparison.regions["+id+"].totalAmountWithTax"));
            regions.add(metric("province."+id+".yoy", name+" 同比", percentage(number(row.get("yoyRate"))), "%", "去年同期基数缺失或不大于零", regionCoverage, "regional-comparison.regions["+id+"].yoyRate*100"));
        }
        regions.add(metric("province.noAddress", "地址未填销售额（无法归省）", number(map(input.region().get("noAddress")).get("totalAmountWithTax")), "元", "订单与客户均无地址", regionCoverage, "regional-comparison.noAddress.totalAmountWithTax"));
        regions.add(metric("province.unrecognized", "无法识别地区销售额（不列入省份排行）", number(map(input.region().get("unrecognized")).get("totalAmountWithTax")), "元", "地区识别失败", regionCoverage, "regional-comparison.unrecognized.totalAmountWithTax"));
        sections.add(section("provinces", "省份销售排行", true, "完全复用 address_region 地址优先级与识别口径；覆盖率为识别省份的含税金额占比。", regions));

        Map<String,Object> marginSummary=map(input.marginHigh().get("summary"));
        BigDecimal costCoverage=number(marginSummary.get("costCoverage"));
        sections.add(section("margin", "产品毛利与成本覆盖", true, "仅已回填成本的折后未税收入减成本；缺成本不当零。毛利率分母为已回填成本的收入。", List.of(
                metric("margin.revenue", "产品折后未税收入", number(marginSummary.get("revenue")), "元", "收入缺失", costCoverage, "gross-margin.summary.revenue"),
                metric("margin.costedRevenue", "已回填成本的收入", number(marginSummary.get("costedRevenue")), "元", "成本覆盖收入缺失", costCoverage, "gross-margin.summary.costedRevenue"),
                metric("margin.cost", "已回填成本", number(marginSummary.get("cost")), "元", "成本缺失", costCoverage, "gross-margin.summary.cost"),
                metric("margin.profit", "已覆盖部分毛利", number(marginSummary.get("grossProfit")), "元", "成本缺失", costCoverage, "gross-margin.summary.grossProfit"),
                metric("margin.rate", "已覆盖部分毛利率", percentage(number(marginSummary.get("marginRate"))), "%", "成本缺失或分母不大于零", costCoverage, "gross-margin.summary.marginRate*100"))));
        sections.add(productRank("marginHigh", "产品毛利率高榜", input.marginHigh(), false));
        sections.add(productRank("marginLow", "产品毛利率低榜", input.marginLow(), false));
        sections.add(productRank("profitHigh", "产品毛利金额高榜", input.marginHigh(), true));
        sections.add(productRank("profitLow", "产品毛利金额低榜", input.marginHigh(), true));

        String[] buckets={"current","days1_30","days31_60","days61_90","days91_120","days120plus","total"};
        String[] names={"未到期","逾期1至30天","逾期31至60天","逾期61至90天","逾期91至120天","逾期120天以上","应收合计"};
        List<Metric> aging=new ArrayList<>();
        for(int i=0;i<buckets.length;i++) aging.add(metric("aging."+buckets[i], names[i], sum(input.aging(),buckets[i]), "元", "账龄数据缺失", null, "sum(aging.CUSTOMER."+buckets[i]+")"));
        BigDecimal over90=add(sum(input.aging(),"days91_120"),sum(input.aging(),"days120plus"));
        aging.add(metric("aging.over90", "90天以上应收（坏账风险观察项）", over90, "元", "账龄完整性覆盖未知", null, "sum(days91_120)+sum(days120plus)"));
        aging.add(metric("aging.over90Share", "90天以上应收占比", percentage(ratio(over90,sum(input.aging(),"total"))), "%", "应收合计缺失或不大于零", null, "over90/total*100"));
        sections.add(section("aging", "应收账龄与坏账风险", true, "截至取数当天，按到期日、未冲销余额复用 ArApService /aging；旧记录缺余额时沿接口取挂账原值。逾期不等于已确认坏账；坏账计提数据覆盖未知。", aging));

        boolean costsAvailable=Boolean.TRUE.equals(input.income().get("costDataAvailable"));
        List<Metric> profit=new ArrayList<>();
        for(String key:List.of("totalRevenue","totalCost","grossProfit","totalExpense","operatingProfit","incomeTax","netProfit","pendingDraftVoucherCount")) {
            boolean needsCost=Set.of("totalCost","grossProfit","operatingProfit","netProfit").contains(key);
            String name=switch(key){case "totalRevenue"->"已过账营业收入";case "totalCost"->"已过账营业成本";case "grossProfit"->"利润表毛利";case "totalExpense"->"期间费用";case "operatingProfit"->"营业利润";case "incomeTax"->"所得税";case "netProfit"->"净利润";default->"待过账草稿凭证";};
            profit.add(metric("income."+key,name,needsCost&&!costsAvailable?null:number(input.income().get(key)),key.equals("pendingDraftVoucherCount")?"份":"元",needsCost&&!costsAvailable?"无已过账成本，利润不可确认；覆盖率未知":"已过账凭证口径，凭证完整性覆盖未知",null,"income-statement."+key));
        }
        sections.add(section("income", "盈利与利润表", true, "复用 POSTED-only income-statement；日/周报此段展示覆盖所在自然月的月度财务口径，非日利润。期间："+input.start().withDayOfMonth(1)+" 至 "+input.end().withDayOfMonth(input.end().lengthOfMonth()), profit));

        List<Metric> quantities=new ArrayList<>(), values=new ArrayList<>();
        for(Map<String,Object> row:input.inventory()) {
            String id=String.valueOf(row.get("materialTypeId"))+":"+row.get("unit"); String name=text(row.get("materialName"),"物料名称缺失");
            quantities.add(metric("inventory."+id+".quantity",name+" 公司自有批次可用余量（扣预留）",number(row.get("totalQuantity")),text(row.get("unit"),"单位缺失"),"可用余量缺失",null,"stock-summary["+id+"].totalQuantity"));
            quantities.add(metric("inventory."+id+".batches",name+" 同口径批次数",number(row.get("batchCount")),"批","批次数缺失",null,"stock-summary["+id+"].batchCount"));
            BigDecimal coverage=input.inventoryCostCoverage().get(id);
            boolean complete=coverage!=null&&coverage.compareTo(BigDecimal.ONE)==0;
            values.add(metric("inventory."+id+".value",name+" 存货价值",complete?number(row.get("totalValue")):null,"元",complete?null:"存在缺单价批次或价格覆盖率未知；不可把部分估值当总价值",coverage,"stock-summary["+id+"].totalValue"));
        }
        sections.add(section("inventory", "存货数量", false, "取数时点公司自有批次可用余量，扣除已用与预留；按现有 stock-summary 范围和称重单位分组。非物理库存总量或历史日末结存。", quantities));
        sections.add(section("inventoryValue", "存货估值与成本覆盖", true, "复用公司自有批次 stock-summary，按其既有范围（不声明排除 WIP）；价值只在完全相同余量/单位范围所有批次有单价时确认。成品与客户自有存货无同口径估值，覆盖率未知。", values));
        return new OperatingReportSnapshot(SCHEMA,Status.SUCCESS,input.type(),input.start().toString(),input.end().toString(),input.fetchedAt(),input.receipts(),sections,null,null);
    }

    private Section productRank(String id,String title,Map<String,Object> payload,boolean byProfit) {
        List<Map<String,Object>> rank=new ArrayList<>(rows(payload.get("rank")));
        if(byProfit) rank.sort(Comparator.comparing((Map<String,Object> row)->number(row.get("grossProfit")),Comparator.nullsLast(id.equals("profitLow")?Comparator.<BigDecimal>naturalOrder():Comparator.<BigDecimal>reverseOrder())));
        List<Metric> metrics=new ArrayList<>();
        for(Map<String,Object> row:rank.stream().limit(10).toList()) {
            String product=String.valueOf(row.get("productTypeId")),name=text(row.get("productName"),"产品名称缺失"); BigDecimal coverage=number(row.get("costCoverage"));
            metrics.add(metric(id+"."+product+".profit",name+" 毛利",number(row.get("grossProfit")),"元","成本缺失",coverage,"gross-margin.rank["+product+"].grossProfit"));
            metrics.add(metric(id+"."+product+".rate",name+" 毛利率",percentage(number(row.get("marginRate"))),"%","成本缺失或分母不大于零",coverage,"gross-margin.rank["+product+"].marginRate*100"));
        }
        BigDecimal productCount=number(map(payload.get("summary")).get("productCount"));
        String basis=byProfit?"从既有 gross-margin 返回产品按毛利金额排序":"完全复用 gross-margin 的高/低毛利率排序";
        if(byProfit&&productCount!=null&&productCount.compareTo(BigDecimal.valueOf(rank.size()))>0) basis+="；仅接口返回产品范围，未覆盖全体产品，不能视为全厂高低榜";
        return section(id,title,true,basis,metrics);
    }
    private Section section(String id,String title,boolean price,String basis,List<Metric> metrics){return new Section(id,title,price,basis,metrics);}
    private Metric metric(String id,String label,BigDecimal value,String unit,String missing,BigDecimal coverage,String path){return new Metric(id,label,value,unit,value==null?missing:missing!=null&&missing.contains("覆盖未知")?missing:null,coverage,path);}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Map.of();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object value){return value instanceof List?(List<Map<String,Object>>)value:List.of();}
    private String text(Object value,String missing){return value==null||value.toString().isBlank()?missing:value.toString();}
    public static BigDecimal number(Object value){if(value==null)return null;try{return new BigDecimal(value.toString());}catch(NumberFormatException error){return null;}}
    public static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator){return numerator==null||denominator==null||denominator.signum()<=0?null:numerator.divide(denominator,8,RoundingMode.HALF_UP);}
    public static BigDecimal growth(BigDecimal current,BigDecimal base){return current==null||base==null?null:ratio(current.subtract(base),base);}
    private BigDecimal percentage(BigDecimal ratio){return ratio==null?null:ratio.multiply(BigDecimal.valueOf(100));}
    private BigDecimal add(BigDecimal a,BigDecimal b){return a==null||b==null?null:a.add(b);}
    private BigDecimal sum(List<Map<String,Object>> rows,String key){BigDecimal total=BigDecimal.ZERO;for(Map<String,Object> row:rows){BigDecimal value=number(row.get(key));if(value==null)return null;total=total.add(value);}return total;}
}
