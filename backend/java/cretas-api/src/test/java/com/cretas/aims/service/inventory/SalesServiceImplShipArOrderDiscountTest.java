package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 订单级折扣在<b>分批发货</b>下怎么摊到每一次应收挂账。
 *
 * <h1>缺口是什么</h1>
 * 应收按<b>发货行</b>算（{@code taxInclusiveShippedValue}），而 {@code discountAmount}
 * 是<b>订单级</b>的。2026-08-29 之前它<b>完全不参与</b>那个算式 ——
 * 有折扣的订单整单发完，应收会比 {@code order.getPayableAmount()} 整整多出一个折扣额。
 *
 * <p>⚠️ 生产实测 63 张订单 <b>0 张</b>用订单级折扣 ⇒ 这是理论缺口，不是在燃的洞。
 * 也正因为如此，本次改动对存量是<b>零行为变化</b>（{@link ZeroImpactOnExistingData} 钉住这一条）。
 *
 * <h1>选定的口径（javadoc 在 {@code SalesServiceImpl.orderDiscountShareForShipment}）</h1>
 * <ul>
 *   <li><b>按发货未税金额占订单未税总额的比例摊</b> —— 依据是
 *       {@code getPayableAmount() = 未税 − 折扣 + 税}，折扣是扣在未税上的、不影响税额。</li>
 *   <li><b>累计摊派</b>处理取整残差：{@code 本次 = round(D×累计/总) − round(D×先前/总)}。
 *       ⛔ 不是「最后一批兜底」（要预知哪一批是最后一批 —— 那是未来的信息），
 *       ⛔ 也不是「逐批 HALF_UP」（Σ 会漂几分，整单发完后应收 ≠ 应付）。</li>
 * </ul>
 *
 * <h1>本类能证明什么</h1>
 * 全部是纯算术，直接反射调 {@code taxInclusiveShippedValue}，
 * 唯一的协作者是 {@code deliveryRecordRepository.findBySalesOrderId}（查兄弟发货单）。
 * ⇒ 这些断言是真的行为断言，不是接线断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceImplShipArOrderDiscountTest {

    @Mock com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository deliveryRecordRepository;

    private static final String ORDER_ID = "SO-DISC-1";

    private SalesServiceImpl service() {
        SalesServiceImpl svc = org.mockito.Mockito.mock(SalesServiceImpl.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(svc, "deliveryRecordRepository", deliveryRecordRepository);
        return svc;
    }

    private BigDecimal receivable(SalesServiceImpl svc, SalesOrder order, SalesDeliveryRecord record) {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                svc, "taxInclusiveShippedValue", order, record);
    }

    /** 订单: 未税 net, 税 tax, 折扣 discount; 一行, 税率 taxRate(%)。 */
    private SalesOrder order(String net, String tax, String discount, String taxRate) {
        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setTotalAmount(new BigDecimal(net));
        o.setTaxAmount(new BigDecimal(tax));
        if (discount != null) o.setDiscountAmount(new BigDecimal(discount));
        SalesOrderItem oi = new SalesOrderItem();
        ReflectionTestUtils.setField(oi, "id", 1L);
        oi.setTaxRate(taxRate == null ? null : new BigDecimal(taxRate));
        o.setItems(new ArrayList<>(List.of(oi)));
        return o;
    }

    /** 一次发货: qty × unitPrice(未税), 挂在订单行 1 上。 */
    private SalesDeliveryRecord delivery(String id, String qty, String unitPrice) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", id);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.SHIPPED);
        SalesDeliveryItem it = new SalesDeliveryItem();
        ReflectionTestUtils.setField(it, "id", 1L);
        it.setSalesOrderItemId(1L);
        it.setDeliveredQuantity(new BigDecimal(qty));
        it.setUnitPrice(new BigDecimal(unitPrice));
        d.setItems(new ArrayList<>(List.of(it)));
        d.setTotalAmount(new BigDecimal(qty).multiply(new BigDecimal(unitPrice))
                .setScale(2, java.math.RoundingMode.HALF_UP));
        return d;
    }

    private void siblings(SalesDeliveryRecord... records) {
        when(deliveryRecordRepository.findBySalesOrderId(anyString()))
                .thenReturn(new ArrayList<>(List.of(records)));
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 自洽判据: 整单一次发完 ⇒ 应收 == order.getPayableAmount()")
    class WholeOrderInOneShipment {

        @Test
        @DisplayName("未税 1000 + 税 117 − 折扣 100 = 1017.00 (改动前是 1117.00, 多记一个折扣额)")
        void oneShotEqualsPayable() {
            SalesOrder o = order("1000.00", "117.00", "100.00", "11.7");
            SalesDeliveryRecord d = delivery("DLV-1", "100", "10.00");   // 未税 1000.00
            siblings(d);

            assertThat(receivable(service(), o, d))
                    .as("🔴 这是本次改动的承重断言: 整单发完时挂账必须等于应付额")
                    .isEqualByComparingTo("1017.00");
            assertThat(o.getPayableAmount())
                    .as("与实体自己的权威口径逐分一致")
                    .isEqualByComparingTo("1017.00");
        }

        @Test
        @DisplayName("阴性对照: 同一笔订单去掉折扣 → 1117.00 (证明那 100.00 确实是折扣扣出来的)")
        void withoutDiscount_noDeduction() {
            SalesOrder o = order("1000.00", "117.00", null, "11.7");
            SalesDeliveryRecord d = delivery("DLV-1", "100", "10.00");
            siblings(d);

            assertThat(receivable(service(), o, d))
                    .as("没有这条对照, 上一条的 1017.00 可能只是算式凑巧")
                    .isEqualByComparingTo("1117.00");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 分批发货: 累计摊派, 且 Σ 各批 == 折扣总额")
    class SplitShipments {

        /**
         * 订单 1000 未税 / 税 117 / 折扣 100，分三批发 30% / 30% / 40%。
         * 每批各自的等比份额是 30 / 30 / 40，加起来正好 100，没有残差 —— 先钉这个干净的格。
         */
        @Test
        @DisplayName("三批 30/30/40: 折扣份额 30.00 / 30.00 / 40.00, 合计 == 100.00")
        void threeBatches_sumsToDiscount() {
            SalesOrder o = order("1000.00", "117.00", "100.00", "11.7");
            SalesDeliveryRecord b1 = delivery("DLV-1", "30", "10.00");   // 未税 300.00
            SalesDeliveryRecord b2 = delivery("DLV-2", "30", "10.00");
            SalesDeliveryRecord b3 = delivery("DLV-3", "40", "10.00");   // 未税 400.00
            SalesServiceImpl svc = service();

            // 第 1 批: 先前无发货
            siblings(b1);
            BigDecimal r1 = receivable(svc, o, b1);
            // 第 2 批: 先前有 b1
            siblings(b1, b2);
            BigDecimal r2 = receivable(svc, o, b2);
            // 第 3 批: 先前有 b1 b2
            siblings(b1, b2, b3);
            BigDecimal r3 = receivable(svc, o, b3);

            // 折扣前含税: 300×1.117=335.10 / 335.10 / 400×1.117=446.80
            assertThat(r1).isEqualByComparingTo("305.10");   // 335.10 − 30.00
            assertThat(r2).isEqualByComparingTo("305.10");
            assertThat(r3).isEqualByComparingTo("406.80");   // 446.80 − 40.00

            assertThat(r1.add(r2).add(r3))
                    .as("🔴 三批应收合计必须等于应付额 1017.00 —— 一分不差")
                    .isEqualByComparingTo("1017.00");
        }

        /**
         * 🔴 取整残差那一格 —— 这条是「累计摊派 vs 逐批 HALF_UP」的分水岭。
         *
         * <p>订单未税 100.00 / 折扣 10.00 / 分三批各 1/3（33.33 / 33.33 / 33.34）。
         * <b>逐批 HALF_UP</b> 会摊出 3.33 + 3.33 + 3.33 = <b>9.99</b>，差 1 分永远对不上。
         * <b>累计摊派</b>：round(10×0.3333)=3.33 → round(10×0.6666)=6.67（本批 3.34）
         * → round(10×1.0000)=10.00（本批 3.33），合计 <b>10.00</b>。
         */
        @Test
        @DisplayName("🔴 残差格 (33.33/33.33/33.34): 累计摊派合计 10.00, 逐批 HALF_UP 会是 9.99")
        void roundingResidue_isAbsorbedByCumulativeAllocation() {
            SalesOrder o = order("100.00", "0.00", "10.00", "0");
            SalesDeliveryRecord b1 = delivery("DLV-1", "3333", "0.01");   // 33.33
            SalesDeliveryRecord b2 = delivery("DLV-2", "3333", "0.01");   // 33.33
            SalesDeliveryRecord b3 = delivery("DLV-3", "3334", "0.01");   // 33.34
            SalesServiceImpl svc = service();

            siblings(b1);
            BigDecimal r1 = receivable(svc, o, b1);
            siblings(b1, b2);
            BigDecimal r2 = receivable(svc, o, b2);
            siblings(b1, b2, b3);
            BigDecimal r3 = receivable(svc, o, b3);

            assertThat(r1).isEqualByComparingTo("30.00");   // 33.33 − 3.33
            assertThat(r2)
                    .as("🔴 第二批摊 3.34 (不是 3.33) —— 累计摊派把残差在【发生的时候】就吸收掉了, "
                            + "⛔ 不需要知道哪一批是最后一批")
                    .isEqualByComparingTo("29.99");         // 33.33 − 3.34
            assertThat(r3).isEqualByComparingTo("30.01");   // 33.34 − 3.33

            assertThat(r1.add(r2).add(r3))
                    .as("🔴 合计 == 未税 100 − 折扣 10 = 90.00。逐批 HALF_UP 会得到 90.01")
                    .isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("阴性对照: 已取消/已退回的兄弟发货单不占折扣额度")
        void cancelledSiblingsDoNotConsumeDiscount() {
            SalesOrder o = order("1000.00", "0.00", "100.00", "0");
            SalesDeliveryRecord cancelled = delivery("DLV-X", "50", "10.00");
            cancelled.setStatus(SalesDeliveryStatus.CANCELLED);
            SalesDeliveryRecord mine = delivery("DLV-1", "100", "10.00");
            siblings(cancelled, mine);

            assertThat(receivable(service(), o, mine))
                    .as("被取消那 500 若算进累计, 本批只能摊到剩下的一半折扣 → 950.00")
                    .isEqualByComparingTo("900.00");
        }

        @Test
        @DisplayName("🔴 超发不多分折扣: 发到订单量的 150% 时折扣仍封顶在 100.00")
        void overShipmentDoesNotEarnMoreDiscount() {
            SalesOrder o = order("1000.00", "0.00", "100.00", "0");
            SalesDeliveryRecord d = delivery("DLV-1", "150", "10.00");   // 未税 1500.00
            siblings(d);

            assertThat(receivable(service(), o, d))
                    .as("折扣是整单谈定的一笔钱, 多发的货没有理由分走更多 ⇒ 1500 − 100")
                    .isEqualByComparingTo("1400.00");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 诚实的降级: 摊不了就不摊, ⛔ 不猜")
    class HonestDegradation {

        @Test
        @DisplayName("订单未税总额为 0 → 没有分母, 不扣折扣 (⛔ 不兜底成「全摊到本次」)")
        void zeroOrderNet_doesNotAllocate() {
            SalesOrder o = order("0.00", "0.00", "100.00", "0");
            SalesDeliveryRecord d = delivery("DLV-1", "10", "10.00");
            siblings(d);

            assertThat(receivable(service(), o, d))
                    .as("把「不知道」变成一个很有说服力的数, 正是本仓反复踩的那个形态")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("折扣为 0 → 与没有折扣完全一致")
        void zeroDiscount_isANoOp() {
            SalesOrder o = order("1000.00", "0.00", "0.00", "0");
            SalesDeliveryRecord d = delivery("DLV-1", "100", "10.00");
            siblings(d);
            assertThat(receivable(service(), o, d)).isEqualByComparingTo("1000.00");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("🔴 对生产存量零影响 (63/63 订单无折扣)")
    class ZeroImpactOnExistingData {

        @Test
        @DisplayName("无折扣的订单【连兄弟发货单都不查】—— 摊派逻辑在这一格上完全不执行")
        void noDiscount_neverTouchesTheRepository() {
            SalesOrder o = order("1000.00", "117.00", null, "11.7");
            SalesDeliveryRecord d = delivery("DLV-1", "100", "10.00");

            assertThat(receivable(service(), o, d)).isEqualByComparingTo("1117.00");

            verifyNoInteractions(deliveryRecordRepository);
            // ⚠️ 这条比「金额没变」更硬: 金额没变也可能是查了一圈又算回来。
            //    「一次查询都没发生」才证明这条新路径对存量是真的零影响 (也零性能开销)。
        }
    }
}
