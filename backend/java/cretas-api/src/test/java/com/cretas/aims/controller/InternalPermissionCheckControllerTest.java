package com.cretas.aims.controller;

import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalPermissionCheckControllerTest {
    @Mock PermissionService permissionService;

    @Test
    void allowedWhenAnyCodeIsGrantedAndDeniedWhenNoneIsAndAskedPerFactory() {
        InternalPermissionCheckController c = new InternalPermissionCheckController(permissionService);
        // 同一岗位: 工厂 A 有 sales:read, 工厂 B 被收回
        when(permissionService.roleHasPermission("A", "dispatcher", "sales:read_write")).thenReturn(false);
        when(permissionService.roleHasPermission("A", "dispatcher", "sales:read")).thenReturn(true);
        when(permissionService.roleHasPermission("B", "dispatcher", "sales:read_write")).thenReturn(false);
        when(permissionService.roleHasPermission("B", "dispatcher", "sales:read")).thenReturn(false);

        List<String> codes = List.of("sales:read_write", "sales:read");
        assertTrue(c.roleCheck("A", "dispatcher", codes).getBody().getData().get("allowed"));
        assertFalse(c.roleCheck("B", "dispatcher", codes).getBody().getData().get("allowed"));
    }
}
