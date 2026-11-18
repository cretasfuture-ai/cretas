package com.cretas.aims.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 销售/采购订单 AI 录入：用 2026-11-18 演示前实测到的「模型原始输出」喂给确定性校正，
 * 断言交货日期、完整产品/物料名、供应商、单价四处都被纠正。
 */
class FormOrderInputPostProcessorContractTest {

    private static final LocalDate WED = LocalDate.of(2026, 11, 18);

    private static Map<String, Object> item(String key, String name, Object price) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, name);
        m.put("quantity", 100);
        m.put("unit", "盒");
        if (price != null) m.put("unitPrice", price);
        return m;
    }

    @Test
    @DisplayName("销售：模型给 11-26 + 酱卤牛肉 → 11-27 + 演示·酱卤牛肉250g")
    void salesOrder() {
        String input = "给演示·上海连锁餐饮创建100盒演示·酱卤牛肉250g的订单，下周五交货";
        Map<String, Object> v = new HashMap<>();
        v.put("customerName", "演示·上海连锁餐饮");
        v.put("requiredDeliveryDate", "2026-11-26");
        v.put("items", new ArrayList<>(List.of(item("productName", "酱卤牛肉", 0))));

        boolean changed = FormOrderInputPostProcessor.apply(input, "SALES_ORDER", v, new ArrayList<>(), WED);

        assertThat(changed).isTrue();
        assertThat(v.get("requiredDeliveryDate")).isEqualTo("2026-11-27");
        @SuppressWarnings("unchecked")
        Map<String, Object> it = (Map<String, Object>) ((List<?>) v.get("items")).get(0);
        assertThat(it.get("productName")).isEqualTo("演示·酱卤牛肉250g");
        assertThat(it).doesNotContainKey("unitPrice");
        assertThat(it.get("unit")).isEqualTo("盒");
        assertThat(v.get("customerName")).isEqualTo("演示·上海连锁餐饮");
    }

    @Test
    @DisplayName("采购：供应商=演示(实为名字前缀)、12-01、藤椒调味粉、单价0 → 供应商待补、11-25、演示·藤椒调味粉、无单价")
    void purchaseOrder() {
        String input = "采购50kg演示·藤椒调味粉，下周三交货";
        Map<String, Object> v = new HashMap<>();
        v.put("supplierName", "演示");
        v.put("expectedDeliveryDate", "2026-12-01");
        Map<String, Object> it = item("materialName", "藤椒调味粉", 0);
        it.put("unit", "kg");
        v.put("items", new ArrayList<>(List.of(it)));
        List<String> missing = new ArrayList<>();

        assertThat(FormOrderInputPostProcessor.apply(input, "PURCHASE_ORDER", v, missing, WED)).isTrue();

        assertThat(v).doesNotContainKey("supplierName");
        assertThat(missing).contains("supplierName");
        assertThat(v.get("expectedDeliveryDate")).isEqualTo("2026-11-25");
        assertThat(it.get("materialName")).isEqualTo("演示·藤椒调味粉");
        assertThat(it).doesNotContainKey("unitPrice");
    }

    @Test
    @DisplayName("用户明确说了供应商/单价时一律不动")
    void explicitValuesUntouched() {
        String input = "从演示供应商采购50kg藤椒调味粉，单价12元，下周三交货";
        Map<String, Object> v = new HashMap<>();
        v.put("supplierName", "演示供应商");
        Map<String, Object> it = item("materialName", "藤椒调味粉", 12);
        v.put("items", new ArrayList<>(List.of(it)));
        FormOrderInputPostProcessor.apply(input, "PURCHASE_ORDER", v, new ArrayList<>(), WED);
        assertThat(v.get("supplierName")).isEqualTo("演示供应商");
        assertThat(it.get("unitPrice")).isEqualTo(12);
        assertThat(it.get("materialName")).isEqualTo("藤椒调味粉");
        assertThat(v.get("expectedDeliveryDate")).isEqualTo("2026-11-25");
    }

    @Test
    @DisplayName("名字补全：原文里找不到就不猜；动词不并入前缀")
    void nameRestoreRules() {
        assertThat(FormOrderInputPostProcessor.restoreFullName("采购演示·藤椒调味粉", "藤椒调味粉"))
                .isEqualTo("演示·藤椒调味粉");
        assertThat(FormOrderInputPostProcessor.restoreFullName("卖100盒酱卤牛肉", "酱卤牛肉"))
                .isEqualTo("酱卤牛肉");
        assertThat(FormOrderInputPostProcessor.restoreFullName("卖100盒酱卤牛肉", "红烧肉")).isEqualTo("红烧肉");
        assertThat(FormOrderInputPostProcessor.restoreFullName("酱卤牛肉250g 100盒", "酱卤牛肉"))
                .isEqualTo("酱卤牛肉250g");
    }

    @Test
    @DisplayName("其它实体不受影响")
    void otherEntitiesIgnored() {
        Map<String, Object> v = new HashMap<>();
        assertThat(FormOrderInputPostProcessor.apply("下周五", "PRODUCT", v, null, WED)).isFalse();
    }
}
