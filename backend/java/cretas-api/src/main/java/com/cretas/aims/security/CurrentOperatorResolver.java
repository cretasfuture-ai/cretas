package com.cretas.aims.security;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who is acting — display name and role for audit records and notifications — given the actor id a
 * controller read from the request.
 *
 * <p>⚠️ platform_admins.id and users.id are independent sequences, so a platform admin's id equals
 * some unrelated factory user's id (on a fresh DB, platform admin 1 vs F999's phase2a_test_user 1).
 * {@code users.findById(actorId)} would write that factory user's name and role into the record.
 * The typed actor of the current request decides which table the id belongs to.
 */
@Component
@RequiredArgsConstructor
public class CurrentOperatorResolver {

    public record Operator(Long id, String displayName, String role) {
    }

    private final UserRepository userRepository;
    private final PlatformAdminRepository platformAdminRepository;

    /**
     * @return the operator, or empty when {@code actorId} is a factory user id without a users row
     * @throws BusinessException 403 for a platform admin whose role has no factory access
     */
    public Optional<Operator> resolve(Long actorId) {
        if (actorId == null) {
            return Optional.empty();
        }
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest().orElse(null);
        if (actor != null && actor.isPlatformAdmin() && actor.id().equals(actorId)) {
            if (!actor.hasFactoryBypass()) {
                throw new BusinessException(403, "平台角色 " + actor.role() + " 无权操作工厂数据");
            }
            String name = platformAdminRepository.findById(actorId)
                    .map(admin -> admin.getRealName() != null && !admin.getRealName().isBlank()
                            ? admin.getRealName()
                            : admin.getUsername())
                    .orElse("平台管理员#" + actorId);
            return Optional.of(new Operator(actorId, name, actor.role()));
        }
        return userRepository.findById(actorId)
                .map(user -> new Operator(user.getId(), user.getFullName(), user.getRole()));
    }
}
