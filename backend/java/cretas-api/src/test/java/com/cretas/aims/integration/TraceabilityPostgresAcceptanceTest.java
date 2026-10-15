package com.cretas.aims.integration;

import com.cretas.aims.controller.TraceabilityController;
import com.cretas.aims.controller.IndicatorController;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.indicator.impl.IndicatorQueryServiceImpl;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.cretas.aims.service.indicator.strategy.impl.FactoryQualityRejectRateStrategy;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.service.impl.TraceabilityServiceImpl;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP -> existing Controller/Service/JPA -> disposable PostgreSQL.
 * Auth is deliberately excluded from this loopback fixture; no production identity is used. */
@SpringBootTest(classes = TraceabilityPostgresAcceptanceTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1", "server.port=10058",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_TRACE_PG_VERIFY", matches = "true")
class TraceabilityPostgresAcceptanceTest {
    static final String FACTORY = "TRACE-ACCEPTANCE";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({TraceabilityController.class, TraceabilityServiceImpl.class, LineageGraphAssembler.class,
            IndicatorController.class, IndicatorQueryServiceImpl.class, IndicatorComputationStrategyRegistry.class,
            FactoryQualityRejectRateStrategy.class})
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired FactoryRepository factories;
    @Autowired ProductTypeRepository products;
    @Autowired ProductionBatchRepository productions;
    @Autowired MaterialBatchRepository materials;
    @Autowired BatchLineageClosureRepository closure;
    @Autowired TraceabilityServiceImpl service;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockBean PythonSmartBIClient pythonClient; // This slice must use the real business strategy, never an external service.

    @Test
    void persistedTraceAndNegativeControlsReachTheRealHttpEntry() throws Exception {
        // Explicit fixtures avoid legacy String->jsonb INSERT mappings outside
        // this query-only acceptance slice. All writes target this private DB.
        jdbc.update("insert into factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) values (?,?,'FACTORY',0,true,false,20,now(),now())", FACTORY, "隔离追溯验收工厂");
        jdbc.update("insert into users (id,factory_id,username,password_hash,is_active,created_at,updated_at) values (10058,?,'trace-fixture','UNUSABLE-LOCAL-FIXTURE',true,now(),now())", FACTORY);
        jdbc.update("insert into product_types (id,factory_id,code,name,unit,is_active,created_by,created_at,updated_at) values ('trace-product',?,'TRACE-SKU','验收产品','kg',true,10058,now(),now())", FACTORY);
        jdbc.update("insert into production_batches (id,factory_id,batch_number,product_type_id,product_name,quantity,unit,status,batch_type,created_at,updated_at) values (10058001,?,'TRACE-PG-001','trace-product','验收产品',10,'kg','PLANNED','REGULAR',now(),now())", FACTORY);
        jdbc.update("insert into material_batches (id,factory_id,batch_number,inbound_date,warehouse_id,version,receipt_quantity,quantity_unit,used_quantity,reserved_quantity,status,created_by,created_at,updated_at) values ('trace-mb',?,'TRACE-RAW-001',current_date,'TRACE-WH',0,10,'kg',0,0,'AVAILABLE',10058,now(),now())", FACTORY);
        ProductionBatch batch = productions.findByIdAndFactoryId(10058001L, FACTORY).orElseThrow();
        MaterialBatch material = materials.findByIdAndFactoryId("trace-mb", FACTORY).orElseThrow();
        BatchLineageClosure link = new BatchLineageClosure();
        link.setFactoryId(FACTORY);
        link.setAncestorType("MATERIAL_BATCH");
        link.setAncestorId(material.getId());
        link.setDescendantType("PRODUCTION_BATCH");
        link.setDescendantId(batch.getId().toString());
        link.setDepth(1);
        closure.saveAndFlush(link);
        String endpoint = "/api/mobile/" + FACTORY + "/traceability/full/TRACE-PG-001";
        var response = json.readTree(http.getForObject(endpoint, String.class));
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.at("/data/lineage/nodeCount").asInt()).isEqualTo(1);
        assertThat(response.at("/data/lineage/ancestors/0/nodes/0/batchNumber").asText()).isEqualTo("TRACE-RAW-001");
        assertThat(response.at("/data/traceCode").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from batch_lineage_closure where factory_id = ?", Integer.class, FACTORY)).isEqualTo(1);
        var foreign = json.readTree(http.getForObject(endpoint.replace(FACTORY, "OTHER-FACTORY"), String.class));
        assertThat(foreign.path("success").asBoolean()).isFalse();
        assertThat(foreign.path("code").asInt()).isEqualTo(404);
        assertThatThrownBy(() -> service.getImpact(FACTORY, "MATERIAL_BATCH", "missing", 5))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class).hasMessageContaining("未找到");
        assertThat(service.getImpact(FACTORY, "PRODUCTION_BATCH", batch.getId().toString(), 5).getShipmentCount()).isZero();

        jdbc.update("insert into indicators (id,factory_id,code,name,category,unit,is_active,compute_strategy,created_at,updated_at) values ('quality-kpi',?,'FACTORY_QUALITY_REJECT_RATE','质检不合格率','QUALITY','%',true,'REALTIME',now(),now())", FACTORY);
        jdbc.update("insert into indicator_computations (id,indicator_id,compute_type,compute_source,is_active,priority,created_at,updated_at) values ('quality-compute','quality-kpi','JPA_AGGREGATE','FACTORY_QUALITY_REJECT_RATE',true,1,now(),now())");
        String valueEndpoint = "/api/mobile/" + FACTORY + "/indicators/FACTORY_QUALITY_REJECT_RATE/value";
        var noSamples = json.readTree(http.getForObject(valueEndpoint, String.class));
        assertThat(noSamples.at("/data/value").isNull()).isTrue();
        assertThat(noSamples.at("/data/status").asText()).isEqualTo("NO_DATA");
        jdbc.update("insert into quality_inspections (id,factory_id,production_batch_id,inspector_id,inspection_date,sample_size,pass_count,fail_count,created_at,updated_at) values ('trace-quality',?,10058001,10058,current_date,10,10,0,now(),now())", FACTORY);
        var zero = json.readTree(http.getForObject(valueEndpoint, String.class));
        assertThat(zero.at("/data/value").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.at("/data/status").asText()).isEqualTo("AVAILABLE");
        assertThat(zero.at("/data/source").asText()).isEqualTo("REAL_BUSINESS:FACTORY_QUALITY_REJECT_RATE");
        assertThat(jdbc.queryForObject("select count(*) from indicator_versions where factory_id = ?", Integer.class, FACTORY)).isEqualTo(1);
        jdbc.update("insert into indicators (id,factory_id,code,name,category,is_active,compute_strategy,created_at,updated_at) values ('pending-kpi',?,'TRUST_PENDING','待计算指标','QUALITY',true,'PRECOMPUTED',now(),now())", FACTORY);
        var pending = json.readTree(http.getForObject("/api/mobile/" + FACTORY + "/indicators/TRUST_PENDING/value", String.class));
        assertThat(pending.at("/data/value").isNull()).isTrue();
        assertThat(pending.at("/data/computedAt").isNull()).isTrue();
        assertThat(pending.at("/data/status").asText()).isEqualTo("NOT_COMPUTED");
        org.mockito.Mockito.verifyNoInteractions(pythonClient);

        String evidence = System.getenv("CRETAS_TRACE_EVIDENCE_DIR");
        if (evidence != null) {
            Path directory = Path.of(evidence);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("postgres-http-readback.json"), response.toPrettyString());
            Files.writeString(directory.resolve("postgres-indicator-readback.json"), zero.toPrettyString());
            Files.writeString(directory.resolve("postgres-http-ready.txt"), "loopback:10058; factory=" + FACTORY);
            // Optional bounded window for the real Web component to query this fixture.
            if ("true".equals(System.getenv("CRETAS_TRACE_HOLD_FOR_UI"))) {
                long deadline = System.currentTimeMillis() + 180_000;
                while (!Files.exists(directory.resolve("postgres-ui-done.txt")) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500);
                }
                assertThat(Files.exists(directory.resolve("postgres-ui-done.txt"))).as("browser acceptance completed before deadline").isTrue();
            }
        }
    }
}
