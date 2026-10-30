package com.cretas.aims.service.platform;

import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit platform control, independent of the gated factory-assembly product. */
@Service
@RequiredArgsConstructor
public class PlatformFactoryControlAuthority {
    private final FactoryRepository factories;
    private final PlatformAdminRepository admins;
    private final FactoryFeatureMenuWriteLock writeLock;

    public ActorRef require(HttpServletRequest request, String factoryId, boolean write) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> denied("无法确认平台登录身份"));
        if (actor.type() != AuthenticatedActorType.PLATFORM_ADMIN
                || !"PLATFORM".equals(actor.factoryId())
                || PlatformFactoryViewReadPolicy.isView(request)
                || !("super_admin".equals(actor.role()) || "platform_admin".equals(actor.role()))) {
            throw denied("仅平台管理员可管理工厂功能与权限");
        }
        ActorRef ref = new ActorRef(actor.type(), actor.id());
        checkFactory(factoryId);
        requireReadRef(ref, factoryId);
        if (write) requireWriteRef(ref, factoryId);
        return ref;
    }

    public boolean canManage(ActorRef actor) {
        return actor != null && actor.type() == AuthenticatedActorType.PLATFORM_ADMIN
                && admins.findById(actor.id()).filter(a -> a.isActive())
                .map(a -> a.getPlatformRole() == PlatformRole.super_admin).orElse(false);
    }

    public void requireWriteRef(ActorRef actor, String factoryId) {
        checkFactory(factoryId);
        if (!canManage(actor)) throw denied("仅当前有效的平台超级管理员可调整业务开通和权限");
    }

    public void requireReadRef(ActorRef actor, String factoryId) {
        checkFactory(factoryId);
        if (actor == null || actor.type() != AuthenticatedActorType.PLATFORM_ADMIN
                || admins.findById(actor.id()).filter(a -> a.isActive())
                .filter(a -> a.getPlatformRole() == PlatformRole.super_admin).isEmpty()) {
            throw denied("仅当前有效的平台管理员可查看工厂功能与权限");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ActorRef actor, String factoryId) {
        requireWriteRef(actor, factoryId);
        writeLock.lock(factoryId);
    }

    private void checkFactory(String factoryId) {
        if (factoryId == null || !factoryId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,49}")) {
            throw new BusinessException(400, "工厂标识无效或超过业务配置支持长度");
        }
        factories.findById(factoryId).orElseThrow(() -> new BusinessException(404, "工厂不存在"));
    }

    private BusinessException denied(String message) { return new BusinessException(403, message); }
}
