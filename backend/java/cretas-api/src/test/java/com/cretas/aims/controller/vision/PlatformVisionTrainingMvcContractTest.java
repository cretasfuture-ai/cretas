package com.cretas.aims.controller.vision;

import com.cretas.aims.blueprint.platform.PlatformAssemblyAccessConfiguration;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC mapping must pass through the existing factory assembly actor boundary. */
class PlatformVisionTrainingMvcContractTest {
    @Configuration @EnableWebMvc
    static class Mvc { }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        ResponseEntity<String> business(BusinessException failure) {
            return ResponseEntity.status(failure.getCode()).body(failure.getMessage());
        }
    }

    @Test void signedPlatformLoginReachesVisionThroughExistingAssemblyIdentityGate() throws Exception { verifyFeature(true); }
    @Test void disabledAssemblyDoesNotExposeVisionController() throws Exception { verifyFeature(false); }

    private void verifyFeature(boolean enabled) throws Exception {
        var jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        var admins = mock(PlatformAdminRepository.class);
        when(admins.findById(62L)).thenReturn(Optional.of(PlatformAdmin.builder()
                .id(62L).status(Status.active).platformRole(PlatformRole.super_admin).build()));
        var python = mock(PythonSmartBIClient.class);
        when(python.callPlatformVisionTraining("FA", 62L, VisionTrainingAction.TEMPLATES, null, null))
                .thenReturn(new ObjectMapper().readTree("{\"templates\":[],\"recipes\":[]}"));
        var factories = mock(FactoryService.class);
        var factory = new FactoryDTO(); factory.setId("FA");
        when(factories.getFactoryById("FA")).thenReturn(factory);
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("feature",
                    Map.of("cretas.platform.factory-assembly.enabled", Boolean.toString(enabled))));
            context.addBeanFactoryPostProcessor(registry -> {
                registry.registerSingleton("jwtUtil", jwt);
                registry.registerSingleton("admins", admins);
                registry.registerSingleton("python", python);
                registry.registerSingleton("factories", factories);
            });
            context.register(Mvc.class, Errors.class, PlatformAssemblyAccessConfiguration.class,
                    PlatformVisionTrainingController.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String endpoint = "/api/platform/factory-assembly/factories/FA/vision-training/templates";
            if (enabled) {
                mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
                mvc.perform(get(endpoint).header("X-Actor-Type", "PLATFORM_ADMIN").header("X-User-Id", "62"))
                        .andExpect(status().isUnauthorized());
                mvc.perform(get(endpoint).header("Authorization", "Bearer " + jwt.generateToken(62L, "FA", "same-id", "super_admin")))
                        .andExpect(status().isForbidden());
                verifyNoInteractions(factories, python);
            }
            mvc.perform(get(endpoint).header("Authorization", "Bearer " + jwt.generatePlatformToken(62L, "platform-test", "super_admin")))
                    .andExpect(status().is(enabled ? 200 : 404));
            if (enabled) {
                verify(python).callPlatformVisionTraining("FA", 62L, VisionTrainingAction.TEMPLATES, null, null);
            } else {
                verifyNoInteractions(factories, python);
            }
        }
    }
}
