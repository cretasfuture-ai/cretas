package com.cretas.aims.service.demo;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Startup fence for the separately persisted sandbox runtime node. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cretas.demo-sandbox.node.enabled", havingValue = "true")
public class DemoSandboxNodeGuardConfiguration {

    private static final String SCHEDULED_PROCESSOR =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Bean
    DemoSandboxNodeGuard demoSandboxNodeGuard(
            @Value("${spring.datasource.url:}") String databaseUrl,
            @Value("${spring.data.redis.host:${spring.redis.host:}}") String redisHost,
            @Value("${spring.data.redis.port:${spring.redis.port:0}}") int redisPort,
            @Value("${cretas.jwt.secret:}") String actualJwtSecret,
            @Value("${cretas.demo.sandbox.production-jwt-sha256:}") String productionJwtFingerprint,
            JdbcTemplate jdbcTemplate) {
        return new DemoSandboxNodeGuard(databaseUrl, redisHost, redisPort,
                actualJwtSecret, productionJwtFingerprint, jdbcTemplate);
    }

    /** Remove the global scheduler processor before scheduled beans can register. */
    @Bean
    static BeanFactoryPostProcessor disableSandboxScheduling() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(SCHEDULED_PROCESSOR)) {
                registry.removeBeanDefinition(SCHEDULED_PROCESSOR);
            }
        };
    }

    public static final class DemoSandboxNodeGuard {
        private final String databaseUrl;
        private final String redisHost;
        private final int redisPort;
        private final String databaseFingerprint;

        DemoSandboxNodeGuard(String databaseUrl,
                             String redisHost,
                             int redisPort,
                             String actualJwtSecret,
                             String productionJwtFingerprint) {
            this(databaseUrl, redisHost, redisPort, actualJwtSecret, productionJwtFingerprint, null);
        }

        DemoSandboxNodeGuard(String databaseUrl,
                             String redisHost,
                             int redisPort,
                             String actualJwtSecret,
                             String productionJwtFingerprint,
                             JdbcTemplate jdbcTemplate) {
            if (!allowedDatabaseUrl(databaseUrl)) {
                throw new IllegalStateException("F006 sandbox database must be f006_demo_sandbox on postgres:5432 or loopback:55432");
            }
            if (!allowedRedis(redisHost, redisPort)) {
                throw new IllegalStateException("F006 sandbox Redis must be redis:6379 or loopback:6387");
            }
            if (actualJwtSecret == null || actualJwtSecret.isBlank()
                    || actualJwtSecret.length() < 64
                    || actualJwtSecret.contains("CHANGE_ME_NOT_FOR_PRODUCTION")
                    || productionJwtFingerprint == null
                    || !productionJwtFingerprint.matches("[0-9a-fA-F]{64}")
                    || sha256(actualJwtSecret).equalsIgnoreCase(productionJwtFingerprint)) {
                throw new IllegalStateException("F006 sandbox JWT secret must be separately configured");
            }
            this.databaseUrl = sanitizeDatabaseUrl(databaseUrl);
            this.redisHost = redisHost;
            this.redisPort = redisPort;
            this.databaseFingerprint = sha256(this.databaseUrl);
            verifyTenantRows(jdbcTemplate);
        }

        public Map<String, Object> identity() {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("mode", "F006_DEMO_SANDBOX");
            identity.put("databaseUrl", databaseUrl);
            identity.put("databaseFingerprint", databaseFingerprint);
            identity.put("redisHost", redisHost);
            identity.put("redisPort", redisPort);
            identity.put("scheduling", "DISABLED");
            return identity;
        }

        private static boolean allowedDatabaseUrl(String value) {
            return value != null && value.matches(
                    "jdbc:postgresql://(?:postgres:5432|127\\.0\\.0\\.1:55432|localhost:55432)/f006_demo_sandbox(?:[?].*)?");
        }

        private static boolean allowedRedis(String host, int port) {
            return ("redis".equalsIgnoreCase(host) && port == 6379)
                    || (("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) && port == 6387);
        }

        private static void verifyTenantRows(JdbcTemplate jdbcTemplate) {
            if (jdbcTemplate == null) return;
            Long foreignRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE factory_id IS NULL OR factory_id <> 'F006'",
                    Long.class);
            if (foreignRows != null && foreignRows > 0) {
                throw new IllegalStateException("F006 sandbox users table contains foreign tenants: " + foreignRows);
            }
        }

        private static String sanitizeDatabaseUrl(String value) {
            return value.replaceFirst("[?].*$", "")
                    .replaceAll("//[^/@]+:[^/@]+@", "//");
        }

        private static String sha256(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte item : digest) hex.append(String.format("%02x", item));
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
