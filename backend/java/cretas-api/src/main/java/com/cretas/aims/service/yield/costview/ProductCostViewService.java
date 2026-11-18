package com.cretas.aims.service.yield.costview;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成本新视图 (口径 {@link ProductUnitCostAggregator#BASIS_LABEL}): 只读、复用 OrderCostBreakdownService.compute
 * 的材料/人工/产出, 不改动任何现有成本数字。
 */
@Service
@RequiredArgsConstructor
public class ProductCostViewService {

    static final int MAX_ORDERS = 200;
    private static final BigDecimal SIXTY = new BigDecimal("60");
    static final String SECRET_REF = "cost-view";

    private final SalesOrderRepository salesOrderRepository;
    private final OrderCostBreakdownService breakdownService;
    private final ProcessSecretCostMask secretMask;

    /**
     * @param truncated 区间内订单超过 {@value #MAX_ORDERS} 张时为 true: 加权数只代表前 includedOrders 张,
     *                  页面必须如实显示「仅统计前 N 张」。
     */
    public record ByProductResult(String basis, String basisLabel, int totalOrders, int includedOrders,
                                  boolean truncated, boolean priceMasked,
                                  List<ProductUnitCostAggregator.Group> groups) {
    }

    public ByProductResult byProduct(String factoryId, LocalDate start, LocalDate end, boolean maskPrice) {
        List<SalesOrder> orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, start, end);
        int total = orders.size();
        boolean truncated = total > MAX_ORDERS;
        if (truncated) orders = orders.subList(0, MAX_ORDERS);
        List<ProductUnitCostAggregator.OrderPoint> points = new ArrayList<>();
        for (SalesOrder so : orders) {
            if (so.getId() == null) continue;
            OrderCostBreakdownDTO cb = breakdownService.compute(factoryId, so.getId(), maskPrice);
            if (cb == null || !cb.isHasData()) continue;
            points.add(toPoint(cb, maskPrice));
        }
        List<ProductUnitCostAggregator.Group> groups = ProductUnitCostAggregator.aggregate(points);

        // 工艺保密: 无权者看不到保密产品的任何金额 (与 /cost-breakdown 同一把尺: ProcessSecretCostMask)
        Set<String> ids = new LinkedHashSet<>();
        for (ProductUnitCostAggregator.Group g : groups) if (g.productTypeId() != null) ids.add(g.productTypeId());
        Set<String> hidden = secretMask.hiddenFor(factoryId, ids, SECRET_REF);
        boolean anyHidden = !hidden.isEmpty() || secretMask.anyHidden(factoryId, SECRET_REF);
        List<ProductUnitCostAggregator.Group> out = new ArrayList<>();
        for (ProductUnitCostAggregator.Group g : groups) {
            boolean mask = g.productTypeId() == null ? anyHidden : hidden.contains(g.productTypeId());
            out.add(mask ? g.maskedForSecret() : g);
        }
        return new ByProductResult(ProductUnitCostAggregator.BASIS, ProductUnitCostAggregator.BASIS_LABEL, total,
                orders.size(), truncated, maskPrice, out);
    }

    static ProductUnitCostAggregator.OrderPoint toPoint(OrderCostBreakdownDTO cb, boolean maskPrice) {
        boolean hasLabor = cb.getLaborDetails() != null && !cb.getLaborDetails().isEmpty();
        boolean customerSupplied = cb.getCustomerSuppliedItemCount() != null && cb.getCustomerSuppliedItemCount() > 0;
        // productTypeId 为 null = 一张单挂了多个计划/产品 (上游 uniqueOrNull), 不挑一个冒充
        return new ProductUnitCostAggregator.OrderPoint(cb.getOrderId(), cb.getOrderNumber(), cb.getProductTypeId(),
                cb.getProductName(), cb.getOutputUnit(), cb.getRawMaterialCost(), cb.getLaborCost(),
                cb.getOutputQuantity(), cb.getMissingCostItems(), hasLabor, customerSupplied, maskPrice);
    }

    public LaborProcessAllocator.Result laborAllocation(String factoryId, String orderId, boolean maskPrice) {
        if (maskPrice) {
            return new LaborProcessAllocator.Result(LaborProcessAllocator.Status.PRICE_MASKED, null, null, List.of());
        }
        OrderCostBreakdownDTO cb = breakdownService.compute(factoryId, orderId, false);
        if (cb == null || !cb.isHasData()) {
            return new LaborProcessAllocator.Result(LaborProcessAllocator.Status.LABOR_TOTAL_UNKNOWN, null, null,
                    List.of());
        }
        boolean hidden = cb.getProductTypeId() == null
                ? secretMask.anyHidden(factoryId, SECRET_REF)
                : !secretMask.hiddenFor(factoryId, List.of(cb.getProductTypeId()), SECRET_REF).isEmpty();
        if (hidden) {
            return new LaborProcessAllocator.Result(LaborProcessAllocator.Status.SECRET_HIDDEN, null, null,
                    List.of());
        }
        // 分摊总额 = 本单各工序实际人工金额之和 (本单可见部分); 订单人工合计里多出来的是上游转入, 单列
        BigDecimal own = BigDecimal.ZERO;
        boolean ownKnown = cb.getLaborDetails() != null && !cb.getLaborDetails().isEmpty();
        if (ownKnown) {
            for (OrderCostBreakdownDTO.LaborDetail d : cb.getLaborDetails()) {
                if (d.getAmount() == null) {
                    ownKnown = false;
                    break;
                }
                own = own.add(d.getAmount());
            }
        }
        LaborProcessAllocator.Result r = LaborProcessAllocator.allocate(ownKnown ? own : null,
                processHours(cb.getLaborDetails()));
        BigDecimal upstream = null;
        if (ownKnown && cb.getLaborCost() != null && cb.getLaborCost().subtract(own).signum() > 0) {
            upstream = cb.getLaborCost().subtract(own).setScale(2, RoundingMode.HALF_UP);
        }
        return new LaborProcessAllocator.Result(r.status(), r.laborCostTotal(), upstream, r.shares());
    }

    /** 按工序汇总人时 (laborMinutes/60); 某工序的明细里出现 null 分钟 → 该工序人时未知 (null)。 */
    static List<LaborProcessAllocator.ProcessHours> processHours(List<OrderCostBreakdownDTO.LaborDetail> details) {
        Map<Integer, BigDecimal> hours = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        if (details != null) {
            for (OrderCostBreakdownDTO.LaborDetail d : details) {
                int key = d.getProcessOrder() == null ? -1 : d.getProcessOrder();
                names.putIfAbsent(key, d.getProcessName());
                if (d.getLaborMinutes() == null) {
                    hours.put(key, null);
                    continue;
                }
                if (hours.containsKey(key) && hours.get(key) == null) continue;
                BigDecimal h = BigDecimal.valueOf(d.getLaborMinutes()).divide(SIXTY, 4, RoundingMode.HALF_UP);
                hours.merge(key, h, BigDecimal::add);
            }
        }
        List<LaborProcessAllocator.ProcessHours> out = new ArrayList<>();
        hours.forEach((k, v) -> out.add(new LaborProcessAllocator.ProcessHours(k == -1 ? null : k, names.get(k), v)));
        out.sort((a, b) -> Integer.compare(a.processOrder() == null ? -1 : a.processOrder(),
                b.processOrder() == null ? -1 : b.processOrder()));
        return out;
    }
}
