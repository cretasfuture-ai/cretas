package com.cretas.aims.config;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.*;
import com.cretas.aims.security.*;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtTypedDataScopeContractTest {
    JwtUtil jwt; JwtAuthInterceptor interceptor; UserRepository users;
    @BeforeEach void setup() {
        jwt=new JwtUtil();
        ReflectionTestUtils.setField(jwt,"secret","nonproduction-contract-secret-for-typed-identity-20261016");
        ReflectionTestUtils.setField(jwt,"expiration",3600000L);
        interceptor=new JwtAuthInterceptor();users=mock(UserRepository.class);
        ReflectionTestUtils.setField(interceptor,"jwtUtil",jwt);
        ReflectionTestUtils.setField(interceptor,"userRepository",users);
        ReflectionTestUtils.setField(interceptor,"factoryRepository",mock(FactoryRepository.class));
        ReflectionTestUtils.setField(interceptor,"tokenBlacklistService",mock(TokenBlacklistService.class));
        var user=new User();user.setId(56L);user.setFactoryId("F1");user.setIsActive(true);user.setRoleCode("platform_admin");
        when(users.findById(56L)).thenReturn(Optional.of(user));
    }
    MockHttpServletRequest request(String token,String factory) {
        var request=new MockHttpServletRequest("GET","/api/mobile/"+factory+"/customers");
        request.addHeader("Authorization","Bearer "+token);return request;
    }
    @Test void factoryTokenWithPlatformRoleNeverBorrowsPlatformNamespace() throws Exception {
        String token=jwt.generateToken(56L,"F1","factory_collision","platform_admin");
        var request=request(token,"F1");
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
        assertThat(AuthenticatedActor.current(request).orElseThrow().type()).isEqualTo(AuthenticatedActorType.FACTORY_USER);
        assertThat(AuthenticatedActor.current(request).orElseThrow().hasFactoryBypass()).isFalse();
        var response=new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(token,"F2"),response,null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        verify(users,times(2)).findById(56L);
    }
    @Test void explicitPlatformTokenDoesNotResolveCollidingFactoryUser() throws Exception {
        var request=request(jwt.generatePlatformToken(56L,"platform_collision","super_admin"),"F2");
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
        var actor=AuthenticatedActor.current(request).orElseThrow();
        assertThat(actor.type()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
        assertThat(actor.hasFactoryBypass()).isTrue();verifyNoInteractions(users);
    }
    @Test void inactiveFactoryUserCannotGainTypedActorFromRoleName() throws Exception {
        var user=new User();user.setFactoryId("F1");user.setIsActive(false);when(users.findById(56L)).thenReturn(Optional.of(user));
        var request=request(jwt.generateToken(56L,"F1","disabled_collision","platform_admin"),"F1");
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isFalse();
        assertThat(AuthenticatedActor.current(request)).isEmpty();
    }

    @org.springframework.web.bind.annotation.RestController
    static class PermissionProbe {
        @org.springframework.web.bind.annotation.GetMapping("/api/mobile/{factoryId}/typed-permission-probe")
        @com.cretas.aims.annotation.RequirePermission("system:read")
        public String read(jakarta.servlet.http.HttpServletRequest request) {
            return AuthenticatedActor.current(request).orElseThrow().type().name();
        }
    }

    @Test void realJwtAndPermissionChainUsesPlatformNamespaceWithoutNumericUserLookup() throws Exception {
        var permissions=mock(com.cretas.aims.service.PermissionService.class);
        var guard=new PermissionInterceptor(permissions,users,new com.fasterxml.jackson.databind.ObjectMapper());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PermissionProbe())
                .addInterceptors(interceptor,guard).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F2/typed-permission-probe")
                .header("Authorization","Bearer "+jwt.generatePlatformToken(56L,"platform_collision","super_admin")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("PLATFORM_ADMIN"));
        verifyNoInteractions(users,permissions);
    }

    @Test void realFactoryJwtAndPermissionChainCannotBorrowPlatformPrivilegeFromRoleCode() throws Exception {
        var permissions=mock(com.cretas.aims.service.PermissionService.class);
        var guard=new PermissionInterceptor(permissions,users,new com.fasterxml.jackson.databind.ObjectMapper());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PermissionProbe())
                .addInterceptors(interceptor,guard).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/F1/typed-permission-probe")
                .header("Authorization","Bearer "+jwt.generateToken(56L,"F1","factory_collision","platform_admin")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verify(permissions).hasAnyPermission(any(User.class),eq(new String[]{"system:read"}));
    }
}
