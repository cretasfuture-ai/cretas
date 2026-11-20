package com.cretas.aims.controller;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalPermissionCheckContractTest {
    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private User user(long id, String factory, boolean active) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(factory);
        u.setIsActive(active);
        return u;
    }

    private boolean allowed(InternalPermissionCheckController c, String factory, long userId, List<String> codes) {
        return c.userCheck(factory, userId, codes).getBody().getData().get("allowed");
    }

    @Test
    void anyGrantedCodeAllowsAndAskedPerRealUser() {
        InternalPermissionCheckController c = new InternalPermissionCheckController(permissionService, userRepository);
        User a = user(1, "A", true);
        when(userRepository.findByIdAndFactoryId(1L, "A")).thenReturn(Optional.of(a));
        when(permissionService.hasPermission(a, "sales:read_write")).thenReturn(false);
        when(permissionService.hasPermission(a, "sales:read")).thenReturn(true);
        assertTrue(allowed(c, "A", 1, List.of("sales:read_write", "sales:read")));
    }

    @Test
    void samePositionRevokedForThisPersonIsDenied() {
        // 同岗位的两个人: 甲有权, 乙被个人 REVOKE (hasPermission(user, code) 才会叠加个人覆盖, 岗位探针不会)
        InternalPermissionCheckController c = new InternalPermissionCheckController(permissionService, userRepository);
        User granted = user(1, "A", true);
        User revoked = user(2, "A", true);
        when(userRepository.findByIdAndFactoryId(1L, "A")).thenReturn(Optional.of(granted));
        when(userRepository.findByIdAndFactoryId(2L, "A")).thenReturn(Optional.of(revoked));
        when(permissionService.hasPermission(granted, "sales:read")).thenReturn(true);
        when(permissionService.hasPermission(revoked, "sales:read")).thenReturn(false);
        assertTrue(allowed(c, "A", 1, List.of("sales:read")));
        assertFalse(allowed(c, "A", 2, List.of("sales:read")));
    }

    @Test
    void unknownCrossFactoryOrDisabledUserIsDeniedWithoutAskingPermissions() {
        InternalPermissionCheckController c = new InternalPermissionCheckController(permissionService, userRepository);
        when(userRepository.findByIdAndFactoryId(1L, "B")).thenReturn(Optional.empty());   // 该用户不在 B 厂
        when(userRepository.findByIdAndFactoryId(3L, "A")).thenReturn(Optional.of(user(3, "A", false)));
        assertFalse(allowed(c, "B", 1, List.of("sales:read")));
        assertFalse(allowed(c, "A", 3, List.of("sales:read")));
        verify(permissionService, never()).hasPermission(any(User.class), anyString());
    }
}
