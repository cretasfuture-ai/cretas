package com.cretas.aims.repository.sales;

import com.cretas.aims.entity.sales.SalesPackagingConversionConfirmation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q10 换算确认留痕的实体映射和查询在真实 Hibernate 上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑):
 * 插入读回字段齐全、按发货行查只看本厂、实体层拒绝删除。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SalesPackagingConversionConfirmationRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired private SalesPackagingConversionConfirmationRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void savedTrailReadsBackPerFactoryAndLine() {
        repository.save(row("F-Q10", "271", "FG02", 7001L));
        repository.save(row("F-Q10", "271", "FG03", 7002L));
        repository.save(row("F-Q10", "272", "FG02", 7001L));
        repository.save(row("F-OTHER", "271", "FG02", 9999L));
        entityManager.flush();
        entityManager.clear();

        var trail = repository.findByFactoryIdAndDeliveryItemIdOrderByIdAsc("F-Q10", "271");

        assertThat(trail).extracting(SalesPackagingConversionConfirmation::getFinishedGoodsBatchId)
                .containsExactly("FG02", "FG03");
        assertThat(trail.get(0).getConfirmedBy()).isEqualTo(7001L);
        assertThat(trail.get(0).getFromPackagingFactor()).isEqualByComparingTo("96");
        assertThat(trail.get(0).getToPackagingFactor()).isEqualByComparingTo("8");
        assertThat(trail.get(0).getCreatedAt()).isNotNull();
        assertThat(repository.countByFactoryIdAndDeliveryItemId("F-Q10", "271")).isEqualTo(2L);
        assertThat(repository.countByFactoryIdAndDeliveryItemId("F-OTHER", "272")).isZero();
    }

    @Test
    void entityRefusesRemoval() {
        SalesPackagingConversionConfirmation saved = repository.save(row("F-Q10", "273", "FG02", 7001L));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.remove(saved);
            entityManager.flush();
        }).satisfies(error -> {
            Throwable cause = error;
            while (cause != null && !(cause instanceof UnsupportedOperationException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("删除被实体回调拒绝").isInstanceOf(UnsupportedOperationException.class);
        });
    }

    private static SalesPackagingConversionConfirmation row(String factoryId, String itemId, String batchId, Long actor) {
        SalesPackagingConversionConfirmation row = new SalesPackagingConversionConfirmation();
        row.setFactoryId(factoryId);
        row.setDeliveryRecordId("DLV-1");
        row.setDeliveryItemId(itemId);
        row.setFinishedGoodsBatchId(batchId);
        row.setBatchNumber(batchId);
        row.setAllocatedQty(BigDecimal.ONE);
        row.setUnit("箱");
        row.setFromPackagingUnit("托盘");
        row.setFromPackagingBaseUnit("盒");
        row.setFromPackagingFactor(new BigDecimal("96"));
        row.setToPackagingUnit("箱");
        row.setToPackagingBaseUnit("盒");
        row.setToPackagingFactor(new BigDecimal("8"));
        row.setConfirmedBy(actor);
        row.setConfirmedAt(LocalDateTime.of(2026, 11, 9, 10, 0));
        return row;
    }
}
