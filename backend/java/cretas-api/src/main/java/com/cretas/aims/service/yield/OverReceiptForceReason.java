package com.cretas.aims.service.yield;

import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纪律排查 A10: 报工产出超过超收容差上限时「强制提交」必须写原因并留痕 (同配比偏离 / 回料超限)。
 * 以前只认一个 {@code forceSubmit} 布尔值, 放行时只写一行日志, 事后查不到是谁、为什么超收。
 *
 * <p>唯一的判定处: {@code YieldReportServiceImpl} 在确认超收且 forceSubmit=true 后调用 {@link #auditOrRefuse},
 * 拿到的快照并入 {@code production_reports.custom_fields.overReceipt}。
 */
public final class OverReceiptForceReason {

    /** 前端据此弹「选超收原因」的错误码。 */
    public static final String REASON_REQUIRED = "OVER_RECEIPT_REASON_REQUIRED";
    static final int MAX_REASON_LENGTH = 500;

    private OverReceiptForceReason() {
    }

    /**
     * 没写原因 (null / 全空白) ⇒ 409 {@link #REASON_REQUIRED}; 写了 ⇒ 返回要并入 customFields 的留痕快照。
     */
    public static Map<String, Object> auditOrRefuse(String reason, BigDecimal target, BigDecimal maxAllowed,
                                                    BigDecimal cumulative, String unit) {
        String r = reason == null ? "" : reason.strip();
        if (r.isEmpty()) {
            throw new BusinessException(409, "产出超过超收容差上限, 强制提交必须填写原因")
                    .withCode(REASON_REQUIRED)
                    .withHint("请选一个超收原因 (或选「其他」写明) 再提交; 原因会随这次报工记录下来")
                    .withHintTarget("forceSubmitReason")
                    .withSeverity("BLOCKING");
        }
        if (r.length() > MAX_REASON_LENGTH) {
            r = r.substring(0, MAX_REASON_LENGTH);
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("target", target == null ? null : target.setScale(4, RoundingMode.HALF_UP));
        audit.put("maxAllowed", maxAllowed == null ? null : maxAllowed.setScale(4, RoundingMode.HALF_UP));
        audit.put("cumulative", cumulative);
        audit.put("unit", unit);
        audit.put("reason", r);
        return audit;
    }
}
