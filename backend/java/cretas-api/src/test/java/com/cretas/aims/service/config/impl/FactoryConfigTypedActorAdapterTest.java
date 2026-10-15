package com.cretas.aims.service.config.impl;

import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.auth.UserMenuPermissionRepository;
import com.cretas.aims.repository.config.ConfigChangeLogRepository;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FactoryConfigTypedActorAdapterTest {
    @Mock ModuleSchemaRepository moduleSchemas;
    @Mock FactoryConfigurationRepository configurations;
    @Mock FactoryModuleConfigRepository moduleConfigs;
    @Mock ConfigChangeLogRepository changes;
    @Mock ObjectMapper objectMapper;
    @Mock DDLExecutor ddlExecutor;
    @Mock DynamicFieldService dynamicFields;
    @Mock AssemblyActivationActorResolver actorResolver;
    @Mock UserMenuPermissionRepository permissions;

    @InjectMocks FactoryConfigServiceImpl service;

    @Test
    void typedPublishRejectsBeforeDraftOrAuditWrite() {
        ReflectionTestUtils.setField(service, "actorResolver", actorResolver);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L);
        doThrow(new BusinessException(403, "denied"))
                .when(actorResolver).authorize(eq(actor), eq("F1"), any());

        assertThrows(BusinessException.class,
                () -> service.publishConfigByActor("F1", actor, "formal publish"));
        verifyNoInteractions(configurations, moduleConfigs, changes, ddlExecutor, dynamicFields);
    }
}
