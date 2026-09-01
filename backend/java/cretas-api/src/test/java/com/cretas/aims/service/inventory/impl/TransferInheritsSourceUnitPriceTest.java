package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.inventory.InternalTransferItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调拨明细继承源批次单价 —— owner 2026-09-02 裁定「内部移库不改变成本基础」。
 *
 * <p>🔴 守的是成本基础不会在移库时丢失。原来目标批次的单价只来自请求体, 而界面上那一列
 * 默认留空且被 canViewPrice 门控 ⇒ 仓管不填就 unit_price IS NULL, 下游成本全丢。
 * 本机实拍: 半成品单价 ¥0.4132/kg == 48工时×26元÷3020kg, 即成本里只有人工。
 *
 * <p>⛔ 阴性对照两条(源批次无价 → 保持 null; 请求体已给价 → 不覆盖)不是凑数:
 * 前者防的是「把不知道写成 0」, 后者防的是「把跨厂结算价冲掉」。
 */
class TransferInheritsSourceUnitPriceTest {

    private static void inherit(InternalTransferItem item, String qty, String cost) throws Exception {
        Method m = TransferServiceImpl.class.getDeclaredMethod(
                "inheritSourceUnitPrice", InternalTransferItem.class, BigDecimal.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(newService(), item, new BigDecimal(qty), new BigDecimal(cost));
    }

    /** 只需要一个能调私有方法的实例; 该方法不碰任何注入的协作者。 */
    private static Object newService() throws Exception {
        var ctor = TransferServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return ctor.newInstance(args);
    }

    private static InternalTransferItem item(BigDecimal requestPrice) {
        InternalTransferItem it = new InternalTransferItem();
        it.setMaterialTypeId("RMT-X");
        it.setQuantity(new BigDecimal("100"));
        it.setUnitPrice(requestPrice);
        return it;
    }

    @Test
    @DisplayName("单一源批次: 单价原样继承")
    void singleSourceBatch() throws Exception {
        InternalTransferItem it = item(null);
        inherit(it, "1500", "69750");     // 1500kg × 46.50
        assertThat(it.getUnitPrice()).isEqualByComparingTo("46.5000");
    }

    @Test
    @DisplayName("FEFO 扣了多个批次: 按实际扣减量加权, ⛔ 不取第一个批次的价")
    void weightedAcrossBatches() throws Exception {
        InternalTransferItem it = item(null);
        // 1000kg@40 + 500kg@70 ⇒ (40000+35000)/1500 = 50.0000
        inherit(it, "1500", "75000");
        assertThat(it.getUnitPrice()).isEqualByComparingTo("50.0000");
        assertThat(it.getUnitPrice()).isNotEqualByComparingTo("40");
    }

    @Test
    @DisplayName("阴性对照: 源批次全都没有单价 → 保持 null, ⛔ 不写 0")
    void noSourcePriceStaysNull() throws Exception {
        InternalTransferItem it = item(null);
        inherit(it, "0", "0");
        assertThat(it.getUnitPrice())
                .as("写成 0 会被成本报表当成「这批料不要钱」—— 那是个会一路往下传的假读数")
                .isNull();
    }

    @Test
    @DisplayName("阴性对照: 请求体已给单价 → 不覆盖(跨厂结算价是调拨方主动定的)")
    void explicitRequestPriceWins() throws Exception {
        InternalTransferItem it = item(new BigDecimal("88.00"));
        inherit(it, "1500", "69750");
        assertThat(it.getUnitPrice()).isEqualByComparingTo("88.00");
    }
}
