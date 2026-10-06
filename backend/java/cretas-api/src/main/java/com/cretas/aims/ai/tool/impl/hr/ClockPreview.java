package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.cretas.aims.ai.tool.WritePreviewSupport.EMPTY;
import static com.cretas.aims.ai.tool.WritePreviewSupport.blocked;
import static com.cretas.aims.ai.tool.WritePreviewSupport.hourMinute;
import static com.cretas.aims.ai.tool.WritePreviewSupport.notBlank;
import static com.cretas.aims.ai.tool.WritePreviewSupport.ready;

/**
 * 上班 / 下班打卡的<b>只读</b>预览。
 *
 * <p>⛔ 只调 {@link TimeClockService#getTodayRecord} (纯读), 绝不调 {@code clockIn / clockOut}。
 *
 * <p>规则来源 (镜像 {@code TimeClockServiceImpl}, 不另立一套):
 * <ul>
 *   <li>上班: 今天已有上班卡且没下班 → 拒绝 (409); 已下班 → 允许重新上班 (清空下班时间);
 *       没有记录 / 记录里没有上班时间 → 允许</li>
 *   <li>下班: 今天没有记录或没有上班时间 → 拒绝; 已下班 → 拒绝</li>
 *   <li>迟到 / 早退的界线读 {@link TimeClockService#STANDARD_START_TIME} /
 *       {@link TimeClockService#STANDARD_END_TIME} —— 与写路径是同一份常量</li>
 * </ul>
 */
final class ClockPreview {

    private static final String ROW = "今天的打卡";
    private static final String ATTENDANCE = "考勤记为";

    private ClockPreview() {
    }

    static Map<String, Object> clockIn(TimeClockRecord today, LocalDateTime now, String location) {
        boolean late = now.toLocalTime().isAfter(TimeClockService.STANDARD_START_TIME);
        String attendance = late ? "迟到" : "正常";

        if (today != null && today.getClockOutTime() == null && today.getClockInTime() != null) {
            return blocked("INVALID_STATE", String.format(
                    "你今天已经打过上班卡了（%s），还没有下班。要打卡的话请先下班打卡。",
                    hourMinute(today.getClockInTime())), null);
        }

        Map<String, Object> current = new LinkedHashMap<>();
        Map<String, Object> next = new LinkedHashMap<>();
        String lead;
        if (today != null && today.getClockOutTime() != null) {
            // 写路径: 已下班可以再次上班, 记录被重置 (下班 / 休息时间清空)
            current.put(ROW, String.format("上班 %s，下班 %s（已下班）",
                    hourMinute(today.getClockInTime()), hourMinute(today.getClockOutTime())));
            next.put(ROW, String.format("上班 %s（重新开始，原来的下班时间会清掉）", hourMinute(now)));
            lead = "你今天已经下班打过卡了，确认后会重新开始一轮上班，原来的下班时间会被清掉。";
        } else {
            current.put(ROW, "还没有上班打卡");
            next.put(ROW, String.format("上班 %s", hourMinute(now)));
            lead = "确认现在上班打卡？";
        }
        next.put(ATTENDANCE, attendance);
        if (notBlank(location)) {
            next.put("打卡位置", location);
        }
        String standard = hourMinute(TimeClockService.STANDARD_START_TIME.atDate(now.toLocalDate()));
        String message = String.format(
                "%s打卡时间以你点确认的那一刻为准；现在是 %s，标准上班时间 %s，按现在的时间会记为「%s」。",
                lead, hourMinute(now), standard, attendance);
        return ready(message, current, next, null);
    }

    static Map<String, Object> clockOut(TimeClockRecord today, LocalDateTime now) {
        if (today == null || today.getClockInTime() == null) {
            return blocked("INVALID_STATE",
                    "你今天还没有打上班卡，不能下班打卡。请先上班打卡。", null);
        }
        if (today.getClockOutTime() != null) {
            return blocked("INVALID_STATE", String.format(
                    "你今天已经打过下班卡了（%s），不用再打。", hourMinute(today.getClockOutTime())), null);
        }

        boolean early = now.toLocalTime().isBefore(TimeClockService.STANDARD_END_TIME);
        String before = today.getAttendanceStatus();
        // 镜像 TimeClockServiceImpl#clockOut: 18:00 前下班记早退 (迟到的记「迟到并早退」); 之后保持原状态
        String after = early ? ("LATE".equals(before) ? "LATE_AND_EARLY_LEAVE" : "EARLY_LEAVE") : before;
        long minutes = TimeClockRecord.netWorkMinutes(today.getClockInTime(), now,
                today.getBreakStartTime(), today.getBreakEndTime());

        Map<String, Object> current = new LinkedHashMap<>();
        current.put(ROW, String.format("上班 %s，还没有下班", hourMinute(today.getClockInTime())));
        current.put(ATTENDANCE, attendanceLabel(before));
        Map<String, Object> next = new LinkedHashMap<>();
        next.put(ROW, String.format("上班 %s，下班 %s", hourMinute(today.getClockInTime()), hourMinute(now)));
        next.put("今天上班时长", durationText(minutes));
        next.put(ATTENDANCE, attendanceLabel(after));

        String message = String.format(
                "确认现在下班打卡？下班时间以你点确认的那一刻为准；现在是 %s，今天已上班约 %s，下班后考勤记为「%s」。",
                hourMinute(now), durationText(minutes), attendanceLabel(after));
        return ready(message, current, next, null);
    }

    /** 考勤状态的中文名; 没有值写「—」, 不认识的原样保留 (不编一个名字)。 */
    static String attendanceLabel(String code) {
        if (code == null) {
            return EMPTY;
        }
        switch (code) {
            case "NORMAL":
                return "正常";
            case "LATE":
                return "迟到";
            case "EARLY_LEAVE":
                return "早退";
            case "LATE_AND_EARLY_LEAVE":
                return "迟到并早退";
            default:
                return code;
        }
    }

    private static String durationText(long minutes) {
        if (minutes < 0) {
            return EMPTY;
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? String.format("%d 小时 %d 分钟", h, m) : String.format("%d 分钟", m);
    }
}
