package com.cretas.aims.entity.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— BOM 行的缓存成本 {@code item_cost} 必须与<b>它自己的输入</b>对得上。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25 生产库实测）</h2>
 *
 * {@code item_cost} 是缓存字段（{@code actualQuantity × factor × unitPrice}），
 * 而带价这一步<b>没有刷新它</b>。调用方的顺序是：
 * <pre>
 * applyDtoToItem(dto, item);              // 末尾 setItemCost(computeItemCost())  ← 用 DTO 的空单价算
 * applyMaterialMasterPricing(item, ...);  // 【之后】才从物料主数据带真实单价, 却不重算
 * </pre>
 * 于是 {@code itemCost} 来自 DTO、{@code unitPrice} 来自物料主数据 ——
 * 两个字段各自都「对」，合起来必然不一致。本仓形态 D：同一个东西两份，必定漂。
 *
 * <h3>当天的实测读数</h3>
 * 全库 8 行可算的 BOM 行里 <b>5 行不一致（62.5%）</b>，其中 3 行是
 * {@code item_cost=0} 而 {@code unit_price>0}：
 * <pre>
 * 成品盒  qty 1.0000  单价 0.0100  存的成本 0.0000  应为 0.0100
 * 封膜    qty 0.0500  单价 8.8500  存的成本 0.0000  应为 0.4425
 * 外箱    qty 0.1250  单价 0.4400  存的成本 0.0000  应为 0.0550
 * 原料行  qty 1.2500  单价 18.0693 存的成本 23.1250 应为 22.5866
 * </pre>
 *
 * <p>后果是成本归集读到一堆 0，把「算得出来的成本」当成「零成本」——
 * 这正是 2026-08-25 那轮「成品单位成本为空 / 包材成本算成 0」的上游。
 */
class BomItemCostMatchesItsInputsContractTest {

    private static BomRecipeItem item(String qty, String factor, String price) {
        BomRecipeItem it = new BomRecipeItem();
        it.setStandardQuantity(qty == null ? null : new BigDecimal(qty));
        it.setYieldRate(new BigDecimal("100.00"));
        it.setQuantityToPriceFactor(factor == null ? null : new BigDecimal(factor));
        it.setUnitPrice(price == null ? null : new BigDecimal(price));
        return it;
    }

    @Test
    @DisplayName("🔴 成本 = 实际用量 × 换算系数 × 单价 (逐条对上生产库那三行)")
    void costEqualsQuantityTimesFactorTimesPrice() {
        // 成品盒 / 封膜 / 外箱 —— 数字与 259cb14f 那张 BOM 逐字一致
        assertEquals(0, new BigDecimal("0.0100")
                .compareTo(item("1.0000", "1", "0.0100").computeItemCost()), "成品盒");
        assertEquals(0, new BigDecimal("0.4425")
                .compareTo(item("0.0500", "1", "8.8500").computeItemCost()), "封膜");
        assertEquals(0, new BigDecimal("0.0550")
                .compareTo(item("0.1250", "1", "0.4400").computeItemCost()), "外箱");
    }

    @Test
    @DisplayName("🔴 阴性对照: 单价未知时成本必须是 null, 不许是 0")
    void unknownPriceYieldsNullNotZero() {
        assertNull(item("1.0000", "1", null).computeItemCost(),
                "缺价算成 0 会让下游把它当成一个确定的数 —— 那正是本仓禁止的假数据");
    }

    @Test
    @DisplayName("阴性对照: 没配参考用量时是「尚未归集」, 也不是 0")
    void unknownQuantityYieldsNull() {
        assertNull(item(null, "1", "8.8500").computeItemCost());
    }

    @Test
    @DisplayName("阳性对照: 换算系数真的参与计算 (否则上面几条可能只是碰巧相等)")
    void factorActuallyParticipates() {
        BigDecimal withFactor = item("2", "1000", "0.5").computeItemCost();
        BigDecimal withoutFactor = item("2", "1", "0.5").computeItemCost();
        assertNotNull(withFactor);
        assertTrue(withFactor.compareTo(withoutFactor) > 0,
                "factor=1000 与 factor=1 算出同一个数 —— 换算系数没进公式");
    }

    /**
     * 🔴 接线闸：带价那一步必须<b>就地重算</b>缓存成本。
     *
     * <p>这条守的不是公式（上面几条守公式），而是<b>谁负责刷新</b>：
     * 改了 {@code unitPrice} 却不刷新 {@code itemCost}，公式再对也没用。
     */
    @Test
    @DisplayName("🔴 applyMaterialMasterPricing 带完价必须重算 itemCost")
    void pricingStepRecomputesTheCachedCost() throws Exception {
        Path src = Path.of("src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java");
        String code = Files.readString(src)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");

        int at = code.indexOf("private void applyMaterialMasterPricing(");
        assertTrue(at > 0, "找不到带价方法 —— 这道闸的范围钉不住");
        int end = code.indexOf("\n    private ", at + 1);
        String body = end > at ? code.substring(at, end) : code.substring(at);

        // 阳性对照: 方法体真的取到了 (否则下面 contains 全是恒假)
        assertTrue(body.contains("setUnitPrice("),
                "方法体范围不对, 里面连 setUnitPrice 都没有: " + body.length() + " 字符");

        assertTrue(body.contains("setItemCost(item.computeItemCost())"),
                "带完价没有重算 itemCost —— item_cost 会停留在带价【之前】那个值, "
                        + "与同一行的 unit_price 必然对不上(实测生产库 8 行里 5 行不一致)");
    }
}
