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
                || p.contains("restaurant-dashboard") || p.contains("restaurant-health-check")
                || p.contains("/restaurant/")));
        assertTrue(patterns.stream().anyMatch(p -> p.contains("/warehouse/supplier-delivery-notes")));
    }
}
