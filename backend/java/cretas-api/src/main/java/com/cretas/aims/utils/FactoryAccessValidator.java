package com.cretas.aims.utils;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.AuthenticationException;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

/**
 * 工厂访问权限验证工具类
 *
 * 用于验证用户是否有权访问指定工厂的数据，防止跨工厂数据泄露。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Component
public class FactoryAccessValidator {

    private final UserRepository userRepository;

    public FactoryAccessValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 验证当前用户是否有权访问指定工厂的数据
     *
     * @param factoryId 要访问的工厂ID
     * @param userId 当前用户ID
     * @throws AuthenticationException 如果用户未登录
     * @throws AuthorizationException 如果用户无权访问该工厂
     */
    public void validateAccess(String factoryId, Long userId) {
        if (userId == null) {
            throw new AuthenticationException("用户未登录");
        }

        // ⚠️ Branch on actor type BEFORE any users lookup: a platform admin's id is from
        // platform_admins, an independent sequence, so users.findById(userId) would answer with
        // an unrelated factory account (on a fresh DB, id=1 is F999's phase2a_test_user) and
        // decide cross-factory access by that account's factory.
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest().orElse(null);
        if (actor != null && actor.isPlatformAdmin() && actor.id().equals(userId)) {
            if (actor.hasFactoryBypass()) {
                return;
            }
            throw new AuthorizationException("平台角色 " + actor.role() + " 无权访问工厂数据");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("用户不存在"));

        // 平台管理员可访问所有工厂
        if (isPlatformAdmin(user)) {
            return;
        }

        // 验证用户是否属于该工厂
        if (!factoryId.equals(user.getFactoryId())) {
            throw new AuthorizationException("无权访问该工厂数据");
        }
    }

    /**
     * 验证当前用户是否有权访问指定工厂的数据（使用 SecurityUtils 获取用户ID）
     *
     * @param factoryId 要访问的工厂ID
     * @throws AuthenticationException 如果用户未登录
     * @throws AuthorizationException 如果用户无权访问该工厂
     */
    public void validateAccess(String factoryId) {
        Long userId = SecurityUtils.currentUserId();
        validateAccess(factoryId, userId);
    }

    /**
     * 检查用户是否为平台管理员
     *
     * 平台管理员可以访问所有工厂的数据
     */
    private boolean isPlatformAdmin(User user) {
        String factoryId = user.getFactoryId();
        String role = user.getRole();

        // PLATFORM 工厂ID 表示平台级用户
        if ("PLATFORM".equals(factoryId)) {
            return true;
        }

        // super_admin 和 platform_admin 角色可访问所有工厂
        if ("super_admin".equals(role) || "platform_admin".equals(role)) {
            return true;
        }

        // developer 角色可访问所有工厂（开发调试用）
        if ("developer".equals(role)) {
            return true;
        }

        return false;
    }
}
