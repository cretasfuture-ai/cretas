package com.cretas.aims.blueprint.activation;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Revalidates current authority; token role strings and colliding numeric IDs confer no rights. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class AssemblyActivationActorResolver {
    private final FactoryRepository factories;
    private final UserRepository users;
    private final PlatformAdminRepository admins;
    private final PermissionService permissions;

    public AssemblyActivationActorResolver(FactoryRepository factories, UserRepository users,
            PlatformAdminRepository admins, PermissionService permissions) {
        this.factories=factories; this.users=users; this.admins=admins; this.permissions=permissions;
    }

    public ActorRef authenticated(HttpServletRequest request, String factoryId, Set<String> required) {
        AuthenticatedActor actor=AuthenticatedActor.current(request)
                .orElseThrow(()->denied("缺少已验证的登录操作人"));
        String claimedFactory=actor.factoryId();
        boolean globalPlatform=actor.type()==AuthenticatedActorType.PLATFORM_ADMIN
                && (claimedFactory==null || claimedFactory.isBlank()
                    || "PLATFORM".equalsIgnoreCase(claimedFactory));
        if (actor.type()==AuthenticatedActorType.PLATFORM_ADMIN ? !globalPlatform
                : claimedFactory==null || claimedFactory.isBlank() || !claimedFactory.equals(factoryId)) {
            throw denied("登录操作人不属于目标工厂");
        }
        ActorRef ref=new ActorRef(actor.type(),actor.id());
        authorize(ref,factoryId,required);
        return ref;
    }

    /** Also used for saved requester/approver authority checks; does not impersonate a login. */
    public Set<String> authorize(ActorRef actor, String factoryId, Set<String> required) {
        if (actor==null || factoryId==null || factoryId.isBlank() || required==null
                || required.isEmpty() || required.stream().anyMatch(p->p==null || p.isBlank())) {
            throw denied("缺少操作人或目标工厂权限范围");
        }
        factories.findById(factoryId).filter(f->Boolean.TRUE.equals(f.getIsActive()))
                .orElseThrow(()->denied("目标工厂不存在或已停用"));
        if (actor.type()==AuthenticatedActorType.PLATFORM_ADMIN) {
            var admin=admins.findById(actor.id()).filter(a->a.isActive())
                    .orElseThrow(()->denied("平台操作人不存在或已停用"));
            if (admin.getPlatformRole()!=PlatformRole.super_admin) {
                throw denied("当前平台角色无配置应用或发布权限");
            }
            return Set.copyOf(required);
        }
        User user=users.findByIdAndFactoryId(actor.id(),factoryId)
                .filter(u->Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(()->denied("工厂操作人不存在或已停用"));
        Set<String> granted=new LinkedHashSet<>();
        for (String permission:required) {
            if (permission==null || permission.isBlank() || !permissions.hasPermission(user,permission)) {
                throw denied("缺少配置应用或发布权限："+permission);
            }
            granted.add(permission);
        }
        return Set.copyOf(granted);
    }

    private static BusinessException denied(String message) {
        return new BusinessException(403,message).withCode("ASSEMBLY_ACTIVATION_FORBIDDEN");
    }
}
