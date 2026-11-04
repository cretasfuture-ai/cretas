package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;

import java.util.List;

public interface UserModuleAccessService {

    record ModuleAccessView(
            String moduleCode,
            String displayName,
            String category,
            String permissionModule,
            boolean roleDefaultAllowed,
            String override,
            boolean effectiveAllowed) {
    }

    boolean canAccessModule(User user, String moduleCode);

    /** 管理员是否给这个账号显式 DENY 了这个模块 (区别于角色本来就没有)。 */
    boolean isExplicitlyDenied(User user, String moduleCode);

    List<ModuleAccessView> listEffectiveAccess(String factoryId, String userId);

    UserModuleAccess setOverride(
            String factoryId,
            String userId,
            String moduleCode,
            UserModuleAccess.AccessType accessType,
            String grantedBy,
            String remark);

    void clearOverride(String factoryId, String userId, String moduleCode);
}
