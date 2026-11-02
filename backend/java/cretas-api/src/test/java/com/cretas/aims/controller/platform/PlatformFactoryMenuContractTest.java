package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.dto.platform.PlatformFactoryMenuDTO;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.service.platform.PlatformFactoryMenuService;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformFactoryMenuContractTest {
    @Mock FactoryService factories;
    @Mock FactoryFeatureConfigRepository repository;
    @Mock JwtUtil jwt;
    @Mock FactoryFeatureMenuWriteLock writeLock;
    @Mock HttpServletRequest request;

    private PlatformFactoryMenuService service;
    private PlatformFactoryMenuController controller;

    @BeforeEach
    void setUp() {
        service = new PlatformFactoryMenuService(factories, repository, jwt, writeLock);
        controller = new PlatformFactoryMenuController(service);
        when(factories.getFactoryById("F001")).thenReturn(FactoryDTO.builder().id("F001").name("Test").type("FACTORY").build());
        when(repository.findByFactoryIdAndDeletedAtIsNull("F001")).thenReturn(List.of());
    }

    @Test
    void menuSaveMergesOnlyMenuFieldsAndNeverEnablesBusinessFeature() {
        Map<String, Object> original = new HashMap<>(Map.of("enabledForReports", true, "published", true));
        FactoryFeatureConfig row = FactoryFeatureConfig.builder().factoryId("F001").moduleId("analytics")
                .moduleName("Analytics").enabled(false).config(original).build();
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F001", "analytics"))
                .thenReturn(Optional.of(row));
        when(repository.findByFactoryIdAndDeletedAtIsNull("F001")).thenReturn(List.of(row));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.update("F001", "analytics", new PlatformFactoryMenuDTO.Update(true, List.of("/analytics/overview")));

        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getConfig()).containsEntry("enabledForReports", true).containsEntry("published", true)
                .containsEntry("menuVisible", true).containsEntry("showInSidebar", true);
        verify(repository).save(row);
    }

    @Test
    void exactModulePathWhitelistRejectsCrossModuleAndPathPrefixTricks() {
        assertThatThrownBy(() -> service.update("F001", "analytics",
                new PlatformFactoryMenuDTO.Update(true, List.of("/analytics-evil"))))
                .hasMessageContaining("不受支持");
        assertThatThrownBy(() -> service.update("F001", "analytics",
                new PlatformFactoryMenuDTO.Update(true, List.of("/dashboard"))))
                .hasMessageContaining("不受支持");
    }

    @Test
    void legacySmartbiRowIsReadAsAnalyticsMenuState() {
        FactoryFeatureConfig row = FactoryFeatureConfig.builder().factoryId("F001").moduleId("smartbi")
                .moduleName("SmartBI").config(new HashMap<>(Map.of("showInSidebar", false))).build();
        when(repository.findByFactoryIdAndDeletedAtIsNull("F001")).thenReturn(List.of(row));
        assertThat(service.get("F001").getModules().stream()
                .filter(module -> "analytics".equals(module.getModuleId())).findFirst().orElseThrow().isVisible()).isFalse();
    }

    @Test
    void managementControllerRejectsDeveloperAndFactoryActors() {
        when(request.getAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE))
                .thenReturn(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "developer"));
        assertThatThrownBy(() -> controller.get("F001", request)).hasMessageContaining("仅平台管理员");
        when(request.getAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE))
                .thenReturn(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 2L, "F001", "factory_admin"));
        assertThatThrownBy(() -> controller.get("F001", request)).hasMessageContaining("仅平台管理员");
    }
}
