package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.WageCalculationService;
import com.cretas.aims.service.WagePolicyService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 本仓「我的工资」功能最重要的一道闸: <b>工人只能读到自己的工资</b>。
 *
 * <p>这个功能一旦设计错就不是 bug 而是数据事故 —— 全厂人的工资单互相可见。
 * 已有的 {@link WageController} 之所以不能直接放宽权限, 是因为
 * {@code /wage/efficiency/daily/{workerId}} / {@code /wage/efficiency/trend/{workerId}}
 * 的 workerId 是<b>路径参数</b>, {@code /wage/payroll} 的是<b>查询参数</b> ——
 * 放宽 = 任何人填别人的 ID 就能读别人的工资。
 *
 * <p>{@link MyWageController} 的做法是<b>结构上没有那条路</b>: 端点不接受任何工人标识入参,
 * workerId 只从 token 推导。本测试守的就是这个不变量, 三层:
 *
 * <ol>
 *   <li>{@link #tokenIdentityWins_requestCarryingAnotherWorkersIdStillReturnsMyOwnWage()} —
 *       行为层: 拿 A 的 token 带上 B 的 ID 请求, 拿到的必须是 A 的钱,
 *       且 B 的金额/姓名<b>一个字节都不许出现在响应里</b>。</li>
 *   <li>{@link #noEndpointAcceptsAnyWorkerIdentityParameter()} —
 *       结构层: 反射扫所有 handler 方法的参数, 白名单只允许 factoryId / month / limit。
 *       ⛔ 用反射而不是 grep 源码 —— 文本匹配会把注释和 javadoc 里提到的参数名也数进去。</li>
 *   <li>{@link #operatorPassesTheSelfWageGateButNotTheHrGate()} —
 *       权限层: 跑<b>真的</b> {@link PermissionServiceImpl}, 证明 operator 过得了新码、
 *       过不了 {@code hr:read}/{@code finance:read} —— 这既是新码存在的理由,
 *       也是「没有顺手给工人开 hr 权限」的证据。</li>
 * </ol>
 *
 * <h2>变异对照 (2026-08-25 实跑, 见 PR 描述)</h2>
 * <p>把 {@code getMyMonthlyWage} 改成从 {@code @RequestParam("workerId")} 取身份
 * (即人为制造越权漏洞), 第 1、2 条断言双双变红:
 * 第 1 条读到 B 的 {@code 99999.99} / {@code 李四}, 第 2 条报出多余参数 {@code workerId}。
 * ⚠️ 变异生效的证据不是「测试红了」而是「红的原因换了」—— 见 PR 描述里贴的实际输出。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MyWageController — 工人只读得到自己的工资 (越权闸)")
class MyWageControllerSelfScopeTest {

    // ---- 两个人: 我(A) 和 同事(B). B 的数据全程都在 mock 里备着, ----
    // ---- 所以一旦控制器改成认入参, 测试红的方式是「读到了 B」, 而不是 NPE.  ----
    private static final String FACTORY = "F001";

    private static final long ME_ID = 101L;
    private static final String ME_NAME = "张三";
    private static final BigDecimal MY_WAGE = new BigDecimal("3210.50");

    private static final long COLLEAGUE_ID = 202L;
    private static final String COLLEAGUE_NAME = "李四";
    private static final BigDecimal COLLEAGUE_WAGE = new BigDecimal("99999.99");

    private static final String MY_TOKEN = "TOKEN-OF-ME";
    private static final String MONTH = "2026-08";
    private static final String URL = "/api/mobile/" + FACTORY + "/wage/me/monthly";

    @Mock private MobileService mobileService;
    @Mock private WagePolicyService wagePolicyService;
    @Mock private WageCalculationService wageCalculationService;
    @Mock private WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    @Mock private WageCalculationRepository wageCalculationRepository;
    @Mock private PayrollRecordRepository payrollRecordRepository;
    @Mock private UserRepository userRepository;

    /**
     * ⚠️ 故意用<b>真的</b> PermissionServiceImpl, 不是 mock。
     * 用 mock 的话「operator 能不能过这道闸」就变成我自己编的答案, 那条断言会是恒真式。
     * 它的两个 repo 是 {@code @Autowired(required = false)}, 留 null 时走 hardcoded 矩阵。
     */
    private final PermissionService realPermissionService = new PermissionServiceImpl();

    private MockMvc buildMockMvc() {
        MyWageController controller = new MyWageController(
                mobileService,
                wagePolicyService,
                wageCalculationService,
                workerDailyEfficiencyRepository,
                wageCalculationRepository,
                payrollRecordRepository);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(
                        realPermissionService, userRepository, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 车间工人本人 (operator 角色) —— 权限拦截器从 request attribute "userId" 反查到他。 */
    private void givenLoggedInAsOperator() {
        User me = new User();
        me.setId(ME_ID);
        me.setFactoryId(FACTORY);
        me.setRoleCode("operator");
        when(userRepository.findById(ME_ID)).thenReturn(Optional.of(me));

        UserDTO meDto = new UserDTO();
        meDto.setId(ME_ID);
        meDto.setFactoryId(FACTORY);
        meDto.setFullName(ME_NAME);
        meDto.setUsername("zhangsan");
        when(mobileService.getUserFromToken(MY_TOKEN)).thenReturn(meDto);

        when(wagePolicyService.resolveModeForEmployee(eq(FACTORY), any()))
                .thenReturn(WageMode.PIECE_RATE);
        when(wagePolicyService.resolveEffectivePolicy(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
        when(wageCalculationService.findEffectivePieceRateRule(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(wageCalculationRepository.findByFactoryIdAndEmployeeIdAndPeriodMonth(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    /**
     * 两个人的数据都备好。
     *
     * <p>🔴 <b>同事那份是故意备着的</b>: 如果只 stub 我的, 那么控制器一旦改成认入参就会拿到
     * Mockito 的默认空集合 / NPE —— 测试确实会红, 但红的理由是「崩了」, 不是「读到了别人」。
     * 那种红没法区分「越权被挡住了」和「代码坏了」。备上 B 的数据, 红的方式才精确。
     */
    private void givenBothWorkersHaveWageData() {
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(eq(FACTORY), eq(ME_ID), any(), any()))
                .thenReturn(List.of(efficiency(ME_ID, ME_NAME, LocalDate.of(2026, 8, 3), "CUTTING", 120, 480)));
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(eq(FACTORY), eq(COLLEAGUE_ID), any(), any()))
                .thenReturn(List.of(efficiency(COLLEAGUE_ID, COLLEAGUE_NAME, LocalDate.of(2026, 8, 3), "CUTTING", 900, 480)));

        when(payrollRecordRepository.findByFactoryIdAndWorkerId(FACTORY, ME_ID))
                .thenReturn(List.of(payroll(1L, ME_ID, ME_NAME, MY_WAGE, "PAID")));
        when(payrollRecordRepository.findByFactoryIdAndWorkerId(FACTORY, COLLEAGUE_ID))
                .thenReturn(List.of(payroll(2L, COLLEAGUE_ID, COLLEAGUE_NAME, COLLEAGUE_WAGE, "PAID")));
    }

    // ==================== 1. 行为层: 越权读不到 ====================

    @Test
    @DisplayName("🔴 拿 A 的 token 带上 B 的 workerId 请求 —— 拿到的必须是 A 的工资, B 的数字/姓名一个字节都不许出现")
    void tokenIdentityWins_requestCarryingAnotherWorkersIdStillReturnsMyOwnWage() throws Exception {
        givenLoggedInAsOperator();
        givenBothWorkersHaveWageData();

        // 把能想到的越权入参形式一次全带上: 查询参数的三种常见命名 + 请求头。
        MvcResult result = buildMockMvc().perform(get(URL)
                        .header("Authorization", "Bearer " + MY_TOKEN)
                        .requestAttr("userId", ME_ID)          // JwtAuthInterceptor 在真实链路里设的
                        .param("month", MONTH)
                        .param("workerId", String.valueOf(COLLEAGUE_ID))
                        .param("employeeId", String.valueOf(COLLEAGUE_ID))
                        .param("userId", String.valueOf(COLLEAGUE_ID))
                        .header("X-Worker-Id", String.valueOf(COLLEAGUE_ID)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 🔴 最硬的三条放最前面 —— 顺序是有意的。
        // 如果先跑 jsonPath("$.data.workerId") 的相等断言, 它会在越权发生时**先**抛,
        // 下面这几条整包扫描就永远跑不到, 于是没人知道它们到底会不会响 (= 未验证的断言)。
        //
        // 用「整包扫字符串」而不是逐字段断言: 逐字段只守得住我今天想到的字段,
        // 将来有人往 DTO 里加字段又忘了自我作用域, 逐字段的断言不会红。
        // ⚠️ 三条一起收集再一次性断言, ⛔ 不写成三条 assertFalse ——
        //    分开写的话第一条一红后面两条就不跑了, 那两条会长期处于「从没被验证过」的状态。
        //    (2026-08-25 实测正是这么发现「姓名那条是恒真式」的: MockMvc 的
        //     getContentAsString() 默认按 ISO-8859-1 解, 中文永远匹配不上。已改成显式 UTF-8。)
        List<String> leaks = new ArrayList<>();
        if (body.contains(COLLEAGUE_WAGE.toPlainString())) {
            leaks.add("同事的工资金额 " + COLLEAGUE_WAGE);
        }
        if (body.contains(COLLEAGUE_NAME)) {
            leaks.add("同事的姓名 " + COLLEAGUE_NAME);
        }
        if (body.contains("\"workerId\":" + COLLEAGUE_ID)) {
            leaks.add("同事的 workerId " + COLLEAGUE_ID);
        }
        assertTrue(leaks.isEmpty(), () ->
                "🔴 越权: 响应里出现了 " + String.join(" / ", leaks) + "\n实际响应: " + body);

        // 仓储层也不许被同事的 ID 查过。
        // ⚠️ 用 any() 而不是 anyString(): anyString() 不匹配 null, 一旦 factoryId 是 null
        //    这条阴性断言会因为「匹配不上」而恒真 (本仓踩过这个坑)。
        verify(payrollRecordRepository, never()).findByFactoryIdAndWorkerId(any(), eq(COLLEAGUE_ID));
        verify(workerDailyEfficiencyRepository, never())
                .findByWorkerAndDateRange(any(), eq(COLLEAGUE_ID), any(), any());

        // 阳性对照: 证明上面那两条 never() 不是因为「这些方法根本没被调过」而恒真。
        verify(payrollRecordRepository).findByFactoryIdAndWorkerId(FACTORY, ME_ID);
        verify(workerDailyEfficiencyRepository)
                .findByWorkerAndDateRange(eq(FACTORY), eq(ME_ID), any(), any());

        // 最后才是「拿到的确实是我的」正向断言。
        assertTrue(body.contains("\"workerId\":" + ME_ID), "响应里没有我的 workerId。实际: " + body);
        assertTrue(body.contains(ME_NAME), "响应里没有我的姓名。实际: " + body);
        assertTrue(body.contains(MY_WAGE.toPlainString()), "响应里没有我的工资。实际: " + body);
    }

    @Test
    @DisplayName("🔴 同一条路: 工资单历史也不认入参, 只认 token")
    void payrollHistoryIsAlsoSelfScoped() throws Exception {
        givenLoggedInAsOperator();
        givenBothWorkersHaveWageData();

        MvcResult result = buildMockMvc().perform(
                        get("/api/mobile/" + FACTORY + "/wage/me/payrolls")
                                .header("Authorization", "Bearer " + MY_TOKEN)
                                .requestAttr("userId", ME_ID)
                                .param("workerId", String.valueOf(COLLEAGUE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].totalWage").value(MY_WAGE.doubleValue()))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(COLLEAGUE_WAGE.toPlainString()),
                "🔴 越权: 工资单历史里出现了同事的金额");
        verify(payrollRecordRepository, never()).findByFactoryIdAndWorkerId(any(), eq(COLLEAGUE_ID));
        verify(payrollRecordRepository).findByFactoryIdAndWorkerId(FACTORY, ME_ID);
    }

    @Test
    @DisplayName("拿本厂 token 去读别厂路径 —— 403, 不是「查不到」")
    void crossFactoryAccessIsRejected() throws Exception {
        givenLoggedInAsOperator();
        givenBothWorkersHaveWageData();

        buildMockMvc().perform(get("/api/mobile/F999/wage/me/monthly")
                        .header("Authorization", "Bearer " + MY_TOKEN)
                        .requestAttr("userId", ME_ID))
                .andExpect(status().isForbidden());

        verify(payrollRecordRepository, never()).findByFactoryIdAndWorkerId(any(), any());
    }

    // ==================== 2. 结构层: 端点不许长出身份入参 ====================

    /**
     * 白名单式的反射闸: {@link MyWageController} 的每个 handler 方法, 参数只许是
     * {@code @PathVariable("factoryId")} / {@code @RequestParam("month"|"limit")} /
     * {@link HttpServletRequest}。
     *
     * <p>⛔ 为什么是<b>白名单</b>而不是「禁止叫 workerId 的参数」: 黑名单只能挡住我今天想得到的名字,
     * 挡不住 {@code targetUserId} / {@code employeeNo} / 一个塞了 id 的 request body。
     * 白名单反过来 —— 想加任何新入参都会先弄红这条, 逼人来读这段说明。
     *
     * <p>⛔ 为什么用<b>反射</b>而不是 grep 源码: 文本匹配会把 javadoc、注释里提到的
     * {@code workerId} 也数进去 (本仓的源码闸踩过好几次这个坑)。反射看的是真结构。
     */
    @Test
    @DisplayName("🔴 结构闸: 本 controller 的端点只许有 factoryId / month / limit 三种入参")
    void noEndpointAcceptsAnyWorkerIdentityParameter() {
        List<String> violations = new ArrayList<>();
        int handlerCount = 0;

        for (Method method : MyWageController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            handlerCount++;
            for (Parameter p : method.getParameters()) {
                PathVariable pathVar = p.getAnnotation(PathVariable.class);
                RequestParam reqParam = p.getAnnotation(RequestParam.class);

                if (pathVar != null) {
                    if (!"factoryId".equals(pathVar.value())) {
                        violations.add(method.getName() + " 的路径参数 @PathVariable(\""
                                + pathVar.value() + "\") 不在白名单里");
                    }
                } else if (reqParam != null) {
                    String name = reqParam.name().isEmpty() ? reqParam.value() : reqParam.name();
                    if (!"month".equals(name) && !"limit".equals(name)) {
                        violations.add(method.getName() + " 的查询参数 @RequestParam(\""
                                + name + "\") 不在白名单里");
                    }
                } else if (!HttpServletRequest.class.isAssignableFrom(p.getType())) {
                    violations.add(method.getName() + " 有一个无注解参数 " + p.getType().getSimpleName()
                            + " —— 白名单外的入参一律不许");
                }
            }
        }

        // 阳性对照: 扫到 0 个 handler 时上面的循环恒不产生 violation, 那这条断言就是恒真式。
        assertTrue(handlerCount >= 2,
                "只扫到 " + handlerCount + " 个 @GetMapping —— 多半是本测试的反射坏了, "
                        + "空集合会让下面那条断言假绿");

        assertTrue(violations.isEmpty(), () -> String.format(
                "🔴 MyWageController 长出了白名单外的入参:%n  %s%n%n"
                        + "这个 controller 的全部安全性建立在「不接受任何工人标识入参」上 —— "
                        + "身份只能来自 token。要支持「班组长看组员」请另开端点 + 另加一道"
                        + "「他是不是我的组员」的闸, ⛔ 不要在这里开口子。",
                String.join("\n  ", violations)));
    }

    /** @RequestMapping 前缀必须钉在 /wage/me 下 —— 换了前缀上面那条反射闸还绿, 但路由已经变了。 */
    @Test
    @DisplayName("端点前缀钉死在 /api/mobile/{factoryId}/wage/me")
    void endpointPrefixIsPinned() {
        RequestMapping mapping = MyWageController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/mobile/{factoryId}/wage/me", mapping.value()[0]);
    }

    // ==================== 3. 权限层 ====================

    /**
     * 用真的 {@link PermissionServiceImpl} 回答两个问题:
     * ① operator 过得了新的 self-wage 码吗 (否则功能对工人不可达);
     * ② operator <b>过不了</b> {@code hr:read}/{@code finance:read} 吗
     *    (证明我没有顺手把工人塞进 hr 权限 —— 那样他就能读全厂工资了)。
     */
    @Test
    @DisplayName("operator 过得了 self-wage 闸, 过不了 hr/finance 闸")
    void operatorPassesTheSelfWageGateButNotTheHrGate() {
        User operator = new User();
        operator.setId(ME_ID);
        operator.setFactoryId(FACTORY);
        operator.setRoleCode("operator");

        assertTrue(realPermissionService.hasPermission(operator, PermissionService.SELF_WAGE_VIEW_PERMISSION),
                "车间工人过不了 self-wage 闸 —— 「我的工资」屏对他不可达, 这个功能等于没做");

        // ⛔ 阴性对照: 这三条一旦变绿, 说明有人把工人塞进了 hr/finance,
        //    那他就能调 WageController 读**全厂**工资 —— 正是本功能要避免的事故。
        assertFalse(realPermissionService.hasPermission(operator, "hr:read"),
                "🔴 车间工人拿到了 hr:read —— 他能读全厂工资单了");
        assertFalse(realPermissionService.hasPermission(operator, "hr:read_write"),
                "🔴 车间工人拿到了 hr:read_write");
        assertFalse(realPermissionService.hasPermission(operator, "finance:read"),
                "🔴 车间工人拿到了 finance:read");
    }

    @Test
    @DisplayName("未激活账号过不了 self-wage 闸")
    void unactivatedUserIsDenied() {
        User ghost = new User();
        ghost.setId(999L);
        ghost.setFactoryId(FACTORY);
        ghost.setRoleCode("unactivated");

        assertFalse(realPermissionService.hasPermission(ghost, PermissionService.SELF_WAGE_VIEW_PERMISSION),
                "未激活账号不该读到任何工资");
    }

    // ==================== 4. 不兜底 ====================

    @Test
    @DisplayName("⛔ 这个月没报过工 —— 说「没有」而不是「0 元」")
    void emptyMonthReportsAbsenceNotZero() throws Exception {
        givenLoggedInAsOperator();
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(eq(FACTORY), eq(ME_ID), any(), any()))
                .thenReturn(List.of());
        when(payrollRecordRepository.findByFactoryIdAndWorkerId(FACTORY, ME_ID))
                .thenReturn(List.of());

        buildMockMvc().perform(get(URL)
                        .header("Authorization", "Bearer " + MY_TOKEN)
                        .requestAttr("userId", ME_ID)
                        .param("month", MONTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work.recordCount").value(0))
                .andExpect(jsonPath("$.data.payrolls").isEmpty())
                // 🔴 一张工资单都没有时, 金额汇总必须是 null (前端显示「—」),
                //    ⛔ 不是一个 0 —— 把「我还没查到」显示成「0 元」是工资场景最糟的错误。
                .andExpect(jsonPath("$.data.payrollTotals").doesNotExist())
                .andExpect(jsonPath("$.data.payrollUnavailableReason").value("NO_PAYROLL_FOR_PERIOD"))
                .andExpect(jsonPath("$.data.calculationUnavailableReason").value("NOT_CALCULATED_YET"));
    }

    @Test
    @DisplayName("⛔ 只有待审核的单时, 已发放合计是 null 而不是 0 (预估和已核准不混成一个数)")
    void unpaidAndPaidAreNeverMerged() throws Exception {
        givenLoggedInAsOperator();
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(eq(FACTORY), eq(ME_ID), any(), any()))
                .thenReturn(List.of());
        when(payrollRecordRepository.findByFactoryIdAndWorkerId(FACTORY, ME_ID))
                .thenReturn(List.of(payroll(7L, ME_ID, ME_NAME, new BigDecimal("880.00"), "PENDING")));

        buildMockMvc().perform(get(URL)
                        .header("Authorization", "Bearer " + MY_TOKEN)
                        .requestAttr("userId", ME_ID)
                        .param("month", MONTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payrollTotals.pendingTotal").value(880.00))
                .andExpect(jsonPath("$.data.payrollTotals.pendingCount").value(1))
                .andExpect(jsonPath("$.data.payrollTotals.paidTotal").doesNotExist())
                .andExpect(jsonPath("$.data.payrollTotals.paidCount").value(0))
                .andExpect(jsonPath("$.data.payrolls[0].paid").value(false))
                .andExpect(jsonPath("$.data.payrolls[0].statusLabel").value("待审核"));
    }

    /**
     * ⛔ 这一屏不给人打分。{@code efficiencyRating}(A/B/C/D) / {@code rankInTeam} /
     * {@code efficiencyScore} / {@code comparedToStandard} 都是<b>存在于实体上</b>的字段,
     * 所以「没投影出去」需要一条闸钉住 —— 否则下一个人顺手 map 一下就出去了。
     *
     * <p>owner 裁定: 质量责任归工厂不归个人, 且奖励产出会诱导少报不良品。
     */
    @Test
    @DisplayName("⛔ 响应里不许出现个人评级 / 排名字段 (owner 裁定不做个人排行榜)")
    void noPersonalRatingOrRankingLeaksIntoTheScreen() throws Exception {
        givenLoggedInAsOperator();

        WorkerDailyEfficiency withRanking = efficiency(ME_ID, ME_NAME, LocalDate.of(2026, 8, 3), "CUTTING", 120, 480);
        withRanking.setRankInTeam(3);                                  // 团队排名
        withRanking.setEfficiencyScore(new BigDecimal("88.50"));       // 效率评分
        withRanking.setComparedToStandard(new BigDecimal("12.30"));    // 与标准对比
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(eq(FACTORY), eq(ME_ID), any(), any()))
                .thenReturn(List.of(withRanking));

        PayrollRecord graded = payroll(1L, ME_ID, ME_NAME, MY_WAGE, "PAID");
        graded.setEfficiencyRating("A");                               // 效率评级
        when(payrollRecordRepository.findByFactoryIdAndWorkerId(FACTORY, ME_ID))
                .thenReturn(List.of(graded));

        String body = buildMockMvc().perform(get(URL)
                        .header("Authorization", "Bearer " + MY_TOKEN)
                        .requestAttr("userId", ME_ID)
                        .param("month", MONTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 阳性对照: 先证明这次请求真的有内容 —— 空响应会让下面四条断言恒真。
        assertTrue(body.contains(MY_WAGE.toPlainString()),
                "响应里连我自己的工资都没有 —— 这一轮什么都没量到, 下面的断言无意义。实际: " + body);

        for (String forbidden : List.of("rankInTeam", "efficiencyScore", "comparedToStandard", "efficiencyRating")) {
            assertFalse(body.contains(forbidden), () ->
                    "⛔ 响应里出现了个人评级/排名字段 " + forbidden + " —— owner 裁定不做个人排行榜, "
                            + "质量责任归工厂不归个人, 奖励产出会诱导少报不良品。\n实际响应: " + body);
        }
    }

    /**
     * ⚠️ 本仓有一道<b>全局</b>的响应剥离切面 {@code PriceFieldResponseAdvice}: 拿不到
     * {@code procurement:price:view} 的角色 (车间工人正在其列), 响应里所有
     * {@code @PriceSensitive} 字段会被<b>置 null</b>。
     *
     * <p>它对本 DTO 目前无害 —— 剥离是<b>注解驱动</b>的, 我们一个都没标。但这个失效模式很阴:
     * 一旦有人给某个工资字段加上 {@code @PriceSensitive}, <b>工人看自己的工资会变成空白</b>,
     * 而所有 standalone MockMvc 测试照样全绿 (切面在 standalone 链路里根本不跑)。
     * 所以钉一条 —— 它红的时候人才会想起来去读这段说明。
     */
    @Test
    @DisplayName("⛔ 工资 DTO 上不许出现 @PriceSensitive (会让工人看自己的工资变空白)")
    void wageDtoIsNotPriceSensitiveAnnotated() {
        List<Class<?>> toScan = new ArrayList<>();
        toScan.add(com.cretas.aims.dto.wage.MyWageResponse.class);
        toScan.addAll(List.of(com.cretas.aims.dto.wage.MyWageResponse.class.getDeclaredClasses()));

        assertTrue(toScan.size() >= 6,
                "只扫到 " + toScan.size() + " 个类 —— 嵌套 record 没被枚举到, 下面的断言会假绿");

        for (Class<?> c : toScan) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                assertFalse(f.isAnnotationPresent(com.cretas.aims.security.PriceSensitive.class),
                        "⛔ " + c.getSimpleName() + "." + f.getName() + " 标了 @PriceSensitive —— "
                                + "PriceFieldResponseAdvice 会把它对车间工人置 null, "
                                + "工人打开「我的工资」看到的是空白, 而且没有任何测试会红。");
            }
        }
    }

    // ==================== fixtures ====================

    private static WorkerDailyEfficiency efficiency(Long workerId, String name, LocalDate date,
                                                    String stage, int pieces, int minutes) {
        WorkerDailyEfficiency e = new WorkerDailyEfficiency();
        e.setId(workerId * 10);
        e.setFactoryId(FACTORY);
        e.setWorkerId(workerId);
        e.setWorkerName(name);
        e.setWorkDate(date);
        e.setProcessStageType(stage);
        e.setTotalPieceCount(pieces);
        e.setQualifiedCount(pieces);
        e.setDefectCount(0);
        e.setEffectiveWorkMinutes(minutes);
        return e;
    }

    private static PayrollRecord payroll(Long id, Long workerId, String name,
                                         BigDecimal totalWage, String status) {
        PayrollRecord p = new PayrollRecord();
        p.setId(id);
        p.setFactoryId(FACTORY);
        p.setWorkerId(workerId);
        p.setWorkerName(name);
        p.setPeriodStart(LocalDate.of(2026, 8, 1));
        p.setPeriodEnd(LocalDate.of(2026, 8, 31));
        p.setPeriodType("MONTHLY");
        p.setStatus(status);
        p.setTotalWage(totalWage);
        p.setPieceRateWage(totalWage);
        p.setTotalPieceCount(120);
        return p;
    }
}
