package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.CustomerPriceMemoryService;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * owner 裁定 (2026-09-06, 原文): 「客户名单只有<b>销售、财务、工厂管理员、运营</b>能看。」
 *
 * <p>{@code CustomerController#getCustomerList} 此前对同厂任何已登录用户开放 (Bug #318
 * 「读保持开放」)。本测试穿过<b>真实的</b> {@link PermissionInterceptor}，证明新加的
 * {@code @RequirePermission} 真的按裁定放行/拒绝，而不是只在源码里躺着一行注解。</p>
 *
 * <h3>每条断言对应的角色 (L1 {@code platform_role_permissions} 2026-09-06 prod 实测)</h3>
 * <pre>
 *   sales_manager          sales=rw finance=-   -> 200  (销售)
 *   finance_manager        sales=-  finance=rw  -> 200  (财务)
 *   cashier                sales=r  finance=rw  -> 200  (财务)
 *   operations_coordinator sales=r  finance=-   -> 200  (运营, 靠 sales:read 覆盖)
 *   production_manager     sales=-  finance=-   -> 403  🔴 裁定的直接后果
 *   hr_admin               sales=-  finance=-   -> 403
 * </pre>
 * <p>{@code factory_super_admin} 不在这里断言: 它在
 * {@link com.cretas.aims.service.impl.PermissionServiceImpl#hasPermission} 的
 * 「超级管理员拥有所有权限」分支直接 return true，**不经过任何权限码**，
 * 所以这一层 mock 掉 {@code PermissionService} 之后测它等于测 mock 自己。</p>
 *
 * @see MaterialBatchControllerGetMaterialBatchListPermissionTest 同一 MockMvc+PermissionInterceptor 模板 (PR #590)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerController#getCustomerList — 客户名单只有 销售/财务/工厂管理员/运营 能看 (owner 裁定 2026-09-06)")
class CustomerControllerGetCustomerListPermissionTest {

    @Mock private CustomerService customerService;
    @Mock private MobileService mobileService;
    @Mock private PriceMaskResolver priceMaskResolver;
    @Mock private CustomerPriceMemoryService customerPriceMemoryService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    private static final String URL = "/api/mobile/F006/customers";

    /** 裁定落成的那四个权限码 —— 改这行等于改裁定。 */
    private static final Set<String> EXPECTED_CODES = new LinkedHashSet<>(Arrays.asList(
            "sales:read_write", "sales:read", "finance:read_write", "finance:read"));

    private MockMvc buildMockMvc() {
        CustomerController controller = new CustomerController(
                customerService, mobileService, priceMaskResolver, customerPriceMemoryService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /**
     * 按角色在 L1 上实际持有的权限码给 PermissionService 打桩。
     *
     * <p>⚠️ Mockito varargs 坑 (沿用 #590 的既有先例): {@code getArguments()} 把 varargs
     * 按位置摊平 —— index 0 是 user, index 1.. 才是各个权限码。</p>
     */
    private User stubRole(long userId, String roleCode, Set<String> grantedCodes) {
        User user = new User();
        user.setId(userId);
        user.setRoleCode(roleCode);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(eq(user), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        if (grantedCodes.contains(String.valueOf(args[i]))) {
                            return true;
                        }
                    }
                    return false;
                });
        return user;
    }

    private void expectAllowed(long userId, String roleCode, Set<String> grantedCodes) throws Exception {
        stubRole(userId, roleCode, grantedCodes);
        PageResponse<CustomerDTO> page = PageResponse.of(List.of(), 1, 20, 0L);
        when(customerService.getCustomerList(eq("F006"), any(), any(), any(), any(), any()))
                .thenReturn(page);

        buildMockMvc().perform(get(URL).requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(customerService).getCustomerList(eq("F006"), any(), any(), any(), any(), any());
    }

    private void expectForbidden(long userId, String roleCode) throws Exception {
        stubRole(userId, roleCode, Set.of());

        buildMockMvc().perform(get(URL).requestAttr("userId", userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        // 「403」还不够 —— 必须证明 service 一次都没被调用 (没有把名单算出来又扔掉)。
        verify(customerService, never()).getCustomerList(anyString(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 正向: 四类各一条

    @Test
    @DisplayName("销售: sales_manager (L1 sales=rw) → 200, 到达 service")
    void salesManager_reachesService() throws Exception {
        expectAllowed(3001L, "sales_manager", Set.of("sales:read_write", "sales:read"));
    }

    @Test
    @DisplayName("财务: finance_manager (L1 finance=rw, sales='-') → 200 —— 证明 finance 那一支能单独放行")
    void financeManager_reachesService() throws Exception {
        expectAllowed(3002L, "finance_manager", Set.of("finance:read_write", "finance:read"));
    }

    @Test
    @DisplayName("财务: cashier (L1 finance=rw / sales=r) → 200")
    void cashier_reachesService() throws Exception {
        expectAllowed(3003L, "cashier", Set.of("finance:read_write", "finance:read", "sales:read"));
    }

    /**
     * 运营。⚠️ 这条的信息量全在桩里: {@code operations_coordinator} 在 L1 上<b>只有
     * {@code sales=r}</b> (finance='-')，所以桩里只给 {@code sales:read} 一个码 ——
     * 它 200 就证明「不必额外加 {@code operations:*} 码，运营已被 {@code sales:read} 覆盖」。
     */
    @Test
    @DisplayName("运营: operations_coordinator 只持 sales:read → 200 (所以注解里不需要 operations:* 码)")
    void operationsCoordinator_passesViaSalesRead() throws Exception {
        expectAllowed(3004L, "operations_coordinator", Set.of("sales:read"));
    }

    // ---------------------------------------------------------------- 阴性对照

    /**
     * 🔴 这条是裁定的<b>直接后果</b>，不是回归。owner 被告知过
     * ({@code production_manager} 建的 20 个生产计划里 7 个挂了客户,
     * {@code ProductionPlanManagementScreen} 的客户下拉会变空) 且没有推翻。
     *
     * <p>⛔ 谁想「顺手把生产经理放进来」，必须先弄红这条测试 —— 那时请回去读 owner 裁定，
     * 而不是把这条删掉。</p>
     */
    @Test
    @DisplayName("🔴 裁定后果: production_manager (L1 sales='-' finance='-') → 403, service 未被调用")
    void productionManager_isForbidden_deliberateConsequence() throws Exception {
        expectForbidden(3005L, "production_manager");
    }

    @Test
    @DisplayName("hr_admin (L1 sales='-' finance='-') → 403, service 未被调用")
    void hrAdmin_isForbidden() throws Exception {
        expectForbidden(3006L, "hr_admin");
    }

    // ---------------------------------------------------------------- 注解形状本身

    /**
     * 上面每条断言都经过 mock 出来的 {@code PermissionService}，所以它们证明的是
     * 「拦截器按注解里的码放行/拒绝」；<b>注解里到底写了哪四个码</b>由这条钉住。
     *
     * <p>阴性对照那半 ({@code operations:*} 不许出现) 同样是裁定的一部分:
     * {@code permission_admin} 在 L1 上 {@code operations=rw} 而 sales/finance 都是 '-',
     * 加 {@code operations:*} 会把它放进来，那是四类之外的角色。</p>
     */
    @Test
    @DisplayName("注解形状: 恰好 sales/finance 四个码; ⛔ 不含 operations:* (否则 permission_admin 会被顺带放行)")
    void annotationCarriesExactlyTheRulingCodes() throws Exception {
        Method m = CustomerController.class.getDeclaredMethod(
                "getCustomerList", String.class, com.cretas.aims.dto.common.PageRequest.class,
                String.class, String.class,
                com.cretas.aims.entity.enums.CustomerStatus.class,
                com.cretas.aims.entity.enums.CustomerImportance.class,
                com.cretas.aims.entity.enums.CustomerSource.class);

        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertThat(ann)
                .as("getCustomerList 必须带 @RequirePermission —— 没有它，客户名单对同厂任何已登录用户开放")
                .isNotNull();
        assertThat(new LinkedHashSet<>(Arrays.asList(ann.value())))
                .as("注解里的权限码就是 owner 裁定本身, 改它等于改裁定")
                .isEqualTo(EXPECTED_CODES);
        assertThat(ann.requireAll())
                .as("四个码是「任一即可」(销售 OR 财务), 不是「全都要」")
                .isFalse();
        assertThat(Arrays.asList(ann.value()))
                .as("⛔ 不许出现 operations:* —— 运营已由 sales:read 覆盖, 加它会顺带放行 permission_admin")
                .noneMatch(code -> code.startsWith("operations:"));
    }
}
