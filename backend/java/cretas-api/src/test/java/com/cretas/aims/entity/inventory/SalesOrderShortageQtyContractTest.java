package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「缺料」的口径：还需要、但既没发出去也没预留的量。
 *
 * <h3>为什么需要这一条（2026-08-28 逐屏走查实测）</h3>
 *
 * 原来的算法是 {@code quantity - reservedQty}，<b>不减已发货</b>。发货完成后预留会被释放
 * （{@code reservedQty} 归 0），于是一张全部发完的单子算出来的缺料等于全量，
 * 销售单列表上那个红色 {@code 缺:N} chip 常驻不散。
 *
 * <p>实测 F006 生产库：<b>12 张 COMPLETED 订单，12 张都在报缺</b>（100%）。
 * 例 {@code SO-20260828-0003}：{@code quantity=10 / deliveredQuantity=10 /
 * pendingQuantity=0}，而 {@code shortageQty=10}。
 * 而这个 chip 按原注释的用途是「销售员一眼判断催生产 / 紧急采购」——
 * 它一直在催已经发完的货。
 *
 * <p>⚠️ 正确的量<b>当时就在同一个文件里</b>：{@link SalesOrderItem#getPendingQuantity()}
 * 用的就是 {@code quantity - delivered}。两个概念各写各的，其中一个漏了一项。
 *
 * <h3>判据</h3>
 *
 * 每条正向断言都配一条能让它红的对照：既断言「发完了不该报缺」，也断言
 * 「没发没备<b>就该</b>报缺」——否则把 getter 直接返回 0 也能全绿。
 */
@DisplayName("销售单缺料口径：不把已发货的量再算一次")
class SalesOrderShortageQtyContractTest {

    private static SalesOrderItem item(String quantity, String delivered, String reserved) {
        SalesOrderItem it = new SalesOrderItem();
        it.setQuantity(new BigDecimal(quantity));
        it.setDeliveredQuantity(delivered == null ? null : new BigDecimal(delivered));
        it.setReservedQty(reserved == null ? null : new BigDecimal(reserved));
        return it;
    }

    private static SalesOrder order(SalesOrderStatus status, SalesOrderItem... items) {
        SalesOrder o = new SalesOrder();
        o.setStatus(status);
        o.setItems(List.of(items));
        return o;
    }

    @Test
    @DisplayName("全部发货完成 ⇒ 缺料 0（这一条是 SO-20260828-0003 的真实形状）")
    void fullyDeliveredHasNoShortage() {
        assertThat(item("10", "10", "0").getShortageQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("阴性对照：没发货也没预留 ⇒ 照旧报全量缺料（⛔ 防「直接返回 0」也能过）")
    void nothingDeliveredNorReservedStillReportsShortage() {
        assertThat(item("10", "0", "0").getShortageQty()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("已预留但未发货 ⇒ 缺料 0（预留就是已有着落）")
    void reservedButNotDeliveredHasNoShortage() {
        assertThat(item("10", "0", "10").getShortageQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("部分发货 + 部分预留 ⇒ 只报剩下那一段")
    void partiallyDeliveredAndPartiallyReserved() {
        // 需求 10, 已发 4, 剩下 6 里预留了 3 ⇒ 还缺 3
        assertThat(item("10", "4", "3").getShortageQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("发货 + 预留超过需求 ⇒ clamp 到 0，不出负数")
    void neverNegative() {
        assertThat(item("10", "8", "5").getShortageQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("null 字段按 0 处理，不 NPE")
    void nullsAreTreatedAsZero() {
        assertThat(item("10", null, null).getShortageQty()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("订单级聚合 = 各行之和")
    void orderLevelSumsItems() {
        SalesOrder o = order(SalesOrderStatus.FINANCE_APPROVED,
                item("10", "0", "0"), item("5", "5", "0"), item("8", "2", "3"));
        // 10 + 0 + 3
        assertThat(o.getTotalShortageQty()).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("已取消的订单恒 0 —— 作废的单子不该让人去催货")
    void cancelledOrderReportsNoShortage() {
        SalesOrder o = order(SalesOrderStatus.CANCELLED, item("100", "0", "0"));
        assertThat(o.getTotalShortageQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("阴性对照：同样的行，状态不是 CANCELLED 时照旧报缺")
    void sameItemsStillReportShortageWhenNotCancelled() {
        SalesOrder o = order(SalesOrderStatus.DRAFT, item("100", "0", "0"));
        assertThat(o.getTotalShortageQty()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("缺料与「未发货量」不是同一个量：预留过的行两者会不同")
    void shortageIsNotPendingQuantity() {
        SalesOrderItem it = item("10", "0", "10");
        assertThat(it.getPendingQuantity()).isEqualByComparingTo("10"); // 还没发出去
        assertThat(it.getShortageQty()).isEqualByComparingTo("0");      // 但已经有着落
    }
}
