package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.exception.BusinessException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阈值 key 的合法集合与归类 —— <b>唯一一份</b>。
 *
 * <h2>为什么提出来</h2>
 *
 * 这套「哪些 key 合法 + 每个 key 归哪一类」原先是
 * {@code FactoryThresholdSetTool} 的两个 private 方法。现在写阈值的入口有两个
 * (AI 工具、对话建厂落库), 各写一份的后果不是重复代码, 是
 * <b>同一个 key 在一条路上合法、在另一条路上 400</b>, 而两边都自称在校验。
 *
 * <h2>合法集合不手抄</h2>
 *
 * 从 {@link ThresholdKeys} 的 public static final String 常量反射取。
 * 手抄一份清单的问题是: 新增 key 时只会改到你正在看的那一处
 * (这条判据今天已经在 {@code PLATFORM_BUILD_ROLES} 上用过一次)。
 *
 * <h2>分类完整性</h2>
 *
 * {@code sales.*} 曾是唯一无法归类的已声明前缀，导致销售分析阈值虽然存在于
 * {@link ThresholdKeys}，却无法通过 API、AI 工具或配置页创建。现在显式归入
 * {@link ThresholdCategory#SALES}；覆盖测试要求所有已声明 key 都必须可归类。
 */
public final class ThresholdKeyCatalog {

    private ThresholdKeyCatalog() {
    }

    /** key → 常量名。反射取, 不手抄。 */
    public static final Map<String, String> ALLOWED_KEYS = Arrays.stream(
                    ThresholdKeys.class.getDeclaredFields())
            .filter(f -> Modifier.isPublic(f.getModifiers())
                    && Modifier.isStatic(f.getModifiers())
                    && f.getType() == String.class)
            .collect(Collectors.toMap(
                    f -> {
                        try {
                            return (String) f.get(null);
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    Field::getName,
                    (a, b) -> a,
                    LinkedHashMap::new));

    public static boolean isKnown(String key) {
        return key != null && ALLOWED_KEYS.containsKey(key);
    }

    /**
     * 校验 + 归类。
     *
     * ⛔ 预览与执行<b>必须走同一份</b> —— 各写一份必然漂成
     * 「预览说能改, 确认时才报错」, 那正是预览要消灭的东西。
     */
    public static ThresholdCategory validateAndCategorize(String key) {
        if (!isKnown(key)) {
            throw new BusinessException(400, "没有这条标准: " + key)
                    .withHintTarget("thresholdKey");
        }
        return categoryOf(key);
    }

    /** 按 key 前缀归类。前缀表与原 {@code FactoryThresholdSetTool#categoryOf} 逐字一致。 */
    public static ThresholdCategory categoryOf(String key) {
        int dot = key.indexOf('.');
        if (dot <= 0) {
            throw new BusinessException(400, "标准 key 缺少前缀(应形如 inventory.xxx): " + key);
        }
        String prefix = key.substring(0, dot);
        return switch (prefix) {
            case "inventory" -> ThresholdCategory.INVENTORY;
            case "iot" -> ThresholdCategory.IOT;
            case "credit" -> ThresholdCategory.CREDIT;
            case "bom" -> ThresholdCategory.BOM;
            case "shortage" -> ThresholdCategory.SHORTAGE;
            case "ai" -> ThresholdCategory.AI;
            case "quality" -> ThresholdCategory.QUALITY;
            case "processing" -> ThresholdCategory.PROCESSING;
            case "production" -> ThresholdCategory.PRODUCTION;
            case "purchase" -> ThresholdCategory.PURCHASE;
            case "procurement" -> ThresholdCategory.PROCUREMENT;
            case "pricing" -> ThresholdCategory.PRICING;
            // "reminder"(收款催办) 语义上属应收账款/财务, 与 labor/efficiency 一并挂 FINANCE。
            case "finance" -> ThresholdCategory.FINANCE;
            case "efficiency" -> ThresholdCategory.EFFICIENCY;
            case "sales" -> ThresholdCategory.SALES;
            default -> throw new BusinessException(400, "无法归类的标准前缀: " + prefix);
        };
    }
}
