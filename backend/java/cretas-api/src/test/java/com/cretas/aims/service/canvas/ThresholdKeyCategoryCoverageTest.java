package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.ThresholdCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 钉住「{@link ThresholdKeys} 声明的每个 key，都有一个能装它的 {@link ThresholdCategory}」。
 *
 * <h2>它守的是一个真实发生过、且没有任何东西会报的裂缝</h2>
 * 2026-08-28 实测：{@code ThresholdKeys} 已声明 15 个前缀的 key，
 * 而 {@code ThresholdCategory} 只有 6 个枚举值。后果是
 * {@code CanvasThresholdsController.parseCategory} 拒绝其余 9 类，
 * <b>那 38 个 key 在 API / UI 上一行都创建不出来</b> ——
 * 「这些阈值是可配置的」这件事对它们从来没成立过。
 *
 * <p>⚠️ <b>它不报错、不抛异常、不变红。</b> 编译通过、单测全绿、
 * 那些 service 照常用 hard-coded fallback 跑得好好的。
 * 唯一的症状是「管理员在界面上找不到这一项」，而没有人会去逐项数。
 *
 * <h2>为什么这道闸是「反向」的</h2>
 * 它<b>不</b>要求每个枚举值都有 key（先加分类、后加阈值是合法的开发顺序）。
 * 它只要求<b>反过来</b>不成立：不许存在「声明了 key 却没有分类装它」。
 * 前者会制造无谓的红，后者才是真缺陷。
 *
 * <p>⛔ 若本用例红了，正确的修法是<b>补枚举值</b>（加值是安全方向），
 * ⛔ 不是把 key 从 {@code ThresholdKeys} 里删掉 —— 那是把温度计砸了。
 */
class ThresholdKeyCategoryCoverageTest {

    /**
     * 从 {@code ThresholdKeys} 的 public static final String 常量里取出所有 key 值。
     *
     * <p>⚠️ 用反射读<b>常量的值</b>，不是文本 grep 源码 ——
     * grep 会把 javadoc 里<b>提到</b>某个 key 的那一行也数进来（本仓记过这个坑）。
     */
    private Map<String, String> declaredKeys() {
        Map<String, String> byConstantName = new LinkedHashMap<>();
        for (Field f : ThresholdKeys.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    || !Modifier.isFinal(f.getModifiers())
                    || f.getType() != String.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof String s && !s.isBlank()) {
                    byConstantName.put(f.getName(), s);
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError("读不到常量 " + f.getName(), e);
            }
        }
        return byConstantName;
    }

    /** {@code "sales.delivery.over_ship_rate"} → {@code "SALES"}。 */
    private String categoryPrefixOf(String key) {
        int dot = key.indexOf('.');
        return (dot < 0 ? key : key.substring(0, dot)).toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("阳性对照: 确实读到了 key —— 若这里为 0, 后面那条断言就是恒真式")
    void thereAreKeysToCheck() {
        Map<String, String> keys = declaredKeys();
        assertThat(keys)
                .as("反射一个常量都没读到 ⇒ 仪器坏了, 本类的其余断言全部无意义")
                .hasSizeGreaterThan(30);
        assertThat(keys.values())
                .as("key 必须长成 {category}.{subdomain}.{name}")
                .allMatch(k -> k.contains("."), "含分隔点");
    }

    @Test
    @DisplayName("🔴 每个已声明 key 的分类前缀, 都必须有对应的 ThresholdCategory 枚举值")
    void everyDeclaredKeyHasABackingCategory() {
        Set<String> known = Arrays.stream(ThresholdCategory.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, Set<String>> orphansByPrefix = new LinkedHashMap<>();
        declaredKeys().forEach((constantName, key) -> {
            String prefix = categoryPrefixOf(key);
            if (!known.contains(prefix)) {
                orphansByPrefix.computeIfAbsent(prefix, ignored -> new TreeSet<>()).add(key);
            }
        });

        if (!orphansByPrefix.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "以下阈值 key 的分类在 ThresholdCategory 里不存在 ⇒ 它们在 API/UI 上【建不出来】,\n"
                            + "永远只能走代码里的 hard-coded fallback:\n\n");
            orphansByPrefix.forEach((prefix, keys) -> {
                sb.append("  分类 ").append(prefix).append("  (").append(keys.size()).append(" 个 key)\n");
                keys.forEach(k -> sb.append("      ").append(k).append('\n'));
            });
            sb.append("\n修法: 在 ThresholdCategory 补上这些枚举值(加值是安全方向),\n")
              .append("      并同步 web-admin/src/api/thresholdsHub.ts 里那份平行的 TS 联合类型。\n")
              .append("⛔ 不要把 key 从 ThresholdKeys 删掉来让本用例变绿。\n")
              .append("⚠️ 补完枚举再写 seed 迁移 —— category 是 @Enumerated(STRING),\n")
              .append("   库里出现枚举没有的值会让整个阈值中心的查询在反序列化时抛异常。");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("本次新增的超发上限 key 确实落在 SALES 分类下 (防止改名后静默失配)")
    void overShipRateKeyIsUnderSales() {
        assertThat(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE).isEqualTo("sales.delivery.over_ship_rate");
        assertThat(categoryPrefixOf(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE))
                .isEqualTo(ThresholdCategory.SALES.name());
    }
}
