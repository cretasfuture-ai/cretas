package com.cretas.aims.utils;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.AuthenticationException;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.UserRepository;
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

        // 平台身份可访问所有工厂。平台账号的 id 与 users.id 是两个命名空间, 不能拿它查 users。
        if (com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .filter(com.cretas.aims.security.AuthenticatedActor::hasFactoryBypass).isPresent()) {
            return;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("用户不存在"));

        if ("PLATFORM".equals(user.getFactoryId())) {
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

}
