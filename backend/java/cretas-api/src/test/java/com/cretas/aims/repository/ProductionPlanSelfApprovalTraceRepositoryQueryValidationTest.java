package com.cretas.aims.repository;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.service.impl.DecisionAuditServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 红线 4 (2026-11-13): 强制插单「本人审批」结构化留痕在真实 JPA 映射上落库、读回 (H2; 设了
 * CRETAS_WORKFLOW_PG_URL 时在 PG 上跑)。
 *
 * <ul>
 *   <li>{@code production_plans.self_approved / self_approval_reason} (V20261114_01) 能写能按字段查;</li>
 *   <li>{@code DecisionAuditServiceImpl.logApproval(..., selfApproved, reason)} 把两列写进
 *       {@code decision_audit_logs} (V20261110_47), 旧 7 参版本写 false/null;</li>
 *   <li>迁移里加的列名 = 实体 {@code @Column} 名 (两份必须一致)。</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Sql(
    scripts = "/test-data/restock-fixtures.sql",
    config = @SqlConfig(errorMode = SqlConfig.ErrorMode.FAIL_ON_ERROR),
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class ProductionPlanSelfApprovalTraceRepositoryQueryValidationTest {

    private static final String FID = "F-TEST";
    private static final Long UID = 900001L;   // restock-fixtures.sql

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

    @Autowired private ProductionPlanRepository plans;
    @Autowired private DecisionAuditLogRepository auditLogs;
    @Autowired private EntityManager entityManager;

    private ProductionPlan plan(String id, boolean selfApproved, String reason) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(FID);
        p.setPlanNumber("QA-" + id);
        p.setProductTypeId("PT-ZS");
        p.setPlannedQuantity(new BigDecimal("10"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setCreatedBy(UID);
        p.setSelfApproved(selfApproved);
        p.setSelfApprovalReason(reason);
        return plans.saveAndFlush(p);
    }

    @Test
    @DisplayName("计划上的 self_approved / self_approval_reason 落库并可按字段查回 (不靠意见前缀)")
    void planSelfApprovalColumnsRoundTripAndAreQueryable() {
        plan("QA-SA-SELF", true, "本厂只有我一个人能审批");
        plan("QA-SA-OTHER", false, null);
        ProductionPlan untouched = new ProductionPlan();
        assertThat(untouched.isSelfApproved()).as("新计划默认不是本人审批").isFalse();
        entityManager.clear();

        // 标量投影: H2 上把整行实体读回会撞既有的 JSON 列反序列化问题 (与本改动无关), 这里只读这两列
        List<Object[]> self = entityManager.createQuery(
                        "SELECT p.id, p.selfApprovalReason FROM ProductionPlan p "
                                + "WHERE p.factoryId = :f AND p.selfApproved = true", Object[].class)
                .setParameter("f", FID)
                .getResultList();

        assertThat(self).hasSize(1);
        assertThat(self.get(0)[0]).isEqualTo("QA-SA-SELF");
        assertThat(self.get(0)[1]).isEqualTo("本厂只有我一个人能审批");
        Object[] other = entityManager.createQuery(
                        "SELECT p.selfApproved, p.selfApprovalReason FROM ProductionPlan p WHERE p.id = :id",
                        Object[].class)
                .setParameter("id", "QA-SA-OTHER")
                .getSingleResult();
        assertThat(other[0]).isEqualTo(false);
        assertThat(other[1]).isNull();
    }

    @Test
    @DisplayName("logApproval 带本人审批: 审计日志两列落库; 旧 7 参版本写 false/null")
    void auditLogSelfApprovalColumnsAreWrittenByTheService() {
        DecisionAuditServiceImpl service = new DecisionAuditServiceImpl(auditLogs, new com.fasterxml.jackson.databind.ObjectMapper());

        DecisionAuditLog self = service.logApproval(FID, "ProductionPlan", "QA-SA-AUDIT-1",
                DecisionAuditLog.ApprovalStatus.APPROVED, "【本人审批】只有我", UID, "QA",
                true, "  只有我  ");
        DecisionAuditLog legacy = service.logApproval(FID, "ProductionPlan", "QA-SA-AUDIT-2",
                DecisionAuditLog.ApprovalStatus.APPROVED, "同意", UID, "QA");
        auditLogs.flush();
        entityManager.clear();

        DecisionAuditLog selfRead = auditLogs.findById(self.getId()).orElseThrow();
        assertThat(selfRead.isSelfApproved()).isTrue();
        assertThat(selfRead.getSelfApprovalReason()).isEqualTo("只有我");
        DecisionAuditLog legacyRead = auditLogs.findById(legacy.getId()).orElseThrow();
        assertThat(legacyRead.isSelfApproved()).isFalse();
        assertThat(legacyRead.getSelfApprovalReason()).isNull();

        Long selfCount = entityManager.createQuery(
                        "SELECT COUNT(d) FROM DecisionAuditLog d WHERE d.factoryId = :f "
                                + "AND d.entityType = 'ProductionPlan' AND d.selfApproved = true", Long.class)
                .setParameter("f", FID)
                .getSingleResult();
        assertThat(selfCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("迁移 V20261114_01 加的列 = ProductionPlan 实体的 @Column 名")
    void migrationColumnsMatchEntityColumns() throws IOException, NoSuchFieldException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261114_01__production_plan_self_approval_trace.sql"),
                StandardCharsets.UTF_8);
        for (String field : new String[]{"selfApproved", "selfApprovalReason"}) {
            String column = ProductionPlan.class.getDeclaredField(field)
                    .getAnnotation(jakarta.persistence.Column.class).name();
            assertThat(sql).as(field).contains("ADD COLUMN IF NOT EXISTS " + column + " ");
        }
        assertThat(sql).contains("ALTER TABLE production_plans");
    }
}
