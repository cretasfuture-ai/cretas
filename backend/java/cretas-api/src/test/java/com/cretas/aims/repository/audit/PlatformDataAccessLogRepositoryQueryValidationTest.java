package com.cretas.aims.repository.audit;

import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Hibernate: 工厂只能按自己的 factoryId 查到针对本厂的平台访问记录, 新的在前。 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = PlatformDataAccessLog.class)
@EnableJpaRepositories(basePackageClasses = PlatformDataAccessLogRepository.class)
class PlatformDataAccessLogRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:platform_access_log;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired PlatformDataAccessLogRepository repository;

    PlatformDataAccessLog row(String factory, LocalDateTime at, String path) {
        return PlatformDataAccessLog.builder().createdAt(at).targetFactoryId(factory)
                .targetSource(PlatformDataAccessLog.TargetSource.PATH).actorType("PLATFORM_ADMIN")
                .actorUserId(900L).httpMethod("GET").requestPath(path).responseStatus(200).build();
    }

    @Test
    void factoryOnlySeesRowsAboutItselfNewestFirst() {
        LocalDateTime now = LocalDateTime.now();
        repository.saveAndFlush(row("F006", now.minusMinutes(2), "/old"));
        repository.saveAndFlush(row("F006", now, "/new"));
        repository.saveAndFlush(row("LIUSHANMEN", now, "/other"));

        var page = repository.findByTargetFactoryIdOrderByCreatedAtDescIdDesc("F006", PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(PlatformDataAccessLog::getRequestPath).containsExactly("/new", "/old");
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(repository.findByTargetFactoryIdOrderByCreatedAtDescIdDesc("F-NONE", PageRequest.of(0, 10)))
                .isEmpty();
    }
}
