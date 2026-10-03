package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Session;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MobileAuthServiceImplDisabledUserTest {
    private MobileAuthServiceImpl service;
    private UserRepository users;
    private PlatformAdminRepository admins;
    private JwtUtil jwt;
    private SessionRepository sessions;
    private TokenBlacklistService blacklist;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        admins = mock(PlatformAdminRepository.class);
        jwt = mock(JwtUtil.class);
        sessions = mock(SessionRepository.class);
        blacklist = mock(TokenBlacklistService.class);
        service = new MobileAuthServiceImpl(users, sessions, admins, mock(WhitelistRepository.class), mock(PasswordEncoder.class), jwt, mock(UserMapper.class), mock(TempTokenService.class), blacklist, mock(MobileDeviceService.class), mock(PermissionService.class));
        when(jwt.validateToken("synthetic-refresh")).thenReturn(true);
        when(jwt.getUserIdFromTokenAsString("synthetic-refresh")).thenReturn("101");
        when(jwt.generateToken(anyLong(), anyString(), anyString(), anyString())).thenReturn("synthetic-new-access");
    }

    private User user(Boolean active) {
        User user = new User();
        user.setId(101L);
        user.setFactoryId("SIM24A");
        user.setUsername("synthetic-user");
        user.setRoleCode("viewer");
        user.setIsActive(active);
        return user;
    }

    @Test
    void disabledOrNullActiveUserCannotRefreshUnexpiredToken() {
        for (Boolean active : new Boolean[]{false, null}) {
            when(users.findById(101L)).thenReturn(Optional.of(user(active)));
            BusinessException failure = assertThrows(BusinessException.class, () -> service.refreshToken("synthetic-refresh"));
            assertEquals(403, failure.getCode());
        }
        verify(jwt, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void missingUserCannotRefresh() {
        when(users.findById(101L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.refreshToken("synthetic-refresh"));
        verify(jwt, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void activeUserCanRefreshWithSameExistingRoleAndRefreshToken() {
        when(users.findById(101L)).thenReturn(Optional.of(user(true)));
        var response = service.refreshToken("synthetic-refresh");
        assertEquals("synthetic-new-access", response.getToken());
        assertEquals("synthetic-refresh", response.getRefreshToken());
        assertEquals("viewer", response.getRole());
        verify(jwt).generateToken(101L, "SIM24A", "synthetic-user", "viewer");
        verify(users, never()).save(any());
    }

    @Test
    void repositoryFailureStopsBeforeSigning() {
        when(users.findById(101L)).thenThrow(new IllegalStateException("synthetic-db-down"));
        assertThrows(IllegalStateException.class, () -> service.refreshToken("synthetic-refresh"));
        verify(jwt, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void platformPrefixUsesIndependentRepositoryDespiteOrdinaryIdCollision() {
        when(jwt.getUserIdFromTokenAsString("synthetic-refresh")).thenReturn("platform_101");
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(101L);
        admin.setUsername("synthetic-platform");
        admin.setPlatformRole(PlatformRole.super_admin);
        when(admins.findById(101L)).thenReturn(Optional.of(admin));
        assertEquals("synthetic-new-access", service.refreshToken("synthetic-refresh").getToken());
        verifyNoInteractions(users);
        verify(jwt).generateToken(101L, "PLATFORM", "synthetic-platform", "super_admin");
    }

    @Test
    void invalidRefreshNeverQueriesAnyPrincipal() {
        when(jwt.validateToken("synthetic-refresh")).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.refreshToken("synthetic-refresh"));
        verifyNoInteractions(users, admins);
    }

    @Test
    void normalLogoutStillRevokesOnlyPresentedOwnedSession() {
        Session current = new Session();
        current.setUserId(101L);
        current.setIsRevoked(false);
        Session other = new Session();
        other.setUserId(101L);
        other.setIsRevoked(false);
        when(jwt.getExpirationDateFromToken("synthetic-access")).thenReturn(new java.util.Date(System.currentTimeMillis() + 60000));
        when(sessions.findByTokenAndIsRevokedFalse("synthetic-access")).thenReturn(Optional.of(current));
        service.logout(101L, null, "synthetic-access");
        assertTrue(current.getIsRevoked());
        assertFalse(other.getIsRevoked());
        verify(sessions).save(current);
        verify(sessions, never()).revokeAllUserSessions(anyLong());
        verify(blacklist).blacklistToken(eq("synthetic-access"), anyLong());
        verifyNoInteractions(users, admins);
    }

    @Test
    void logoutNeverRevokesAnotherUsersPresentedSession() {
        Session other = new Session();
        other.setUserId(202L);
        other.setIsRevoked(false);
        when(sessions.findByTokenAndIsRevokedFalse("synthetic-access")).thenReturn(Optional.of(other));
        service.logout(101L, null, "synthetic-access");
        assertFalse(other.getIsRevoked());
        verify(sessions, never()).save(any());
        verify(sessions, never()).revokeAllUserSessions(anyLong());
    }
}
