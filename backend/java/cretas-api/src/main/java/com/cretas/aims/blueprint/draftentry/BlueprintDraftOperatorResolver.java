package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the operator a module DRAFT command trusts — on the server, from the authenticated
 * request, never from the request body (design §17.37: a deserialized operator lets the caller
 * declare any permission set).
 *
 * <p>Two paths. {@link #resolve} / {@link #resolveForAny} are factory-only: an active member of
 * {@code users} in that factory, and the three-segment draft permissions
 * ({@code account:role:draft} …) cannot be granted to any role by the {@code module:action}
 * matrix — only the {@code factory_super_admin} bypass in {@link PermissionService#hasPermission}
 * returns true for them. {@link #resolveDraft} / {@link #resolveDraftForAny} additionally accept a
 * platform admin, re-verified against {@code platform_admins} on every call (active, role
 * {@code super_admin} or {@code system_admin}, not bound to another factory) and carried as a
 * {@code PLATFORM_ADMIN} {@link ActorRef}.</p>
 *
 * <p>⚠️ Platform admins are told apart from factory users by <b>actor type</b>, before any
 * membership lookup: their token id is the same number space as {@code users.id} (the
 * {@code platform_} prefix is stripped), so a membership check alone would let an admin whose id
 * happens to equal some factory user's id act as that user. A platform id is never looked up in
 * {@code users} and never written into a column that references it.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class BlueprintDraftOperatorResolver {

    private final UserRepository users;
    private final PermissionService permissions;
    private final PlatformAdminRepository platformAdmins;

    public BlueprintDraftOperatorResolver(UserRepository users, PermissionService permissions) {
        this(users, permissions, null);
    }

    @Autowired
    public BlueprintDraftOperatorResolver(UserRepository users, PermissionService permissions,
                                          PlatformAdminRepository platformAdmins) {
        this.users = users;
        this.permissions = permissions;
        this.platformAdmins = platformAdmins;
    }

    /** The operator identity and the permissions the server vouches for. */
    public record DraftOperator(ActorRef actor, String factoryId, Set<String> permissions) {
        public DraftOperator(long userId, String factoryId, Set<String> permissions) {
            this(new ActorRef(AuthenticatedActorType.FACTORY_USER, userId), factoryId, permissions);
        }

        public long userId() {
            return actor.id();
        }
    }

    /** Draft-only resolver: permits a server-authenticated platform admin namespace while the
     * promotion resolver above remains factory-only. The platform role is read from the
     * validated actor, never from request JSON or a user-row id collision. */
    public DraftOperator resolveDraft(HttpServletRequest request, String pathFactoryId, String requiredPermission) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> fail(401, "无法获取登录身份，请重新登录", "BLUEPRINT_DRAFT_ACTOR_MISSING"));
        if (actor.isPlatformAdmin()) {
            Set<String> granted = verifiedPlatformPermissions(actor, pathFactoryId, Set.of(requiredPermission));
            return new DraftOperator(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, actor.id()),
                    pathFactoryId, granted);
        }
        return resolve(request, pathFactoryId, requiredPermission);
    }

    public DraftOperator resolve(HttpServletRequest request, String pathFactoryId, String requiredPermission) {
        Member member = member(request, pathFactoryId);
        if (!permissions.hasPermission(member.user(), requiredPermission)) {
            throw fail(403, "当前账号没有确认这类建厂草稿的权限，请联系工厂超级管理员",
                    "BLUEPRINT_DRAFT_PERMISSION_REQUIRED");
        }
        return new DraftOperator(new ActorRef(AuthenticatedActorType.FACTORY_USER, member.actorId()), pathFactoryId, Set.of(requiredPermission));
    }

    /**
     * Same identity checks as {@link #resolve}, for a read that spans capabilities: the operator
     * carries the subset of {@code candidatePermissions} the server grants. None granted is refused.
     */
    public DraftOperator resolveForAny(HttpServletRequest request, String pathFactoryId,
                                       Collection<String> candidatePermissions) {
        Member member = member(request, pathFactoryId);
        Set<String> granted = new LinkedHashSet<>();
        for (String permission : candidatePermissions) {
            if (permissions.hasPermission(member.user(), permission)) {
                granted.add(permission);
            }
        }
        if (granted.isEmpty()) {
            throw fail(403, "当前账号没有任何建厂草稿的权限，请联系工厂超级管理员",
                    "BLUEPRINT_DRAFT_PERMISSION_REQUIRED");
        }
        return new DraftOperator(new ActorRef(AuthenticatedActorType.FACTORY_USER, member.actorId()), pathFactoryId, Set.copyOf(granted));
    }

    public DraftOperator resolveDraftForAny(HttpServletRequest request, String pathFactoryId,
                                            Collection<String> candidatePermissions) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> fail(401, "无法获取登录身份，请重新登录", "BLUEPRINT_DRAFT_ACTOR_MISSING"));
        if (actor.isPlatformAdmin()) {
            Set<String> granted = verifiedPlatformPermissions(actor, pathFactoryId, candidatePermissions);
            return new DraftOperator(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, actor.id()),
                    pathFactoryId, granted);
        }
        return resolveForAny(request, pathFactoryId, candidatePermissions);
    }

    /** The operator id is the authenticated actor's id, as before; the user row is for permission checks. */
    private record Member(long actorId, User user) {
    }

    private Member member(HttpServletRequest request, String pathFactoryId) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> fail(401, "无法获取登录身份，请重新登录", "BLUEPRINT_DRAFT_ACTOR_MISSING"));
        if (actor.isPlatformAdmin()) {
            throw fail(403, "建厂草稿要由本厂工厂超级管理员确认写入；平台管理员可以预览，不能确认",
                    "BLUEPRINT_DRAFT_FACTORY_OPERATOR_REQUIRED");
        }
        if (pathFactoryId == null || !pathFactoryId.equals(actor.factoryId())) {
            throw fail(403, "只能为自己所在的工厂确认建厂草稿", "BLUEPRINT_DRAFT_FACTORY_SCOPE_MISMATCH");
        }
        User user = users.findByIdAndFactoryId(actor.id(), pathFactoryId)
                .filter(member -> Boolean.TRUE.equals(member.getIsActive()))
                .orElseThrow(() -> fail(403, "当前账号不是本厂的有效成员，不能确认建厂草稿",
                        "BLUEPRINT_DRAFT_MEMBER_REQUIRED"));
        return new Member(actor.id(), user);
    }

    private Set<String> verifiedPlatformPermissions(AuthenticatedActor actor, String pathFactoryId,
                                                    Collection<String> candidates) {
        if (actor.factoryId() != null && !actor.factoryId().isBlank()
                && !"PLATFORM".equalsIgnoreCase(actor.factoryId())) {
            throw fail(403, "平台身份不能绑定其他工厂执行建厂草稿", "BLUEPRINT_DRAFT_FACTORY_SCOPE_MISMATCH");
        }
        if (platformAdmins == null) {
            throw fail(403, "无法验证当前平台管理员状态", "BLUEPRINT_DRAFT_PLATFORM_ADMIN_UNVERIFIED");
        }
        var admin = platformAdmins.findById(actor.id())
                .filter(value -> value.isActive())
                .orElseThrow(() -> fail(403, "当前平台管理员已失效", "BLUEPRINT_DRAFT_PLATFORM_ADMIN_INACTIVE"));
        if (admin.getPlatformRole() == null
                || (admin.getPlatformRole() != com.cretas.aims.entity.enums.PlatformRole.super_admin
                && admin.getPlatformRole() != com.cretas.aims.entity.enums.PlatformRole.system_admin)) {
            throw fail(403, "当前平台角色没有建厂草稿权限", "BLUEPRINT_DRAFT_PLATFORM_PERMISSION_REQUIRED");
        }
        Set<String> serverPermissions = Set.of(admin.getPermissions());
        Set<String> granted = new LinkedHashSet<>();
        for (String permission : candidates) {
            if (serverPermissions.contains(permission)
                    || admin.getPlatformRole() == com.cretas.aims.entity.enums.PlatformRole.super_admin
                    || admin.getPlatformRole() == com.cretas.aims.entity.enums.PlatformRole.system_admin) {
                granted.add(permission);
            }
        }
        if (granted.isEmpty()) throw fail(403, "当前平台角色没有建厂草稿权限", "BLUEPRINT_DRAFT_PLATFORM_PERMISSION_REQUIRED");
        return Set.copyOf(granted);
    }

    private static BusinessException fail(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }
}
