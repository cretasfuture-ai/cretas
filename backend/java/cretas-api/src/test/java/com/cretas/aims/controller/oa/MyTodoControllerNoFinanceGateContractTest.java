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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 纪律排查 B13 线上验收 (2026-11-11): F006 质量经理调「我的待办」被类上的财务门槛 403,
 * 按权限聚合的待办对没有财务权限的岗位整个不生效。
 *
 * <p>这里经真实的 {@link PermissionInterceptor} 打控制器: 一个没有任何财务权限的岗位
 * 必须拿到 200, 且请求要真的落到聚合层 (由聚合层按 TYPE_PERMISSIONS 决定给什么)。
 * 有人把 {@code @RequirePermission(finance:...)} 加回类或方法上, 这两条会红。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("我的待办入口不再要求财务权限 (MyTodoController)")
class MyTodoControllerNoFinanceGateContractTest {

    private static final String F = "F006";

    @Mock private MyTodoAggregatorService aggregator;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new MyTodoController(aggregator))
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /** 一个任何权限码都没有的质量经理: 若入口有任何 @RequirePermission, 拦截器会 403。 */
    private void stubQualityManagerWithoutAnyPermission() {
        User user = new User();
        user.setId(7L);
        user.setRoleCode("quality_manager");
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(false);
        when(permissionService.hasAllPermissions(any(User.class), any(String[].class))).thenReturn(false);
    }

    @Test
    @DisplayName("没有财务权限的质量经理: 待办列表 200, 由聚合层出数")
    void listReachesAggregatorWithoutFinancePermission() throws Exception {
        stubQualityManagerWithoutAnyPermission();
        TodoItemDTO item = new TodoItemDTO();
        item.setType(TodoItemDTO.TodoType.FORCE_INSERT_APPROVAL);
        when(aggregator.listTodos(F, "quality_manager")).thenReturn(List.of(item));

        mvc().perform(get("/api/mobile/" + F + "/my-todos")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "quality_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].type").value("FORCE_INSERT_APPROVAL"));

        verify(aggregator).listTodos(eq(F), eq("quality_manager"));
    }

    @Test
    @DisplayName("没有财务权限的质量经理: 待办数量 200 (以前 403)")
    void countReachesAggregatorWithoutFinancePermission() throws Exception {
        stubQualityManagerWithoutAnyPermission();
        when(aggregator.countTodos(F, "quality_manager")).thenReturn(0);

        mvc().perform(get("/api/mobile/" + F + "/my-todos/count")
                        .requestAttr("userId", 7L)
                        .requestAttr("role", "quality_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));

        verify(aggregator).countTodos(eq(F), eq("quality_manager"));
    }
}
