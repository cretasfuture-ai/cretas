package com.cretas.aims.security;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SOP-only actor boundary; generic attachment authorization remains unchanged. */
@Component
@RequiredArgsConstructor
public class SopAttachmentAccessResolver {

    private final AttachmentPermissionResolver genericPermissions;
    private final UserRepository userRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final FactoryConfigService factoryConfigService;
    private final UserModuleAccessService userModuleAccessService;

    public AuthenticatedActor require(HttpServletRequest request, String factoryId,
                                      EntityType entityType, boolean write) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "未识别的调用者，SOP 未开放"));
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(403, "SOP 工厂范围无效");
        }
        if (entityType == EntityType.EQUIPMENT) {
            requireEquipmentModuleEnabled(factoryId);
        }
        if (actor.isPlatformAdmin()) {
            requirePlatform(actor);
            return actor;
        }
        if (actor.type() != AuthenticatedActorType.FACTORY_USER
                || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "不能访问其他工厂的 SOP");
        }
        User user = userRepository.findById(actor.id()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(401, "工厂账号不存在或已停用");
        }
        if (!factoryId.equals(user.getFactoryId())) {
            throw new BusinessException(403, "工厂账号范围不一致");
        }
        if (entityType == EntityType.EQUIPMENT) {
            requireEquipmentModuleAccess(user);
        }
        if (write) genericPermissions.requireWrite(user, entityType);
        else genericPermissions.requireRead(user, entityType);
        return actor;
    }

    private void requireEquipmentModuleEnabled(String factoryId) {
        try {
            if (!factoryConfigService.isModuleEnabled(factoryId, "equipment")) {
                throw new BusinessException(403, "设备 SOP 模块未启用");
            }
        } catch (BusinessException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(503, "设备 SOP 模块校验暂不可用，SOP 未开放", unavailable);
        }
    }

    private void requireEquipmentModuleAccess(User user) {
        try {
            if (!userModuleAccessService.canAccessModule(user, "equipment")) {
                throw new BusinessException(403, "账号没有设备 SOP 模块访问权限");
            }
        } catch (BusinessException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(503, "设备 SOP 账号模块校验暂不可用，SOP 未开放", unavailable);
        }
    }

    private void requirePlatform(AuthenticatedActor actor) {
        if (!actor.hasFactoryBypass()) {
            throw new BusinessException(403, "平台身份未获 SOP 工厂范围授权");
        }
        PlatformAdmin admin = platformAdminRepository.findById(actor.id()).orElse(null);
        if (admin == null || !admin.isActive() || admin.getPlatformRole() == null
                || actor.role() == null
                || !admin.getPlatformRole().name().equalsIgnoreCase(actor.role())) {
            throw new BusinessException(401, "平台账号不存在、已停用或角色已变更");
        }
    }
}
