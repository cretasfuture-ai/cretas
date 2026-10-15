package com.cretas.aims.blueprint.platform;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises actual MVC registration, so an unregistered interceptor cannot pass these checks. */
class PlatformAssemblyMvcContractTest {
    @Configuration @EnableWebMvc
    static class Mvc { }

    @RestController
    static class Writes {
        final AtomicInteger calls = new AtomicInteger();
        @PostMapping("/api/platform/factory-assembly/factories/NEW/ai-initialize/preview")
        String platform() { calls.incrementAndGet(); return "platform"; }
        @GetMapping("/api/mobile/EXISTING/products")
        String ordinary() { return "ordinary"; }
        @PostMapping("/api/platform/factories/EXISTING/ai-initialize")
        String historicalPlatformPreview() { return "historical"; }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        ResponseEntity<String> business(BusinessException failure) {
            return ResponseEntity.status(failure.getCode()).body(failure.getMessage());
        }
    }

    private void verify(boolean enabled) throws Exception {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        when(admins.findById(62L)).thenReturn(Optional.of(PlatformAdmin.builder()
                .id(62L).status(Status.active).platformRole(PlatformRole.super_admin).build()));
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("feature",
                    Map.of("cretas.platform.factory-assembly.enabled", Boolean.toString(enabled))));
            context.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("jwtUtil", jwt);
                factory.registerSingleton("admins", admins);
            });
            context.register(Mvc.class, Writes.class, Errors.class,
                    PlatformAssemblyAccessConfiguration.class,
                    PlatformAssemblyAccessConfiguration.CapabilitiesController.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/api/platform/factories/EXISTING/ai-initialize"))
                    .andExpect(status().isOk()).andExpect(content().string("historical"));
            String endpoint = "/api/platform/factory-assembly/factories/NEW/ai-initialize/preview";
            String platform = "Bearer " + jwt.generatePlatformToken(62L, "platform-test", "super_admin");
            String factory = "Bearer " + jwt.generateToken("62", "factory_super_admin");
            mvc.perform(post(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(post(endpoint).header("Authorization", factory)).andExpect(status().isForbidden());
            assertEquals(0, context.getBean(Writes.class).calls.get());
            mvc.perform(post(endpoint).header("Authorization", platform))
                    .andExpect(status().is(enabled ? 200 : 404));
            assertEquals(enabled ? 1 : 0, context.getBean(Writes.class).calls.get());
            mvc.perform(get("/api/platform/factory-assembly/capabilities").header("Authorization", platform))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(enabled));
            mvc.perform(get("/api/mobile/EXISTING/products"))
                    .andExpect(status().isOk()).andExpect(content().string("ordinary"));
        }
    }

    @Test void disabledFeatureRejectsBeforeInvocationAndLeavesOrdinaryRoutesUntouched() throws Exception { verify(false); }
    @Test void enabledFeatureAllowsOnlySignedPlatformAccountBeforeInvocation() throws Exception { verify(true); }
}
