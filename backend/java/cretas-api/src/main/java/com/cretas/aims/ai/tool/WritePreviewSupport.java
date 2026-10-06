package com.cretas.aims.ai.tool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写工具「预览」的统一出口形状 —— AI 对话里「预览 → 确认」的预览段。
 *
 * <p>形状与 {@code ReceiveWithLimitTool} / {@code WorkProcessTaskTransitionPreview} 一致,
 * {@code ToolDispatchService.executeToolPreview} 据此判断:
 * <ul>
 *   <li>{@code canDo=false} → 回 {@code PREVIEW_BLOCKED}, 不铸确认 token (没有票就没有确认按钮)</li>
 *   <li>{@code currentValues / newValues} → 确认卡的「当前 → 修改后」对比表</li>
 * </ul>
 *
 * <p>⛔ 本类只拼数据, 不做任何读写。预览工具自己的合同: 只读、零副作用。
 */
public final class WritePreviewSupport {

    /** 「没有值」的统一写法 —— 不兜底成 0。 */
    public static final String EMPTY = "—";

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private WritePreviewSupport() {
    }

    /** 能做: status=PREVIEW, canDo=true, 带改前 / 改后两张表。 */
    public static Map<String, Object> ready(String message,
                                            Map<String, Object> currentValues,
                                            Map<String, Object> newValues,
                                            Map<String, Object> extra) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "PREVIEW");
        r.put("canDo", true);
        r.put("message", message);
        if (extra != null) {
            r.putAll(extra);
        }
        r.put("currentValues", currentValues);
        r.put("newValues", newValues);
        return r;
    }

    /** 不能做: canDo=false + 一句人话原因; 不给对比表 (拒绝时没有「修改后」)。 */
    public static Map<String, Object> blocked(String status, String message, Map<String, Object> extra) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("canDo", false);
        r.put("message", message);
        if (extra != null) {
            r.putAll(extra);
        }
        return r;
    }

    /** 数量 + 单位, 缺值写「—」, 末尾的 0 去掉 (100.000 → 100)。 */
    public static String quantityText(BigDecimal qty, String unit) {
        if (qty == null) {
            return EMPTY;
        }
        String n = qty.stripTrailingZeros().toPlainString();
        return unit != null && !unit.isBlank() ? n + " " + unit : n;
    }

    public static String quantityText(Integer qty, String unit) {
        return qty == null ? EMPTY : quantityText(BigDecimal.valueOf(qty), unit);
    }

    /** HH:mm, 缺值写「—」。 */
    public static String hourMinute(LocalDateTime time) {
        return time == null ? EMPTY : time.format(HOUR_MINUTE);
    }

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
