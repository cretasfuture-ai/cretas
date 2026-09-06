package com.cretas.aims.service.permission;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.entity.permission.PlatformRolePermissionAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionAuditRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * step-2 可写 L1 的契约 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906, owner 2026-09-06 裁定)。
 *
 * <p>核心契约<b>真跑</b> {@link PermissionServiceImpl}, 不 mock 它: 先把一个判定烤进 5 分钟缓存,
 * 写一格, 再判 —— 必须立即变。变异「写服务不调 invalidateCache」⇒ 缓存里还是旧值 ⇒ 红。
 * 假 L1 表是一个 map, 两条仓库查询与 save 都对着它, 所以「写进去了」和「读得到」是同一份数据。
 *
 * <p>阴性对照按设计稿第 6/9 节: 无操作人 403 (⛔ 不写 changed_by=NULL) / 白名单外模块 400 /
 * 非法级别 400 / 防锁死 400 / 餐饮格 400 (N8) / 审计失败整笔抛出且缓存不失效 (N5 的 mock 版:
 * 真事务回滚要 DB, 这里只能钉「异常传播 + 没有走到失效那一步」)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformRolePermissionWriteServiceContractTest {

    @Mock private PlatformRolePermissionRepository l1Repo;
    @Mock private PlatformRolePermissionAuditRepository auditRepo;
    @Mock private FactoryModuleConfigRepository factoryConfigRepo;

    private PermissionServiceImpl permissionService;
    private PlatformRolePermissionWriteService service;

    /** 假 L1 表: "role|module" → 行. */
    private final Map<String, PlatformRolePermission> table = new HashMap<>();
    private final AtomicLong auditSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        permissionService = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissionService, "platformRepo", l1Repo);
        ReflectionTestUtils.setField(permissionService, "factoryConfigRepo", factoryConfigRepo);
        service = new PlatformRolePermissionWriteService(l1Repo, auditRepo, permissionService);

        when(factoryConfigRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenReturn(List.of());
        when(l1Repo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(l1Repo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenAnswer(inv ->
                table.values().stream().filter(r -> r.getRoleCode().equals(inv.getArgument(0))).toList());
        when(l1Repo.save(any(PlatformRolePermission.class))).thenAnswer(inv -> {
            PlatformRolePermission r = inv.getArgument(0);
            table.put(r.getRoleCode() + "|" + r.getModuleCode(), r);
            return r;
        });
        when(auditRepo.save(any(PlatformRolePermissionAudit.class))).thenAnswer(inv -> {
            PlatformRolePermissionAudit a = inv.getArgument(0);
            a.setId(auditSeq.incrementAndGet());
            return a;
        });
    }

    private void seed(String role, String module, String level) {
        PlatformRolePermission r = new PlatformRolePermission();
        r.setRoleCode(role);
        r.setModuleCode(module);
        r.setPermissionLevel(level);
        table.put(role + "|" + module, r);
    }

    private static User user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setRoleCode(role);
        u.setFactoryId("F006");
        return u;
    }

    @Test
    @DisplayName("契约: 一次写入 ⇒ 下一次 @RequirePermission 判定立即变 (先烤进缓存, 真跑 PermissionServiceImpl)")
    void oneWriteChangesTheVeryNextPermissionDecision() {
        seed("sales_manager", "sales", "rw");
        User salesMgr = user(7001L, "sales_manager");
        User admin = user(7000L, "permission_admin");

        boolean before = permissionService.hasPermission(salesMgr, "sales:write");
        boolean beforeAgainFromCache = permissionService.hasPermission(salesMgr, "sales:write");
        System.out.println("OBSERVED sales:write before=" + before + " cached=" + beforeAgainFromCache);
        assertThat(before).isTrue();
        assertThat(beforeAgainFromCache).isTrue();

        PlatformRolePermissionWriteService.WriteResult result =
                service.write(admin, "F006", "sales_manager", "sales", "-", "收回销售写权");

        boolean after = permissionService.hasPermission(salesMgr, "sales:write");
        boolean afterRead = permissionService.hasPermission(salesMgr, "sales:read");
        System.out.println("OBSERVED sales:write after=" + after + " sales:read after=" + afterRead + " audit=" + result.auditId());

        assertThat(after).as("写完那一刻起, 缓存里的 true 必须失效").isFalse();
        assertThat(afterRead).isFalse();
        assertThat(result.oldLevel()).isEqualTo("rw");
        assertThat(result.row().getPermissionLevel()).isEqualTo("-");
        assertThat(result.auditId()).isNotNull();
        // 登录响应那条路也立刻变 (同一条解析链)
        assertThat(permissionService.resolveModuleLevels(salesMgr)).containsEntry("sales", "-");
    }

    @Test
    @DisplayName("审计行与 L1 行同一批: 记 old/new/操作人/角色/工厂/原因; 原来没有行时 old_level 是 NULL 不是 '-'")
    void auditRowCarriesTheChange() {
        User admin = user(7000L, "permission_admin");
        service.write(admin, "F006", "viewer", "finance", "r", null);   // 没有种子 ⇒ 新行

        ArgumentCaptor<PlatformRolePermissionAudit> cap = ArgumentCaptor.forClass(PlatformRolePermissionAudit.class);
        verify(auditRepo).save(cap.capture());
        PlatformRolePermissionAudit a = cap.getValue();
        System.out.println("OBSERVED audit old=" + a.getOldLevel() + " new=" + a.getNewLevel() + " by=" + a.getChangedBy() + " role=" + a.getChangedByRole() + " factory=" + a.getFactoryId());
        assertThat(a.getOldLevel()).isNull();
        assertThat(a.getNewLevel()).isEqualTo("r");
        assertThat(a.getChangedBy()).isEqualTo(7000L);
        assertThat(a.getChangedByRole()).isEqualTo("permission_admin");
        assertThat(a.getFactoryId()).isEqualTo("F006");
        assertThat(a.getChangedAt()).isNotNull();
        assertThat(table.get("viewer|finance").getUpdatedBy()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("N7: 没有操作人 ⇒ 403, 不写 L1 也不写审计 (⛔ 不写 changed_by=NULL)")
    void noActorIsRejectedBeforeAnyWrite() {
        seed("viewer", "finance", "-");
        assertThatThrownBy(() -> service.write(null, "F006", "viewer", "finance", "r", null))
                .isInstanceOf(BusinessException.class);
        User noId = new User();
        noId.setRoleCode("permission_admin");
        assertThatThrownBy(() -> service.write(noId, "F006", "viewer", "finance", "r", null))
                .isInstanceOf(BusinessException.class);
        verify(l1Repo, never()).save(any());
        verify(auditRepo, never()).save(any());
        assertThat(table.get("viewer|finance").getPermissionLevel()).isEqualTo("-");
    }

    @Test
    @DisplayName("N2/N3: 白名单外模块 / 非法级别 / 防锁死 ⇒ 400 (IllegalArgumentException), 零写入")
    void validationAndLockoutGuards() {
        User admin = user(7000L, "permission_admin");
        assertThatThrownBy(() -> service.write(admin, "F006", "viewer", "ai", "r", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无效模块");
        assertThatThrownBy(() -> service.write(admin, "F006", "viewer", "finance", "rwx", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无效权限级别");
        assertThatThrownBy(() -> service.write(admin, "F006", "platform_admin", "system", "r", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("platform_admin");
        assertThatThrownBy(() -> service.write(admin, "F006", "factory_super_admin", "system", "-", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("factory_super_admin");
        verify(l1Repo, never()).save(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    @DisplayName("N8 餐饮不管: 餐饮角色 或 餐饮模块 的格 ⇒ 400, 零写入; 阳性对照: 同一调用换成工厂格能写")
    void restaurantCellsAreNotWritable() {
        User admin = user(7000L, "permission_admin");
        for (String[] cell : new String[][]{
                {"restaurant_owner", "finance"}, {"restaurant_purchaser", "procurement"},
                {"viewer", "restaurant"}, {"viewer", "restaurantOps"}, {"viewer", "restaurantFinance"}}) {
            assertThatThrownBy(() -> service.write(admin, "F006", cell[0], cell[1], "r", null))
                    .as("%s × %s", cell[0], cell[1])
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("餐饮侧待定");
        }
        verify(l1Repo, never()).save(any());
        verify(auditRepo, never()).save(any());
        // 阳性对照: 工厂格走得通
        assertThat(service.write(admin, "F006", "viewer", "finance", "r", null).row().getPermissionLevel()).isEqualTo("r");
        assertThat(PlatformRolePermissionWriteService.isRestaurantCell("restaurant_chef", "sales")).isTrue();
        assertThat(PlatformRolePermissionWriteService.isRestaurantCell("viewer", "restaurantMarketing")).isTrue();
        assertThat(PlatformRolePermissionWriteService.isRestaurantCell("viewer", "sales")).isFalse();
    }

    @Test
    @DisplayName("N5 (mock 版): 审计 save 抛出 ⇒ 异常传播, 缓存不失效 (真事务回滚由 @Transactional 保证)")
    void auditFailurePropagatesAndSkipsCacheInvalidation() {
        seed("sales_manager", "sales", "rw");
        User salesMgr = user(7001L, "sales_manager");
        assertThat(permissionService.hasPermission(salesMgr, "sales:write")).isTrue();   // 烤进缓存
        when(auditRepo.save(any())).thenThrow(new RuntimeException("audit insert failed"));

        assertThatThrownBy(() -> service.write(user(7000L, "permission_admin"), "F006", "sales_manager", "sales", "-", null))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("audit insert failed");
        // 缓存没被清: 判定仍是写之前的值 (真库上 L1 行会随事务回滚, 这里只钉「没走到失效那一步」)
        assertThat(permissionService.hasPermission(salesMgr, "sales:write")).isTrue();
    }
}
