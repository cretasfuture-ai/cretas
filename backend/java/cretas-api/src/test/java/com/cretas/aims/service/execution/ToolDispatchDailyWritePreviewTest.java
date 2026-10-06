package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.hr.ClockInTool;
import com.cretas.aims.ai.tool.impl.hr.LeaveRequestSubmitTool;
import com.cretas.aims.ai.tool.impl.warehouse.ReusableContainerShipOutTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.hr.LeaveBalance;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.TimeClockService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.hr.LeaveBalanceService;
import com.cretas.aims.service.hr.LeaveRequestService;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一线日常写工具补预览之后, 从<b>派发入口</b>走一遍: 预览 → 确认卡 → 铸 token。
 *
 * <p>用<b>真实的</b> {@link WriteGuardService} (不是桩): 「这个工具算不算写工具」是铸 token 的前提,
 * 桩成恒 true 就等于没测这一环。工具是真实的 {@code ClockInTool} / {@code LeaveRequestSubmitTool} /
 * {@code ReusableContainerShipOutTool}, 只桩掉它们背后的 service。
 */
class ToolDispatchDailyWritePreviewTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatchService dispatch;
    private PreviewTokenService previewTokenService;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(
                registry,
                mapper,
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        previewTokenService = mock(PreviewTokenService.class);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        when(previewTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("tok-1").commandDigest("digest-1").expiresAt(LocalDateTime.now().plusMinutes(5)).build());
    }

    @Test
    @DisplayName("上班打卡: previewOnly 请求 → 出确认卡 (PREVIEW + 改前/改后) 并铸 token; clockIn 一次没调")
    void clockInPreviewProducesConfirmCardAndToken() throws Exception {
        TimeClockService clock = mock(TimeClockService.class);
        ClockInTool tool = clockInTool(clock);
        when(clock.getTodayRecord(FACTORY, USER)).thenReturn(null);

        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, request("CLOCK_IN", "上班打卡"),
                intent("CLOCK_IN", "上班打卡", "clock_in"), USER, "operator", null);

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getConfirmableAction()).as("预览通过 → 有确认卡").isNotNull();
        assertThat(r.getConfirmableAction().getConfirmToken()).isEqualTo("tok-1");
        assertThat(r.getConfirmableAction().getCurrentValues()).containsEntry("今天的打卡", "还没有上班打卡");
        assertThat(r.getConfirmableAction().getNewValues()).containsKey("今天的打卡").containsKey("考勤记为");
        assertThat(r.getAiMode()).isEqualTo("WRITE");
        verify(previewTokenService, times(1)).createBoundToken(any());
        verify(clock, times(1)).getTodayRecord(FACTORY, USER);          // 必发生: 预览真的读了
        verify(clock, never()).clockIn(anyString(), any(), any(), any());
        verify(clock, never()).clockIn(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("对照 (变异): 把 supportsPreview 改回 false → 同一个调用得到 PREVIEW_UNSUPPORTED, 没有确认卡、没有 token —— 这就是改之前 prod 上的样子")
    void withoutSupportsPreviewTheSameCallIsUnsupported() throws Exception {
        TimeClockService clock = mock(TimeClockService.class);
        ClockInTool mutated = new ClockInTool() {
            @Override
            public boolean supportsPreview() {
                return false;   // 变异: 还原成改动前的状态
            }
        };
        ReflectionTestUtils.setField(mutated, "timeClockService", clock);
        ReflectionTestUtils.setField(mutated, "objectMapper", mapper);
        // 变异真的打进去了 (先证明变异到达, 再看后面的读数)
        assertThat(mutated.supportsPreview()).isFalse();

        IntentExecuteResponse r = dispatch.executeWithTool(mutated, FACTORY, request("CLOCK_IN", "上班打卡"),
                intent("CLOCK_IN", "上班打卡", "clock_in"), USER, "operator", null);

        assertThat(r.getStatus()).isEqualTo("PREVIEW_UNSUPPORTED");
        assertThat(r.getConfirmableAction()).isNull();
        verify(previewTokenService, never()).createBoundToken(any());
        verify(clock, never()).getTodayRecord(anyString(), any());
    }

    @Test
    @DisplayName("上班打卡 对照: 今天已经打过上班卡 → PREVIEW_BLOCKED + 中文原因, 不铸 token")
    void clockInBlockedWhenAlreadyClockedIn() throws Exception {
        TimeClockService clock = mock(TimeClockService.class);
        ClockInTool tool = clockInTool(clock);
        when(clock.getTodayRecord(FACTORY, USER)).thenReturn(TimeClockRecord.builder()
                .factoryId(FACTORY).userId(USER).clockInTime(LocalDate.now().atTime(8, 30)).build());

        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, request("CLOCK_IN", "上班打卡"),
                intent("CLOCK_IN", "上班打卡", "clock_in"), USER, "operator", null);

        assertThat(r.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(r.getConfirmableAction()).isNull();
        assertThat(r.getMessage()).contains("已经打过上班卡").contains("先下班打卡");
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("请假: 日期写反 → PREVIEW_BLOCKED 不铸 token (改之前会铸出一张可确认的票); 合法输入 → 铸 token")
    void leaveWithReversedDatesIsBlockedButValidOneMintsToken() throws Exception {
        LeaveRequestService leaveService = mock(LeaveRequestService.class);
        LeaveBalanceService balanceService = mock(LeaveBalanceService.class);
        LeaveRequestSubmitTool tool = new LeaveRequestSubmitTool();
        ReflectionTestUtils.setField(tool, "leaveRequestService", leaveService);
        ReflectionTestUtils.setField(tool, "leaveBalanceService", balanceService);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(leaveService.findOverlappingActive(anyString(), any(), any(), any())).thenReturn(List.of());
        when(balanceService.getBalance(anyString(), any(), any(), anyString())).thenReturn(Optional.of(LeaveBalance.builder()
                .earnedHours(new BigDecimal("40")).usedHours(BigDecimal.ZERO).balanceHours(new BigDecimal("40")).build()));
        AIIntentConfig intent = intent("HR_LEAVE_SUBMIT", "提交请假申请", "hr_leave_submit");

        Map<String, Object> reversed = new HashMap<>(Map.of("leaveType", "ANNUAL", "startDate", "2026-10-09", "endDate", "2026-10-08"));
        IntentExecuteResponse blocked = dispatch.executeWithTool(tool, FACTORY, request("HR_LEAVE_SUBMIT", "请年假", reversed),
                intent, USER, "operator", null);
        assertThat(blocked.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(blocked.getConfirmableAction()).isNull();
        assertThat(blocked.getMessage()).contains("不能早于");
        verify(previewTokenService, never()).createBoundToken(any());

        Map<String, Object> ok = new HashMap<>(Map.of("leaveType", "ANNUAL", "startDate", "2026-10-08", "endDate", "2026-10-09"));
        IntentExecuteResponse good = dispatch.executeWithTool(tool, FACTORY, request("HR_LEAVE_SUBMIT", "请年假", ok),
                intent, USER, "operator", null);
        assertThat(good.getStatus()).isEqualTo("PREVIEW");
        assertThat(good.getConfirmableAction()).isNotNull();
        assertThat(good.getConfirmableAction().getNewValues()).containsEntry("请假类型", "年假");
        verify(previewTokenService, times(1)).createBoundToken(any());
        verify(leaveService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("周转框发出: 缺数量 → NEED_MORE_INFO + 人话追问, 不铸 token; 补上数量 → 铸 token")
    void shipOutMissingQuantityAsksInsteadOfMintingToken() throws Exception {
        ReusableContainerService service = mock(ReusableContainerService.class);
        ReusableContainerShipOutTool tool = new ReusableContainerShipOutTool();
        ReflectionTestUtils.setField(tool, "service", service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        com.cretas.aims.entity.warehouse.ReusableContainer c = new com.cretas.aims.entity.warehouse.ReusableContainer();
        c.setContainerName("塑料周转筐");
        c.setInWarehouseQuantity(50);
        c.setInTransitQuantity(0);
        when(service.assertShipOutAllowed(FACTORY, "c-1", 10)).thenReturn(c);
        AIIntentConfig intent = intent("REUSABLE_CONTAINER_SHIP_OUT", "周转框发出", "reusable_container_ship_out");

        Map<String, Object> noQty = new HashMap<>(Map.of("containerId", "c-1", "customerId", "cust-1", "customerName", "永辉超市"));
        IntentExecuteResponse asked = dispatch.executeWithTool(tool, FACTORY,
                request("REUSABLE_CONTAINER_SHIP_OUT", "发出周转框", noQty), intent, USER, "warehouse_worker", null);
        assertThat(asked.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(asked.getClarificationQuestions()).containsExactly("请问发出多少个？");
        assertThat(asked.getConfirmableAction()).isNull();
        verify(previewTokenService, never()).createBoundToken(any());

        Map<String, Object> full = new HashMap<>(noQty);
        full.put("quantity", 10);
        IntentExecuteResponse ok = dispatch.executeWithTool(tool, FACTORY,
                request("REUSABLE_CONTAINER_SHIP_OUT", "发出周转框", full), intent, USER, "warehouse_worker", null);
        assertThat(ok.getStatus()).isEqualTo("PREVIEW");
        assertThat(ok.getConfirmableAction()).isNotNull();
        verify(previewTokenService, times(1)).createBoundToken(any());
        verify(service, never()).shipOut(anyString(), anyString(), any(), anyString(), anyString(), any(), any());
    }

    // ==================== helpers ====================

    private ClockInTool clockInTool(TimeClockService clock) {
        ClockInTool tool = new ClockInTool();
        ReflectionTestUtils.setField(tool, "timeClockService", clock);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        return tool;
    }

    private static AIIntentConfig intent(String code, String name, String toolName) {
        return AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("DATA_OP").toolName(toolName).build();
    }

    private static IntentExecuteRequest request(String code, String userInput) {
        return request(code, userInput, new HashMap<>());
    }

    private static IntentExecuteRequest request(String code, String userInput, Map<String, Object> context) {
        return IntentExecuteRequest.builder()
                .intentCode(code)
                .userInput(userInput)
                .context(context)
                .previewOnly(true)
                .build();
    }
}
