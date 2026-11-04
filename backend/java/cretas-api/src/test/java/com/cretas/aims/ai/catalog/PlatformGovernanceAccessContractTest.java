package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.impl.governance.SkillComposeTool;
import com.cretas.aims.ai.tool.impl.governance.SkillManageTool;
import com.cretas.aims.ai.tool.impl.governance.ToolPatternDiscoveryTool;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PlatformGovernanceAccessContractTest {
    @Test
    void claimedRoleCannotAuthorizeGlobalSkillReadsOrWrites() throws Exception {
        assertThat(new SkillComposeTool().hasPermission("super_admin")).isFalse();
        assertThat(new SkillManageTool().hasPermission("platform_admin")).isFalse();
        assertThat(new ToolPatternDiscoveryTool().hasPermission("platform_admin")).isFalse();
        denyAtEntry(new SkillComposeTool(), "doExecute");
        denyAtEntry(new SkillComposeTool(), "doPreview");
        denyAtEntry(new SkillManageTool(), "doExecute");
        denyAtEntry(new ToolPatternDiscoveryTool(), "doExecute");
    }

    @Test
    void authenticatedFactoryAdminAndPlatformAuditorAreDeniedButPlatformAdminIsAllowed() {
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 1L, "A", "factory_super_admin"))) {
            assertThat(PlatformGovernanceAccess.allowed()).isFalse();
            assertThatThrownBy(PlatformGovernanceAccess::requireAdmin).isInstanceOf(BusinessException.class);
        }
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "auditor"))) {
            assertThat(PlatformGovernanceAccess.allowed()).isFalse();
        }
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "platform_admin"))) {
            assertThat(PlatformGovernanceAccess.allowed()).isTrue();
            assertThat(new SkillComposeTool().hasPermission("platform_admin")).isTrue();
            assertThat(new SkillManageTool().hasPermission("platform_admin")).isTrue();
            assertThat(new ToolPatternDiscoveryTool().hasPermission("platform_admin")).isTrue();
        }
    }

    private void denyAtEntry(Object tool, String methodName) throws Exception {
        var method = tool.getClass().getDeclaredMethod(methodName, String.class, Map.class, Map.class);
        method.setAccessible(true);
        try {
            method.invoke(tool, "A", Map.of("name", "x", "tools", java.util.List.of("material_batch_query")),
                    Map.of("userRole", "platform_admin", "factoryId", "A"));
            fail("Untrusted caller reached global skill service");
        } catch (InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException)e.getCause()).getCode()).isEqualTo(403);
        }
    }
}
