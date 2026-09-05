package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.dto.customer.CustomerOptionDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.CustomerPriceMemoryService;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * owner 裁定 (2026-09-06, 原文): 「客户名单只有<b>销售、财务、工厂管理员、运营</b>能看。」
 *
 * <p>PR #615 只关了 {@code GET /customers} 一扇门 ——
 * {@link CustomerControllerGetCustomerListPermissionTest} 守那一扇。
 * 本测试守<b>其余 11 条读路径</b> ({@code SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906}),
 * 外加那条为仓储页新开的最小口子。</p>
 *
 * <h3>为什么是「穿过真实拦截器」而不是「读一下注解」</h3>
 * <p>只断言注解存在，证明不了它<b>接上了</b>(形态 B「机制在、没接上」)。这里每条断言都
 * 真的发一次 HTTP 请求，走 {@link PermissionInterceptor} 本体; 阴性那半还额外证明
 * <b>service 一次都没被调用</b> —— 「403 了」和「算出来又扔掉」是两回事。</p>
 *
 * <h3>三个角色为什么选它们 (L1 {@code platform_role_permissions}, 2026-09-06 prod 实测)</h3>
 * <pre>
 *   sales_manager      sales=rw  finance=-   warehouse=-   -> 名单门 200 / 仓储口子 403
 *   production_manager sales=-   finance=-   warehouse=r   -> 名单门 403
 *   warehouse_worker   sales=-   finance=-   warehouse=rw  -> 名单门 403 / 仓储口子 200
 *   hr_admin           sales=-   finance=-   warehouse=-   -> 两边都 403
 * </pre>
 * <p>🔴 {@code warehouse_worker} 是本票的<b>关键阴性对照</b>: 它是那个「因为
 * {@code /active} 没有门才去调它」的仓储页面所用的角色。它在名单门上必须 403,
 * 在新口子上必须 200 —— 两条一起才说明「收窄了但没把页面弄坏」。</p>
 *
 * <h3>⛔ 阳性对照不是摆设</h3>
 * <p>每条 403 断言都配了一条同路径的 200 (同一个 {@code MockMvc}、同一条 URL,
 * 只换角色)。没有它，「403」分不清是「门拦住了」还是「这条 URL 根本没注册 / 路由写错了」。</p>
 *
 * @see CustomerControllerGetCustomerListPermissionTest #615 关的那一扇 (同一模板)
 * @see com.cretas.aims.ai.tool.CustomerListToolAiPathPermissionTest 同一批裁定的 AI 执行点
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerController 其余读路径 — 客户名单收口 (owner 裁定 2026-09-06 第二票)")
class CustomerControllerRemainingReadPathsPermissionTest {

    private static final String FACTORY = "F006";
    private static final String BASE = "/api/mobile/" + FACTORY + "/customers";

    /** 裁定落成的那四个权限码 —— 改这行等于改裁定。 */
    private static final Set<String> ROSTER_CODES = new LinkedHashSet<>(Arrays.asList(
            "sales:read_write", "sales:read", "finance:read_write", "finance:read"));
    /** 仓储最小口子的两个码 —— 与那个页面自己的模块闸同宽。 */
    private static final Set<String> WAREHOUSE_CODES = new LinkedHashSet<>(Arrays.asList(
            "warehouse:read_write", "warehouse:read"));

    // L1 实测持码 (只列本测试用得到的模块)
    private static final Set<String> SALES_MANAGER_CODES = Set.of("sales:read_write", "sales:read");
    private static final Set<String> PRODUCTION_MANAGER_CODES = Set.of("warehouse:read");
    private static final Set<String> WAREHOUSE_WORKER_CODES = Set.of("warehouse:read_write", "warehouse:read");
    private static final Set<String> HR_ADMIN_CODES = Set.of("hr:read_write");

    @Mock private CustomerService customerService;
    @Mock private MobileService mobileService;
    @Mock private PriceMaskResolver priceMaskResolver;
    @Mock private CustomerPriceMemoryService customerPriceMemoryService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    /**
     * 一条被收口的读路径。
     *
     * @param label     人读的名字 (出现在 @DisplayName 里)
     * @param url       完整 URL (带上必需的 query param, 否则 400 会把 403 盖掉)
     * @param javaMethod 控制器方法名 —— 注解形状断言按它反射查
     */
    private record RosterPath(String label, String url, String javaMethod) {
        @Override public String toString() { return label; }
    }

    /**
     * 本票关掉的 11 条 (⛔ 不含 #615 那条 —— 那条由 #615 自己的测试守)。
     *
     * <p>🔴 其中 {@code /vip} 与 {@code /outstanding-balance} <b>不在派活时那张分档表里</b>:
     * 它们是按硬约束 8 把 {@code CustomerController} 上 18 个 {@code @GetMapping} 从头数了
     * 一遍才捞出来的 (同样返回 {@code List<CustomerDTO>})。⇒ 数的是注解, 不是别人给的清单。</p>
     */
    static Stream<RosterPath> closedRosterPaths() {
        return Stream.of(
                // ---- 档 A: 等价于整份名单 ----
                new RosterPath("/export (整表 Excel, 最重)", BASE + "/export", "exportCustomerList"),
                new RosterPath("/active", BASE + "/active", "getActiveCustomers"),
                new RosterPath("/search", BASE + "/search?keyword=a", "searchCustomers"),
                new RosterPath("/by-type", BASE + "/by-type?type=DEALER", "getCustomersByType"),
                new RosterPath("/by-industry", BASE + "/by-industry?industry=餐饮", "getCustomersByIndustry"),
                new RosterPath("/vip (分档表漏了)", BASE + "/vip", "getVIPCustomers"),
                new RosterPath("/outstanding-balance (分档表漏了)",
                        BASE + "/outstanding-balance", "getCustomersWithOutstandingBalance"),
                // ---- 档 B: 单条但敏感 ----
                new RosterPath("/{id}/price-memory (议价历史, 最敏感)",
                        BASE + "/CUST-001/price-memory?productTypeId=PT-1", "getCustomerPriceMemory"),
                new RosterPath("/{id}/purchase-history",
                        BASE + "/CUST-001/purchase-history", "getCustomerPurchaseHistory"),
                new RosterPath("/{id}/statistics", BASE + "/CUST-001/statistics", "getCustomerStatistics"),
                new RosterPath("/{id}", BASE + "/CUST-001", "getCustomerById"));
    }

    /**
     * 显式登记「故意不关」的 6 条 (档 C)。硬约束 8 的另一半:
     * <b>不登记就分不清「漏了」和「有意跳过」。</b>
     *
     * <p>共同判据: <b>返回值里认不出任何一个具体客户</b> —— 要么是 boolean/静态模板,
     * 要么是 {@code 维度 -> 计数} 的聚合。</p>
     */
    static Stream<String> deliberatelyLeftOpenMethods() {
        return Stream.of(
                "checkCustomerCode",                  // 返 boolean, 且必须先知道那个 code
                "downloadCustomerTemplate",           // 静态空模板, 不接 factoryId 之外的数据
                "getCustomerRatingDistribution",      // 评级 -> 计数
                "getCustomerTypeDistribution",        // 类型 -> 计数
                "getCustomerIndustryDistribution",    // 行业 -> 计数
                "getOverallCustomerStatistics");      // 总数 / 汇总额
    }

    // ================================================================ 装配

    private MockMvc buildMockMvc() {
        CustomerController controller = new CustomerController(
                customerService, mobileService, priceMaskResolver, customerPriceMemoryService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /**
     * 按角色在 L1 上实际持有的权限码给 {@link PermissionService} 打桩。
     *
     * <p>⚠️ Mockito varargs 坑 (沿用 #590/#615 的既有先例): {@code getArguments()} 把 varargs
     * <b>按位置摊平</b> —— index 0 是 user, index 1.. 才是各个权限码。
     * ⛔ 不能把 {@code args[1]} 当 {@code String[]} 强转。</p>
     */
    private void stubRole(long userId, String roleCode, Set<String> grantedCodes) {
        User user = new User();
        user.setId(userId);
        user.setFactoryId(FACTORY);
        user.setRoleCode(roleCode);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(any(User.class), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        if (grantedCodes.contains(String.valueOf(args[i]))) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /** 让 11 条路径的 service 调用都返回一个不为 null 的空壳, 使「放行」那半能走到 200。 */
    private void stubServiceHappyPath() {
        when(customerService.getActiveCustomers(anyString())).thenReturn(List.of());
        when(customerService.searchCustomersByName(anyString(), anyString())).thenReturn(List.of());
        when(customerService.getCustomersByType(anyString(), anyString())).thenReturn(List.of());
        when(customerService.getCustomersByIndustry(anyString(), anyString())).thenReturn(List.of());
        when(customerService.getVIPCustomers(anyString(), any())).thenReturn(List.of());
        when(customerService.getCustomersWithOutstandingBalance(anyString())).thenReturn(List.of());
        when(customerService.getCustomerPurchaseHistory(anyString(), anyString())).thenReturn(List.of());
        when(customerService.getCustomerStatistics(anyString(), anyString())).thenReturn(Map.of());
        when(customerService.getCustomerById(anyString(), anyString())).thenReturn(new CustomerDTO());
        when(customerService.exportCustomerList(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new byte[] {1, 2, 3});
        when(priceMaskResolver.shouldMaskPrice(any())).thenReturn(false);
        when(customerPriceMemoryService.getLatest(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    // ================================================================ 正向 (阳性对照)

    /**
     * 阳性对照。⚠️ 它<b>不是</b>「顺便测一下好人能进」——
     * 没有它，下面每条 403 都分不清是「门拦住了」还是「这条 URL 压根没注册」。
     */
    @ParameterizedTest(name = "阳性对照: sales_manager (L1 sales=rw) → 非 403: {0}")
    @MethodSource("closedRosterPaths")
    void salesManagerReachesEveryClosedPath(RosterPath path) throws Exception {
        stubRole(4101L, "sales_manager", SALES_MANAGER_CODES);
        stubServiceHappyPath();

        buildMockMvc().perform(get(path.url())
                        .header("Authorization", "Bearer test-token")
                        .requestAttr("userId", 4101L))
                .andExpect(status().isOk());
    }

    // ================================================================ 阴性对照

    @ParameterizedTest(name = "🔴 裁定后果: production_manager (L1 sales='-' finance='-') → 403: {0}")
    @MethodSource("closedRosterPaths")
    void productionManagerIsForbiddenEverywhere(RosterPath path) throws Exception {
        expectForbiddenAndServiceUntouched(4102L, "production_manager", PRODUCTION_MANAGER_CODES, path);
    }

    /**
     * 🔴 本票的关键阴性对照。{@code warehouse_worker} 正是那个
     * 「因为 {@code /active} 没有门才去调它」的页面所用的角色 —— 它在名单门上必须 403。
     * 它能继续干活由 {@link #warehouseWorkerReachesTheMinimalOptionsEndpoint} 那条保证。
     */
    @ParameterizedTest(name = "🔴 warehouse_worker (L1 sales='-' finance='-' warehouse=rw) → 403: {0}")
    @MethodSource("closedRosterPaths")
    void warehouseWorkerIsForbiddenEverywhere(RosterPath path) throws Exception {
        expectForbiddenAndServiceUntouched(4103L, "warehouse_worker", WAREHOUSE_WORKER_CODES, path);
    }

    private void expectForbiddenAndServiceUntouched(
            long userId, String roleCode, Set<String> codes, RosterPath path) throws Exception {
        stubRole(userId, roleCode, codes);

        buildMockMvc().perform(get(path.url())
                        .header("Authorization", "Bearer test-token")
                        .requestAttr("userId", userId))
                .andExpect(status().isForbidden());

        // 「403」还不够 —— 必须证明 service 一次都没被叫到 (⛔ 不许算出名单再扔掉)。
        verifyNoInteractions(customerService);
        verifyNoInteractions(customerPriceMemoryService);
        // 导出那条还会先解析价格掩码 —— 它同样不该被叫到。
        org.mockito.Mockito.verify(priceMaskResolver, never()).shouldMaskPrice(any());
    }

    // ================================================================ 仓储最小口子 (反向)

    /**
     * 反向断言: 仓储角色<b>能</b>用新口子, 而 {@code hr_admin} 不能。
     *
     * <p>只有正向那半 (warehouse_worker 200) 说明不了什么 —— 一个完全没有门的端点
     * 也长这样。{@code hr_admin} 那条才把「这里真有一道 warehouse 门」证出来。</p>
     */
    @Test
    @DisplayName("仓储口子: warehouse_worker (L1 warehouse=rw) → 200, 且只吐 {id, name}")
    void warehouseWorkerReachesTheMinimalOptionsEndpoint() throws Exception {
        stubRole(4104L, "warehouse_worker", WAREHOUSE_WORKER_CODES);

        CustomerDTO full = new CustomerDTO();
        full.setId("CUST-001");
        full.setName("海鲜批发商");
        full.setPhone("13800000000");
        full.setContactPerson("张三");
        full.setCreditLimit(new BigDecimal("100000"));
        full.setCurrentBalance(new BigDecimal("-2000"));
        full.setTaxNumber("91330000MA2xxxxxx");
        when(customerService.getActiveCustomers(FACTORY)).thenReturn(List.of(full));

        buildMockMvc().perform(get(BASE + "/warehouse-options").requestAttr("userId", 4104L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("CUST-001"))
                .andExpect(jsonPath("$.data[0].name").value("海鲜批发商"))
                // 收窄字段是这个端点存在的一半理由 —— 逐个把不该出现的钉死。
                .andExpect(jsonPath("$.data[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data[0].contactPerson").doesNotExist())
                .andExpect(jsonPath("$.data[0].creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data[0].currentBalance").doesNotExist())
                .andExpect(jsonPath("$.data[0].taxNumber").doesNotExist());
    }

    @Test
    @DisplayName("仓储口子: hr_admin (L1 warehouse='-') → 403, service 未被调用")
    void hrAdminIsBlockedFromTheMinimalOptionsEndpoint() throws Exception {
        stubRole(4105L, "hr_admin", HR_ADMIN_CODES);

        buildMockMvc().perform(get(BASE + "/warehouse-options").requestAttr("userId", 4105L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(customerService);
    }

    /**
     * ⛔ 仓储口子<b>不许</b>被当成一条通用客户下拉再长回去。
     *
     * <p>{@link CustomerOptionDTO} 恰好 2 个分量; 加第 3 个 (比如联系人/电话) 会弄红这条 ——
     * 那时请回去读那个端点的注释, 而不是把这条改成 3。</p>
     */
    @Test
    @DisplayName("CustomerOptionDTO 恰好 {id, name} 两个字段 —— ⛔ 别给它加字段")
    void optionDtoStaysMinimal() {
        assertThat(CustomerOptionDTO.class.getRecordComponents())
                .as("加字段 = 把「一个仓储录入字段的最小值」变成通用客户下拉, 那是绕开裁定")
                .hasSize(2)
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name");
    }

    // ================================================================ 注解形状本身

    /**
     * 上面每条都经过 mock 出来的 {@link PermissionService}，证明的是「拦截器按注解里的码
     * 放行/拒绝」；<b>注解里到底写了哪四个码</b>由这条钉住。
     */
    @ParameterizedTest(name = "注解形状: {0} 恰好带那四个裁定码, 任一即可, ⛔ 无 operations:*")
    @MethodSource("closedRosterPaths")
    void everyClosedPathCarriesExactlyTheRulingCodes(RosterPath path) {
        RequirePermission ann = uniqueMethod(path.javaMethod()).getAnnotation(RequirePermission.class);

        assertThat(ann)
                .as("%s 必须带 @RequirePermission —— 没有它, 客户名单对同厂任何已登录用户开放",
                        path.label())
                .isNotNull();
        assertThat(new LinkedHashSet<>(Arrays.asList(ann.value())))
                .as("注解里的权限码就是 owner 裁定本身, 改它等于改裁定")
                .isEqualTo(ROSTER_CODES);
        assertThat(ann.requireAll())
                .as("四个码是「任一即可」(销售 OR 财务), 不是「全都要」")
                .isFalse();
        assertThat(Arrays.asList(ann.value()))
                .as("⛔ 不许出现 operations:* —— 运营已由 sales:read 覆盖, "
                        + "加它会顺带放行 permission_admin (L1 operations=rw, sales/finance 均为 '-')")
                .noneMatch(code -> code.startsWith("operations:"));
    }

    @Test
    @DisplayName("仓储口子的注解恰好是 warehouse 两个码 —— ⛔ 不许把裁定码混进来")
    void warehouseOptionsCarriesOnlyWarehouseCodes() {
        RequirePermission ann =
                uniqueMethod("getWarehouseCustomerOptions").getAnnotation(RequirePermission.class);

        assertThat(ann).isNotNull();
        assertThat(new LinkedHashSet<>(Arrays.asList(ann.value())))
                .as("与那个页面自己的模块闸 (meta.module='warehouse') 同宽 —— 不多一个")
                .isEqualTo(WAREHOUSE_CODES);
        assertThat(Arrays.asList(ann.value()))
                .as("⛔ 混进 sales/finance 码 = 又造出一条绕过裁定的路")
                .noneMatch(code -> code.startsWith("sales:") || code.startsWith("finance:"));
    }

    /**
     * 硬约束 8 的收尾: <b>改完再数一次, 两个数必须相等。</b>
     *
     * <pre>
     *   19 个 @GetMapping = 12 (裁定码) + 1 (仓储口子) + 6 (显式登记「故意不关」)
     * </pre>
     *
     * <p>🔴 这条断言的价值不在「19」这个数, 在于<b>新增一个读端点时它会红</b> ——
     * 那正是「又开了一扇没人登记的门」的时刻。加端点的人必须回来把它归档到三类之一。</p>
     */
    @Test
    @DisplayName("硬约束 8: 19 个 GET = 12 裁定门 + 1 仓储口子 + 6 显式登记不关 (加新读端点必须回来登记)")
    void everyGetEndpointIsAccountedFor() {
        List<Method> gets = new ArrayList<>();
        for (Method m : CustomerController.class.getDeclaredMethods()) {
            if (m.getAnnotation(GetMapping.class) != null) {
                gets.add(m);
            }
        }

        List<String> rosterGated = new ArrayList<>();
        List<String> warehouseGated = new ArrayList<>();
        List<String> ungated = new ArrayList<>();
        for (Method m : gets) {
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            if (ann == null) {
                ungated.add(m.getName());
            } else if (new LinkedHashSet<>(Arrays.asList(ann.value())).equals(ROSTER_CODES)) {
                rosterGated.add(m.getName());
            } else if (new LinkedHashSet<>(Arrays.asList(ann.value())).equals(WAREHOUSE_CODES)) {
                warehouseGated.add(m.getName());
            } else {
                ungated.add(m.getName() + " (未知权限码组合: " + Arrays.toString(ann.value()) + ")");
            }
        }

        assertThat(gets).as("CustomerController 的 GET 端点总数").hasSize(19);
        assertThat(rosterGated)
                .as("挂裁定四码的 = #615 那条 + 本票 11 条")
                .hasSize(12);
        assertThat(warehouseGated)
                .as("仓储最小口子有且只有一个 —— ⛔ 多一个就是又开了一条绕行")
                .containsExactly("getWarehouseCustomerOptions");
        assertThat(ungated)
                .as("⛔ 无门的 GET 只允许是这 6 条档 C (一个客户身份都不吐)。"
                        + "新增读端点弄红这里, 请回去把它归档到 A/B/C 之一, 不要改这条断言")
                .containsExactlyInAnyOrderElementsOf(deliberatelyLeftOpenMethods().toList());
    }

    private static Method uniqueMethod(String name) {
        List<Method> found = Arrays.stream(CustomerController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .toList();
        assertThat(found)
                .as("控制器上叫 %s 的方法应当恰好一个 (重载会让反射断言瞄错目标)", name)
                .hasSize(1);
        return found.get(0);
    }
}
