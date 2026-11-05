package com.cretas.aims.service.impl;

import com.cretas.aims.config.IntentKnowledgeBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FactoryOrderToolDomainContractTest {
    @Test
    void factoryCustomerDomainRetainsOrderToolsAfterRestaurantDomainRemoval() {
        Object value = ReflectionTestUtils.getField(LlmIntentFallbackClientImpl.class, "DOMAIN_TOOL_PREFIXES");
        assertInstanceOf(Map.class, value);
        Object customer = ((Map<?, ?>) value).get(IntentKnowledgeBase.Domain.CUSTOMER);
        assertInstanceOf(Set.class, customer);
        assertTrue(((Set<?>) customer).contains("order_"));
        assertTrue(((Map<?, ?>) value).keySet().stream()
                .noneMatch(domain -> "RESTAURANT".equals(String.valueOf(domain))));
    }
}
