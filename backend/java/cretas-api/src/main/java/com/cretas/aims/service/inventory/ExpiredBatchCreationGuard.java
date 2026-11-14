package com.cretas.aims.service.inventory;

import com.cretas.aims.exception.BusinessException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 迁移 P5 R35: 建批次时到期日已早于今天 —— 允许建, 但必须确认并写原因, 原因记入批次
 * ({@code material_batches.expired_on_create_reason})。没过期的批次不需要也不保存原因。
 */
public final class ExpiredBatchCreationGuard {

    public static final int REASON_MIN = 2;
    public static final int REASON_MAX = 200;

    /** 「今天」按北京时间 (服务器 JVM 是 UTC, 北京 00:00–08:00 用 LocalDate.now() 会差一天)。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private ExpiredBatchCreationGuard() {
    }

    /** 北京时间的今天。收货日 / 过期判断都用它, ⛔ 不用 {@code LocalDate.now()} (那是服务器时区)。 */
    public static LocalDate businessToday(Clock clock) {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    /**
     * @return 过期时 = 去掉首尾空白的原因 (落库); 没过期 / 没有到期日 = null。
     * @throws BusinessException 409 过期却没写原因 (或原因不足 2 字 / 超过 200 字)。
     */
    public static String requireReasonIfExpired(LocalDate expireDate, LocalDate today, String reason) {
        if (expireDate == null || today == null || !expireDate.isBefore(today)) return null;
        long days = ChronoUnit.DAYS.between(expireDate, today);
        String r = reason == null ? "" : reason.strip();
        if (r.length() < REASON_MIN) {
            throw new BusinessException(409, "该批次到期日 " + expireDate + " 已过期 " + days + " 天，仍要建需要写原因")
                    .withCode("EXPIRED_BATCH_REASON_REQUIRED")
                    .withHint("确认仍要入库就在「已过期原因」里写一句（至少 2 个字），原因会记在批次上；本次没有增加库存")
                    .withHintTarget("expiredReason");
        }
        if (r.length() > REASON_MAX) {
            throw new BusinessException(400, "已过期原因最多 " + REASON_MAX + " 个字")
                    .withCode("EXPIRED_BATCH_REASON_TOO_LONG")
                    .withHint("请把原因缩短到 " + REASON_MAX + " 个字以内")
                    .withHintTarget("expiredReason");
        }
        return r;
    }
}
