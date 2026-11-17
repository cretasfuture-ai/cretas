package com.cretas.aims.service.yield.costview;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本新视图 (口径名见 {@link ProductUnitCostAggregator#BASIS}): 只读、复用 OrderCostBreakdownService.compute
 * 的材料/人工/产出, 不改动任何现有成本数字。
 */
@Service
@RequiredArgsConstructor
public class ProductCostViewService {

    private static final int MAX_ORDERS = 200;
    private static final BigDecimal SIXTY = new BigDecimal("60");

    private final SalesOrderRepository salesOrderRepository;
    private final OrderCostBreakdownService breakdownService;

    /** 价格被脱敏时材料/人工为 null → 全部不可核算, 不会泄漏金额。 */
    public List<ProductUnitCostAggregator.Group> byProduct(String factoryId, LocalDate start, LocalDate end,
                                                            boolean maskPrice) {
        List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, start, end);
        if (orders.size() > MAX_ORDERS) orders = orders.subList(0, MAX_ORDERS);
        List<ProductUnitCostAggregator.OrderPoint> points = new ArrayList<>();
        for (SalesOrder so : orders) {
            if (so.getId() == null) continue;
            OrderCostBreakdownDTO cb = breakdownService.compute(factoryId, so.getId(), maskPrice);
            if (cb == null || !cb.isHasData()) continue;
            points.add(toPoint(cb));
        }
        return ProductUnitCostAggregator.aggregate(points);
    }

    static ProductUnitCostAggregator.OrderPoint toPoint(OrderCostBreakdownDTO cb) {
        return new ProductUnitCostAggregator.OrderPoint(cb.getOrderId(), cb.getOrderNumber(),
                cb.getProductTypeId() != null ? cb.getProductTypeId() : cb.getProductSku(), cb.getProductName(),
                cb.getOutputUnit(), cb.getRawMaterialCost(), cb.getLaborCost(), cb.getOutputQuantity());
    }

    public LaborProcessAllocator.Result laborAllocation(String factoryId, String orderId, boolean maskPrice) {
        OrderCostBreakdownDTO cb = breakdownService.compute(factoryId, orderId, maskPrice);
        if (cb == null || !cb.isHasData()) {
            return new LaborProcessAllocator.Result(LaborProcessAllocator.Status.LABOR_TOTAL_UNKNOWN, null, List.of());
        }
        return LaborProcessAllocator.allocate(cb.getLaborCost(), processHours(cb.getLaborDetails()));
    }

    /** 按工序汇总人时 (laborMinutes/60); 某工序的明细里出现 null 分钟 → 该工序人时未知 (null)。 */
    static List<LaborProcessAllocator.ProcessHours> processHours(List<OrderCostBreakdownDTO.LaborDetail> details) {
        Map<Integer, BigDecimal> hours = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        if (details != null) {
            for (OrderCostBreakdownDTO.LaborDetail d : details) {
                names.putIfAbsent(d.getProcessOrder(), d.getProcessName());
                if (d.getLaborMinutes() == null) {
                    hours.put(d.getProcessOrder(), null);
                    continue;
                }
                if (hours.containsKey(d.getProcessOrder()) && hours.get(d.getProcessOrder()) == null) continue;
                BigDecimal h = BigDecimal.valueOf(d.getLaborMinutes()).divide(SIXTY, 4, RoundingMode.HALF_UP);
                hours.merge(d.getProcessOrder(), h, BigDecimal::add);
            }
        }
        List<LaborProcessAllocator.ProcessHours> out = new ArrayList<>();
        hours.forEach((k, v) -> out.add(new LaborProcessAllocator.ProcessHours(k, names.get(k), v)));
        out.sort((a, b) -> Integer.compare(a.processOrder() == null ? 0 : a.processOrder(),
                b.processOrder() == null ? 0 : b.processOrder()));
        return out;
    }
}
