package com.cretas.aims.ai.catalog;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;

/** Global skill writes require the server-authenticated platform principal, never a claimed role. */
public final class PlatformGovernanceAccess {
    private PlatformGovernanceAccess() {}

    public static boolean allowed() {
        return AuthenticatedActor.fromCurrentExecution().map(AuthenticatedActor::hasFactoryBypass).orElse(false);
    }

    public static void requireAdmin() {
        if (!allowed()) throw new BusinessException(403, "全局技能管理仅允许已认证的平台管理员操作");
    }
}
