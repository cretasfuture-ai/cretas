package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.entity.hr.LeaveBalance;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.hr.LeaveRequestRepository;
import com.cretas.aims.service.hr.LeaveBalanceService;
import com.cretas.aims.service.hr.impl.LeaveRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.needMoreInfoQuestions;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假申请预览 —— 走工具的<b>真实入口</b> {@code preview(ToolCall, context)} (基类缺参校验 + doPreview + JSON 出口),
 * 并把工具接到<b>真实的</b> {@link LeaveRequestServiceImpl} 上 (只桩 repository / 余额服务),
 * 让「撞期」是真实写路径 {@code create} 同一个判断。
 *
 * <p>本类主要守 2026-10-06 的两处修复: ① 凡是「办不了」的预览都带 canDo=false
 * (以前日期写反 / 假别不认识的分支没带, ToolDispatchService 仍会铸确认 token);
 * ② 预览补上「撞期」检查 (execute 的 create 会拒, 预览却放行)。
 */
class LeaveRequestSubmitPreviewEntryTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;
    private static final LocalDate START = LocalDate.of(2026, 10, 8);
    private static final LocalDate END = LocalDate.of(2026, 10, 9);

    private LeaveRequestRepository repository;
    private LeaveBalanceService balanceService;
    private LeaveRequestServiceImpl realService;
    private LeaveRequestSubmitTool tool;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(LeaveRequestRepository.class);
        balanceService = mock(LeaveBalanceService.class);
        realService = new LeaveRequestServiceImpl(repository, balanceService, mock(ApplicationEventPublisher.class));
        when(repository.findOverlappingActive(anyString(), anyLong(), any(), any())).thenReturn(List.of());
        when(repository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        tool = new LeaveRequestSubmitTool();
        ReflectionTestUtils.setField(tool, "leaveRequestService", realService);
        ReflectionTestUtils.setField(tool, "leaveBalanceService", balanceService);
        injectMapper(tool);
    }

    @Test
    @DisplayName("预览放行: canDo=true, 确认卡写中文假别 / 起止与小时数 / 提交后去向; 没有任何写 (repository.save 一次都没调)")
    void allowed() throws Exception {
        when(balanceService.getBalance(FACTORY, USER, LeaveType.ANNUAL, "2026-10")).thenReturn(Optional.of(balance("40", "8", "32")));

        Map<String, Object> d = data(tool.preview(call("hr_leave_submit", args("ANNUAL")), ctx(FACTORY, USER)));

        assertThat(d.get("canDo")).isEqualTo(true);
        assertThat(rows(d, "currentValues")).containsEntry("请假类型", "年假").containsEntry("2026 年 10 月可用余额", "32 小时");
        assertThat(rows(d, "newValues")).containsEntry("请假类型", "年假").containsEntry("请假时间", "2026-10-08 ~ 2026-10-09（2 天，16 小时）")
                .containsKey("提交后");
        assertThat(String.valueOf(d.get("message"))).contains("确认提交年假申请").contains("够用");
        assertPlainChinese(String.valueOf(d.get("message")));
        verify(repository, never()).save(any(LeaveRequest.class));
        verify(repository).findOverlappingActive(FACTORY, USER, START, END);   // 必发生: 撞期检查真的跑了
    }

    @Test
    @DisplayName("每条「办不了」的预览都带 canDo=false —— 九种拒绝各一条, 对照: 同样的调用换成合法输入 canDo=true")
    void everyRefusalCarriesCanDoFalse() throws Exception {
        when(balanceService.getBalance(anyString(), anyLong(), any(), anyString())).thenReturn(Optional.of(balance("40", "8", "32")));
        int refused = 0;

        // ① 假别不认识 ② 起始日期格式错 ③ 结束日期格式错 ④ 结束早于开始
        Map<String, Object> badType = args("ANNUAL");
        badType.put("leaveType", "HOLIDAY");
        Map<String, Object> badStart = args("ANNUAL");
        badStart.put("startDate", "下周一");
        Map<String, Object> badEnd = args("ANNUAL");
        badEnd.put("endDate", "10月9号");
        Map<String, Object> reversed = args("ANNUAL");
        reversed.put("startDate", "2026-10-09");
        reversed.put("endDate", "2026-10-08");
        for (Map<String, Object> bad : List.of(badType, badStart, badEnd, reversed)) {
            Map<String, Object> d = data(tool.preview(call("hr_leave_submit", bad), ctx(FACTORY, USER)));
            assertThat(d.get("canDo")).as(String.valueOf(bad)).isEqualTo(false);
            assertThat(d.get("status")).isEqualTo("PREVIEW_INVALID");
            assertPlainChinese(String.valueOf(d.get("message")).replace("HOLIDAY", ""));
            refused++;
        }
        // ⑤ 撞期
        when(repository.findOverlappingActive(FACTORY, USER, START, END)).thenReturn(List.of(existing(HrRequestStatus.SUBMITTED)));
        Map<String, Object> dup = data(tool.preview(call("hr_leave_submit", args("ANNUAL")), ctx(FACTORY, USER)));
        assertThat(dup.get("canDo")).isEqualTo(false);
        assertThat(dup.get("status")).isEqualTo("DUPLICATE");
        assertThat(String.valueOf(dup.get("message"))).contains("2026-10-07 ~ 2026-10-08").contains("待审批").doesNotContain("SUBMITTED");
        refused++;
        when(repository.findOverlappingActive(FACTORY, USER, START, END)).thenReturn(List.of());
        // ⑥ 余额不够 ⑦ 没有余额记录
        when(balanceService.getBalance(FACTORY, USER, LeaveType.SICK, "2026-10")).thenReturn(Optional.of(balance("8", "0", "8")));
        Map<String, Object> short1 = data(tool.preview(call("hr_leave_submit", args("SICK")), ctx(FACTORY, USER)));
        assertThat(short1.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(short1.get("message"))).contains("病假申请 16 小时").contains("还差 8 小时");
        refused++;
        when(balanceService.getBalance(FACTORY, USER, LeaveType.COMPTIME, "2026-10")).thenReturn(Optional.empty());
        Map<String, Object> none = data(tool.preview(call("hr_leave_submit", args("COMPTIME")), ctx(FACTORY, USER)));
        assertThat(none.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(none.get("message"))).contains("还没有调休的余额记录");
        refused++;
        assertThat(refused).isEqualTo(7);

        // 对照: 合法输入 (年假, 余额 32h ≥ 16h) —— 同一个入口、同一套桩, canDo=true
        assertThat(data(tool.preview(call("hr_leave_submit", args("ANNUAL")), ctx(FACTORY, USER))).get("canDo")).isEqualTo(true);
        assertThat(data(tool.preview(call("hr_leave_submit", args("PERSONAL")), ctx(FACTORY, USER))).get("canDo")).isEqualTo(true);
        verify(repository, never()).save(any(LeaveRequest.class));
    }

    @Test
    @DisplayName("撞期与真实 create 同一个判断: 预览说撞期 ⇔ 真实 create 抛 409; 预览放行 ⇔ 真实 create 落库")
    void overlapVerdictMatchesTheRealCreate() throws Exception {
        when(balanceService.getBalance(anyString(), anyLong(), any(), anyString())).thenReturn(Optional.of(balance("40", "0", "40")));

        // 有撞期
        when(repository.findOverlappingActive(FACTORY, USER, START, END)).thenReturn(List.of(existing(HrRequestStatus.APPROVED)));
        assertThat(data(tool.preview(call("hr_leave_submit", args("ANNUAL")), ctx(FACTORY, USER))).get("canDo")).isEqualTo(false);
        assertThatThrownBy(() -> realService.create(FACTORY, USER, LeaveType.ANNUAL, START, END, new BigDecimal("16"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已有请假申请");
        verify(repository, never()).save(any(LeaveRequest.class));

        // 无撞期
        when(repository.findOverlappingActive(FACTORY, USER, START, END)).thenReturn(List.of());
        assertThat(data(tool.preview(call("hr_leave_submit", args("ANNUAL")), ctx(FACTORY, USER))).get("canDo")).isEqualTo(true);
        realService.create(FACTORY, USER, LeaveType.ANNUAL, START, END, new BigDecimal("16"), null);
        verify(repository).save(any(LeaveRequest.class));   // 必发生: 真实写路径放行时确实落库
    }

    @Test
    @DisplayName("缺假别 / 缺起始 / 缺结束 → 各自 NEED_MORE_INFO, 追问是人话 (中文假别名, 没有枚举码) 且互不相同")
    void missingParamsAreAskedInPlainChinese() throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        for (String missing : new String[]{"leaveType", "startDate", "endDate"}) {
            Map<String, Object> a = args("ANNUAL");
            a.remove(missing);
            String q = needMoreInfoQuestions(tool.preview(call("hr_leave_submit", a), ctx(FACTORY, USER))).get(0);
            assertThat(seen.add(q)).as(missing).isTrue();
        }
        assertThat(seen.iterator().next()).contains("年假").contains("陪产假");
        verify(repository, never()).findOverlappingActive(anyString(), anyLong(), any(), any());
        verify(repository, never()).save(any(LeaveRequest.class));
    }

    @Test
    @DisplayName("枚举的中文名是全的: 每个假别 / 申请状态都有非空中文名 (确认卡不会漏出枚举码)")
    void displayNamesAreComplete() {
        for (LeaveType t : LeaveType.values()) {
            assertThat(t.getDisplayName()).as(t.name()).isNotBlank().doesNotContain(t.name());
        }
        for (HrRequestStatus s : HrRequestStatus.values()) {
            assertThat(s.getDisplayName()).as(s.name()).isNotBlank().doesNotContain(s.name());
        }
    }

    // ==================== helpers ====================

    private static Map<String, Object> args(String leaveType) {
        Map<String, Object> a = new HashMap<>();
        a.put("leaveType", leaveType);
        a.put("startDate", START.toString());
        a.put("endDate", END.toString());
        return a;
    }

    private static LeaveBalance balance(String earned, String used, String balance) {
        return LeaveBalance.builder().factoryId(FACTORY).userId(USER)
                .earnedHours(new BigDecimal(earned)).usedHours(new BigDecimal(used)).balanceHours(new BigDecimal(balance)).build();
    }

    private static LeaveRequest existing(HrRequestStatus status) {
        return LeaveRequest.builder().id("LR-EXIST").factoryId(FACTORY).userId(USER).leaveType(LeaveType.ANNUAL)
                .startDate(LocalDate.of(2026, 10, 7)).endDate(LocalDate.of(2026, 10, 8))
                .durationHours(new BigDecimal("16")).status(status).build();
    }

    @SuppressWarnings("unused")
    private static void keep() {
        eq("x");
    }
}
