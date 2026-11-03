package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.impl.report.ReportFinanceTool;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.security.PriceMaskResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /reports/finance (应收 / 应付 / 客户预收 / 收入 / 成本) 与 App 同口径: App 里只在「经营分析」页出现
 * (web 路由 module=analytics; RN 老板 / 厂长报表与处理看板的时段成本分析), 没有经营分析模块的角色 —— 生产 /
 * 销售 / 采购主管 (prod L1 analytics「-」) —— 接口也不给。与 #1408 的 AI 工具 report_finance 同一组门槛 (同 #1379 的原则)。
 *
 * <p>真实 {@link PermissionInterceptor} + 真实 {@link PermissionServiceImpl} (prod L1, F006 无这些角色的 L2 覆盖)。
 */
class ReportsFinanceAnalyticsGateContractTest {

    private static final String F = "F006";
    /** prod platform_role_permissions (2026-11-03 只读核对)。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "factory_super_admin", Map.of("analytics", "rw", "finance", "rw", "procurement", "rw"),
            "finance_manager", Map.of("analytics", "rw", "finance", "rw", "procurement", "-"),
            "department_admin", Map.of("analytics", "rw", "finance", "r", "procurement", "r"),
            "production_manager", Map.of("analytics", "-", "finance", "-", "procurement", "-"),
            "sales_manager", Map.of("analytics", "-", "finance", "-", "procurement", "-"),
            "procurement_manager", Map.of("analytics", "-", "finance", "-", "procurement", "rw"),
            "warehouse_manager", Map.of("analytics", "-", "finance", "-", "procurement", "r"));
    private static final Path REPO = Path.of("../../..");

    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private PermissionServiceImpl permissions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l2.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(List.of());
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = L1.getOrDefault(inv.getArgument(0), Map.of()).get(inv.getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        long id = 800;
        for (String role : L1.keySet()) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("rf-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }
        ReportService reports = mock(ReportService.class);
        when(reports.getFinanceReport(eq(F), any(), any())).thenAnswer(inv -> {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("accountsReceivable", new BigDecimal("22986.50"));
            report.put("customerPrepayments", new BigDecimal("11433.62"));
            report.put("accountsPayable", new BigDecimal("2896492.69"));
            return report;
        });
        ReportController controller = new ReportController(reports, mock(PriceMaskResolver.class),
                mock(PlanCostVarianceService.class), mock(ProductionPlanRepository.class),
                mock(ProcessSecretLlmCostScope.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, users, json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @ParameterizedTest(name = "{0}: 没有经营分析模块 → 403 (这道闸, 这句话)")
    @ValueSource(strings = {"production_manager", "sales_manager", "procurement_manager"})
    void rolesWithoutAnalyticsGet403FromTheAnalyticsGate(String role) throws Exception {
        User user = users.findById(idOf.get(role)).orElseThrow();
        // 前提: 他们过得了原来那组门槛 (procurement:price:view), 拦住他们的是新加的经营分析这一组
        assertThat(permissions.hasAnyPermission(user, financeGate().value())).as("role=%s 过原门槛", role).isTrue();

        MvcResult r = call(role);
        JsonNode body = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).as(body.toString()).isEqualTo(403);
        assertThat(body.path("message").asText()).as("role=%s 文案", role).contains("数据分析").doesNotContain("财务");
        assertThat(body.toString()).doesNotContain("22986.5", "11433.62", "2896492.69");
    }

    /** 阴性对照的另一侧: 连原门槛都过不了的角色, 由原门槛拒绝 (文案不是经营分析这一句)。 */
    @Test
    void aRoleOutsideTheOriginalGateIsRefusedByThatGate() throws Exception {
        MvcResult r = call("warehouse_manager");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("message").asText())
                .doesNotContain("数据分析");
    }

    @ParameterizedTest(name = "{0}: 200 且看得到往来余额")
    @ValueSource(strings = {"finance_manager", "factory_super_admin", "department_admin"})
    void rolesWithAnalyticsGet200(String role) throws Exception {
        MvcResult r = call(role);
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(200);
        JsonNode data = json.readTree(text).path("data");
        assertThat(data.path("accountsReceivable").decimalValue()).isEqualByComparingTo("22986.50");
        assertThat(data.path("accountsPayable").decimalValue()).isEqualByComparingTo("2896492.69");
    }

    /** 对话与接口同一组门槛 (#1408 的 report_finance)。 */
    @Test
    void chatToolAndRestEndpointHaveTheSameGates() {
        RequirePermission gate = financeGate();
        ReportFinanceTool tool = new ReportFinanceTool();
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrder(gate.value());
        assertThat(tool.getAlsoRequiredPermissions()).containsExactlyInAnyOrder(gate.alsoAny());
        assertThat(gate.alsoAny()).containsExactlyInAnyOrder("analytics:read", "analytics:read_write");
    }

    /**
     * 调用方: web 只有「经营分析」页 (module=analytics) 调它; RN 进「时段成本分析」的入口都按经营分析模块显示,
     * 没有就不放入口 (不留点进去才 403 的死路)。
     */
    @Test
    void everyAppEntryToTheFinanceReportIsBehindTheAnalyticsModule() throws Exception {
        String web = Files.readString(REPO.resolve("web-admin/src/router/modules/smartbi.ts"), StandardCharsets.UTF_8);
        int hub = web.indexOf("path: 'analysis-hub'");
        assertThat(hub).isPositive();
        assertThat(web.substring(hub, web.indexOf("}", web.indexOf("meta", hub)))).contains("module: 'analytics'");

        for (String screen : List.of("ProcessingDashboard.tsx", "AIReportListScreen.tsx")) {
            String src = Files.readString(REPO.resolve("frontend/CretasFoodTrace/src/screens/processing/" + screen),
                    StandardCharsets.UTF_8);
            assertThat(src).as(screen).contains("hasModulePermission(user, 'analytics', 'read')");
            Matcher nav = Pattern.compile("navigate\\('TimeRangeCostAnalysis'").matcher(src);
            int entries = 0;
            while (nav.find()) {
                entries++;
                String before = src.substring(Math.max(0, nav.start() - 400), nav.start());
                assertThat(before).as("%s 第 %d 个入口要在 canSeeFinanceReport 之后", screen, entries)
                        .contains("canSeeFinanceReport && (");
            }
            assertThat(entries).as(screen).isPositive();
        }
        // RN 里没有别处再进时段成本分析 / 直接调财务报表
        try (var files = Files.walk(REPO.resolve("frontend/CretasFoodTrace/src/screens"))) {
            List<String> others = files.filter(p -> p.toString().endsWith(".tsx"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("navigate('TimeRangeCostAnalysis'");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !List.of("ProcessingDashboard.tsx", "AIReportListScreen.tsx").contains(n))
                    .toList();
            assertThat(others).isEmpty();
        }
    }

    private static RequirePermission financeGate() {
        try {
            return ReportController.class.getMethod("getFinanceReport", String.class,
                    java.time.LocalDate.class, java.time.LocalDate.class).getAnnotation(RequirePermission.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult call(String role) throws Exception {
        return mvc.perform(get("/api/mobile/" + F + "/reports/finance").requestAttr("userId", idOf.get(role))).andReturn();
    }
}
