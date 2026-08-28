package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 #292 × #302 的<b>共同盲区</b>钉子：含税单据 + 实发 ≠ 订单。
 *
 * <h2>为什么必须单独有这一类</h2>
 * 两个 PR 各自都对，但**朴素合并会静默退回未税**，而且**两边的测试都不会红**：
 *
 * <table border="1">
 *   <tr><th>PR</th><th>它修的</th><th>它的样本</th><th>为什么盖不住这一格</th></tr>
 *   <tr><td>#292</td><td>口径: 应收要含税</td><td>实发 == 订单</td>
 *       <td>数量基准错了也测不出来</td></tr>
 *   <tr><td>#302</td><td>数量基准: 按实发</td><td>税额 == 0</td>
 *       <td>口径退回未税也测不出来</td></tr>
 * </table>
 *
 * ⇒ 只有「**同时**含税 且 实发≠订单」这一格能同时钉住两者。本类就是那一格。
 *
 * <h2>数字取自生产实测</h2>
 * <ul>
 *   <li>单价 22.1239 未税 / 税率 13% ⇒ 含税单价 25.00（{@code SO-20260828-0003} 的真实行）</li>
 *   <li>超发 {@code SO-20260828-0012}: 订 2 发 5 ⇒ 库存真扣 5，而旧应收只记 44.25</li>
 *   <li>少发 {@code SO-20260828-0013}: 订 4 发 2 ⇒ 旧应收记 88.50（按订单 4 盒）</li>
 * </ul>
 *
 * <h2>⛔ 这一类会红的四种改法（每一种都是真实的退化）</h2>
 * <ol>
 *   <li>取 {@code order.getPayableAmount()}（只有 #292）→ 数量基准退回订单</li>
 *   <li>取 {@code record.getTotalAmount()}（只有 #302）→ 口径退回未税</li>
 *   <li>取 {@code order.getTotalAmount()}（都不要）→ 两个缺陷都回来</li>
 *   <li>手写 {@code net.add(taxAmount)} → 税额是订单级的，按发货行摊不了</li>
 * </ol>
 */
@DisplayName("应收合并口径: 实发量 × 含税单价（#292 × #302 的共同盲区）")
class SalesServiceImplShipArMergedCaliberTest {

    /** 生产实测的真实行: 未税单价 22.1239 @13% ⇒ 含税 25.00/盒。 */
    private static final BigDecimal NET_UNIT_PRICE = new BigDecimal("22.1239");
    private static final BigDecimal TAX_RATE_13 = new BigDecimal("13.00");
    private static final Long ORDER_ITEM_ID = 809L;

    /** 只需要那个纯函数，不必搭 shipDelivery 的一长串仓储桩。 */
    private BigDecimal shippedValue(SalesOrder order, SalesDeliveryRecord record) {
        SalesServiceImpl svc = org.mockito.Mockito.mock(SalesServiceImpl.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                svc, "taxInclusiveShippedValue", order, record);
    }

    private SalesOrder order(String orderedQty, BigDecimal taxRate) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", "SO-MERGED-0001");
        o.setFactoryId("F006");
        o.setCustomerId("CUST-1");
        SalesOrderItem oi = new SalesOrderItem();
        ReflectionTestUtils.setField(oi, "id", ORDER_ITEM_ID);
        oi.setQuantity(new BigDecimal(orderedQty));
        oi.setUnitPrice(NET_UNIT_PRICE);
        oi.setTaxRate(taxRate);
        oi.setUnit("盒");
        o.setItems(new ArrayList<>(List.of(oi)));
        // 订单头: 未税 = 量 × 未税单价, 税额 = 13%
        BigDecimal net = new BigDecimal(orderedQty).multiply(NET_UNIT_PRICE)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        o.setTotalAmount(net);
        o.setTaxAmount(taxRate == null ? BigDecimal.ZERO
                : net.multiply(taxRate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
        return o;
    }

    private SalesDeliveryRecord delivery(String actualQty, Long orderItemId) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", "DLV-MERGED-0001");
        d.setFactoryId("F006");
        d.setDeliveryNumber("DLV-MERGED-0001");
        SalesDeliveryItem di = new SalesDeliveryItem();
        ReflectionTestUtils.setField(di, "id", 1L);
        di.setSalesOrderItemId(orderItemId);
        di.setDeliveredQuantity(new BigDecimal(actualQty));
        di.setUnitPrice(NET_UNIT_PRICE);
        di.setUnit("盒");
        d.setItems(new ArrayList<>(List.of(di)));
        // 发货单额 = Σ 实发 × **未税**单价 —— 这正是不能直接拿来挂账的那个数
        d.setTotalAmount(new BigDecimal(actualQty).multiply(NET_UNIT_PRICE)
                .setScale(2, java.math.RoundingMode.HALF_UP));
        return d;
    }

    // ── 盲区本体: 含税 且 实发 ≠ 订单 ───────────────────────────────────

    @Test
    @DisplayName("超发 订2发5 @13%: 应收 125.00 (⛔ 不是 #292 的 50.00, 也不是 #302 的 110.62)")
    void overShipment_isActualQuantityAtTaxInclusivePrice() {
        SalesOrder o = order("2", TAX_RATE_13);
        SalesDeliveryRecord d = delivery("5", ORDER_ITEM_ID);

        assertThat(shippedValue(o, d))
                .as("5 盒 × 含税 25.00 = 125.00；订单口径给 50.00(少 75)，发货单未税给 110.62(少一个税)")
                .isEqualByComparingTo(new BigDecimal("125.00"));

        // 把两个「单独取一侧」的错值也钉出来, 让退化一眼可见
        assertThat(o.getPayableAmount())
                .as("#292 单独: 订单价税合计 = 50.00 —— 数量基准是错的")
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(d.getTotalAmount())
                .as("#302 单独: 发货单额 = 110.62 —— 是未税的")
                .isEqualByComparingTo(new BigDecimal("110.62"));
    }

    @Test
    @DisplayName("少发 订4发2 @13%: 应收 50.00 (⛔ 不是 #292 的 100.01, 也不是 #302 的 44.25)")
    void underShipment_isActualQuantityAtTaxInclusivePrice() {
        SalesOrder o = order("4", TAX_RATE_13);
        SalesDeliveryRecord d = delivery("2", ORDER_ITEM_ID);

        assertThat(shippedValue(o, d))
                .as("2 盒 × 含税 25.00 = 50.00；订单口径给 100.01(多收客户一倍)")
                .isEqualByComparingTo(new BigDecimal("50.00"));
        // ⚠️ 100.01 不是笔误: 订单头是【先把整单未税取整再乘税率】
        //    (4 × 22.1239 = 88.4956 → 88.50, ×13% = 11.505 → 11.51, 合 100.01),
        //    而逐行算是 2 × 25.00 = 50.00。这与生产实测的 SO-20260828-0013 完全一致
        //    (未税 88.50 / 税 11.51 / 含税 100.01)。
        //    ⇒ 订单级取整与行级取整本来就会差一分, 这也是「不能拿订单额当发货额」的又一个理由。
        assertThat(o.getPayableAmount()).isEqualByComparingTo(new BigDecimal("100.01"));
        assertThat(d.getTotalAmount()).isEqualByComparingTo(new BigDecimal("44.25"));
    }

    // ── 回归护栏 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("整单发完 @13%: 与订单价税合计一致 (证明没有把正常单据算漂)")
    void exactShipment_matchesOrderPayableAmount() {
        SalesOrder o = order("10", TAX_RATE_13);
        SalesDeliveryRecord d = delivery("10", ORDER_ITEM_ID);
        // 10 × 22.1239 = 221.24 (行级取整) → ×1.13 = 250.00
        assertThat(shippedValue(o, d)).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(shippedValue(o, d)).isEqualByComparingTo(o.getPayableAmount());
    }

    @Test
    @DisplayName("零税率 订2发5: 退化成纯实发额, 不凭空造税")
    void zeroTaxRate_isPlainActualValue() {
        SalesOrder o = order("2", BigDecimal.ZERO);
        SalesDeliveryRecord d = delivery("5", ORDER_ITEM_ID);
        assertThat(shippedValue(o, d)).isEqualByComparingTo(new BigDecimal("110.62"));
    }

    @Test
    @DisplayName("发货行挂不上订单行 → 按 0 税率只算未税, ⛔ 不猜税率")
    void unmatchedLine_doesNotGuessTaxRate() {
        SalesOrder o = order("2", TAX_RATE_13);
        SalesDeliveryRecord d = delivery("5", 999L);   // 对不上任何订单行
        assertThat(shippedValue(o, d))
                .as("宁可少算, 也不凭空造一笔税")
                .isEqualByComparingTo(new BigDecimal("110.62"));
    }

    @Test
    @DisplayName("行无单价 → 该行贡献 0, 让上游诚实跳过挂账")
    void missingUnitPrice_contributesZero() {
        SalesOrder o = order("2", TAX_RATE_13);
        SalesDeliveryRecord d = delivery("5", ORDER_ITEM_ID);
        d.getItems().get(0).setUnitPrice(null);
        assertThat(shippedValue(o, d)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
