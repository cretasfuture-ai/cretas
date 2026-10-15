package com.cretas.aims.integration;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonServiceCircuitBreaker;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.controller.IndicatorController;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.impl.IndicatorQueryServiceImpl;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Loopback Java HTTP -> production Python client/router/calculation -> private PG.
 * Java production authentication is excluded; Python internal authentication is real. */
@SpringBootTest(classes = IndicatorPythonBridgePostgresAcceptanceTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1", "server.port=10059", "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false"})
@EnabledIfEnvironmentVariable(named = "CRETAS_INDICATOR_BRIDGE_PG_VERIFY", matches = "true")
class IndicatorPythonBridgePostgresAcceptanceTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan(basePackageClasses = Indicator.class)
    @EnableJpaRepositories(basePackageClasses = IndicatorRepository.class)
    @Import({IndicatorController.class, IndicatorQueryServiceImpl.class, IndicatorComputationStrategyRegistry.class})
    static class Runtime {
        @Bean PythonSmartBIClient pythonClient(ObjectMapper mapper) {
            var config = new PythonSmartBIConfig();
            config.setEnabled(true);
            config.setUrl("http://127.0.0.1:18085");
            return new PythonSmartBIClient(config, new OkHttpClient(), mapper,
                    new PythonServiceCircuitBreaker(), "local-indicator-acceptance-only-secret");
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        if (!url.contains("/cretas_workflow_verify_indicator_bridge_20261015")) {
            throw new IllegalArgumentException("this fixture requires its own indicator bridge database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;

    @Test void exactFactoryAndPeriodReachExistingGoldCalculationAndVersionReadback() throws Exception {
        // These are private fixture tables. No production migrations or data are touched.
        for (String table : List.of("product_types", "dim_product_excluded", "dim_restaurant_cost_product",
                "fact_pos_transaction", "dim_product", "fact_pos_item", "agg_restaurant_product_cost")) {
            jdbc.execute("drop table if exists " + table);
        }
        jdbc.execute("create table product_types (id text, factory_id text, name text)");
        jdbc.execute("create table dim_product_excluded (factory_id text, pos_name text)");
        jdbc.execute("create table dim_restaurant_cost_product (factory_id text, normalized_name text, product_source_pk text, is_active boolean)");
        jdbc.execute("create table fact_pos_transaction (id bigint, factory_id text, date date)");
        jdbc.execute("create table dim_product (product_id text, factory_id text, name text, normalized_name text)");
        jdbc.execute("create table fact_pos_item (transaction_id bigint, factory_id text, product_id text, qty numeric, amount numeric)");
        jdbc.execute("create table agg_restaurant_product_cost (factory_id text, product_source_pk text, food_cost numeric)");
        jdbc.update("insert into product_types values ('dish-a','IND-BRIDGE-A','dish'),('dish-b','IND-BRIDGE-B','dish')");
        jdbc.update("insert into dim_product values ('pos-a','IND-BRIDGE-A','dish','dish'),('pos-b','IND-BRIDGE-B','dish','dish'),('unknown','IND-BRIDGE-A','unpriced','unpriced')");
        jdbc.update("insert into agg_restaurant_product_cost values ('IND-BRIDGE-A','dish-a',10),('IND-BRIDGE-B','dish-b',1)");
        jdbc.update("insert into fact_pos_transaction values (1,'IND-BRIDGE-A','2026-01-01'),(2,'IND-BRIDGE-A','2026-01-02'),(3,'IND-BRIDGE-B','2026-01-01'),(4,'IND-BRIDGE-A','2026-01-03')");
        jdbc.update("insert into fact_pos_item values (1,'IND-BRIDGE-A','pos-a',10,100),(2,'IND-BRIDGE-A','pos-a',10,200),(3,'IND-BRIDGE-B','pos-b',10,10000),(4,'IND-BRIDGE-A','unknown',10,1000)");
        for (String factory : List.of("IND-BRIDGE-A", "IND-BRIDGE-B")) {
            jdbc.update("insert into indicators(id,factory_id,code,name,category,unit,is_active,compute_strategy,cache_ttl_seconds,created_at,updated_at) values (?,?, 'RESTAURANT_DISH_MARGIN','fixture','RESTAURANT','%',true,'CACHED',3600,now(),now())", factory, factory);
            jdbc.update("insert into indicator_computations(id,indicator_id,compute_type,compute_source,is_active,priority,created_at,updated_at) values (?,?,'PYTHON_ENDPOINT','/api/smartbi/restaurant-ops/gross-margin',true,1,now(),now())", factory, factory);
        }
        JsonNode zero = query("IND-BRIDGE-A", "2026-01-01");
        assertThat(zero.at("/data/status").asText()).isEqualTo("AVAILABLE");
        assertThat(zero.at("/data/value").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.at("/data/source").asText()).isEqualTo("PYTHON_ENDPOINT:RESTAURANT_DISH_MARGIN");
        assertThat(zero.at("/data/computedAt").isNull()).isFalse();
        assertThat(query("IND-BRIDGE-A", "2026-01-01").at("/data/cacheHit").asBoolean()).isTrue();
        assertThat(versionCount("IND-BRIDGE-A")).isEqualTo(1);
        JsonNode fifty = query("IND-BRIDGE-A", "2026-01-02");
        assertThat(fifty.at("/data/value").decimalValue()).isEqualByComparingTo("50");
        assertThat(fifty.at("/data/cacheHit").asBoolean()).isFalse();
        assertThat(versionCount("IND-BRIDGE-A")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select value from indicator_versions where factory_id=? and period_start='2026-01-02' and period_end='2026-01-02'", BigDecimal.class, "IND-BRIDGE-A")).isEqualByComparingTo("50");
        assertThat(versionCount("IND-BRIDGE-B")).isZero();
        JsonNode unpriced = query("IND-BRIDGE-A", "2026-01-03");
        assertThat(unpriced.at("/data/status").asText()).isEqualTo("NO_DATA");
        assertThat(unpriced.at("/data/value").isNull()).isTrue();
        assertThat(unpriced.at("/data/computedAt").isNull()).isTrue();
        JsonNode empty = query("IND-BRIDGE-A", "2026-01-04");
        assertThat(empty.at("/data/status").asText()).isEqualTo("NO_DATA");
        assertThat(versionCount("IND-BRIDGE-A")).isEqualTo(2);
        JsonNode other = query("IND-BRIDGE-B", "2026-01-01");
        assertThat(other.at("/data/value").decimalValue()).isEqualByComparingTo("99.9");
        assertThat(jdbc.queryForObject("select count(*) from fact_pos_item", Integer.class)).isEqualTo(4);
        String evidence = System.getenv("CRETAS_INDICATOR_BRIDGE_EVIDENCE_DIR");
        if (evidence != null) {
            Path root = Path.of(evidence); Files.createDirectories(root);
            Files.writeString(root.resolve("java-python-pg-zero.json"), zero.toPrettyString());
            Files.writeString(root.resolve("java-python-pg-period.json"), fifty.toPrettyString());
            Files.writeString(root.resolve("java-python-pg-no-data.json"), unpriced.toPrettyString());
            Files.writeString(root.resolve("java-python-pg-foreign.json"), other.toPrettyString());
        }
    }

    private JsonNode query(String factory, String day) throws Exception {
        return mapper.readTree(http.getForObject("/api/mobile/" + factory
                + "/indicators/RESTAURANT_DISH_MARGIN/value?periodStart=" + day + "&periodEnd=" + day, String.class));
    }

    private int versionCount(String factory) {
        return jdbc.queryForObject("select count(*) from indicator_versions where factory_id=?", Integer.class, factory);
    }
}
