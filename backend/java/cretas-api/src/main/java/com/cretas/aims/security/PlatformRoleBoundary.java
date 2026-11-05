package com.cretas.aims.security;

import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;

/**
 * A platform role cannot sit under a factory. Platform authority comes from identity, an explicit
 * platform token that became a {@link AuthenticatedActor} of type {@code PLATFORM_ADMIN}, and never
 * from a role string: {@code users.role_code} is written by factory-side admins, so a role string
 * proves nothing about who issued it.
 *
 * <p>An existing factory account that already holds such a role keeps logging in and keeps its
 * factory access; it simply gets no platform authority, and editing its other fields still works.
 */
public final class PlatformRoleBoundary {

    private static final Set<String> PLATFORM_TIER_ROLES = Set.of(
            "platform_admin", "super_admin", "developer", "platform_super_admin",
            "system_admin", "operation_admin", "auditor");

    private static final Set<String> CROSS_FACTORY_ROLES = Set.of("platform_admin", "super_admin");

    private PlatformRoleBoundary() {
    }

    public static boolean isPlatformTier(String role) {
        // Legacy consumers also recognize Unicode case aliases, including dotless i and long s.
        // Every spelling they recognize must be restricted at the assignment boundary too.
        return role != null && PLATFORM_TIER_ROLES.stream()
                .anyMatch(known -> known.equalsIgnoreCase(role.trim()));
    }

    /**
     * Refuses turning a factory user's role or position into a platform-tier value. Keeping the
     * value it already holds is not a grant, so it passes.
     */
    public static void refuseOnFactoryUser(String newValue, String currentValue) {
        if (isPlatformTier(newValue) && !newValue.trim().equalsIgnoreCase(currentValue)) {
            throw new BusinessException(403, "平台角色不能分配给工厂账号")
                    .withHint("请选择工厂角色");
        }
    }

    /** True only for an explicit platform identity holding a role that may act across factories. */
    public static boolean hasCrossFactoryAuthority(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .filter(AuthenticatedActor::isPlatformAdmin)
                .map(AuthenticatedActor::role)
                .map(role -> CROSS_FACTORY_ROLES.contains(role.toLowerCase(Locale.ROOT)))
                .orElse(false);
    }
}
