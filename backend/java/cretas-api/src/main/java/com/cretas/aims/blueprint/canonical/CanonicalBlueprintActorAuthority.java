package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.springframework.stereotype.Component;

/**
 * Server-side authority for canonical blueprint writes.
 *
 * <p>The actor value is an audit namespace, not an authorization assertion.  Every write
 * re-reads the current active principal from its own database namespace before touching a
 * canonical row.  This deliberately prevents a platform id from colliding with a factory
 * user's numeric id.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class CanonicalBlueprintActorAuthority {

    private final UserRepository users;
    private final PlatformAdminRepository platformAdmins;

    public CanonicalBlueprintActorAuthority(UserRepository users,
                                            PlatformAdminRepository platformAdmins) {
        this.users = users;
        this.platformAdmins = platformAdmins;
    }

    public void requireAuthorized(String factoryId, ActorRef actor) {
        if (factoryId == null || factoryId.isBlank() || actor == null) {
            throw denied("当前登录身份不足以执行建厂蓝图写入", "CANONICAL_ACTOR_REQUIRED");
        }
        if (actor.type() == AuthenticatedActorType.FACTORY_USER) {
            User member = users.findByIdAndFactoryId(actor.id(), factoryId)
                    .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                    .orElseThrow(() -> denied("当前账号不是目标工厂的有效成员", "CANONICAL_FACTORY_MEMBER_REQUIRED"));
            if (!"factory_super_admin".equals(member.getRoleCode())) {
                throw denied("当前工厂账号没有建厂蓝图写入权限", "CANONICAL_FACTORY_ROLE_REQUIRED");
            }
            return;
        }
        if (actor.type() == AuthenticatedActorType.PLATFORM_ADMIN) {
            PlatformAdmin admin = platformAdmins.findById(actor.id())
                    .filter(PlatformAdmin::isActive)
                    .orElseThrow(() -> denied("当前平台管理员已失效", "CANONICAL_PLATFORM_ADMIN_INACTIVE"));
            PlatformRole role = admin.getPlatformRole();
            if (role != PlatformRole.super_admin) {
                throw denied("当前平台角色没有建厂蓝图写入权限", "CANONICAL_PLATFORM_ROLE_REQUIRED");
            }
            return;
        }
        throw denied("不支持的登录身份空间", "CANONICAL_ACTOR_NAMESPACE_UNSUPPORTED");
    }

    private static BusinessException denied(String message, String code) {
        return new BusinessException(403, message).withCode(code).withSeverity("BLOCKING");
    }
}
