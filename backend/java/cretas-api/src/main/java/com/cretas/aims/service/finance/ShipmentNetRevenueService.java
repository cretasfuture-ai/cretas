package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ShipmentNetRevenueService {
    private final JdbcTemplate jdbc;
    private final ReturnOrderRepository returns;

    // Read prior confirmed lines as well so cent rounding and the order discount cap carry across periods.
    private static final String DISCOUNT_LINES = """
        WITH period_orders AS (
            SELECT DISTINCT r.sales_order_id
            FROM sales_delivery_items i JOIN sales_delivery_records r ON r.id=i.delivery_record_id
            WHERE r.factory_id=? AND r.delivery_date BETWEEN ? AND ?
              AND r.sales_order_id IS NOT NULL AND
        """ + ShipmentSkuMarginService.CONFIRMED_LINE_FILTER + """
        )
        SELECT r.sales_order_id, r.delivery_date, o.discount_amount, o.total_amount,
               i.sales_order_item_id, oi.id AS linked_order_item_id, i.delivered_quantity, i.unit_price
        FROM sales_delivery_items i JOIN sales_delivery_records r ON r.id=i.delivery_record_id
        LEFT JOIN sales_orders o ON o.id=r.sales_order_id AND o.factory_id=r.factory_id AND o.deleted_at IS NULL
        LEFT JOIN sales_order_items oi ON oi.id=i.sales_order_item_id AND oi.sales_order_id=o.id
            AND oi.product_type_id=i.product_type_id AND oi.deleted_at IS NULL
        WHERE r.factory_id=? AND
        """ + ShipmentSkuMarginService.CONFIRMED_LINE_FILTER + """
          AND (r.sales_order_id IN (SELECT sales_order_id FROM period_orders)
               OR (r.sales_order_id IS NULL AND i.sales_order_item_id IS NOT NULL AND r.delivery_date BETWEEN ? AND ?))
        ORDER BY COALESCE((
            SELECT MIN(e.event_time) FROM batch_lineage_edges e
            WHERE e.factory_id=r.factory_id AND e.target_id=r.id AND e.target_type='SHIPMENT_RECORD'
              AND e.source_type='FINISHED_BATCH' AND e.edge_type='FINISHED_TO_SHIPMENT' AND e.deleted_at IS NULL
              AND jsonb_extract_path_text(e.meta,'origin')='SALES_DELIVERY_SHIPMENT'
              AND jsonb_extract_path_text(e.meta,'shipmentCarrier')='SALES_DELIVERY_RECORD'
        ), r.created_at), r.shipment_sequence, r.id, i.id
        """;

    Adjustments adjustments(String factoryId, LocalDate start, LocalDate end) {
        BigDecimal discounts = allocatedDiscount(factoryId, start, end);
        var completed = returns.findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                factoryId, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, start, end);
        BigDecimal returnedRevenue = BigDecimal.ZERO;
        for (var order : completed) {
            // totalAmount is the saved untaxed value. The separate taxAmount is used by receivable reversals.
            BigDecimal amount = order.getTotalAmount();
            if (amount == null || amount.signum() < 0) {
                throw missing("本期已完成销售退货缺少有效的不含税金额，请核对退货单");
            }
            returnedRevenue = returnedRevenue.add(amount);
        }
        return new Adjustments(discounts, returnedRevenue, !completed.isEmpty());
    }

    private BigDecimal allocatedDiscount(String factoryId, LocalDate start, LocalDate end) {
        return jdbc.query(DISCOUNT_LINES, rs -> {
            // An absent entry starts at zero. A null entry marks incomplete prior shipment evidence.
            Map<String, BigDecimal> priorByOrder = new HashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            while (rs.next()) {
                String order = rs.getString("sales_order_id");
                LocalDate date = rs.getObject("delivery_date", LocalDate.class);
                boolean inPeriod = !date.isBefore(start) && !date.isAfter(end);
                BigDecimal discount = rs.getBigDecimal("discount_amount");
                if (discount == null || discount.signum() < 0) {
                    if (inPeriod) throw missing("发运单缺少有效的本厂订单折扣资料，请核对关联订单与折扣");
                    continue;
                }
                if (discount.signum() == 0) continue;

                BigDecimal orderValue = rs.getBigDecimal("total_amount");
                BigDecimal price = rs.getBigDecimal("unit_price");
                if (orderValue == null || orderValue.signum() <= 0 || price == null || price.signum() < 0
                        || rs.getObject("linked_order_item_id") == null) {
                    priorByOrder.put(order, null);
                }
                BigDecimal prior = priorByOrder.getOrDefault(order, BigDecimal.ZERO);
                if (prior == null) {
                    if (inPeriod) throw missing("订单折扣无法分摊，请核对订单不含税金额、发运单关联的订单行和实发单价");
                    continue;
                }
                BigDecimal throughLine = prior.add(ShipmentSkuMarginService.lineRevenue(
                        rs.getBigDecimal("delivered_quantity"), price));
                if (inPeriod) {
                    total = total.add(ShipmentDiscountAllocation.cumulative(discount, throughLine, orderValue)
                            .subtract(ShipmentDiscountAllocation.cumulative(discount, prior, orderValue)));
                }
                priorByOrder.put(order, throughLine);
            }
            return total;
        }, factoryId, start, end, factoryId, start, end);
    }

    private static BusinessException missing(String message) {
        return new BusinessException(409, message);
    }

    record Adjustments(BigDecimal discount, BigDecimal returns, boolean hasReturns) {}
}
