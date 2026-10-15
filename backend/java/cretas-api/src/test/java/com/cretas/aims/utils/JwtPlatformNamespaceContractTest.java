package com.cretas.aims.utils;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class JwtPlatformNamespaceContractTest {
    private JwtUtil jwt() {
        var jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        return jwt;
    }

    @Test void explicitPlatformIssuerPreservesNumericIdAndAddsTrustedNamespace() {
        var jwt = jwt();
        String token = jwt.generatePlatformToken(62L, "fixture", "super_admin");
        assertThat(jwt.validateToken(token)).isTrue();
        assertThat(jwt.getUserIdFromToken(token)).isEqualTo(62L);
        assertThat(jwt.getFactoryIdFromToken(token)).isEqualTo("PLATFORM");
        assertThat(jwt.isExplicitPlatformToken(token)).isTrue();
    }

    @Test void sameNumericFactoryIdOrSentinelWithoutMarkerCannotBecomePlatform() {
        var jwt = jwt();
        for (String scope : new String[]{"F1", "PLATFORM"}) {
            String token = jwt.generateToken(62L, scope, "fixture", "super_admin");
            assertThat(jwt.validateToken(token)).isTrue();
            assertThat(jwt.getUserIdFromToken(token)).isEqualTo(62L);
            assertThat(jwt.getFactoryIdFromToken(token)).isEqualTo(scope);
            assertThat(jwt.isExplicitPlatformToken(token)).isFalse();
        }
    }
}
