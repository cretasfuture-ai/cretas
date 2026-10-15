package com.cretas.aims.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Typed identity established by {@code JwtAuthInterceptor} from a validated JWT.
 */
public record AuthenticatedActor(
        AuthenticatedActorType type,
        Long id,
        String factoryId,
        String role) {

    public static final String REQUEST_ATTRIBUTE = AuthenticatedActor.class.getName();

    /**
     * Platform roles that may act on any factory's {@code /api/mobile/{factoryId}/**} endpoints:
     * {@code JwtAuthInterceptor} lets them cross factories, {@code PermissionInterceptor} and
     * {@code ModuleEnabledInterceptor} let them past the per-account checks (which are keyed by
     * {@code users.id} and so cannot describe a platform admin). Other platform roles
     * ({@code system_admin}, {@code operation_admin}, {@code auditor}) get none of this.
     */
    public static final Set<String> FACTORY_BYPASS_PLATFORM_ROLES = Set.of(
            "super_admin", "platform_admin", "developer");

    public AuthenticatedActor {
        if (type == null || id == null || id <= 0) {
            throw new IllegalArgumentException("Authenticated actor requires a type and positive id");
        }
    }

    public boolean isPlatformAdmin() {
        return type == AuthenticatedActorType.PLATFORM_ADMIN;
    }

    /** A platform actor whose role is in {@link #FACTORY_BYPASS_PLATFORM_ROLES}. */
    public boolean hasFactoryBypass() {
        return isPlatformAdmin()
                && role != null
                && FACTORY_BYPASS_PLATFORM_ROLES.contains(role.toLowerCase(Locale.ROOT));
    }

    public static Optional<AuthenticatedActor> current(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof AuthenticatedActor actor ? Optional.of(actor) : Optional.empty();
    }

    /** The current request's actor when it is a platform admin; empty for factory users and outside a request. */
    public static Optional<AuthenticatedActor> currentPlatformAdmin() {
        return fromCurrentRequest().filter(AuthenticatedActor::isPlatformAdmin);
    }

    /** The actor of the HTTP request bound to this thread; empty outside a request (jobs, async). */
    public static Optional<AuthenticatedActor> fromCurrentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? current(attributes.getRequest())
                : Optional.empty();
    }
}
