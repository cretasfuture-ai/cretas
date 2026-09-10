package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.impl.QualityInspectionServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.*;

/** Actual service proxy + PostgreSQL commit/readback, with JDBC repository adapters.
 * No test transaction or Mockito persistence. This is NOT a Hibernate/schema gate. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class QualityInspectionAtomicWritebackIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate reader;
    private QualityInspectionService service;
    private Store store;
    private String schema;
    private JdbcTemplate admin;

    @BeforeEach
    void start() {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "b04_qc_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DataSource writer = new DriverManagerDataSource(isolatedUrl, user, password);
        // Separate DataSource identity ensures readback never joins the service transaction.
        reader = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, user, password));
        reader.execute("CREATE TABLE inspections(id text primary key, factory_id text, batch_id bigint, result text)");
        reader.execute("CREATE TABLE batches(id bigint primary key, factory_id text, plan_id text, quality_status text)");
        reader.execute("CREATE TABLE goods(id bigint primary key, factory_id text, plan_id text, status text)");
        reader.execute("CREATE TABLE alerts(id bigserial primary key)");
        reader.update("INSERT INTO batches VALUES (1, 'B04', 'plan', 'PENDING_INSPECTION')");
        reader.update("INSERT INTO goods VALUES (1,'B04','plan','AVAILABLE'),(2,'B04','plan','AVAILABLE'),(3,'B04','plan','DEPLETED')");
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> writer);
        context.register(Config.class);
        context.refresh();
        store = context.getBean(Store.class);
        service = context.getBean(QualityInspectionService.class);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    @AfterEach
    void stop() {
        if (reader != null) System.out.println("B04_READBACK schema=" + schema
                + " inspections=" + reader.queryForList("SELECT * FROM inspections")
                + " batches=" + reader.queryForList("SELECT * FROM batches")
                + " goods=" + reader.queryForList("SELECT * FROM goods ORDER BY id"));
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private QualityInspection inspection(Long batchId, String result) {
        QualityInspection qi = new QualityInspection();
        qi.setId("inspection");
        qi.setProductionBatchId(batchId);
        qi.setResult(result);
        qi.setPassRate(new BigDecimal("50"));
        return qi;
    }

    private void unchanged() {
        assertThat(reader.queryForObject("SELECT count(*) FROM inspections", Integer.class)).isZero();
        assertThat(reader.queryForObject("SELECT quality_status FROM batches WHERE id=1", String.class))
                .isEqualTo("PENDING_INSPECTION");
        assertThat(reader.queryForObject("SELECT count(*) FROM goods WHERE status='AVAILABLE'", Integer.class)).isEqualTo(2);
    }

    @Test void committedPassWritesBothRecords() {
        service.createInspection("B04", inspection(1L, "PASS"));
        assertThat(reader.queryForObject("SELECT result FROM inspections", String.class)).isEqualTo("PASS");
        assertThat(reader.queryForObject("SELECT quality_status FROM batches", String.class)).isEqualTo("PASSED");
    }

    @Test void committedFailQuarantinesOnlyAvailableGoods() {
        service.createInspection("B04", inspection(1L, "FAIL"));
        assertThat(reader.queryForObject("SELECT result FROM inspections", String.class)).isEqualTo("FAIL");
        assertThat(reader.queryForObject("SELECT quality_status FROM batches", String.class)).isEqualTo("FAILED");
        assertThat(reader.queryForList("SELECT status FROM goods ORDER BY id", String.class))
                .containsExactly("DEFECTIVE", "DEFECTIVE", "DEPLETED");
    }

    @Test void legitimateInspectionWithoutBatchCommits() {
        service.createInspection("B04", inspection(null, "PASS"));
        assertThat(reader.queryForObject("SELECT count(*) FROM inspections WHERE batch_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(reader.queryForObject("SELECT quality_status FROM batches", String.class)).isEqualTo("PENDING_INSPECTION");
    }

    @Test void missingBatchRollsBackInspection() {
        assertThatThrownBy(() -> service.createInspection("B04", inspection(999L, "PASS"))).isInstanceOf(RuntimeException.class);
        unchanged();
    }

    @Test void otherTenantBatchRollsBackInspection() {
        assertThatThrownBy(() -> service.createInspection("OTHER", inspection(1L, "PASS"))).isInstanceOf(RuntimeException.class);
        unchanged();
    }

    @Test void ordinaryFailureAfterBatchUpdateRollsBackBoth() {
        store.failAfterBatchUpdate = true;
        assertThatThrownBy(() -> service.createInspection("B04", inspection(1L, "PASS"))).hasMessage("B04 ordinary writeback failure");
        unchanged();
    }

    @Test void realDatabaseConstraintFailureRollsBackBoth() {
        reader.execute("ALTER TABLE batches ADD CONSTRAINT block_pass CHECK (quality_status <> 'PASSED')");
        assertThatThrownBy(() -> service.createInspection("B04", inspection(1L, "PASS")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        unchanged();
    }

    @Test void quarantineFailureAfterFirstGoodsUpdateRollsBackAll() {
        store.failSecondGoodsUpdate = true;
        assertThatThrownBy(() -> service.createInspection("B04", inspection(1L, "FAIL"))).hasMessage("B04 quarantine failure");
        unchanged();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean Store store(DataSource dataSource) { return new Store(new JdbcTemplate(dataSource)); }
        @Bean QualityInspectionRepository inspections(Store store) { return store.inspections(); }
        @Bean ProductionBatchRepository batches(Store store) { return store.batches(); }
        @Bean FinishedGoodsBatchRepository goods(Store store) { return store.goods(); }
        @Bean ProductionAlertRepository alerts(Store store) { return adapter(ProductionAlertRepository.class, (method, args) -> {
            if (!method.equals("save")) throw new UnsupportedOperationException(method);
            store.jdbc.update("INSERT INTO alerts DEFAULT VALUES"); return args[0];
        }); }
        @Bean QualityInspectionService service(QualityInspectionRepository inspections, ProductionAlertRepository alerts,
                                               ApplicationEventPublisher events) {
            return new QualityInspectionServiceImpl(inspections, alerts, events);
        }
    }

    private static <T> T adapter(Class<T> type, BiFunction<String, Object[], Object> calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> type.getSimpleName() + "JdbcAdapter";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return calls.apply(method.getName(), args);
        }));
    }

    static class Store {
        final JdbcTemplate jdbc;
        boolean failAfterBatchUpdate;
        boolean failSecondGoodsUpdate;
        Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        QualityInspectionRepository inspections() { return adapter(QualityInspectionRepository.class, (method, args) -> {
            if (!method.equals("save")) throw new UnsupportedOperationException(method);
            QualityInspection qi = (QualityInspection) args[0];
            jdbc.update("INSERT INTO inspections VALUES (?,?,?,?)", qi.getId(), qi.getFactoryId(), qi.getProductionBatchId(), qi.getResult());
            return qi;
        }); }
        ProductionBatchRepository batches() { return adapter(ProductionBatchRepository.class, (method, args) -> {
            if (method.equals("findById")) return jdbc.query("SELECT * FROM batches WHERE id=?", (rs, row) -> {
                ProductionBatch batch = new ProductionBatch();
                batch.setId(rs.getLong("id")); batch.setFactoryId(rs.getString("factory_id"));
                batch.setProductionPlanId(rs.getString("plan_id")); batch.setQualityStatus(QualityStatus.valueOf(rs.getString("quality_status")));
                return batch;
            }, args[0]).stream().findFirst();
            if (!method.equals("save")) throw new UnsupportedOperationException(method);
            ProductionBatch batch = (ProductionBatch) args[0];
            jdbc.update("UPDATE batches SET quality_status=? WHERE id=?", batch.getQualityStatus().name(), batch.getId());
            if (failAfterBatchUpdate) throw new IllegalStateException("B04 ordinary writeback failure");
            return batch;
        }); }
        FinishedGoodsBatchRepository goods() { return adapter(FinishedGoodsBatchRepository.class, (method, args) -> {
            if (method.equals("findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull")) return jdbc.query(
                    "SELECT * FROM goods WHERE factory_id=? AND plan_id=? ORDER BY id", (rs, row) -> {
                        FinishedGoodsBatch goods = new FinishedGoodsBatch(); goods.setId(rs.getString("id"));
                        goods.setFactoryId(rs.getString("factory_id")); goods.setProductionPlanId(rs.getString("plan_id"));
                        goods.setStatus(rs.getString("status")); return goods;
                    }, args);
            if (!method.equals("save")) throw new UnsupportedOperationException(method);
            FinishedGoodsBatch goods = (FinishedGoodsBatch) args[0];
            if (failSecondGoodsUpdate && goods.getId().equals("2")) throw new IllegalStateException("B04 quarantine failure");
            jdbc.update("UPDATE goods SET status=? WHERE id=?", goods.getStatus(), Long.valueOf(goods.getId())); return goods;
        }); }
    }
}
