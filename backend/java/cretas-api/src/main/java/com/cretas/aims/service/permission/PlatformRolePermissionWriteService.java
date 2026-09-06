package com.cretas.aims.service.permission;

import com.cretas.aims.controller.platform.PlatformRolePermissionController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.entity.permission.PlatformRolePermissionAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.permission.PlatformRolePermissionAuditRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * L1 {@code platform_role_permissions} 的<b>唯一</b>写入口 (step-2, owner 2026-09-06 裁定)。
 *
 * <p>两个 controller 都委托这里: 工厂前缀的 {@code PUT /api/mobile/{factoryId}/permissions/matrix/{role}/{module}}
 * (门禁 {@code system:read_write}, permission_admin 走得到) 与旧的平台前缀 PUT
 * (只放平台管理员)。⛔ 守卫只写这一份 —— 两处各抄一遍就是形态 D。
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>L1 行与审计行<b>同一事务</b>: 审计写不进去 ⇒ L1 那格不变。</li>
 *   <li>{@code changedBy} 必须有: token 取不到用户 ⇒ 403, ⛔ 不写 NULL。</li>
 *   <li>写后 {@link PermissionServiceImpl#invalidateCache()} 全清 ⇒ 下一次 {@code @RequirePermission} 判定立即变
 *       (契约见 {@code PlatformRolePermissionWriteServiceContractTest})。</li>
 *   <li>餐饮不管 (Steve 2026-09-06): 餐饮角色 / 餐饮模块的格一律 400, 不动不删不补。</li>
 *   <li>防锁死: platform_admin / factory_super_admin 的 system 不许降 (与旧 PUT 一致)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformRolePermissionWriteService {

    public static final Set<String> ALLOWED_LEVELS = Set.of("rw", "r", "w", "-");

    /** 餐饮角色: owner 裁定不管. 与 FactoryUserRole 里 restaurant_* 四个一致, 用前缀判避免再抄一份清单. */
    static final String RESTAURANT_ROLE_PREFIX = "restaurant_";
    /** 餐饮模块码: restaurant 与 restaurantOps / restaurantFinance / ... 细分. */
    static final String RESTAURANT_MODULE_PREFIX = "restaurant";

    private final PlatformRolePermissionRepository repo;
    private final PlatformRolePermissionAuditRepository auditRepo;
    private final PermissionService permissionService;

    /** 一次写入的结果: 写后从 L1 <b>回读</b>的行 (不是请求体), 加审计 id. */
    public record WriteResult(PlatformRolePermission row, Long auditId, String oldLevel) {}

    public static boolean isRestaurantCell(String roleCode, String moduleCode) {
        return (roleCode != null && roleCode.startsWith(RESTAURANT_ROLE_PREFIX))
                || (moduleCode != null && moduleCode.startsWith(RESTAURANT_MODULE_PREFIX));
    }

    /**
     * 写一格. 校验 → 守卫 → L1 upsert → 审计 → 缓存失效, 同一事务.
     *
     * @param actor     操作人 (必须非 null 且有 id; 否则 403)
     * @param factoryId 从哪个工厂页面发起 (平台前缀端点传 null)
     */
    @Transactional
    public WriteResult write(User actor, String factoryId, String roleCode, String moduleCode,
                             String level, String reason) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(403, "无法确认操作人, 拒绝写入权限 (审计行不能没有 changed_by)");
        }
        // R1 (终审): 「餐饮不管」是双向的 —— 餐饮角色的**操作者**也不许写 L1。
        // prod 今日 restaurant_owner 的 L1 system=rw (4-20 种子), 过得了 system:read_write 门禁, 能改工厂角色的全平台权限。
        if (actor.getRoleCode() != null && actor.getRoleCode().startsWith(RESTAURANT_ROLE_PREFIX)) {
            throw new BusinessException(403, "餐饮侧待定: 餐饮角色的操作者不可写 L1 权限 (" + actor.getRoleCode() + ")");
        }
        if (roleCode == null || roleCode.isBlank() || moduleCode == null || moduleCode.isBlank()) {
            throw new IllegalArgumentException("role / module 不能为空");
        }
        // R3 (终审): role_code 必须是 FactoryUserRole 里的枚举名, 拼错的角色不得写进 L1 与审计
        // (fromRoleCode 把未知串折叠成 unactivated, 所以要和字面量一起判)
        if (FactoryUserRole.fromRoleCode(roleCode) == FactoryUserRole.unactivated
                && !FactoryUserRole.unactivated.name().equals(roleCode)) {
            throw new IllegalArgumentException("未知角色: " + roleCode + " (必须是 FactoryUserRole 枚举名)");
        }
        PlatformRolePermissionController.assertModuleAllowed(moduleCode);
        if (level == null || !ALLOWED_LEVELS.contains(level)) {
            throw new IllegalArgumentException("无效权限级别: " + level + ". 允许: rw/r/w/-");
        }
        if (isRestaurantCell(roleCode, moduleCode)) {
            throw new IllegalArgumentException("餐饮侧待定: 餐饮角色/模块的权限格本页不可写 (" + roleCode + " × " + moduleCode + ")");
        }
        // 防锁死 (与旧平台 PUT 同一份守卫)
        if ("platform_admin".equals(roleCode) && "system".equals(moduleCode) && !"rw".equals(level)) {
            throw new IllegalArgumentException("禁止降低 platform_admin 对 system 的权限 (会锁死管理 UI)");
        }
        if ("factory_super_admin".equals(roleCode) && "system".equals(moduleCode) && !"rw".equals(level)) {
            throw new IllegalArgumentException("禁止降低 factory_super_admin 对 system 的权限");
        }
        if (reason != null && reason.length() > 200) {
            throw new IllegalArgumentException("reason 不能超过 200 字");
        }

        Optional<PlatformRolePermission> existing = repo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode);
        String oldLevel = existing.map(PlatformRolePermission::getPermissionLevel).orElse(null);

        PlatformRolePermission row = existing.orElseGet(() -> {
            PlatformRolePermission p = new PlatformRolePermission();
            p.setRoleCode(roleCode);
            p.setModuleCode(moduleCode);
            return p;
        });
        row.setPermissionLevel(level);
        row.setUpdatedBy(actor.getId());
        row = repo.save(row);

        PlatformRolePermissionAudit audit = auditRepo.save(PlatformRolePermissionAudit.builder()
                .roleCode(roleCode)
                .moduleCode(moduleCode)
                .oldLevel(oldLevel)
                .newLevel(level)
                .changedBy(actor.getId())
                .changedByRole(actor.getRoleCode())
                .factoryId(factoryId)
                .reason(reason)
                .changedAt(LocalDateTime.now())
                .build());

        // 缓存失效: 不做精准失效 (30 角色 × 22 模块量级下全清没有代价, 少一份逻辑)
        if (permissionService instanceof PermissionServiceImpl impl) {
            impl.invalidateCache();
        } else {
            log.warn("permissionService 不是 PermissionServiceImpl, 无法失效缓存: {}", permissionService.getClass());
        }

        log.info("L1 write: {} × {} {} -> {} by user={} role={} factory={} audit={}",
                roleCode, moduleCode, oldLevel, level, actor.getId(), actor.getRoleCode(), factoryId, audit.getId());

        // 回读: 返回的是仓库里那一行, 不是请求体
        PlatformRolePermission reread = repo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode).orElse(row);
        return new WriteResult(reread, audit.getId(), oldLevel);
    }
}
