package com.cretas.aims.util;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 库存批次「过期」的唯一口径 —— 原料批次与成品批次共用, 不各写一份。
 *
 * <h3>口径: 到期日当天仍可用, 次日起过期</h3>
 * {@code expireDate < today} 才算过期。这是原料定时任务
 * ({@code MaterialBatchRepository#findAllExpiredAvailableBatches}: {@code m.expireDate < :currentDate})
 * 一直以来的口径: 到期日 2026-09-08 的批次在 09-09 02:00 被置 EXPIRED, 即到期日当天仍可投料。
 * 成品沿用同一口径 —— 两种批次对「过期」答出不同的日子, 仓管无从解释。
 *
 * <h3>「今天」是业务日历日</h3>
 * 后端全线东八区 (服务器 Asia/Shanghai)。⛔ 不跟 JVM 默认时区走, ⛔ 不用 UTC 截日:
 * UTC 截日会在东八区 00:00–08:00 把「今天」算成昨天, 正好让刚过期的批次多活 8 小时。
 *
 * <h3>到期日为空</h3>
 * 返回「未过期」, 维持没有本口径之前的行为 —— 这只表示「不因日期拦截」,
 * ⛔ 不表示「有效期充足」: 没有到期日就没有任何关于有效期的结论。
 */
public final class InventoryExpiry {

    /** 业务日历日的时区, 与 {@link WorkProcessTaskOverdue#BUSINESS_ZONE} 同值。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private InventoryExpiry() {
    }

    /** 业务上的「今天」(东八区本地日历日)。 */
    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    /**
     * 纯函数本体: 到期日严格早于 {@code today} 才算过期; 到期日为空 → {@code false}。
     */
    public static boolean isExpired(LocalDate expireDate, LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("today 不能为空 —— 过期判断必须有明确的业务日期");
        }
        return expireDate != null && expireDate.isBefore(today);
    }
}
