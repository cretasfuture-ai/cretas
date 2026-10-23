package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 真 PostgreSQL 事务回读：验证 WIP 数量更正的回滚和审批前锁定后竞争消费重检。
 * 只使用任务专用 disposable schema，不是 Hibernate schema gate。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WipOutputCorrectionPostgresContractTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate reader;
    private JdbcTemplate writerJdbc;
    private JdbcTemplate admin;
    private JdbcWipStore store;
    private WipInventoryService service;
    private String schema;

    @BeforeEach
    void start() {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "s305_wip_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DataSource writer = new DriverManagerDataSource(isolatedUrl, user, password);
        writerJdbc = new JdbcTemplate(writer);
        reader = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, user, password));
        createTables(writerJdbc);
        store = new JdbcWipStore(writerJdbc);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> writer);
        context.registerBean(JdbcWipStore.class, () -> store);
        context.register(PgConfig.class);
        context.refresh();
        service = context.getBean(WipInventoryService.class);
    }

    @AfterEach
    void stop() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void ledgerFailureRollsBackWipAndCorrectionRow() {
        insertFixture();
        store.failLedgerSave = true;

        assertThatThrownBy(() -> service.correctApprovedOutputQuantity(
                "F006", report(), task(), new BigDecimal("0.81"), new BigDecimal("0.82"), "PG-ROLLBACK", 7L))
                .isInstanceOf(RuntimeException.class);

        assertThat(reader.queryForObject("SELECT produced_quantity FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("0.81");
        assertThat(reader.queryForObject("SELECT count(*) FROM semi_finished_inventory_transactions "
                + "WHERE source_type='PRODUCTION_REPORT_CORRECTION'", Integer.class)).isZero();
    }

    @Test
    void competingConsumptionAfterApprovalPreflightIsRejectedOnSecondLock() {
        insertFixture();
        service.validateApprovedOutputQuantityCorrection(
                "F006", report(), task(), new BigDecimal("0.81"), new BigDecimal("0.80"), "PG-RACE");
        reader.update("UPDATE semi_finished_inventory SET consumed_quantity=0.80, available_quantity=0.01 "
                + "WHERE id=383 AND factory_id='F006'");

        assertThatThrownBy(() -> service.correctApprovedOutputQuantity(
                "F006", report(), task(), new BigDecimal("0.81"), new BigDecimal("0.80"), "PG-RACE", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下游消费");
        assertThat(reader.queryForObject("SELECT produced_quantity FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("0.81");
        assertThat(reader.queryForObject("SELECT count(*) FROM semi_finished_inventory_transactions "
                + "WHERE source_type='PRODUCTION_REPORT_CORRECTION'", Integer.class)).isZero();
    }

    @Test
    void productionPrecisionRoundTripKeepsCorrectionCostAfterDownstreamReverseReplay() throws Exception {
        insertFixture();

        service.correctApprovedOutputQuantity(
                "F006", report(), task(), new BigDecimal("0.81"), new BigDecimal("0.82"), "PG-ROUNDTRIP", 7L);

        assertThat(reader.queryForObject("SELECT produced_quantity FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("0.82");
        assertThat(reader.queryForObject("SELECT accumulated_cost FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("10.13");
        assertThat(reader.queryForObject("SELECT unit_cost FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("12.3537");
        assertThat(reader.queryForObject("SELECT balance_cost_after FROM semi_finished_inventory_transactions "
                + "WHERE source_type='PRODUCTION_REPORT_CORRECTION' AND source_ref LIKE '%PG-ROUNDTRIP%'",
                BigDecimal.class)).isEqualByComparingTo("12.3537");

        insertDownstreamRoundTripRows();
        ReportReversalServiceImpl reversal = new ReportReversalServiceImpl(
                mock(ReportReversalLogRepository.class), mock(com.cretas.aims.repository.ProductionBatchRepository.class),
                mock(ProductionReportRepository.class), store.wipRepository(), store.txnRepository(),
                mock(FinishedGoodsBatchRepository.class), mock(UserRepository.class),
                mock(WorkProcessTaskRepository.class), mock(NotificationService.class));
        Method replay = ReportReversalServiceImpl.class.getDeclaredMethod(
                "replayMovingAverage", Long.class, String.class, LocalDateTime.class);
        replay.setAccessible(true);
        replay.invoke(reversal, 383L, "F006", LocalDateTime.now());

        assertThat(reader.queryForObject("SELECT produced_quantity FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("0.82");
        assertThat(reader.queryForObject("SELECT available_quantity FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("0.82");
        assertThat(reader.queryForObject("SELECT accumulated_cost FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("10.13");
        assertThat(reader.queryForObject("SELECT unit_cost FROM semi_finished_inventory WHERE id=383",
                BigDecimal.class)).isEqualByComparingTo("12.3537");
    }

    private ProductionReport report() {
        return ProductionReport.builder().id(23989L).factoryId("F006").outputQuantity(new BigDecimal("0.81"))
                .outputUnit("kg").reportKind("OUTPUT").outputKind(null).semiOutputs(null).build();
    }

    private WorkProcessTask task() {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1989L);
        task.setFactoryId("F006");
        task.setProductTypeId("SKU-1");
        task.setProductionBatchId(2398L);
        task.setProcessOrder(1);
        task.setPlannedUnit("kg");
        return task;
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE semi_finished_inventory ("
                + "id bigint primary key, factory_id text not null, intermediate_batch_no text not null, "
                + "source_work_process_task_id bigint, produced_quantity numeric(12,2), consumed_quantity numeric(12,2), "
                + "available_quantity numeric(12,2), adjustment_quantity numeric(12,2), unit text, status text, "
                + "unit_cost numeric(14,4), accumulated_cost numeric(14,2), deleted_at timestamp, version bigint)");
        jdbc.execute("CREATE TABLE semi_finished_inventory_transactions ("
                + "id bigserial primary key, factory_id text not null, semi_finished_id bigint not null, "
                + "txn_type text not null, source_type text not null, source_ref text, quantity numeric(12,6) not null, "
                + "unit_cost_at_txn numeric(14,4), balance_after numeric(12,6), balance_cost_after numeric(14,4), report_id bigint, "
                + "operator_id bigint, created_at timestamp not null)");
    }

    private void insertFixture() {
        reader.update("INSERT INTO semi_finished_inventory VALUES "
                + "(383,'F006','SKU-1-B2398-S1-1989',1989,0.81,0,0.81,0,'kg','AVAILABLE',12.50,10.125,NULL,0)");
        reader.update("INSERT INTO semi_finished_inventory_transactions "
                + "(factory_id,semi_finished_id,txn_type,source_type,source_ref,quantity,unit_cost_at_txn,"
                + "report_id,created_at) "
                + "VALUES ('F006',383,'IN','PRODUCTION_OUTPUT','SKU-1-B2398-S1-1989',0.81,12.50,23989,now())");
    }

    private void insertDownstreamRoundTripRows() {
        Timestamp base = reader.queryForObject("SELECT max(created_at) FROM "
                + "semi_finished_inventory_transactions WHERE semi_finished_id=383", Timestamp.class);
        reader.update("INSERT INTO semi_finished_inventory_transactions "
                        + "(factory_id,semi_finished_id,txn_type,source_type,source_ref,quantity,"
                        + "unit_cost_at_txn,balance_after,balance_cost_after,created_at) VALUES "
                        + "('F006',383,'OUT','SECONDARY_CONSUME','PG-ROUNDTRIP-OUT',?,?,?,?,?)",
                new BigDecimal("-0.20"), new BigDecimal("12.3537"), new BigDecimal("0.62"),
                new BigDecimal("12.3537"), new Timestamp(base.getTime() + 1000));
        reader.update("INSERT INTO semi_finished_inventory_transactions "
                        + "(factory_id,semi_finished_id,txn_type,source_type,source_ref,quantity,"
                        + "unit_cost_at_txn,balance_after,balance_cost_after,created_at) VALUES "
                        + "('F006',383,'REVERSE','REVERSAL','PG-ROUNDTRIP-REVERSE',?,?,?,?,?)",
                new BigDecimal("0.20"), new BigDecimal("12.3537"), new BigDecimal("0.82"),
                new BigDecimal("12.3537"), new Timestamp(base.getTime() + 2000));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    static class PgConfig {
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SemiFinishedInventoryRepository wipRepository(JdbcWipStore store) { return store.wipRepository(); }
        @Bean SemiFinishedInventoryTransactionRepository txnRepository(JdbcWipStore store) { return store.txnRepository(); }
        @Bean ProductionReportRepository reportRepository() { return mock(ProductionReportRepository.class); }
        @Bean BatchLineageEdgeRepository lineageRepository() { return mock(BatchLineageEdgeRepository.class); }
        @Bean WorkProcessTaskRepository taskRepository() { return mock(WorkProcessTaskRepository.class); }
        @Bean WorkProcessRepository workProcessRepository() { return mock(WorkProcessRepository.class); }
        @Bean ProductTypeRepository productTypeRepository() { return mock(ProductTypeRepository.class); }
        @Bean ProductProcessWorkflowRepository productProcessWorkflowRepository() {
            return mock(ProductProcessWorkflowRepository.class);
        }
        @Bean ProductionWorkflowInstanceRepository workflowInstanceRepository() {
            return mock(ProductionWorkflowInstanceRepository.class);
        }
        @Bean WorkflowTaskPortRepository workflowTaskPortRepository() { return mock(WorkflowTaskPortRepository.class); }
        @Bean ProductFamilyResolver productFamilyResolver() { return mock(ProductFamilyResolver.class); }
        @Bean WipInventoryServiceImpl wipInventoryService(
                SemiFinishedInventoryRepository wipRepository,
                SemiFinishedInventoryTransactionRepository txnRepository,
                ProductionReportRepository reportRepository,
                BatchLineageEdgeRepository lineageRepository,
                WorkProcessTaskRepository taskRepository,
                WorkProcessRepository workProcessRepository,
                ProductTypeRepository productTypeRepository,
                ProductionWorkflowInstanceRepository workflowInstanceRepository,
                WorkflowTaskPortRepository workflowTaskPortRepository,
                ProductFamilyResolver productFamilyResolver) {
            return new WipInventoryServiceImpl(wipRepository, txnRepository, reportRepository, lineageRepository,
                    taskRepository, workProcessRepository, productTypeRepository, workflowInstanceRepository,
                    workflowTaskPortRepository, productFamilyResolver, event -> { });
        }
    }

    static final class JdbcWipStore {
        private final JdbcTemplate jdbc;
        private boolean failLedgerSave;

        JdbcWipStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        SemiFinishedInventoryRepository wipRepository() {
            return proxy(SemiFinishedInventoryRepository.class, (method, args) -> {
                if (method.getName().equals("findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull")) {
                    List<SemiFinishedInventory> rows = jdbc.query(
                            "SELECT * FROM semi_finished_inventory WHERE factory_id=? AND intermediate_batch_no=? "
                                    + "AND deleted_at IS NULL FOR UPDATE", (rs, n) -> SemiFinishedInventory.builder()
                                    .id(rs.getLong("id")).factoryId(rs.getString("factory_id"))
                                    .intermediateBatchNo(rs.getString("intermediate_batch_no"))
                                    .sourceWorkProcessTaskId(rs.getLong("source_work_process_task_id"))
                                    .producedQuantity(rs.getBigDecimal("produced_quantity"))
                                    .consumedQuantity(rs.getBigDecimal("consumed_quantity"))
                                    .availableQuantity(rs.getBigDecimal("available_quantity"))
                                    .adjustmentQuantity(rs.getBigDecimal("adjustment_quantity"))
                                    .unit(rs.getString("unit")).status(rs.getString("status"))
                                    .unitCost(rs.getBigDecimal("unit_cost"))
                                    .accumulatedCost(rs.getBigDecimal("accumulated_cost"))
                                    .version(rs.getLong("version")).build(), args[0], args[1]);
                    return rows.stream().findFirst();
                }
                if (method.getName().equals("findByIdForUpdate")) {
                    List<SemiFinishedInventory> rows = jdbc.query(
                            "SELECT * FROM semi_finished_inventory WHERE id=? FOR UPDATE", (rs, n) -> SemiFinishedInventory.builder()
                                    .id(rs.getLong("id")).factoryId(rs.getString("factory_id"))
                                    .intermediateBatchNo(rs.getString("intermediate_batch_no"))
                                    .sourceWorkProcessTaskId(rs.getLong("source_work_process_task_id"))
                                    .producedQuantity(rs.getBigDecimal("produced_quantity"))
                                    .consumedQuantity(rs.getBigDecimal("consumed_quantity"))
                                    .availableQuantity(rs.getBigDecimal("available_quantity"))
                                    .adjustmentQuantity(rs.getBigDecimal("adjustment_quantity"))
                                    .unit(rs.getString("unit")).status(rs.getString("status"))
                                    .unitCost(rs.getBigDecimal("unit_cost"))
                                    .accumulatedCost(rs.getBigDecimal("accumulated_cost"))
                                    .version(rs.getLong("version")).build(), args[0]);
                    return rows.stream().findFirst();
                }
                if (method.getName().equals("save")) {
                    SemiFinishedInventory wip = (SemiFinishedInventory) args[0];
                    jdbc.update("UPDATE semi_finished_inventory SET produced_quantity=?,consumed_quantity=?,"
                                    + "available_quantity=?,adjustment_quantity=?,unit_cost=?,status=?,version=version+1 "
                                    + "WHERE id=?", wip.getProducedQuantity(), wip.getConsumedQuantity(),
                            wip.getAvailableQuantity(), wip.getAdjustmentQuantity(), wip.getUnitCost(),
                            wip.getStatus(), wip.getId());
                    return wip;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        SemiFinishedInventoryTransactionRepository txnRepository() {
            return proxy(SemiFinishedInventoryTransactionRepository.class, (method, args) -> {
                if (method.getName().equals("findByFactoryIdAndReportId")) {
                    return readTransactions("WHERE factory_id=? AND report_id=?", args[0], args[1]);
                }
                if (method.getName().equals("findBySemiFinishedIdOrderByCreatedAtAsc")) {
                    return readTransactions("WHERE semi_finished_id=? ORDER BY created_at ASC", args[0]);
                }
                if (method.getName().equals("save")) {
                    if (failLedgerSave) throw new IllegalStateException("forced ledger failure");
                    SemiFinishedInventoryTransaction txn = (SemiFinishedInventoryTransaction) args[0];
                    jdbc.update("INSERT INTO semi_finished_inventory_transactions "
                                    + "(factory_id,semi_finished_id,txn_type,source_type,source_ref,quantity,"
                                    + "unit_cost_at_txn,balance_after,balance_cost_after,report_id,operator_id,created_at) "
                                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", txn.getFactoryId(), txn.getSemiFinishedId(),
                            txn.getTxnType(), txn.getSourceType(), txn.getSourceRef(), txn.getQuantity(),
                            txn.getUnitCostAtTxn(), txn.getBalanceAfter(), txn.getBalanceCostAfter(), txn.getReportId(),
                            txn.getOperatorId(), Timestamp.valueOf(LocalDateTime.now()));
                    return txn;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        private List<SemiFinishedInventoryTransaction> readTransactions(String suffix, Object... args) {
            return jdbc.query("SELECT * FROM semi_finished_inventory_transactions " + suffix,
                    (rs, n) -> SemiFinishedInventoryTransaction.builder().id(rs.getLong("id"))
                            .factoryId(rs.getString("factory_id")).semiFinishedId(rs.getLong("semi_finished_id"))
                            .txnType(rs.getString("txn_type")).sourceType(rs.getString("source_type"))
                            .sourceRef(rs.getString("source_ref")).quantity(rs.getBigDecimal("quantity"))
                            .unitCostAtTxn(rs.getBigDecimal("unit_cost_at_txn"))
                            .balanceAfter(rs.getBigDecimal("balance_after"))
                            .balanceCostAfter(rs.getBigDecimal("balance_cost_after"))
                            .reportId((Long) rs.getObject("report_id"))
                            .operatorId((Long) rs.getObject("operator_id"))
                            .createdAt(rs.getTimestamp("created_at").toLocalDateTime()).build(), args);
        }

        private static <T> T proxy(Class<T> type, java.util.function.BiFunction<java.lang.reflect.Method, Object[], Object> fn) {
            return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                case "toString" -> type.getSimpleName() + "[jdbc]";
                                default -> null;
                            };
                        }
                        return fn.apply(method, args == null ? new Object[0] : args);
                    }));
        }
    }
}
