package com.cretas.aims.controller;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC authorization chain and permission resolver; only external stores/vision are mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseLayoutRecognitionPermissionContractTest {
    private static final String FACTORY = "FOOD_0000_001";
    private static final String IMAGE = "{\"imageBase64\":\"AQID\",\"contentType\":\"image/png\"}";
    @Mock private JwtUtil jwt;
    @Mock private TokenBlacklistService blacklist;
    @Mock private UserRepository users;
    @Mock private FactoryRepository factories;
    @Mock private FactoryModuleConfigRepository modules;
    @Mock private FactoryFeatureConfigRepository layouts;
    @Mock private FinishedGoodsBatchRepository goods;
    @Mock private PythonLLMClient vision;
    private PermissionServiceImpl permissions;
    private MockMvc mvc;
    private User user;
    private String warehouseLevel;

    @BeforeEach
    void setUp() {
        warehouseLevel = "rw";
        user = new User();
        user.setId(1703L);
        user.setFactoryId(FACTORY);
        user.setRoleCode("department_admin");
        user.setPosition("factory_super_admin");
        user.setIsActive(true);
        when(users.findById(1703L)).thenReturn(Optional.of(user));
        when(jwt.validateToken("unit-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("unit-token")).thenReturn(1703L);
        when(jwt.getUsernameFromToken("unit-token")).thenReturn("ydwm_admin");
        when(jwt.getFactoryIdFromToken("unit-token")).thenReturn(FACTORY);
        when(jwt.getRoleFromToken("unit-token")).thenReturn("department_admin");
        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setIsActive(true);
        when(factories.findById(FACTORY)).thenReturn(Optional.of(factory));
        when(modules.findByFactoryIdAndConfigVersion(FACTORY, 1)).thenAnswer(call -> {
            FactoryModuleConfig config = new FactoryModuleConfig();
            config.setRoleModuleOverride(Map.of("department_admin", Map.of(
                    "warehouse", warehouseLevel, "finance", "-", "hr", "-", "system", "-")));
            return List.of(config);
        });
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", modules);
        JwtAuthInterceptor auth = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(auth, "jwtUtil", jwt);
        ReflectionTestUtils.setField(auth, "tokenBlacklistService", blacklist);
        ReflectionTestUtils.setField(auth, "userRepository", users);
        ReflectionTestUtils.setField(auth, "factoryRepository", factories);
        ObjectMapper mapper = new ObjectMapper();
        WarehouseLayoutController controller = new WarehouseLayoutController(layouts, goods,
                new WarehouseLocationCatalog(layouts), vision, mapper);
        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(auth,
                new PermissionInterceptor(permissions, users, mapper), new RequireRoleInterceptor(mapper)).build();
        when(vision.visionChatWarehouse(any(byte[].class), eq("image/png"), anyString()))
                .thenReturn("{\"zones\":[{\"id\":\"z1\",\"name\":\"冷藏区\",\"prefix\":\"LC\"}],\"racks\":[]}");
    }

    @Test
    void departmentAdminWithExistingWarehouseWriteCanRecognizeWithoutSavingLayout() throws Exception {
        mvc.perform(post("/api/mobile/{factoryId}/warehouse/layout/recognize", FACTORY)
                .header("Authorization", "Bearer unit-token").contentType(MediaType.APPLICATION_JSON).content(IMAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.zones[0].prefix").value("LC"));
        verify(vision).visionChatWarehouse(any(byte[].class), eq("image/png"), anyString());
        verifyNoInteractions(layouts, goods);
        assertThat(user.getRoleCode()).isEqualTo("department_admin");
        assertThat(permissions.hasPermission(user, "finance:write")).isFalse();
        assertThat(permissions.hasPermission(user, "hr:write")).isFalse();
        assertThat(permissions.hasPermission(user, "system:write")).isFalse();
    }

    @Test
    void readOnlyWarehouseIsDeniedEvenWhenPositionSaysFactorySuperAdmin() throws Exception {
        warehouseLevel = "r";
        assertRecognitionDenied(403);
    }

    @Test
    void disabledWarehouseIsDeniedBeforeVisionOrStorage() throws Exception {
        warehouseLevel = "-";
        assertRecognitionDenied(403);
    }

    @Test
    void writeOnlyWarehousePermissionIsSufficientForRecognition() throws Exception {
        warehouseLevel = "w";
        mvc.perform(post("/api/mobile/{factoryId}/warehouse/layout/recognize", FACTORY)
                .header("Authorization", "Bearer unit-token").contentType(MediaType.APPLICATION_JSON).content(IMAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verifyNoInteractions(layouts, goods);
    }

    @Test
    void missingLoginAndCrossFactoryRequestsCannotReachVision() throws Exception {
        mvc.perform(post("/api/mobile/{factoryId}/warehouse/layout/recognize", FACTORY)
                .contentType(MediaType.APPLICATION_JSON).content(IMAGE)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/OTHER/warehouse/layout/recognize")
                .header("Authorization", "Bearer unit-token").contentType(MediaType.APPLICATION_JSON).content(IMAGE))
                .andExpect(status().isForbidden());
        verifyNoInteractions(vision, layouts, goods);
    }

    @Test
    void recognitionDoesNotGrantTheExistingLayoutSaveRole() throws Exception {
        mvc.perform(put("/api/mobile/{factoryId}/warehouse/layout", FACTORY)
                .header("Authorization", "Bearer unit-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zones\":[],\"locations\":[],\"floors\":[]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(vision, layouts, goods);
    }

    private void assertRecognitionDenied(int status) throws Exception {
        mvc.perform(post("/api/mobile/{factoryId}/warehouse/layout/recognize", FACTORY)
                .header("Authorization", "Bearer unit-token").contentType(MediaType.APPLICATION_JSON).content(IMAGE))
                .andExpect(status().is(status)).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(vision, layouts, goods);
    }
}
