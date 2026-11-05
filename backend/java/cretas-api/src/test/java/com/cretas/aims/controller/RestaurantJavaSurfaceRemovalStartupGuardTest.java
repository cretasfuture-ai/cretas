package com.cretas.aims.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full production MVC registry guard: removed restaurant handlers must not be registered. */
@SpringBootTest
@ActiveProfiles("test")
class RestaurantJavaSurfaceRemovalStartupGuardTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void removedRestaurantMappingsAreAbsentAndFactoryDeliveryRemainsPresent() {
        var patterns = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).toList();
        assertFalse(patterns.stream().anyMatch(p -> p.contains("recipe-versions")
                || p.toLowerCase(java.util.Locale.ROOT).contains("restaurant")));
        assertTrue(patterns.stream().anyMatch(p -> p.contains("/warehouse/supplier-delivery-notes")));
    }

    @Test
    void liveHandlersDoNotDeclareRetiredModulePermissions() {
        mappings.getHandlerMethods().values().forEach(handler -> {
            var methodPermission = handler.getMethodAnnotation(com.cretas.aims.annotation.RequirePermission.class);
            var classPermission = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    handler.getBeanType(), com.cretas.aims.annotation.RequirePermission.class);
            for (var permission : new com.cretas.aims.annotation.RequirePermission[]{methodPermission, classPermission}) {
                if (permission == null) continue;
                assertFalse(java.util.Arrays.stream(permission.value())
                        .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith("restaurant")),
                        () -> "Retired permission remains on " + handler);
            }
        });
    }
}
