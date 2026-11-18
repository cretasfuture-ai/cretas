package com.cretas.aims.ai.tool.impl;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.crm.CustomerCreateTool;
import com.cretas.aims.ai.tool.impl.foodsafety.SsopSchedulePlanTool;
import com.cretas.aims.ai.tool.impl.hr.EmployeeWorkLocationQueryTool;
import com.cretas.aims.ai.tool.impl.hr.MyWageQueryTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.wage.MyWageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerAssignmentRepository;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.MyWageQueryService;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 意图蒸馏 v3 复核缺口 (2026-11-18, INTENT-DISTILL-20261111) 新加的 4 个意图:
 * 码 → 工具 / 读写 / 权限码合约, 以及每个工具守住的那条行为 (只看本人 / 字段白名单 / 预览不落库)。
 */
class IntentGapV3ToolsContractTest {

    private static final String FACTORY = "F006";

    // ------------------------------------------------------------------ 码 → 工具 / 读写 / 权限

    @Test
    @DisplayName("目录: 4 个新意图各绑一个工具, 读写与工具自报一致; 成品入库/给客户回消息不在目录里")
    void catalogBindsEachNewIntentToItsTool() throws Exception {
        Map<String, String> expected = Map.of(
                "CUSTOMER_CREATE", "customer_create",
                "HR_MY_WAGE_QUERY", "my_wage_query",
                "HR_EMPLOYEE_WORK_LOCATION_QUERY", "employee_work_location_query",
                "SSOP_SCHEDULE_PLAN_CREATE", "ssop_schedule_plan");
        Map<String, String> bound = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/ai/factory-intent-catalog.json")) {
            assertThat(in).as("目录不在 classpath 上").isNotNull();
            for (JsonNode r : new ObjectMapper().readTree(in).path("canonical_intents")) {
                bound.put(r.path("intent_code").asText(), r.path("tool_name").asText(null));
            }
        }
        assertThat(bound).as("目录一个意图都没读到 = 仪器坏了").hasSizeGreaterThan(200);
        expected.forEach((code, tool) -> assertThat(bound).containsEntry(code, tool));
        // 11-17 范围调整去掉的两条: 不能残留在目录里
        assertThat(bound).doesNotContainKeys("INVENTORY_FINISHED_GOODS_INBOUND_STATUS");
        assertThat(bound.values()).doesNotContain("finished_goods_inbound_status");
    }

    @Test
    @DisplayName("工具元数据: 两个写工具 WRITE + 预览 (走写确认卡), 两个读工具 READ; 权限码各自声明")
    void toolMetadataMatchesTheIntentContract() {
        ToolExecutor customer = new CustomerCreateTool();
        ToolExecutor ssop = new SsopSchedulePlanTool();
        ToolExecutor wage = new MyWageQueryTool();
        ToolExecutor where = new EmployeeWorkLocationQueryTool();

        assertThat(ToolAccessModes.isWrite(customer)).isTrue();
        assertThat(customer.supportsPreview()).isTrue();
        assertThat(ToolAccessModes.isWrite(ssop)).isTrue();
        assertThat(ssop.supportsPreview()).isTrue();
        assertThat(ssop.getActionType()).isEqualTo(ToolExecutor.ActionType.WRITE);
        assertThat(ssop.getRequiredPermissions()).containsExactly("quality:read_write");

        assertThat(ToolAccessModes.isWrite(wage)).isFalse();
        assertThat(wage.getRequiredPermissions()).containsExactly(PermissionService.SELF_WAGE_VIEW_PERMISSION);
        assertThat(ToolAccessModes.isWrite(where)).isFalse();
        assertThat(where.getRequiredPermissions())
                .containsExactlyInAnyOrder("production:read", "procurement:read", "sales:read", "scheduling:read");
    }

    // ------------------------------------------------------------------ 复核原话 → 意图 (真 KB)

    private static IntentKnowledgeBase knowledge;

    @BeforeAll
    static void realKnowledgeBase() {
        knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
    }

    @Nested
    class ReviewPhrases {

        @ParameterizedTest
        @ValueSource(strings = {"那个东城，客户资料帮我录一下，回头好对账", "帮我新建客户", "录入客户资料"})
        void customerCreate(String input) {
            assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("CUSTOMER_CREATE");
        }

        @ParameterizedTest
        @ValueSource(strings = {"这个月的工资条能提前给我看看吗？", "把我工资算下呗", "看看我的工资"})
        void myWage(String input) {
            assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("HR_MY_WAGE_QUERY");
        }

        @ParameterizedTest
        @ValueSource(strings = {"帮我查下张三现在在哪条线，别整别的", "你说的那个老张，是哪个车间的"})
        void employeeWorkLocation(String input) {
            assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("HR_EMPLOYEE_WORK_LOCATION_QUERY");
        }

        @ParameterizedTest
        @ValueSource(strings = {"明天的清洁计划给排一下吧。", "排一下清洁计划"})
        void ssopSchedule(String input) {
            assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("SSOP_SCHEDULE_PLAN_CREATE");
        }

        /** 去掉的成品入库: 原话不再被新短语截走。 */
        @Test
        void finishedGoodsInboundIsNotRoutedToARemovedIntent() {
            assertThat(knowledge.matchPhrase("包装好后入库了没", "FACTORY"))
                    .isNotEqualTo(Optional.of("INVENTORY_FINISHED_GOODS_INBOUND_STATUS"));
        }
    }

    // ------------------------------------------------------------------ 我的工资: 只看本人

    @Nested
    class MyWage {
        private final MyWageQueryTool tool = new MyWageQueryTool();
        private final MyWageQueryService service = mock(MyWageQueryService.class);
        private final UserRepository users = mock(UserRepository.class);

        MyWage() throws Exception {
            inject(tool, "myWageQueryService", service);
            inject(tool, "userRepository", users);
            User me = new User();
            me.setId(7L);
            me.setFactoryId(FACTORY);
            me.setUsername("op7");
            me.setFullName("李四");
            when(users.findByIdAndFactoryId(7L, FACTORY)).thenReturn(Optional.of(me));
        }

        @Test
        @DisplayName("阳性: 不带人 = 查本人, service 按 token 里的本人 id 取数")
        void selfQueryReadsOnlyTheCaller() throws Exception {
            when(service.monthly(eq(FACTORY), eq(7L), anyString(), any(YearMonth.class)))
                    .thenReturn(mock(MyWageResponse.class));
            Map<String, Object> out = call(tool, "doExecute", FACTORY, Map.of("month", "2026-11"), Map.of("userId", 7L));
            verify(service, times(1)).monthly(FACTORY, 7L, "李四", YearMonth.of(2026, 11));
            assertThat(out.get("data")).isNotInstanceOf(Map.class);
        }

        @Test
        @DisplayName("阴性对照: 带别人的名字/工号 ⇒ DENIED, service 一次都没被调")
        void otherPersonIsDeniedAndServiceIsNeverCalled() throws Exception {
            for (Map<String, Object> params : List.<Map<String, Object>>of(
                    Map.of("workerName", "张三"), Map.of("userId", "8"), Map.of("employeeId", 8))) {
                Map<String, Object> out = call(tool, "doExecute", FACTORY, params, Map.of("userId", 7L));
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) out.get("data");
                assertThat(data).as("params=%s", params).containsEntry("status", "DENIED");
                assertThat(String.valueOf(out.get("message"))).contains("只能看你本人");
            }
            verify(service, never()).monthly(anyString(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("本人写自己名字不算别人")
        void namingYourselfIsStillSelf() throws Exception {
            when(service.monthly(anyString(), anyLong(), anyString(), any())).thenReturn(mock(MyWageResponse.class));
            call(tool, "doExecute", FACTORY, Map.of("workerName", "李四"), Map.of("userId", 7L));
            verify(service, times(1)).monthly(eq(FACTORY), eq(7L), anyString(), any());
        }

        @Test
        @DisplayName("当前登录人不属于本厂 ⇒ 拒绝, 不取数")
        void crossFactoryCallerIsRefused() {
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() ->
                    call(tool, "doExecute", "F999", Map.of(), Map.of("userId", 7L)));
            assertThat(t).isNotNull();
            verifyNoInteractions(service);
        }
    }

    // ------------------------------------------------------------------ 查员工在哪条线: 字段白名单

    @Nested
    class EmployeeWhere {
        private final EmployeeWorkLocationQueryTool tool = new EmployeeWorkLocationQueryTool();
        private final UserRepository users = mock(UserRepository.class);

        EmployeeWhere() throws Exception {
            inject(tool, "userRepository", users);
            inject(tool, "workerAssignmentRepository", mock(WorkerAssignmentRepository.class));
            inject(tool, "lineScheduleRepository", mock(LineScheduleRepository.class));
            inject(tool, "productionLineRepository", mock(ProductionLineRepository.class));
        }

        private User user(long id, String name, String code) {
            User u = new User();
            u.setId(id);
            u.setFactoryId(FACTORY);
            u.setFullName(name);
            u.setEmployeeCode(code);
            u.setDepartment("一车间");
            u.setPosition("包装");
            u.setPhone("13800000000");
            u.setEmail("x@example.com");
            return u;
        }

        @Test
        @DisplayName("找到一人: 返回键 ⊆ 白名单, 不出手机号/邮箱; 没排班就说没排班")
        void singleMatchReturnsOnlyWorkFields() throws Exception {
            when(users.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(user(1, "张三", "011")));
            Map<String, Object> out = call(tool, "doExecute", FACTORY, Map.of("name", "张三"), Map.of("userId", 1L));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get("data");
            assertThat(data).containsEntry("status", "FOUND");
            @SuppressWarnings("unchecked")
            Map<String, Object> employee = (Map<String, Object>) data.get("employee");
            assertThat(employee.keySet()).isSubsetOf(Set.of(
                    "name", "employeeCode", "department", "position", "todayLines"));
            assertThat(employee).containsEntry("department", "一车间");
            String text = String.valueOf(out);
            assertThat(text).doesNotContain("13800000000").doesNotContain("x@example.com");
            assertThat(String.valueOf(out.get("message"))).contains("今天没有排班");
        }

        @Test
        @DisplayName("老张 两位: 不猜, NEEDS_SELECTION, 候选也只给工作字段")
        void surnameOnlyAsksToChoose() throws Exception {
            when(users.findByFactoryIdAndIsActive(FACTORY, true))
                    .thenReturn(List.of(user(1, "张三", "011"), user(2, "张伟", "012"), user(3, "王五", "013")));
            Map<String, Object> out = call(tool, "doExecute", FACTORY, Map.of("name", "老张"), Map.of("userId", 1L));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get("data");
            assertThat(data).containsEntry("status", "NEEDS_SELECTION").containsEntry("total", 2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
            candidates.forEach(c -> assertThat(c.keySet())
                    .isSubsetOf(Set.of("name", "employeeCode", "department", "position")));
            assertThat(String.valueOf(out)).doesNotContain("13800000000");
        }
    }

    // ------------------------------------------------------------------ 两个写工具: 预览不落库

    @Nested
    class WritePreviews {

        @Test
        @DisplayName("排清洁计划: 预览列出将排的模板, 不 save; 确认执行才 save, 已排过的跳过")
        void ssopPreviewDoesNotPersist() throws Exception {
            SsopSchedulePlanTool tool = new SsopSchedulePlanTool();
            SsopProcedureRepository procedures = mock(SsopProcedureRepository.class);
            SsopExecutionRecordRepository records = mock(SsopExecutionRecordRepository.class);
            inject(tool, "procedureRepository", procedures);
            inject(tool, "recordRepository", records);
            SsopProcedure daily = SsopProcedure.builder().id(11L).factoryId(FACTORY).procedureCode("SSOP-01")
                    .name("车间地面清洁").frequency("DAILY").build();
            SsopProcedure done = SsopProcedure.builder().id(12L).factoryId(FACTORY).procedureCode("SSOP-02")
                    .name("刀具消毒").frequency("DAILY").build();
            when(procedures.findByFactoryIdAndActiveTrue(FACTORY)).thenReturn(List.of(daily, done));
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            when(records.existsByProcedureIdAndExecutionDate(12L, tomorrow)).thenReturn(true);

            Map<String, Object> preview = call(tool, "doPreview", FACTORY, Map.of(), Map.of("userId", 1L));
            assertThat(preview).containsEntry("status", "PREVIEW").containsEntry("canDo", true);
            assertThat(String.valueOf(preview.get("message"))).contains("车间地面清洁").contains("确认后");
            verify(records, never()).save(any());

            when(records.save(any(SsopExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));
            call(tool, "doExecute", FACTORY, Map.of(), Map.of("userId", 1L));
            verify(records, times(1)).save(any(SsopExecutionRecord.class));
        }

        @Test
        @DisplayName("新建客户: 预览不调 createCustomer; 同名已存在 canDo=false")
        void customerPreviewDoesNotCreate() throws Exception {
            CustomerCreateTool tool = new CustomerCreateTool();
            CustomerService service = mock(CustomerService.class);
            CustomerRepository repo = mock(CustomerRepository.class);
            inject(tool, "customerService", service);
            inject(tool, "customerRepository", repo);

            Map<String, Object> fresh = call(tool, "doPreview", FACTORY, Map.of("name", "东城便利"), Map.of("userId", 1L));
            assertThat(fresh).containsEntry("canDo", true);
            assertThat(String.valueOf(fresh.get("message"))).contains("将新建客户「东城便利」");

            when(repo.existsByFactoryIdAndName(FACTORY, "东城超市")).thenReturn(true);
            Map<String, Object> dup = call(tool, "doPreview", FACTORY, Map.of("name", "东城超市"), Map.of("userId", 1L));
            assertThat(dup).containsEntry("canDo", false).containsEntry("status", "DUPLICATE");

            verify(service, never()).createCustomer(anyString(), any(), any());
        }
    }

    // ------------------------------------------------------------------ helpers

    static void inject(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("no field " + field + " on " + target.getClass());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(Object tool, String method, String factoryId, Map<String, Object> params,
                                    Map<String, Object> context) throws Exception {
        Class<?> c = tool.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(method, String.class, Map.class, Map.class);
                m.setAccessible(true);
                try {
                    return (Map<String, Object>) m.invoke(tool, factoryId, new HashMap<>(params), new HashMap<>(context));
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("no method " + method);
    }
}
