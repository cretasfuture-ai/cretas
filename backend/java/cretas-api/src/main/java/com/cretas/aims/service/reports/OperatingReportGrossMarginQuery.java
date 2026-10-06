package com.cretas.aims.service.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.Date;
import java.util.*;
import static com.cretas.aims.service.reports.OperatingReportAssembler.*;

/** Report-only adapter: original sales_preset aggregate SQL, without its display limit. */
@Component
public class OperatingReportGrossMarginQuery {
    public static final String SQL = """

            SELECT soi.product_type_id,
                   MAX(soi.product_name) AS product_name,
                   MAX(soi.unit) AS unit,
                   SUM(COALESCE(soi.quantity, 0)) AS total_qty,
                   COUNT(DISTINCT soi.sales_order_id) AS order_count,
                   COUNT(*) AS line_count,
                   COUNT(*) FILTER (WHERE soi.unit_price IS NULL) AS missing_price_line_count,
                   COUNT(*) FILTER (WHERE soi.unit_price IS NOT NULL
                                      AND soi.cost_unit_price IS NOT NULL) AS costed_line_count,
                   COUNT(*) FILTER (WHERE soi.unit_price IS NOT NULL
                                      AND soi.cost_unit_price = 0) AS zero_cost_line_count,
                   SUM(COALESCE(soi.quantity, 0) * soi.unit_price
                       * (1 - COALESCE(soi.discount_rate, 0) / 100))
                     FILTER (WHERE soi.unit_price IS NOT NULL) AS revenue,
                   SUM(COALESCE(soi.quantity, 0) * soi.unit_price
                       * (1 - COALESCE(soi.discount_rate, 0) / 100))
                     FILTER (WHERE soi.unit_price IS NOT NULL
                               AND soi.cost_unit_price IS NOT NULL) AS costed_revenue,
                   SUM(COALESCE(soi.quantity, 0) * soi.cost_unit_price)
                     FILTER (WHERE soi.unit_price IS NOT NULL
                               AND soi.cost_unit_price IS NOT NULL) AS cost
            FROM sales_order_items soi
            JOIN sales_orders so ON so.id = soi.sales_order_id
            WHERE so.factory_id = ? AND so.order_date BETWEEN ? AND ?
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
              AND soi.deleted_at IS NULL
            GROUP BY soi.product_type_id
            
            """;
    private final JdbcTemplate jdbc;
    public OperatingReportGrossMarginQuery(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> fetch(String factoryId,LocalDate start,LocalDate end){
        return payload(jdbc.queryForList(SQL,factoryId,Date.valueOf(start),Date.valueOf(end)),false);
    }
    public static Map<String,Object> payload(List<Map<String,Object>> rows,boolean ascending){
        List<Map<String,Object>> products=new ArrayList<>();
        BigDecimal revenue=BigDecimal.ZERO,costedRevenue=BigDecimal.ZERO,cost=BigDecimal.ZERO;
        int costedProducts=0,costsPresent=0;
        for(Map<String,Object> row:rows){
            Map<String,Object> product=new LinkedHashMap<>();
            BigDecimal rowRevenue=zero(number(row.get("revenue")));
            BigDecimal rowCostedRevenue=zero(number(row.get("costed_revenue")));
            BigDecimal rowCost=number(row.get("cost"));
            BigDecimal costedLines=number(row.get("costed_line_count"));
            if(costedLines==null||costedLines.signum()==0) rowCost=null;
            BigDecimal profit=rowCost==null?null:rowCostedRevenue.subtract(rowCost);
            BigDecimal margin=ratio(profit,rowCostedRevenue);
            product.put("productTypeId",row.get("product_type_id"));product.put("productName",row.get("product_name"));
            product.put("unit",row.get("unit"));product.put("quantity",row.get("total_qty"));
            product.put("orderCount",row.get("order_count"));product.put("lineCount",row.get("line_count"));
            product.put("costedLineCount",costedLines);product.put("zeroCostLineCount",number(row.get("zero_cost_line_count")));product.put("missingPriceLineCount",number(row.get("missing_price_line_count")));product.put("revenue",rowRevenue);product.put("costedRevenue",rowCostedRevenue);
            product.put("cost",rowCost);product.put("grossProfit",profit);product.put("marginRate",margin);
            product.put("costCoverage",ratio(rowCostedRevenue,rowRevenue)); products.add(product);
            revenue=revenue.add(rowRevenue);costedRevenue=costedRevenue.add(rowCostedRevenue);
            if(rowCost!=null){cost=cost.add(rowCost);costsPresent++;} if(margin!=null)costedProducts++;
        }
        products.sort(Comparator.comparing((Map<String,Object> p)->number(p.get("marginRate")),Comparator.nullsLast(ascending?Comparator.<BigDecimal>naturalOrder():Comparator.<BigDecimal>reverseOrder())));
        Map<String,Object> summary=new LinkedHashMap<>();
        BigDecimal totalCost=costsPresent==0?null:cost,profit=totalCost==null?null:costedRevenue.subtract(totalCost);
        summary.put("productCount",products.size());summary.put("costedProductCount",costedProducts);summary.put("revenue",revenue);
        summary.put("costedRevenue",costedRevenue);summary.put("cost",totalCost);summary.put("grossProfit",profit);
        summary.put("marginRate",ratio(profit,costedRevenue));summary.put("costCoverage",ratio(costedRevenue,revenue));
        return Map.of("rank",products,"summary",summary);
    }
    private static BigDecimal zero(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
