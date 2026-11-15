package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.quality.firstarticle.FirstArticleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首检判定的权限走<b>真实的</b> PermissionInterceptor + PermissionServiceImpl (只反射看注解照不出没接上)。
 *
 * <p>阴性: 只有生产写权限的操作员调判定 / 判定预览 / 作废 / 维护标准 → 403, 服务一次都没调。
 * 阳性对照: 同一个操作员能送检 (说明不是整个控制器都拒); 质量写权限的质检员能判定。
 */
class FirstArticlePermissionInterceptorContractTest {

    private static final String F = "F006";

    private final Map<String, Map<String, String>> fakeL1 = new HashMap<>();
    private final Map<Long, User> users = new HashMap<>();
    private FirstArticleService service;
    private MockMvc mvc;
    private long nextId = 7100;

    @BeforeEach
    void setUp() {
        // 两个岗位的模块级别显式给出, 不依赖硬编码兜底矩阵
        fakeL1.put(FactoryUserRole.operator.name(), Map.of("production", "rw", "quality", "r"));
        fakeL1.put(FactoryUserRole.quality_inspector.name(), Map.of("quality", "rw", "production", "r"));
        PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = fakeL1.getOrDefault(inv.<String>getArgument(0), Collections.emptyMap()).get(inv.<String>getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(new ArrayList<>());
        FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
        when(factoryRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenReturn(Collections.emptyList());
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", factoryRepo);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.<Long>getArgument(0))));

        service = mock(FirstArticleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FirstArticleController(service))
                .addInterceptors(new PermissionInterceptor(permissions, userRepository, new ObjectMapper()))
                .build();
    }

    private long login(FactoryUserRole role) {
        User u = new User();
        u.setId(nextId++);
        u.setRoleCode(role.name());
        u.setFactoryId(F);
        u.setIsActive(true);
        users.put(u.getId(), u);
        return u.getId();
    }

    private ResultActions postAs(long userId, String path, String body) throws Exception {
        return mvc.perform(post("/api/mobile/" + F + "/first-article" + path)
                .requestAttr("userId", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("阴性: 只有生产写权限的操作员 → 判定 / 判定预览 / 作废 / 存标准 / 停用标准 全部 403, 服务没被调")
    void operatorWithoutQualityWriteCannotJudge() throws Exception {
        long operator = login(FactoryUserRole.operator);
        String judge = "{\"verdict\":\"PASSED\",\"results\":[]}";
        postAs(operator, "/inspections/42/judge", judge).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        postAs(operator, "/inspections/42/judge/preview", judge).andExpect(status().isForbidden());
        postAs(operator, "/inspections/42/void", "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        postAs(operator, "/standards", "{\"workProcessId\":\"wp\",\"name\":\"n\",\"items\":[]}")
                .andExpect(status().isForbidden());
        postAs(operator, "/standards/5/deactivate", "{\"reason\":\"x\"}").andExpect(status().isForbidden());

        verify(service, never()).judge(anyString(), anyLong(), any(), any());
        verify(service, never()).previewJudge(anyString(), anyLong(), any(), any());
        verify(service, never()).voidInspection(anyString(), anyLong(), any(), any());
        verify(service, never()).saveStandard(anyString(), any(), any());
        verify(service, never()).deactivateStandard(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("阳性对照: 同一操作员能送检 (控制器没有整体拒); 质检员能判定")
    void operatorCanRequestAndInspectorCanJudge() throws Exception {
        long operator = login(FactoryUserRole.operator);
        postAs(operator, "/inspections", "{\"taskId\":31}").andExpect(status().isOk());
        verify(service).requestInspection(eq(F), any(), eq(operator));

        long inspector = login(FactoryUserRole.quality_inspector);
        postAs(inspector, "/inspections/42/judge", "{\"verdict\":\"PASSED\",\"results\":[]}")
                .andExpect(status().isOk());
        verify(service).judge(eq(F), eq(42L), any(), any());
    }

    @Test
    @DisplayName("没登录 (没有 userId) → 401, 判定服务没被调")
    void anonymousCannotJudge() throws Exception {
        mvc.perform(post("/api/mobile/" + F + "/first-article/inspections/42/judge")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).judge(anyString(), anyLong(), any(), any());
    }
}
