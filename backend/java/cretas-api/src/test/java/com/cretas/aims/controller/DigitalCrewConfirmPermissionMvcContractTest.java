package com.cretas.aims.controller;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import com.cretas.aims.repository.crew.DigitalCrewMemberSettingRepository;
import com.cretas.aims.repository.crew.DigitalCrewRoleMappingRepository;
import com.cretas.aims.repository.crew.DigitalCrewSettingLogRepository;
import com.cretas.aims.repository.crew.DigitalCrewWorkItemRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1771 审查 中-5: 「没有事项写权限的人调确认接口 ⇒ HTTP 403、确认链路 0 次」走真实入口 ——
 * MockMvc + 真 {@link PermissionInterceptor} + 真 {@link GlobalExceptionHandler} + 真 {@link DigitalCrewController}
 * + 真 {@link DigitalCrewService} + <b>真 {@link PermissionServiceImpl}</b> (不打桩, 用它的内置岗位矩阵 = 真实角色数据)。
 *
 * <p>质检员: 有 quality:read_write (过得了接口粗筛), 没有 production:read_write (这件事要的) ⇒ 服务端逐件判拒 403。
 * 阳性对照: 工厂超级管理员同一请求过两道闸, 确认链路被调用恰好 1 次 —— 证明 403 不是闸恒红。
 */
class DigitalCrewConfirmPermissionMvcContractTest {

    private static final String F = "F1";
    private static final String DIGEST = "a".repeat(64);

    private final UserRepository users = mock(UserRepository.class);
    private final DigitalCrewWorkItemRepository items = mock(DigitalCrewWorkItemRepository.class);
    private final IntentExecutorService intents = mock(IntentExecutorService.class);
    private final PreviewTokenService previewTokens = mock(PreviewTokenService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 真权限服务: 工厂覆盖 (L2) / 平台默认 (L1) 两张表读得到但都没有行 ⇒ 落到内置岗位矩阵 (真实角色数据)。
        // ⛔ 不能不给仓库: 那是「权限配置读不到」, 真服务会 fail-closed 回 503 (不是本测试要守的 403)。
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        ReflectionTestUtils.setField(permissions, "platformRepo", mock(PlatformRolePermissionRepository.class));
        DigitalCrewService service = new DigitalCrewService(items, mock(DigitalCrewMemberSettingRepository.class),
                mock(DigitalCrewRoleMappingRepository.class), mock(DigitalCrewSettingLogRepository.class), users,
                permissions, intents, mock(AIIntentService.class), previewTokens,
                mock(FactoryCapabilityPackSelector.class), mock(FactoryBusinessTypeResolver.class),
                mock(PlatformTransactionManager.class));
        mvc = MockMvcBuilders.standaloneSetup(new DigitalCrewController(service))
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        user(31L, "quality_inspector");
        user(32L, "factory_super_admin");

        DigitalCrewWorkItem w = new DigitalCrewWorkItem();
        w.setId(5L);
        w.setFactoryId(F);
        w.setMemberKey("planner");
        w.setIntentCode("PRODUCTION_PLAN_CREATE");
        w.setTitle("新建生产计划 · 加 500kg");
        w.setUserInput("加 500kg");
        w.setRequiredPermission("production:read_write");
        w.setModule("production");
        w.setStatus(DigitalCrewWorkItem.Status.DELEGATED);
        w.setCreatedByUserId(99L);
        w.setCreatedByName("操作员");
        w.setPreviewedByUserId(32L);
        w.setCommandDigest(DIGEST);
        when(items.findByIdAndFactoryId(5L, F)).thenReturn(Optional.of(w));
        when(items.claimForConfirm(eq(5L), eq(F), anyLong(), anyString(), eq(DIGEST), any(), any(), any())).thenReturn(1);
        when(items.save(any(DigitalCrewWorkItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intents.confirm(eq(F), any(), anyLong(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").message("已创建").build());
    }

    private void user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("u" + id);
        u.setRoleCode(role);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    private ResultActions confirmAs(long userId, String role) throws Exception {
        String body = "{\"commandDigest\":\"" + DIGEST + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(120)
                + "\",\"requestId\":\"req-12345678\",\"idempotencyKey\":\"idem-12345678\"}";
        return mvc.perform(post("/api/mobile/" + F + "/digital-crew/items/5/confirm")
                .header("X-Cretas-Confirmation-Token", "proof-token-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .requestAttr("userId", userId)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, role)));
    }

    @Test
    void inspectorWithoutTheItemsWritePermissionGets403AndTheConfirmChainIsNeverCalled() throws Exception {
        confirmAs(31L, "quality_inspector")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                // 钉死是服务层逐件判拒 (消息带事项的权限码), 不是拦截器粗筛拒的
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("production:read_write")));
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        verify(items, never()).claimForConfirm(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void positiveControl_superAdminPassesBothGatesAndConfirmRunsExactlyOnce() throws Exception {
        confirmAs(32L, "factory_super_admin").andExpect(status().isOk());
        verify(intents, times(1)).confirm(eq(F), any(), eq(32L), anyString());
    }
}
