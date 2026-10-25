package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.RequestUserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A factory's own read-only readiness report, reachable from the normal factory menu.
 *
 * <p>The platform route ({@link FactoryReadinessController}) stays platform-only behind
 * {@code /api/platform/**}; this one lives under {@code /api/mobile/{factoryId}} so the JWT
 * interceptor's tenant check (path factory = token factory) still applies. Only a factory
 * user is accepted here, and {@link CanonicalBlueprintActorAuthority} re-reads that user as an
 * active {@code factory_super_admin} of this factory. Without a current canonical blueprint
 * the answer says so and never asks for a readiness report.</p>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
@RequestMapping("/api/mobile/{factoryId}/factory-readiness")
public class FactorySelfReadinessController {

    static final String READY_TO_READ = "READY_TO_READ";
    static final String NO_CANONICAL_BLUEPRINT = "NO_CANONICAL_BLUEPRINT";

    private final CanonicalFactoryBlueprintService blueprints;
    private final FactoryReadinessService readiness;
    private final CanonicalBlueprintActorAuthority authority;

    public record BlueprintRef(String id, Integer version, Long rowVersion, String lifecycleState) { }

    public record SelfReadiness(String state, BlueprintRef blueprint, FactoryReadinessReport readiness) { }

    @GetMapping("/current")
    @Transactional(readOnly = true)
    public ApiResponse<SelfReadiness> current(@PathVariable String factoryId, HttpServletRequest request) {
        ActorRef actor = RequestUserContext.currentActor(request);
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            throw new BusinessException(403, "工厂就绪报告只对本厂账号开放，平台账号请使用建厂复核")
                    .withCode("FACTORY_SELF_READINESS_FACTORY_USER_REQUIRED").withSeverity("BLOCKING");
        }
        authority.requireAuthorized(factoryId, actor);
        BlueprintResponse current = blueprints.current(factoryId);
        if (current == null) {
            return ApiResponse.success(new SelfReadiness(NO_CANONICAL_BLUEPRINT, null, null));
        }
        BlueprintRef ref = new BlueprintRef(current.getId(), current.getVersion(), current.getRowVersion(),
                current.getLifecycleState());
        return ApiResponse.success(new SelfReadiness(READY_TO_READ, ref, readiness.read(factoryId, current.getId())));
    }
}
