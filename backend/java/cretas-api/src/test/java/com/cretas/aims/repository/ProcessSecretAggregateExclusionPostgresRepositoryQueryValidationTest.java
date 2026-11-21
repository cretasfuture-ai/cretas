package com.cretas.aims.repository;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO.IncomparableReason;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.yield.YieldAnalysisService;
import com.cretas.aims.service.yield.impl.YieldAnalysisServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 工艺保密: 两条跨批汇总的原生 SQL 在真实 PostgreSQL 上按计划排除 (独立审查第三轮 B、第四轮 M1)。
 * 半成品那条用 jsonb {@code ->>}, H2 跑不了, 所以只在 CI 的 PG 车道跑。
 * 守的行为: 牵涉无权保密产品的计划整张不进汇总 —— 兄弟产出 (不受保护的 Q-OPEN) 的行也不进; 哨兵 = 不排除。
 */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(YieldAnalysisServiceImpl.class)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProcessSecretAggregateExclusionPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-PS-AGG";
    private static final String SCHEMA = "ps_agg_" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> NONE = List.of("__none__");
    private static final LocalDate FROM = LocalDate.of(1900, 1, 1);
    private static final LocalDate TO = LocalDate.of(2999, 12, 31);

    @Autowired private ProductionReportRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private YieldAnalysisService yieldAnalysis;
    @MockBean private ProcessSecretAggregateExclusions aggregateExclusions;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @BeforeEach
    void createFixture() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_reports, work_process_tasks, work_processes, "
                + "production_batches, process_sheet_rows, processing_stage_records CASCADE");
        // factory_id / product_type_id 排在最后: 上面老用例的 VALUES (id, plan) 仍成立, 只有环节记录用例会填
        jdbcTemplate.execute("CREATE TABLE production_batches (id BIGINT PRIMARY KEY, production_plan_id VARCHAR(191), "
                + "factory_id VARCHAR(50), product_type_id VARCHAR(100))");
        jdbcTemplate.execute("CREATE TABLE processing_stage_records (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "production_batch_id BIGINT NOT NULL, stage_type VARCHAR(30) NOT NULL, stage_name VARCHAR(100), "
                + "stage_order INTEGER, start_time TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT now(), "
                + "input_weight NUMERIC(12,3), output_weight NUMERIC(12,3), deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE work_processes (id VARCHAR(50) PRIMARY KEY, process_name VARCHAR(100), "
                + "unit VARCHAR(20), output_unit VARCHAR(20), deleted_at TIMESTAMP, factory_id VARCHAR(50), "
                + "is_active BOOLEAN DEFAULT FALSE, merged_into_id VARCHAR(50))");
        jdbcTemplate.execute("CREATE TABLE work_process_tasks (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "production_batch_id BIGINT NOT NULL, work_process_id VARCHAR(50) NOT NULL, "
                + "product_type_id VARCHAR(50) NOT NULL, "
                + "process_order INTEGER NOT NULL, deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE production_reports (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "batch_id BIGINT, work_process_task_id BIGINT, report_type VARCHAR(20), report_date DATE, "
                + "input_quantity NUMERIC(12,2), output_quantity NUMERIC(12,2), deleted_at TIMESTAMP, "
                + "input_unit VARCHAR(20), output_unit VARCHAR(20))");
        jdbcTemplate.execute("CREATE TABLE process_sheet_rows (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "plan_id VARCHAR(64) NOT NULL, batch_id BIGINT, batch_number VARCHAR(100), row_payload JSONB NOT NULL, "
                + "row_status VARCHAR(20) NOT NULL, interim_settled_at TIMESTAMP, deleted_at TIMESTAMP)");

        // 两张计划共用一道工序, 都在产不受保护的 Q-OPEN; plan-hidden 还牵涉无权的保密产品 (由服务层算出)
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-MIX', '拌料', 'kg', 'kg', NULL)");
        batchWithReport(1L, "plan-hidden", "100", "80");
        batchWithReport(2L, "plan-open", "100", "90");
        // 报工单没记批次 (batch_id 为空) 时按任务的批次判计划 (独立审查第四轮复核)
        jdbcTemplate.update("INSERT INTO production_reports VALUES (3, ?, NULL, 1, 'YIELD', DATE '2026-10-21', 30, 20, NULL)",
                FACTORY);
        sheetRow(1L, "plan-hidden", "50", "40");
        sheetRow(2L, "plan-open", "50", "45");
    }

    private void batchWithReport(long id, String planId, String input, String output) {
        jdbcTemplate.update("INSERT INTO production_batches VALUES (?, ?)", id, planId);
        jdbcTemplate.update("INSERT INTO work_process_tasks VALUES (?, ?, ?, 'WP-MIX', 'Q-OPEN', 1, NULL)",
                id, FACTORY, id);
        jdbcTemplate.update("INSERT INTO production_reports VALUES (?, ?, ?, ?, 'YIELD', DATE '2026-10-20', ?, ?, NULL)",
                id, FACTORY, id, id, new BigDecimal(input), new BigDecimal(output));
    }

    private void sheetRow(long id, String planId, String input, String output) {
        jdbcTemplate.update("INSERT INTO process_sheet_rows VALUES (?, ?, ?, ?, NULL, CAST(? AS JSONB), 'SAVED', "
                        + "TIMESTAMP '2026-10-20 10:00:00', NULL)", id, FACTORY, planId, id,
                "{\"productTypeId\":\"SEMI-1\",\"finished\":\"false\",\"inputQuantity\":\"" + input
                        + "\",\"outputQuantity\":\"" + output + "\",\"unit\":\"kg\"}");
    }

    @Test
    void byProcessAggregateLeavesOutEveryRowOfAnExcludedPlanIncludingUnprotectedSiblingOutputs() {
        List<Map<String, Object>> all = repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", NONE, NONE);
        assertThat(all).hasSize(1);
        assertThat(new BigDecimal(all.get(0).get("total_input").toString())).isEqualByComparingTo("230");
        assertThat(((Number) all.get(0).get("batch_count")).intValue()).isEqualTo(2);
        // 报工没记单位 → 退回工序标准 kg; 三条报工全部「记了单位」(退回后), 只有一种
        assertThat(all.get(0).get("input_unit_min")).isEqualTo("kg");
        assertThat(((Number) all.get(0).get("input_unit_kinds")).intValue()).isEqualTo(1);
        assertThat(((Number) all.get(0).get("input_unit_known")).intValue()).isEqualTo(3);
        assertThat(((Number) all.get(0).get("report_count")).intValue()).isEqualTo(3);

        List<Map<String, Object>> withoutHiddenPlan = repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", NONE,
                List.of("plan-hidden"));
        assertThat(withoutHiddenPlan).hasSize(1);
        assertThat(new BigDecimal(withoutHiddenPlan.get(0).get("total_input").toString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(withoutHiddenPlan.get(0).get("total_output").toString())).isEqualByComparingTo("90");
        assertThat(((Number) withoutHiddenPlan.get(0).get("batch_count")).intValue()).isEqualTo(1);

        // 按产品排除照旧
        assertThat(repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", List.of("Q-OPEN"), NONE)).isEmpty();
    }

    /**
     * 2026-11-12 网页验收: 工序汇总单位要按报工实际所记 (不是只看工序标准), 'unitless' 占位与空串算「没有单位」,
     * 产出两处都没记时退回投入单位。用另一个工厂号, 不碰上面那组保密排除的读数。
     */
    @Test
    void byProcessAggregateReportsTheUnitsActuallyRecordedPerSide() {
        String f = "IT-UNIT";
        // 定量包装: 工序标准 kg→kg, 但两条报工实际记的是 kg→袋、袋→盒
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-PACK', '定量包装', 'kg', NULL, NULL)");
        // 腌制: 新建工序的占位单位 unitless, 报工也没记
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-PICKLE', '腌制', 'unitless', NULL, NULL)");
        // 清洗: 报工只记投入 kg, 产出没记, 工序也没声明产出单位 → 产出按投入单位
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-WASH', '清洗', '', '', NULL)");
        long id = 100;
        for (Object[] r : new Object[][]{
                {"WP-PACK", 1, "10", "kg", "40", "袋"},
                {"WP-PACK", 1, "20", "袋", "5", "盒"},
                {"WP-PICKLE", 2, "0.2", null, "0.1", "unitless"},
                {"WP-WASH", 3, "100", "kg", "90", " "}}) {
            id++;
            jdbcTemplate.update("INSERT INTO production_batches VALUES (?, 'plan-unit')", id);
            jdbcTemplate.update("INSERT INTO work_process_tasks VALUES (?, ?, ?, ?, 'Q-OPEN', ?, NULL)",
                    id, f, id, r[0], r[1]);
            jdbcTemplate.update("INSERT INTO production_reports VALUES (?, ?, ?, ?, 'YIELD', DATE '2026-11-12', ?, ?, NULL, ?, ?)",
                    id, f, id, id, new BigDecimal((String) r[2]), new BigDecimal((String) r[4]), r[3], r[5]);
        }

        List<Map<String, Object>> rows = repository.aggregateYieldByProcess(f, FROM, TO, "", NONE, NONE);
        assertThat(rows).extracting(m -> m.get("process_name")).containsExactly("定量包装", "腌制", "清洗");

        Map<String, Object> pack = rows.get(0);
        assertThat(((Number) pack.get("input_unit_kinds")).intValue()).isEqualTo(2);
        assertThat(((Number) pack.get("output_unit_kinds")).intValue()).isEqualTo(2);
        assertThat(((Number) pack.get("report_count")).intValue()).isEqualTo(2);

        Map<String, Object> pickle = rows.get(1);
        assertThat(((Number) pickle.get("input_unit_known")).intValue()).isZero();
        assertThat(((Number) pickle.get("output_unit_known")).intValue()).isZero();
        assertThat(pickle.get("input_unit_min")).isNull();

        Map<String, Object> wash = rows.get(2);
        assertThat(wash.get("input_unit_min")).isEqualTo("kg");
        assertThat(wash.get("output_unit_min")).isEqualTo("kg");
        assertThat(((Number) wash.get("output_unit_known")).intValue()).isEqualTo(1);

        when(aggregateExclusions.forCurrentCaller(f, "yield by-process aggregate"))
                .thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        ProcessYieldAggDTO mixed = findProcess(yieldAnalysis.aggregateByProcess(f, FROM, TO, null), "定量包装");
        assertThat(mixed.getIncomparableReason()).isEqualTo(IncomparableReason.MIXED_UNITS);
        assertThat(mixed.isComputable()).isFalse();
        assertThat(mixed.getConversionRate()).isNull();
        assertThat(mixed.getKnownInputQuantity()).isNull();
        assertThat(mixed.getKnownOutputQuantity()).isNull();
        assertThat(mixed.getInputRecordCount()).isEqualTo(2);
        assertThat(mixed.getOutputRecordCount()).isEqualTo(2);
    }

    @Test
    void semiFinishedAggregateLeavesOutRowsOfExcludedPlans() {
        ProductionReportRepository.SemiFinishedYieldAggregate all =
                repository.aggregateSettledSemiFinishedYield(FACTORY, "SEMI-1", NONE);
        assertThat(all.getTotalInputKg()).isEqualByComparingTo("100");
        assertThat(all.getTotalOutputKg()).isEqualByComparingTo("85");
        assertThat(all.getBatchCount()).isEqualTo(2L);

        ProductionReportRepository.SemiFinishedYieldAggregate withoutHiddenPlan =
                repository.aggregateSettledSemiFinishedYield(FACTORY, "SEMI-1", List.of("plan-hidden"));
        assertThat(withoutHiddenPlan.getTotalInputKg()).isEqualByComparingTo("50");
        assertThat(withoutHiddenPlan.getTotalOutputKg()).isEqualByComparingTo("45");
        assertThat(withoutHiddenPlan.getBatchCount()).isEqualTo(1L);
    }


    // ==================== PROTO-STAGE-IO: 加工环节记录兼读 ====================

    private void stageBatch(String factory, long id, String plan, String productType) {
        jdbcTemplate.update("INSERT INTO production_batches VALUES (?, ?, ?, ?)", id, plan, factory, productType);
    }

    private void stageRecord(String factory, long id, long batchId, String name, String type, String input, String output,
                             boolean deleted) {
        jdbcTemplate.update("INSERT INTO processing_stage_records (id, factory_id, production_batch_id, stage_type, "
                        + "stage_name, stage_order, start_time, input_weight, output_weight, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, TIMESTAMP '2026-11-10 09:00:00', ?, ?, "
                        + (deleted ? "now()" : "NULL") + ")",
                id, factory, batchId, type, name, input == null ? null : new BigDecimal(input),
                output == null ? null : new BigDecimal(output));
    }

    private void reportFor(String factory, long id, long batchId, String wpId, String input, String output) {
        jdbcTemplate.update("INSERT INTO work_process_tasks VALUES (?, ?, ?, ?, 'Q-OPEN', 1, NULL)", id, factory, batchId, wpId);
        jdbcTemplate.update("INSERT INTO production_reports VALUES (?, ?, ?, ?, 'YIELD', DATE '2026-11-10', ?, ?, NULL, 'kg', 'kg')",
                id, factory, batchId, id, new BigDecimal(input), new BigDecimal(output));
    }

    /** 样本 1: 只有环节记录 (没有任何报工) —— 过去整页没有这道工序, 现在要有, 并标明条数来自环节记录。 */
    @Test
    void stageRecordsAloneNowShowUpAsTheirOwnProcessRow() {
        String f = "IT-STAGE-ONLY";
        stageBatch(f, 301, "plan-s1", "Q-OPEN");
        stageRecord(f, 1, 301, "切片", "SLICING", "100", "80", false);
        stageRecord(f, 2, 301, null, "WASHING", "50", "48", false);   // 没填环节名 -> 退回 stage_type 码

        List<Map<String, Object>> rows = repository.aggregateYieldByProcess(f, FROM, TO, "", NONE, NONE);
        assertThat(rows).extracting(m -> m.get("process_name")).containsExactlyInAnyOrder("切片", "WASHING");
        Map<String, Object> slice = rows.stream().filter(m -> "切片".equals(m.get("process_name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(slice.get("total_input").toString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(slice.get("total_output").toString())).isEqualByComparingTo("80");
        assertThat(slice.get("input_unit_min")).isEqualTo("kg");
        assertThat(((Number) slice.get("stage_record_count")).intValue()).isEqualTo(1);
        assertThat(((Number) slice.get("batch_count")).intValue()).isEqualTo(1);
    }

    /** 样本 2: 只有报工 —— 与改动前一致, stage_record_count = 0。 */
    @Test
    void reportsAloneAreUnchangedAndCountNoStageRecords() {
        String f = "IT-REPORT-ONLY";
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-R', '切片', 'kg', 'kg', NULL, ?)", f);
        stageBatch(f, 311, "plan-r1", "Q-OPEN");
        reportFor(f, 311, 311, "WP-R", "50", "40");

        List<Map<String, Object>> rows = repository.aggregateYieldByProcess(f, FROM, TO, "", NONE, NONE);
        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("total_input").toString())).isEqualByComparingTo("50");
        assertThat(((Number) rows.get(0).get("stage_record_count")).intValue()).isZero();
    }

    /**
     * 样本 3: 两者都有。同名工序合并成一行; 同一批次既有报工又有环节记录时只算报工 (不重复计数);
     * 已删除、别厂、保密计划的环节记录不进; 投入为 0 只进覆盖统计。
     */
    @Test
    void bothSourcesMergeByProcessNameAndABatchWithReportsIsNeverCountedTwice() {
        String f = "IT-STAGE-BOTH";
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-B', '切片', 'kg', 'kg', NULL, ?)", f);
        // 321: 只有环节记录 100->80 (进)
        stageBatch(f, 321, "plan-ok", "Q-OPEN");
        stageRecord(f, 11, 321, "切片", "SLICING", "100", "80", false);
        // 322: 只有报工 50->40 (进)
        stageBatch(f, 322, "plan-ok", "Q-OPEN");
        reportFor(f, 322, 322, "WP-B", "50", "40");
        // 323: 报工 20->10 + 环节记录 999->1: 同一批只算报工
        stageBatch(f, 323, "plan-ok", "Q-OPEN");
        reportFor(f, 323, 323, "WP-B", "20", "10");
        stageRecord(f, 12, 323, "切片", "SLICING", "999", "1", false);
        // 用已软删、零投入、保密计划、别厂批次区分可见与可算的范围。
        stageBatch(f, 324, "plan-ok", "Q-OPEN");
        stageRecord(f, 13, 324, "切片", "SLICING", "777", "7", true);
        stageRecord(f, 14, 324, "切片", "SLICING", "0", "7", false);
        stageBatch(f, 325, "plan-hidden", "Q-OPEN");
        stageRecord(f, 15, 325, "切片", "SLICING", "555", "5", false);
        stageBatch("IT-OTHER-FACTORY", 326, "plan-ok", "Q-OPEN");
        stageRecord("IT-OTHER-FACTORY", 16, 326, "切片", "SLICING", "444", "4", false);

        List<Map<String, Object>> rows = repository.aggregateYieldByProcess(f, FROM, TO, "", NONE, List.of("plan-hidden"));
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("process_name")).isEqualTo("切片");
        assertThat(new BigDecimal(row.get("total_input").toString())).isEqualByComparingTo("170");
        assertThat(new BigDecimal(row.get("total_output").toString())).isEqualByComparingTo("130");
        assertThat(((Number) row.get("batch_count")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("stage_record_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("input_unit_kinds")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("record_count")).intValue()).isEqualTo(4);
        assertThat((BigDecimal) row.get("known_output")).isEqualByComparingTo("137");

        // 日期窗: 环节记录在 2026-11-10, 窗外不进
        assertThat(repository.aggregateYieldByProcess(f, LocalDate.of(2026, 12, 1), TO, "", NONE, List.of("plan-hidden")))
                .isEmpty();
        // 按产品排除对环节记录同样生效
        assertThat(repository.aggregateYieldByProcess(f, FROM, TO, "", List.of("Q-OPEN"), NONE)).isEmpty();
    }

    @Test
    void byProcessShowsIncompleteAndUnconfiguredStagesAndKeepsLegacyRateForCompleteWeights() {
        String f = "IT-STAGE-COVERAGE";
        configureProcess(f, "WP-PACKAGE", "包装", true, false, null);
        configureProcess(f, "WP-DOUGH", "和面", true, false, null);
        configureProcess(f, "WP-COOLING", "冷却", true, false, null);
        stageBatch(f, 401, "plan-open", "Q-OPEN");
        stageRecord(f, 21, 401, "包装", "PACKAGING", "100", "97.42", false);
        stageRecord(f, 22, 401, "包装", "PACKAGING", "23.123", null, false);
        stageRecord(f, 23, 401, "和面", "PREPARATION", "12.345", null, false);
        stageRecord(f, 24, 401, "和面", "PREPARATION", "2.111", null, false);
        stageRecord(f, 25, 401, "抽样称重", "WEIGHT_CHECK", null, null, false);
        stageRecord(f, 26, 401, "抽样称重", "WEIGHT_CHECK", null, null, false);
        when(aggregateExclusions.forCurrentCaller(f, "yield by-process aggregate"))
                .thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);

        List<ProcessYieldAggDTO> rows = yieldAnalysis.aggregateByProcess(f, FROM, TO, "Q-OPEN");
        assertThat(rows).extracting(ProcessYieldAggDTO::getProcessName)
                .containsExactlyInAnyOrder("包装", "和面", "抽样称重", "冷却");
        assertThat(rows).filteredOn(ProcessYieldAggDTO::isComputable)
                .extracting(ProcessYieldAggDTO::getProcessName).containsExactly("包装");

        ProcessYieldAggDTO pack = findProcess(rows, "包装");
        assertThat(pack.getInputQuantity()).isEqualByComparingTo("100");
        assertThat(pack.getOutputQuantity()).isEqualByComparingTo("97.42");
        assertThat(pack.getConversionRate()).isEqualByComparingTo("97.4");
        assertThat(pack.getWastageRate()).isEqualByComparingTo("2.6");
        assertThat(pack.getStageRecordCount()).isEqualTo(1);
        assertThat(pack.getRecordCount()).isEqualTo(2);
        assertThat(pack.getKnownInputQuantity()).isEqualByComparingTo("123.123");
        assertThat(pack.isMissingOutput()).isTrue();

        ProcessYieldAggDTO dough = findProcess(rows, "和面");
        assertThat(dough.getRecordCount()).isEqualTo(2);
        assertThat(dough.getInputRecordCount()).isEqualTo(2);
        assertThat(dough.getOutputRecordCount()).isZero();
        assertThat(dough.getKnownInputQuantity()).isEqualByComparingTo("14.456");
        assertThat(dough.getKnownInputUnit()).isEqualTo("kg");
        assertThat(dough.getKnownOutputQuantity()).isNull();
        assertThat(dough.isMissingInput()).isFalse();
        assertThat(dough.isMissingOutput()).isTrue();

        ProcessYieldAggDTO weighing = findProcess(rows, "抽样称重");
        assertThat(weighing.getRecordCount()).isEqualTo(2);
        assertThat(weighing.getInputRecordCount()).isZero();
        assertThat(weighing.getOutputRecordCount()).isZero();
        assertThat(weighing.getKnownInputQuantity()).isNull();
        assertThat(weighing.getKnownOutputQuantity()).isNull();
        assertThat(weighing.isMissingInput()).isTrue();
        assertThat(weighing.isMissingOutput()).isTrue();

        ProcessYieldAggDTO cooling = findProcess(rows, "冷却");
        assertThat(cooling.getRecordCount()).isZero();
        assertThat(cooling.getIncomparableReason()).isEqualTo(IncomparableReason.NO_RECORDS);
        assertThat(rows).filteredOn(row -> !row.isComputable()).allSatisfy(row -> {
            assertThat(row.getConversionRate()).isNull();
            assertThat(row.getWastageRate()).isNull();
        });
    }

    @Test
    void byProcessExcludesInvisibleRecordsFromCoverageAndOnlyPadsEnabledLocalProcesses() {
        String f = "IT-STAGE-FILTERS";
        configureProcess(f, "WP-PUBLIC", "公开工序", true, false, null);
        configureProcess(f, "WP-DELETED", "已删配置", true, true, null);
        configureProcess(f, "WP-INACTIVE", "停用配置", false, false, null);
        configureProcess(f, "WP-MERGED", "合并配置", true, false, "WP-PUBLIC");
        configureProcess("IT-OTHER", "WP-OTHER", "别厂配置", true, false, null);
        stageBatch(f, 501, "plan-open", "Q-OPEN");
        stageRecord(f, 31, 501, "公开工序", "PREPARATION", "8", null, false);
        stageRecord(f, 32, 501, "已删记录", "PREPARATION", null, null, true);
        stageRecord("IT-OTHER", 33, 501, "别厂记录", "PREPARATION", null, null, false);
        stageBatch("IT-OTHER", 502, "plan-other-factory", "Q-OPEN");
        stageRecord(f, 34, 502, "别厂批次", "PREPARATION", null, null, false);
        stageBatch(f, 503, "plan-secret", "Q-OPEN");
        stageRecord(f, 35, 503, "保密计划", "PREPARATION", null, null, false);
        stageBatch(f, 504, "plan-other-product", "Q-OTHER");
        stageRecord(f, 36, 504, "别种产品", "PREPARATION", null, null, false);
        stageRecord(f, 37, 501, "窗外记录", "PREPARATION", null, null, false);
        jdbcTemplate.update("UPDATE processing_stage_records SET start_time = DATE '2026-11-09' WHERE id = 37");
        stageBatch(f, 505, "plan-report", "Q-OPEN");
        reportFor(f, 505, 505, "WP-PUBLIC", "20", "10");
        jdbcTemplate.update("UPDATE production_reports SET report_date = DATE '2026-11-09' WHERE id = 505");
        stageRecord(f, 38, 505, "报工去重", "PREPARATION", null, null, false);
        when(aggregateExclusions.forCurrentCaller(f, "yield by-process aggregate"))
                .thenReturn(new ProcessSecretAggregateExclusions.Scope(List.of(), List.of("plan-secret")));

        LocalDate day = LocalDate.of(2026, 11, 10);
        List<ProcessYieldAggDTO> rows = yieldAnalysis.aggregateByProcess(f, day, day, "Q-OPEN");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getProcessName()).isEqualTo("公开工序");
            assertThat(row.getRecordCount()).isEqualTo(1);
            assertThat(row.getKnownInputQuantity()).isEqualByComparingTo("8");
            assertThat(row.isComputable()).isFalse();
        });
    }

    private void configureProcess(String factory, String id, String name, boolean active, boolean deleted, String mergedInto) {
        jdbcTemplate.update("INSERT INTO work_processes VALUES (?, ?, 'kg', 'kg', "
                        + (deleted ? "now()" : "NULL") + ", ?, ?, ?)",
                id, name, factory, active, mergedInto);
    }

    private static ProcessYieldAggDTO findProcess(List<ProcessYieldAggDTO> rows, String name) {
        return rows.stream().filter(row -> name.equals(row.getProcessName())).findFirst().orElseThrow();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        return value;
    }
}
