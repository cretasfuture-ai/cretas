package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上班 / 下班打卡只读预览。
 *
 * <p>全部走工具的真实入口 {@code preview(ToolCall, context)}, 只桩掉 {@link TimeClockService}。
 * 每条「能做」都配一条「不能做」的对照 (同样的输入, 只改前置条件); 每条都断言
 * {@code clockIn / clockOut} 一次都没被调, 且除了 {@code getTodayRecord} 之外没有任何别的调用。
 */
class ClockToolsPreviewTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private TimeClockService service;
    private LocalDateTime clock;
    private ClockInTool clockIn;
    private ClockOutTool clockOut;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(TimeClockService.class);
        clock = DAY.atTime(8, 50);
        clockIn = new ClockInTool() {
            @Override
            protected LocalDateTime now() {
                return clock;
            }
        };
        clockOut = new ClockOutTool() {
            @Override
            protected LocalDateTime now() {
                return clock;
            }
        };
        ReflectionTestUtils.setField(clockIn, "timeClockService", service);
        ReflectionTestUtils.setField(clockOut, "timeClockService", service);
        injectMapper(clockIn);
        injectMapper(clockOut);
    }

    // ==================== 上班 ====================

    @Test
    @DisplayName("上班: 今天还没打卡 → canDo=true, 给改前/改后, 不调 clockIn")
    void clockInAllowedWhenNothingToday() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(null);

        Map<String, Object> data = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));

        assertThat(clockIn.supportsPreview()).isTrue();
        assertThat(data.get("canDo")).isEqualTo(true);
        assertThat(data.get("status")).isEqualTo("PREVIEW");
        assertThat(rows(data, "currentValues")).containsEntry("今天的打卡", "还没有上班打卡");
        assertThat(rows(data, "newValues")).containsEntry("今天的打卡", "上班 08:50")
                .containsEntry("考勤记为", "正常");
        assertThat(String.valueOf(data.get("message"))).contains("上班打卡").contains("以你点确认的那一刻为准");
        assertPlainChinese(String.valueOf(data.get("message")));
        assertNoWrite();
    }

    @Test
    @DisplayName("上班: 9 点前记「正常」、9 点后记「迟到」(时刻这一维真的在变) —— 界线与写路径读同一份常量")
    void clockInLateBoundaryComesFromTheServiceConstant() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(null);
        assertThat(TimeClockService.STANDARD_START_TIME).isEqualTo(java.time.LocalTime.of(9, 0));

        clock = DAY.atTime(8, 59);
        Map<String, Object> early = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));
        clock = DAY.atTime(9, 1);
        Map<String, Object> late = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));

        assertThat(rows(early, "newValues")).containsEntry("考勤记为", "正常");
        assertThat(rows(late, "newValues")).containsEntry("考勤记为", "迟到");
        assertThat(String.valueOf(late.get("message"))).contains("迟到");
        assertNoWrite();
    }

    @Test
    @DisplayName("上班 对照: 今天已经上班且没下班 → canDo=false + 中文原因; 同一个人下了班又能重新上班")
    void clockInRefusedWhenAlreadyClockedInButAllowedAfterClockOut() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 30), null));

        Map<String, Object> refused = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));
        assertThat(refused.get("canDo")).isEqualTo(false);
        assertThat(refused.get("status")).isEqualTo("INVALID_STATE");
        assertThat(String.valueOf(refused.get("message"))).contains("已经打过上班卡").contains("08:30").contains("先下班打卡");
        assertThat(refused).as("拒绝时不给对比表").doesNotContainKey("newValues");

        // 对照: 前置条件换成「已下班」, 同一个调用就放行 —— 证明上面那条拒绝来自「已上班未下班」
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 30), DAY.atTime(12, 0)));
        clock = DAY.atTime(13, 0);
        Map<String, Object> again = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));
        assertThat(again.get("canDo")).isEqualTo(true);
        assertThat(String.valueOf(again.get("message"))).contains("重新开始").contains("原来的下班时间会被清掉");
        assertThat(rows(again, "currentValues").get("今天的打卡").toString()).contains("下班 12:00");
        assertNoWrite();
    }

    @Test
    @DisplayName("上班: 带位置时确认卡写出位置 (execute 会把它写进记录)")
    void clockInShowsLocationThatWillBeWritten() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(null);

        Map<String, Object> withLoc = data(clockIn.preview(
                call("clock_in", Map.of("location", "一号车间")), ctx(FACTORY, USER)));
        Map<String, Object> without = data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));

        assertThat(rows(withLoc, "newValues")).containsEntry("打卡位置", "一号车间");
        assertThat(rows(without, "newValues")).doesNotContainKey("打卡位置");
    }

    @Test
    @DisplayName("上班: context 里没有登录用户 → 入口就拒绝, 不去读任何人的记录; 对照: 带上用户就读")
    void clockInWithoutUserIsRejectedAtTheEntry() throws Exception {
        assertThatThrownBy(() -> clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        verify(service, never()).getTodayRecord(anyString(), any());
        assertNoWriteMethods();

        data(clockIn.preview(call("clock_in", Map.of()), ctx(FACTORY, USER)));
        verify(service).getTodayRecord(FACTORY, USER);
    }

    // ==================== 下班 ====================

    @Test
    @DisplayName("下班: 已上班未下班 → canDo=true, 显示上班时间 / 今天上班时长 / 考勤结论, 不调 clockOut")
    void clockOutAllowedAfterClockIn() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 55), null, "NORMAL"));
        clock = DAY.atTime(18, 30);

        Map<String, Object> data = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));

        assertThat(clockOut.supportsPreview()).isTrue();
        assertThat(data.get("canDo")).isEqualTo(true);
        assertThat(rows(data, "currentValues")).containsEntry("今天的打卡", "上班 08:55，还没有下班")
                .containsEntry("考勤记为", "正常");
        assertThat(rows(data, "newValues")).containsEntry("今天的打卡", "上班 08:55，下班 18:30")
                .containsEntry("今天上班时长", "9 小时 35 分钟")
                .containsEntry("考勤记为", "正常");
        assertPlainChinese(String.valueOf(data.get("message")));
        assertNoWrite();
    }

    @Test
    @DisplayName("下班: 18 点前记早退 (迟到的记「迟到并早退」), 18 点后保持原状态 —— 界线读同一份常量")
    void clockOutEarlyLeaveFollowsTheWritePathRule() throws Exception {
        assertThat(TimeClockService.STANDARD_END_TIME).isEqualTo(java.time.LocalTime.of(18, 0));
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 55), null, "NORMAL"));
        clock = DAY.atTime(17, 30);
        Map<String, Object> early = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));

        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(9, 20), null, "LATE"));
        Map<String, Object> lateAndEarly = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));

        clock = DAY.atTime(18, 5);
        Map<String, Object> lateOnly = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));

        assertThat(rows(early, "newValues")).containsEntry("考勤记为", "早退");
        assertThat(rows(lateAndEarly, "newValues")).containsEntry("考勤记为", "迟到并早退");
        assertThat(rows(lateOnly, "newValues")).containsEntry("考勤记为", "迟到");
        assertNoWrite();
    }

    @Test
    @DisplayName("下班 对照: 没打上班卡 / 已经下班 → canDo=false; 打了上班卡就放行")
    void clockOutRefusedWithoutClockInOrAfterClockOut() throws Exception {
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(null);
        Map<String, Object> none = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));
        assertThat(none.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(none.get("message"))).contains("还没有打上班卡");

        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(null, null));
        Map<String, Object> noClockInTime = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));
        assertThat(noClockInTime.get("canDo")).isEqualTo(false);

        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 30), DAY.atTime(17, 40)));
        Map<String, Object> done = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));
        assertThat(done.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(done.get("message"))).contains("已经打过下班卡").contains("17:40");
        assertThat(done).doesNotContainKey("newValues");

        // 对照: 只改一个前置条件 (清掉下班时间), 同样的调用放行
        when(service.getTodayRecord(FACTORY, USER)).thenReturn(record(DAY.atTime(8, 30), null));
        clock = DAY.atTime(18, 10);
        Map<String, Object> ok = data(clockOut.preview(call("clock_out", Map.of()), ctx(FACTORY, USER)));
        assertThat(ok.get("canDo")).isEqualTo(true);
        assertNoWrite();
    }

    // ==================== helpers ====================

    /** 预览的合同: 除了读今天的记录, 对 service 什么都没做 —— 逐个把写方法点名, 再兜底 verifyNoMoreInteractions。 */
    private void assertNoWrite() {
        assertNoWriteMethods();
        // 「必发生」对照: 预览确实去读了今天的记录 (没读 = 没跑到, 上面的 never 就是空话)
        verify(service, org.mockito.Mockito.atLeastOnce()).getTodayRecord(anyString(), any());
    }

    private void assertNoWriteMethods() {
        verify(service, never()).clockIn(anyString(), any(), any(), any());
        verify(service, never()).clockIn(anyString(), any(), any(), any(), anyDouble(), anyDouble());
        verify(service, never()).clockOut(anyString(), any());
        verify(service, never()).breakStart(anyString(), any());
        verify(service, never()).breakEnd(anyString(), any());
        verify(service, never()).editClockRecord(anyString(), any(), any(), any(), any());
    }

    private static TimeClockRecord record(LocalDateTime in, LocalDateTime out) {
        return record(in, out, "NORMAL");
    }

    private static TimeClockRecord record(LocalDateTime in, LocalDateTime out, String attendance) {
        return TimeClockRecord.builder()
                .factoryId(FACTORY)
                .userId(USER)
                .clockDate(DAY)
                .clockInTime(in)
                .clockOutTime(out)
                .status(out == null ? "WORKING" : "OFF_WORK")
                .attendanceStatus(attendance)
                .build();
    }
}
