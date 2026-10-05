package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPERATE 模式 (操作 tab) 的识别层候选过滤 —— 走<b>真的</b>权限判定链。
 *
 * <p>链路: {@link IntentPermissionGate#resolveUserPermissions} → {@link ToolRbacGuard#grantedAmong}
 * → 真 {@link PermissionServiceImpl} (L1 行由桩给出, L2 空) → 真 {@link IntentAccessModeFilter}。
 * 只桩掉仓储与工具注册表。
 *
 * <p>守的缺陷 (2026-10-05): 权限集原来取自 {@code getUserPermissions} —— 只读硬编码矩阵、只产出
 * {@code :read} / {@code :write}; 过滤又拿 requiredPermission <b>整串</b> {@code contains}。
 * 于是 {@code warehouse:read_write} 这种码、以及任何逗号多码, 在 OPERATE 模式下对<b>所有角色</b>恒被剔除。
 */
class OperateModeCandidatePermissionTest {

    private static final String FACTORY = "F006";
    private static final long USER_ID = 77001L;

    private static ToolExecutor tool(String name, AccessMode mode) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public AccessMode getAccessMode() { return mode; }
        };
    }

    private static AIIntentConfig intent(String code, String toolName, String requiredPermission) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(code);
        cfg.setToolName(toolName);
        cfg.setSensitivityLevel("LOW");
        cfg.setRequiredPermission(requiredPermission);
        return cfg;
    }

    /** 意图目录: 三个写意图 (多码 any-of / 单个 read_write / 用户真没有的) + 一个读意图。 */
    private static final Map<String, AIIntentConfig> INTENTS = new LinkedHashMap<>();
    static {
        INTENTS.put("MATERIAL_BATCH_CREATE", intent("MATERIAL_BATCH_CREATE", "w_material", "warehouse:read_write,inventory:write"));
        INTENTS.put("INVENTORY_FREEZE", intent("INVENTORY_FREEZE", "w_inventory", "inventory:read_write"));
        INTENTS.put("VOUCHER_CREATE", intent("VOUCHER_CREATE", "w_finance", "finance:read_write"));
        INTENTS.put("MATERIAL_BATCH_QUERY", intent("MATERIAL_BATCH_QUERY", "r_material", null));
    }

    private PermissionServiceImpl policy;

    /** 组装真链路, 角色的 L1 模块级别由 {@code l1} 给出 (没列的模块 = "-")。 */
    private IntentPermissionGate gateFor(String role, Map<String, String> l1) {
        User user = new User();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setRoleCode(role);
        user.setIsActive(true);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        policy = new PermissionServiceImpl();
        PlatformRolePermissionRepository platform = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository factory = mock(FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(platform, factory);
        when(platform.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(call -> {
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(call.getArgument(0));
            row.setModuleCode(call.getArgument(1));
            row.setPermissionLevel(l1.getOrDefault(call.getArgument(1, String.class), "-"));
            return Optional.of(row);
        });
        ReflectionTestUtils.setField(policy, "platformRepo", platform);
        ReflectionTestUtils.setField(policy, "factoryConfigRepo", factory);

        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", policy);

        IntentConfigManagementService configs = configService();
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", guard);
        ReflectionTestUtils.setField(gate, "configService", configs);
        return gate;
    }

    private static IntentConfigManagementService configService() {
        IntentConfigManagementService configs = mock(IntentConfigManagementService.class);
        when(configs.getAllIntents(FACTORY)).thenReturn(new ArrayList<>(INTENTS.values()));
        when(configs.getIntentByCode(eq(FACTORY), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(INTENTS.get(inv.getArgument(1, String.class))));
        return configs;
    }

    private static IntentAccessModeFilter filter() {
        ToolRegistry registry = mock(ToolRegistry.class);
        Map<String, ToolExecutor> tools = Map.of(
                "w_material", tool("w_material", AccessMode.WRITE),
                "w_inventory", tool("w_inventory", AccessMode.WRITE),
                "w_finance", tool("w_finance", AccessMode.WRITE),
                "r_material", tool("r_material", AccessMode.READ));
        when(registry.getExecutor(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tools.get(inv.getArgument(0, String.class))));
        return new IntentAccessModeFilter(configService(), new WriteGuardService(), registry);
    }

    private static IntentMatchResult allCandidates() {
        List<IntentMatchResult.CandidateIntent> cands = new ArrayList<>();
        for (String code : INTENTS.keySet()) {
            cands.add(IntentMatchResult.CandidateIntent.builder().intentCode(code).intentName(code).confidence(0.8).build());
        }
        return IntentMatchResult.builder().topCandidates(cands).build();
    }

    private static List<String> kept(IntentMatchResult r) {
        List<String> out = new ArrayList<>();
        r.getTopCandidates().forEach(c -> out.add(c.getIntentCode()));
        return out;
    }

    private List<String> operateCandidatesFor(String role, Map<String, String> l1) {
        Set<String> granted = gateFor(role, l1).resolveUserPermissions(USER_ID, FACTORY);
        assertThat(granted).as("权限集解析不能失败 (null 会让过滤整体跳过, 读数就没有意义)").isNotNull();
        return kept(filter().filterForMode(allCandidates(), FACTORY, "OPERATE", granted));
    }

    @Test
    @DisplayName("仓库员 (warehouse/inventory=rw): read_write 写意图与多码写意图都留下, 真没有的 finance 被剔")
    void warehouseWorkerKeepsReadWriteIntents() {
        List<String> kept = operateCandidatesFor("warehouse_worker", Map.of("warehouse", "rw", "inventory", "rw"));
        assertThat(kept).containsExactly("MATERIAL_BATCH_CREATE", "INVENTORY_FREEZE", "MATERIAL_BATCH_QUERY");
    }

    @Test
    @DisplayName("权限集与执行期鉴权同一个答案: 逐码等于 PermissionService.hasAnyPermission")
    void grantedSetAgreesWithAuthoritativeCheck() {
        IntentPermissionGate gate = gateFor("warehouse_worker", Map.of("warehouse", "rw", "inventory", "rw"));
        Set<String> granted = gate.resolveUserPermissions(USER_ID, FACTORY);
        User user = new User();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setRoleCode("warehouse_worker");
        user.setIsActive(true);
        for (String code : List.of("warehouse:read_write", "inventory:write", "inventory:read_write", "finance:read_write")) {
            assertThat(granted.contains(code)).as(code).isEqualTo(policy.hasAnyPermission(user, code));
        }
        // 正是旧来源产不出的那个码
        assertThat(granted).contains("warehouse:read_write");
        assertThat(policy.getUserPermissions(user)).doesNotContain("warehouse:read_write");
    }

    @Test
    @DisplayName("L1 是真的被读了: 硬编码矩阵里质检员没有仓库写, L1 给了它就留下")
    void l1LevelIsHonoured() {
        User inspector = new User();
        inspector.setRoleCode("quality_inspector");
        PermissionServiceImpl bare = new PermissionServiceImpl();
        assertThat(bare.getUserPermissions(inspector))
                .as("前提: 硬编码矩阵里质检员没有 warehouse 写, 否则本条证明不了 L1 被读")
                .doesNotContain("warehouse:write");

        List<String> kept = operateCandidatesFor("quality_inspector", Map.of("quality", "rw", "warehouse", "rw"));
        assertThat(kept).containsExactly("MATERIAL_BATCH_CREATE", "MATERIAL_BATCH_QUERY");
    }

    @Test
    @DisplayName("对照: 没有任何仓库/库存权限的角色, 三个写意图全剔、读意图留下")
    void roleWithoutPermissionsKeepsOnlyReads() {
        List<String> kept = operateCandidatesFor("quality_inspector", Map.of("quality", "rw"));
        assertThat(kept).containsExactly("MATERIAL_BATCH_QUERY");
    }
}
