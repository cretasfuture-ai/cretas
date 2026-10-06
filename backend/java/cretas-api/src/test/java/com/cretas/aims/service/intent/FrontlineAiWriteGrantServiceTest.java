package com.cretas.aims.service.intent;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.config.AiFrontlineIntentGrant;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一线角色 AI 写操作开放名单 (owner 2026-10-06: 先收窄, 管理端可开放)。
 *
 * <p>「写」的判定用真 {@link WriteGuardService} (与 W0 写确认同一个), 只桩掉仓储、意图目录、工具注册表。
 * 开放记录用一个内存列表模拟表, 让「开放 → 立刻生效 / 关闭 → 立刻失效」走的是真实的缓存清理。
 */
class FrontlineAiWriteGrantServiceTest {

    private static final String F006 = "F006";
    private static final String F007 = "F007";

    private AiFrontlineIntentGrantRepository repo;
    private IntentConfigManagementService configs;
    private ToolRegistry registry;
    private FrontlineAiWriteGrantService service;

    /** 模拟表: 只有 revokedAt 为空的算有效。 */
    private final List<AiFrontlineIntentGrant> table = new ArrayList<>();

    private final Map<String, AIIntentConfig> intents = new LinkedHashMap<>();
    private final Map<String, ToolExecutor> tools = new LinkedHashMap<>();

    private static ToolExecutor tool(String name, AccessMode mode) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public AccessMode getAccessMode() { return mode; }
        };
    }

    private void addIntent(String code, String name, String toolName, AccessMode mode, String businessType) {
        AIIntentConfig cfg = AIIntentConfig.builder()
                .intentCode(code).intentName(name).intentCategory("TEST")
                .sensitivityLevel("MEDIUM").toolName(toolName).businessType(businessType).isActive(true)
                .build();
        intents.put(code, cfg);
        tools.put(toolName, tool(toolName, mode));
    }

    @BeforeEach
    void setUp() {
        addIntent("WORK_PROCESS_TASK_COMPLETE", "完成工序", "work_process_task_complete", AccessMode.WRITE, "FACTORY");
        addIntent("PRODUCTION_BATCH_CREATE", "创建生产批次", "production_batch_create", AccessMode.WRITE, "FACTORY");
        addIntent("RELEASE_DECISION", "批次放行决策", "release_decision", AccessMode.WRITE, "FACTORY");
        addIntent("PROCESS_TASK_QUERY", "查询工序任务", "process_task_query", AccessMode.READ, "COMMON");
        addIntent("RESTAURANT_STOCK_ADJUST", "门店盘点调整", "restaurant_stock_adjust", AccessMode.WRITE, "RESTAURANT");

        repo = mock(AiFrontlineIntentGrantRepository.class);
        when(repo.findByFactoryIdAndRevokedAtIsNull(anyString())).thenAnswer(inv -> table.stream()
                .filter(g -> g.getFactoryId().equals(inv.getArgument(0)) && g.getRevokedAt() == null).toList());
        when(repo.findByFactoryIdAndRoleCodeAndIntentCodeAndRevokedAtIsNull(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> table.stream().filter(g -> g.getFactoryId().equals(inv.getArgument(0))
                        && g.getRoleCode().equals(inv.getArgument(1)) && g.getIntentCode().equals(inv.getArgument(2))
                        && g.getRevokedAt() == null).findFirst());
        when(repo.save(any(AiFrontlineIntentGrant.class))).thenAnswer(inv -> {
            AiFrontlineIntentGrant g = inv.getArgument(0);
            if (!table.contains(g)) {
                table.add(g);
            }
            return g;
        });

        configs = mock(IntentConfigManagementService.class);
        when(configs.getAllIntents(anyString())).thenAnswer(inv -> new ArrayList<>(intents.values()));
        registry = mock(ToolRegistry.class);
        when(registry.getExecutor(anyString())).thenAnswer(inv -> Optional.ofNullable(tools.get(inv.getArgument(0, String.class))));

        service = new FrontlineAiWriteGrantService(repo, new WriteGuardService(), configs, registry);
    }

    private Optional<String> deny(String factory, String role, String intentCode) {
        AIIntentConfig intent = intents.get(intentCode);
        return service.denyReason(factory, role, tools.get(intent.getToolName()), intent);
    }

    // ==================== 判定 ====================

    @Test
    @DisplayName("一线角色 + 写操作 + 未开放 ⇒ 拒绝, 说明里有角色名和去哪开放")
    void frontlineWriteNotOpenIsDenied() {
        Optional<String> reason = deny(F006, "operator", "PRODUCTION_BATCH_CREATE");
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("创建生产批次").contains("操作员").contains("一线角色 AI 操作权限");
    }

    @Test
    @DisplayName("对照: 默认开放的日常工作放行 (操作员完成工序)")
    void defaultOpenAllowed() {
        assertThat(deny(F006, "operator", "WORK_PROCESS_TASK_COMPLETE")).isEmpty();
        assertThat(deny(F006, "yield_operator", "WORK_PROCESS_TASK_COMPLETE")).isEmpty();
    }

    @Test
    @DisplayName("对照: 一线角色的查询不受影响")
    void frontlineReadAllowed() {
        assertThat(deny(F006, "warehouse_worker", "PROCESS_TASK_QUERY")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory_super_admin", "workshop_supervisor", "warehouse_manager", "production_manager"})
    @DisplayName("对照: 非一线角色不受本名单影响")
    void nonFrontlineUnaffected(String role) {
        assertThat(deny(F006, role, "RELEASE_DECISION")).isEmpty();
    }

    @Test
    @DisplayName("写工具没有绑定意图 (一线角色) ⇒ 拒绝, 不因为缺意图就放行")
    void writeToolWithoutIntentDenied() {
        assertThat(service.denyReason(F006, "operator", tools.get("release_decision"), null)).isPresent();
    }

    @Test
    @DisplayName("按工厂: F006 开放了, F007 仍未开放")
    void grantIsPerFactory() {
        service.grant(F006, "warehouse_worker", "RELEASE_DECISION", 9L);
        assertThat(deny(F006, "warehouse_worker", "RELEASE_DECISION")).isEmpty();
        assertThat(deny(F007, "warehouse_worker", "RELEASE_DECISION")).isPresent();
        // 按角色: 同厂的另一个一线角色仍未开放
        assertThat(deny(F006, "operator", "RELEASE_DECISION")).isPresent();
    }

    @Test
    @DisplayName("读开放记录失败 ⇒ 只按默认清单放行 (fail-closed)")
    void repositoryFailureFailsClosed() {
        service.grant(F006, "operator", "PRODUCTION_BATCH_CREATE", 9L);
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).as("前提: 开放后放行").isEmpty();

        when(repo.findByFactoryIdAndRevokedAtIsNull(anyString())).thenThrow(new RuntimeException("db down"));
        service.evict(F006);
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).isPresent();
        assertThat(deny(F006, "operator", "WORK_PROCESS_TASK_COMPLETE")).as("默认清单不依赖库").isEmpty();
    }

    // ==================== 管理端: 开放 / 关闭 ====================

    @Test
    @DisplayName("开放: 写一行带操作人, 立即生效 (不等缓存过期); 重复开放不重复写")
    void grantTakesEffectImmediatelyAndIsIdempotent() {
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).as("先读一次, 让缓存里是「未开放」").isPresent();

        FrontlineAiWriteGrantService.GrantItem item = service.grant(F006, "operator", "PRODUCTION_BATCH_CREATE", 9L);
        assertThat(item.grantedRoles()).containsExactly("operator");
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).isEmpty();

        service.grant(F006, "operator", "PRODUCTION_BATCH_CREATE", 9L);
        ArgumentCaptor<AiFrontlineIntentGrant> saved = ArgumentCaptor.forClass(AiFrontlineIntentGrant.class);
        verify(repo, times(1)).save(saved.capture());
        assertThat(saved.getValue().getGrantedBy()).isEqualTo(9L);
        assertThat(saved.getValue().getGrantedAt()).isNotNull();
    }

    @Test
    @DisplayName("关闭: 不删行, 写关闭人与时间, 立即失效")
    void revokeKeepsRowAndTakesEffect() {
        service.grant(F006, "operator", "PRODUCTION_BATCH_CREATE", 9L);
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).isEmpty();

        FrontlineAiWriteGrantService.GrantItem item = service.revoke(F006, "operator", "PRODUCTION_BATCH_CREATE", 10L);
        assertThat(item.grantedRoles()).isEmpty();
        assertThat(deny(F006, "operator", "PRODUCTION_BATCH_CREATE")).isPresent();
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getRevokedBy()).isEqualTo(10L);
        assertThat(table.get(0).getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("默认开放的日常工作不能在管理端关闭")
    void cannotRevokeDefault() {
        assertThatThrownBy(() -> service.revoke(F006, "operator", "WORK_PROCESS_TASK_COMPLETE", 10L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("默认开放");
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("只能给一线角色设; 只能设本厂可见的写操作 (读操作 / 餐饮意图 / 不存在的都拒)")
    void rejectsNonFrontlineRoleAndNonWriteIntents() {
        assertThatThrownBy(() -> service.grant(F006, "factory_super_admin", "RELEASE_DECISION", 9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("一线角色");
        for (String code : List.of("PROCESS_TASK_QUERY", "RESTAURANT_STOCK_ADJUST", "NO_SUCH_INTENT")) {
            assertThatThrownBy(() -> service.grant(F006, "operator", code, 9L))
                    .as(code).isInstanceOf(BusinessException.class).hasMessageContaining("找不到");
        }
        verify(repo, never()).save(any());
    }

    // ==================== 管理端: 列表 ====================

    @Test
    @DisplayName("列表: 只有本厂可见的写操作; 默认开放与本厂开放分开给")
    void listShowsWriteIntentsWithDefaultsAndGrants() {
        service.grant(F006, "quality_inspector", "RELEASE_DECISION", 9L);
        FrontlineAiWriteGrantService.GrantView view = service.list(F006);

        assertThat(view.roles()).extracting(FrontlineAiWriteGrantService.RoleItem::code)
                .containsExactly("operator", "yield_operator", "warehouse_worker", "quality_inspector");
        assertThat(view.intents()).extracting(FrontlineAiWriteGrantService.GrantItem::intentCode)
                .containsExactlyInAnyOrder("WORK_PROCESS_TASK_COMPLETE", "PRODUCTION_BATCH_CREATE", "RELEASE_DECISION");

        FrontlineAiWriteGrantService.GrantItem complete = view.intents().stream()
                .filter(i -> i.intentCode().equals("WORK_PROCESS_TASK_COMPLETE")).findFirst().orElseThrow();
        assertThat(complete.defaultOpenRoles()).containsExactly("operator", "yield_operator");
        assertThat(complete.grantedRoles()).isEmpty();

        FrontlineAiWriteGrantService.GrantItem release = view.intents().stream()
                .filter(i -> i.intentCode().equals("RELEASE_DECISION")).findFirst().orElseThrow();
        assertThat(release.defaultOpenRoles()).isEmpty();
        assertThat(release.grantedRoles()).containsExactly("quality_inspector");
    }

    // ==================== 默认清单本身 ====================

    @Test
    @DisplayName("默认清单: 四个一线角色都有; 出成率录入员与操作员同一组; 每组都含打卡与请假")
    void defaultOpenShape() {
        assertThat(FrontlineAiWriteGrantService.DEFAULT_OPEN.keySet())
                .containsExactlyInAnyOrderElementsOf(FrontlineAiWriteGrantService.FRONTLINE_ROLES.keySet());
        assertThat(FrontlineAiWriteGrantService.DEFAULT_OPEN.get("yield_operator"))
                .isEqualTo(FrontlineAiWriteGrantService.DEFAULT_OPEN.get("operator"));
        FrontlineAiWriteGrantService.DEFAULT_OPEN.values().forEach(set ->
                assertThat(set).contains("CLOCK_IN", "CLOCK_OUT", "HR_LEAVE_SUBMIT"));
        // 高敏感的不在默认清单里 —— 要开得去管理端开
        FrontlineAiWriteGrantService.DEFAULT_OPEN.values().forEach(set ->
                assertThat(set).doesNotContain("RELEASE_DECISION", "INVENTORY_FREEZE", "TRANSFER_CREATE",
                        "PRODUCTION_BATCH_CREATE", "FOOD_SAMPLE_DISPOSE", "CONVERSION_RATE_UPDATE"));
    }

    @Test
    @DisplayName("前提: 测试里的「写」确实被真 WriteGuardService 判成写, 「读」判成读")
    void classifierPrecondition() {
        assertThat(service.isWrite(tools.get("release_decision"), intents.get("RELEASE_DECISION"))).isTrue();
        assertThat(service.isWrite(tools.get("process_task_query"), intents.get("PROCESS_TASK_QUERY"))).isFalse();
        verify(configs, never()).getIntentByCode(eq(F006), anyString());
    }
}
