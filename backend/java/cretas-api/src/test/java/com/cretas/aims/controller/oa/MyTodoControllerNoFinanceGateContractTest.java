package com.cretas.aims.controller.oa;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.oa.MyTodoAggregatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 纪律排查 B13 线上验收 (2026-11-11): F006 质量经理调「我的待办」被类上的财务门槛 403,
 * 按权限聚合的待办对没有财务权限的岗位整个不生效。
 *
 * <p>经真实的 {@link PermissionInterceptor} 打控制器:
 * <ul>
 *   <li>没有任何财务权限的岗位拿到 200, 请求落到「按具体用户」的聚合 (个人权限覆盖由聚合层叠加);
 *       有人把 {@code @RequirePermission(finance:...)} 加回来, 这两条会红 (403)。</li>
 *   <li>库里的用户不属于路径工厂 → 空列表, 聚合层不被调用。</li>
 *   <li>岗位按库里的用户, 不按 token 里的 role 属性 (token 里写着 finance_manager 也不算)。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("我的待办入口不再要求财务权限, 按具体用户出数 (MyTodoController)")
class MyTodoControllerNoFinanceGateContractTest {

    private static final String F = "F006";

    @Mock private MyTodoAggregatorService aggregator;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new MyTodoController(aggregator, userRepository))
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /** 一个任何权限码都没有的质量经理: 若入口有任何 @RequirePermission, 拦截器会 403。 */
    private User qualityManagerWithoutAnyPermission(String factoryId) {
        User user = new User();
        user.setId(7L);
        user.setRoleCode("quality_manager");
        user.setFactoryId(factoryId);
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(false);
        when(permissionService.hasAllPermissions(any(User.class), any(String[].class))).thenReturn(false);
        return user;
    }

    @Test
    @DisplayName("没有财务权限的质量经理: 待办列表 200, 由「按具体用户」的聚合出数")
    void listReachesUserAggregationWithoutFinancePermission() throws Exception {
        User user = qualityManagerWithoutAnyPermission(F);
        TodoItemDTO item = new TodoItemDTO();
        item.setType(TodoItemDTO.TodoType.FORCE_INSERT_APPROVAL);
        when(aggregator.listTodosForUser(F, user)).thenReturn(List.of(item));

        mvc().perform(get("/api/mobile/" + F + "/my-todos")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "finance_manager"))   // token 里的旧岗位不作数
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].type").value("FORCE_INSERT_APPROVAL"));

        verify(aggregator).listTodosForUser(eq(F), eq(user));
        verify(aggregator, never()).listTodos(anyString(), anyString());
    }

    @Test
    @DisplayName("没有财务权限的质量经理: 待办数量 200 (以前 403)")
    void countReachesUserAggregationWithoutFinancePermission() throws Exception {
        User user = qualityManagerWithoutAnyPermission(F);
        when(aggregator.listTodosForUser(F, user)).thenReturn(List.of());

        mvc().perform(get("/api/mobile/" + F + "/my-todos/count")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "quality_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));

        verify(aggregator).listTodosForUser(eq(F), eq(user));
    }

    @Test
    @DisplayName("库里的用户不属于路径工厂: 空列表, 聚合层不被调用")
    void userOfAnotherFactoryGetsNothing() throws Exception {
        qualityManagerWithoutAnyPermission("F999");

        mvc().perform(get("/api/mobile/" + F + "/my-todos")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "quality_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(aggregator, never()).listTodosForUser(anyString(), any());
        verify(aggregator, never()).listTodos(anyString(), anyString());
    }

    @Test
    @DisplayName("B4 能办类型: 按库里的用户出, token 里的岗位不作数; 没有财务权限也 200")
    void typesReachUserAggregation() throws Exception {
        User user = qualityManagerWithoutAnyPermission(F);
        when(aggregator.availableTypesForUser(F, user))
                .thenReturn(java.util.Set.of(TodoItemDTO.TodoType.FORCE_INSERT_APPROVAL));

        mvc().perform(get("/api/mobile/" + F + "/my-todos/types")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "finance_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0]").value("FORCE_INSERT_APPROVAL"));

        verify(aggregator).availableTypesForUser(eq(F), eq(user));
        verify(aggregator, never()).availableTypes(anyString(), anyString());
    }

    @Test
    @DisplayName("B4 能办类型: 库里的用户不属于路径工厂 → 空数组 (App 不显示待办入口), 聚合层不被调用")
    void typesForUserOfAnotherFactoryAreEmpty() throws Exception {
        qualityManagerWithoutAnyPermission("F999");

        mvc().perform(get("/api/mobile/" + F + "/my-todos/types")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "quality_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(aggregator, never()).availableTypesForUser(anyString(), any());
    }
}
