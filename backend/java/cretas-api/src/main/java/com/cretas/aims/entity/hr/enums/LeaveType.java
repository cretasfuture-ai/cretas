package com.cretas.aims.entity.hr.enums;

/**
 * 请假类型.
 *
 * <p>仅 {@link #ANNUAL}, {@link #SICK}, {@link #COMPTIME}, {@link #OTHER}
 * 需要跟踪 LeaveBalance (其它一次性事件型假期不累计余额).
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
public enum LeaveType {
    /** 病假 — 跟踪 LeaveBalance 月度上限 */
    SICK("病假"),

    /** 事假 — 不跟踪余额, 仅记录 */
    PERSONAL("事假"),

    /** 年假 — 跟踪 LeaveBalance, 入职年限发放 */
    ANNUAL("年假"),

    /** 婚假 */
    MARRIAGE("婚假"),

    /** 丧假 */
    FUNERAL("丧假"),

    /** 产假 */
    MATERNITY("产假"),

    /** 陪产假 */
    PATERNITY("陪产假"),

    /** 调休 — 跟踪 LeaveBalance, OvertimeRequest(COMPTIME) approve 累计 */
    COMPTIME("调休"),

    /** 其它 */
    OTHER("其它");

    private final String displayName;

    LeaveType(String displayName) {
        this.displayName = displayName;
    }

    /** 中文名 —— 给人看的 (AI 确认卡 / 提示语) 一律用它, 不写枚举码。 */
    public String getDisplayName() {
        return displayName;
    }
}
