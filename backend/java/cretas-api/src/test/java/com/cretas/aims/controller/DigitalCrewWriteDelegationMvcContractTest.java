package com.cretas.aims.controller;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.catalog.WriteIntentPermissionSources;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.crew.DigitalCrewDelegationLockRepository;
import com.cretas.aims.repository.crew.DigitalCrewMemberSettingRepository;
import com.cretas.aims.repository.crew.DigitalCrewRoleMappingRepository;
import com.cretas.aims.repository.crew.DigitalCrewSettingLogRepository;
import com.cretas.aims.repository.crew.DigitalCrewWorkItemRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.execution.IntentPermissionGate;
import com.cretas.aims.service.execution.IntentTurnSharedSteps;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-11-17 BP 截图实测: 数字仓管「今天到的 500kg 牛肉前腱子入原料仓」→ RECEIVE_CONFIRM_CREATE 没登记权限码 ⇒
 * 「系统里没有登记谁有权确认，所以不交办」。回归跑在真实入口 {@code POST /api/mobile/{factoryId}/digital-crew/members/{member}/ask}:
 * MockMvc + 真 {@link PermissionInterceptor} + 真 {@link DigitalCrewController} + 真 {@link DigitalCrewService}
 * + 真 {@link PermissionServiceImpl} (内置岗位矩阵) + 真 {@link IntentPermissionGate}。
 *
 * <p>意图配置<b>不手写</b>: required_permission 取自迁移层 ({@link WriteIntentPermissionSources#migratedPermissions()},
 * 即 V20261117_81 跑完后的值) —— 迁移里把这一行置空, 这里就回到「没有登记」而红。
 *
 * <p>识别 / 执行编排器是桩, 但按编排器同一顺序用真组件出状态: 先意图权限门 ({@link IntentPermissionGate#check}, 无权限 ⇒ NO_PERMISSION),
 * 再咨询 tab 写拦截 ({@link IntentTurnSharedSteps#readModeWriteBlocked}, ⇒ READ_MODE_WRITE_BLOCKED)。
 * 断言「不落业务写」= 编排器的 preview / confirm 一次都没被调, 只调过一次 mode=READ 的识别。
 */
class DigitalCrewWriteDelegationMvcContractTest {

    private static final String F = "F1";
    private static final String RECEIVE_TEXT = "今天到的 500kg 牛肉前腱子入原料仓";
    private static final String QC_TEXT = "这批酱牛肉做个出厂检验，合格";

    private final UserRepository users = mock(UserRepository.class);
    private final DigitalCrewWorkItemRepository items = mock(DigitalCrewWorkItemRepository.class);
    private final DigitalCrewDelegationLockRepository locks = mock(DigitalCrewDelegationLockRepository.class);
    private final IntentExecutorService intents = mock(IntentExecutorService.class);
    private final AIIntentService intentConfigs = mock(AIIntentService.class);
    private final List<DigitalCrewWorkItem> saved = new ArrayList<>();
    private Map<String, String> migrated;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        ReflectionTestUtils.setField(permissions, "platformRepo", mock(PlatformRolePermissionRepository.class));

        ToolRbacGuard rbac = new ToolRbacGuard();
        ReflectionTestUtils.setField(rbac, "userRepository", users);
        ReflectionTestUtils.setField(rbac, "permissionService", permissions);
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", rbac);
        WriteGuardService writeGuard = mock(WriteGuardService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        migrated = WriteIntentPermissionSources.migratedPermissions();
        intent("RECEIVE_CONFIRM_CREATE", "收货确认入库", "receive_confirm_create");
        intent("QUALITY_CHECK_CREATE", "创建质检记录", "quality_check_create");

        when(intents.execute(eq(F), any(IntentExecuteRequest.class), anyLong(), anyString())).thenAnswer(inv -> {
            IntentExecuteRequest req = inv.getArgument(1);
            Long userId = inv.getArgument(2);
            String role = inv.getArgument(3);
            String code = req.getUserInput().contains("入原料仓") ? "RECEIVE_CONFIRM_CREATE" : "QUALITY_CHECK_CREATE";
            AIIntentConfig cfg = intentConfigs.getIntentByCode(F, code).orElseThrow();
            IntentPermissionGate.PermissionCheck check = gate.check(cfg, userId, role);
            if (check.isDenied()) {
                String msg = "您没有权限执行此操作。需要权限: " + check.requiredPermission() + "，请联系工厂管理员开通。";
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(code)
                        .intentName(cfg.getIntentName()).status("NO_PERMISSION").message(msg).formattedText(msg).build();
            }
            IntentExecuteResponse blocked = IntentTurnSharedSteps.readModeWriteBlocked(req, cfg, null, writeGuard);
            assertThat(blocked).as("咨询 tab 下 %s 必须被当写拦住", code).isNotNull();
            return blocked;
        });

        when(locks.existsById(anyString())).thenReturn(true);
        when(locks.lockForUpdate(anyString(), eq(F))).thenReturn(Optional.of(new DigitalCrewDelegationLock()));
        when(items.saveAndFlush(any(DigitalCrewWorkItem.class))).thenAnswer(inv -> {
            DigitalCrewWorkItem w = inv.getArgument(0);
            w.setId(100L + saved.size());
            saved.add(w);
            return w;
        });
        when(items.findByIdAndFactoryId(anyLong(), eq(F))).thenAnswer(inv -> saved.stream()
                .filter(w -> w.getId().equals(inv.getArgument(0))).findFirst());

        DigitalCrewService service = new DigitalCrewService(items, mock(DigitalCrewMemberSettingRepository.class),
                mock(DigitalCrewRoleMappingRepository.class), mock(DigitalCrewSettingLogRepository.class), locks, users,
                permissions, intents, intentConfigs, mock(PreviewTokenService.class),
                mock(FactoryCapabilityPackSelector.class), mock(FactoryBusinessTypeResolver.class),
                mock(PlatformTransactionManager.class));
        mvc = MockMvcBuilders.standaloneSetup(new DigitalCrewController(service))
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        user(41L, "warehouse_manager");
        user(42L, "quality_inspector");
        user(43L, "dispatcher"); // warehouse/quality 只读: 没有入库 / 质检的写权限
    }

    private void intent(String code, String name, String tool) {
        AIIntentConfig cfg = AIIntentConfig.builder().intentCode(code).intentName(name).toolName(tool)
                .intentCategory("DATA_OPERATION").sensitivityLevel("MEDIUM").isActive(true)
                .requiredPermission(migrated.get(code)).build();
        when(intentConfigs.getIntentByCode(F, code)).thenReturn(Optional.of(cfg));
    }

    private void user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("u" + id);
        u.setRoleCode(role);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    private ResultActions ask(long userId, String role, String member, String text) throws Exception {
        return mvc.perform(post("/api/mobile/" + F + "/digital-crew/members/" + member + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("text", text)))
                .requestAttr("userId", userId)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, role)));
    }

    private void noBusinessWrite() {
        verify(intents, never()).preview(any(), any(), anyLong(), anyString());
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        verify(intents, atLeastOnce()).execute(eq(F), argThat(r -> "READ".equals(r.getMode())), anyLong(), anyString());
        verify(intents, never()).execute(eq(F), argThat(r -> !"READ".equals(r.getMode())), anyLong(), anyString());
    }

    @Test
    void keeperTurnsTheBeefReceiptIntoAPendingDelegatedItem() throws Exception {
        assertThat(migrated.get("RECEIVE_CONFIRM_CREATE")).as("迁移层登记了入库确认的权限码").isNotBlank();
        ask(41L, "warehouse_manager", "keeper", RECEIVE_TEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("DELEGATED"))
                .andExpect(jsonPath("$.data.intentCode").value("RECEIVE_CONFIRM_CREATE"))
                .andExpect(jsonPath("$.data.item.status").value("DELEGATED"))
                .andExpect(jsonPath("$.data.item.module").value("warehouse"))
                // 码是「任一即可」(warehouse:read_write,inventory:write): 仓库主管持有其一 ⇒ 能确认
                .andExpect(jsonPath("$.data.item.canConfirm").value(true))
                .andExpect(jsonPath("$.data.text", not(containsString("没有登记"))));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequiredPermission()).isEqualTo(migrated.get("RECEIVE_CONFIRM_CREATE"));
        assertThat(saved.get(0).getQuotaCost()).isZero();
        noBusinessWrite();
    }

    @Test
    void inspectorTurnsAQualityWriteIntoAPendingDelegatedItem() throws Exception {
        assertThat(migrated.get("QUALITY_CHECK_CREATE")).isNotBlank();
        ask(42L, "quality_inspector", "inspector", QC_TEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("DELEGATED"))
                .andExpect(jsonPath("$.data.intentCode").value("QUALITY_CHECK_CREATE"))
                .andExpect(jsonPath("$.data.item.module").value("quality"))
                .andExpect(jsonPath("$.data.item.canConfirm").value(true));
        assertThat(saved).hasSize(1);
        noBusinessWrite();
    }

    /**
     * 阴性对照: 没有入库写权限的账号 (调度: warehouse / inventory 只读)。意图权限门照常判 ⇒ 告诉他「你没有这项写权限」,
     * 不是「系统里没有登记」; 交办给有权限的人 (DECISIONS 第二十一节), 自己确认被服务端 403 拒、确认链路 0 次。
     */
    @Test
    void negativeControl_accountWithoutTheWritePermissionIsToldSoAndCannotConfirm() throws Exception {
        ask(43L, "dispatcher", "keeper", RECEIVE_TEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_PERMISSION"))
                .andExpect(jsonPath("$.data.text", containsString("你没有这项写权限")))
                .andExpect(jsonPath("$.data.text", not(containsString("没有登记"))))
                .andExpect(jsonPath("$.data.item.canConfirm").value(false));
        assertThat(saved).hasSize(1);
        noBusinessWrite();

        DigitalCrewWorkItem w = saved.get(0);
        w.setPreviewedByUserId(43L);
        w.setCommandDigest("a".repeat(64));
        String body = "{\"commandDigest\":\"" + "a".repeat(64) + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(120)
                + "\",\"requestId\":\"req-12345678\",\"idempotencyKey\":\"idem-12345678\"}";
        mvc.perform(post("/api/mobile/" + F + "/digital-crew/items/" + w.getId() + "/confirm")
                        .header("X-Cretas-Confirmation-Token", "proof-token-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .requestAttr("userId", 43L)
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 43L, F, "dispatcher")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("没有")));
        verify(intents, never()).confirm(any(), any(), anyLong(), anyString());
        verify(items, never()).claimForConfirm(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
