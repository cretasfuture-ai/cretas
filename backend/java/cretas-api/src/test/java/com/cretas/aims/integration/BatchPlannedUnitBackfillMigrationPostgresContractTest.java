package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the production migration V20261110_01 unchanged (the file text, not a copy) on an owned PostgreSQL
 * schema and reads back every seeded row.
 *
 * <p>Fixture tables carry only the columns the migration reads or must leave alone; they mirror the types of
 * the entities (production_batches.planned_unit VARCHAR(20) vs production_plans.planned_unit VARCHAR(32)).
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class BatchPlannedUnitBackfillMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261110_01__backfill_batch_planned_unit_from_plan.sql";
    private static final Timestamp STAMP = Timestamp.valueOf("2026-01-02 03:04:05");

    private record Row(String plannedUnit, BigDecimal plannedQuantity, BigDecimal efficiency, String unit,
                       String batchNumber, Timestamp updatedAt) {
    }

    @Test
    void backfillWritesPlanUnitAndNullsOnlyCrossUnitEfficiency() throws Exception {
        withSchema(true, (c, s) -> {
            seed(s);
            assertThat(batches(s)).as("seed must be in place before the migration runs").hasSize(12);
            run(s);
            Map<Long, Row> after = batches(s);

            // (a) kg batch, qty equals the 盒 plan, wrong cross-unit efficiency 87.50 -> unit written, efficiency NULL
            assertThat(after.get(1L).plannedUnit()).isEqualTo("盒");
            assertThat(after.get(1L).efficiency()).isNull();
            // (b) same unit 盒/盒 -> unit written, efficiency untouched
            assertThat(after.get(2L).plannedUnit()).isEqualTo("盒");
            assertThat(after.get(2L).efficiency()).isEqualByComparingTo("98.00");
            // (c) quantity differs from the plan -> untouched
            assertUntouched(after.get(3L), "100.01", "41.00");
            // (d) no plan -> untouched
            assertUntouched(after.get(4L), "100.00", "55.00");
            // (e) planned_unit already set -> untouched (even though qty equals the plan and units differ)
            assertThat(after.get(5L).plannedUnit()).isEqualTo("袋");
            assertThat(after.get(5L).efficiency()).isEqualByComparingTo("77.00");
            // (f) plan missing -> untouched
            assertUntouched(after.get(6L), "100.00", "66.00");
            // (g) soft-deleted batch -> untouched
            assertUntouched(after.get(7L), "100.00", "33.00");
            // (h) plan unit longer than the batch column (VARCHAR(20)) -> skipped, not truncated, no failure
            assertUntouched(after.get(8L), "100.00", "22.00");
            // (i) equivalent spelling 千克 vs kg: unit written (trimmed), efficiency NULLed (under-reports, by design)
            assertThat(after.get(9L).plannedUnit()).isEqualTo("kg");
            assertThat(after.get(9L).efficiency()).isNull();
            // (j) blank plan unit -> untouched
            assertUntouched(after.get(10L), "100.00", "44.00");
            // (k) batch planned_quantity NULL -> untouched
            assertThat(after.get(11L).plannedUnit()).isNull();
            assertThat(after.get(11L).efficiency()).isEqualByComparingTo("11.00");
            // (l) batch with NULL efficiency + cross unit -> unit written, efficiency stays NULL
            assertThat(after.get(12L).plannedUnit()).isEqualTo("包");
            assertThat(after.get(12L).efficiency()).isNull();

            // Not touching other columns, updated_at in particular, on EVERY row incl. the changed ones.
            Map<Long, Row> before = seededSnapshot();
            for (var e : after.entrySet()) {
                Row b = before.get(e.getKey());
                assertThat(e.getValue().updatedAt()).as("updated_at of batch " + e.getKey()).isEqualTo(STAMP);
                assertThat(e.getValue().batchNumber()).as("batch_number of batch " + e.getKey()).isEqualTo(b.batchNumber());
                assertThat(e.getValue().unit()).as("unit of batch " + e.getKey()).isEqualTo(b.unit());
                if (b.plannedQuantity() == null) {
                    assertThat(e.getValue().plannedQuantity()).as("planned_quantity of batch " + e.getKey()).isNull();
                } else {
                    assertThat(e.getValue().plannedQuantity()).as("planned_quantity of batch " + e.getKey())
                            .isEqualByComparingTo(b.plannedQuantity());
                }
            }
            // the plans table is read-only for the migration
            try (ResultSet r = s.executeQuery("SELECT planned_unit FROM production_plans WHERE id='plan-blank'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("   ");
            }
        });
    }

    @Test
    void secondRunChangesNothing() throws Exception {
        withSchema(true, (c, s) -> {
            seed(s);
            run(s);
            Map<Long, Row> once = batches(s);
            run(s);
            assertThat(batches(s)).isEqualTo(once);
        });
    }

    @Test
    void emptyTablesAndMissingTablesAreBothNoOps() throws Exception {
        // tables exist, zero rows
        withSchema(true, (c, s) -> {
            run(s);
            assertThat(batches(s)).isEmpty();
        });
        // fresh database: Flyway runs before ddl-auto, neither table exists yet -> must not throw
        withSchema(false, (c, s) -> run(s));
        // only one of the two tables exists -> also skipped quietly
        withSchema(false, (c, s) -> {
            s.execute("CREATE TABLE production_plans(id varchar(191) PRIMARY KEY, planned_quantity numeric(10,2),"
                    + " planned_unit varchar(32))");
            run(s);
        });
    }

    // ------------------------------------------------------------------

    private static void assertUntouched(Row row, String plannedQuantity, String efficiency) {
        assertThat(row.plannedUnit()).isNull();
        assertThat(row.plannedQuantity()).isEqualByComparingTo(plannedQuantity);
        assertThat(row.efficiency()).isEqualByComparingTo(efficiency);
    }

    private static void seed(Statement s) throws Exception {
        s.execute("INSERT INTO production_plans VALUES "
                + "('plan-box',100,'盒'),('plan-pack',100,'包'),('plan-kg',100,'kg'),"
                + "('plan-long',100,'" + "x".repeat(25) + "'),('plan-blank',100,'   ')");
        String cols = "(id,batch_number,production_plan_id,planned_quantity,planned_unit,unit,efficiency,updated_at,deleted_at)";
        String t = "'" + STAMP + "'";
        s.execute("INSERT INTO production_batches " + cols + " VALUES "
                + "(1,'B1','plan-box',100,NULL,'kg',87.50," + t + ",NULL),"
                + "(2,'B2','plan-box',100,NULL,'盒',98.00," + t + ",NULL),"
                + "(3,'B3','plan-box',100.01,NULL,'kg',41.00," + t + ",NULL),"
                + "(4,'B4',NULL,100,NULL,'kg',55.00," + t + ",NULL),"
                + "(5,'B5','plan-box',100,'袋','kg',77.00," + t + ",NULL),"
                + "(6,'B6','plan-gone',100,NULL,'kg',66.00," + t + ",NULL),"
                + "(7,'B7','plan-box',100,NULL,'kg',33.00," + t + ",'2026-02-01 00:00:00'),"
                + "(8,'B8','plan-long',100,NULL,'kg',22.00," + t + ",NULL),"
                + "(9,'B9','plan-kg',100,NULL,' 千克 ',50.00," + t + ",NULL),"
                + "(10,'B10','plan-blank',100,NULL,'kg',44.00," + t + ",NULL),"
                + "(11,'B11','plan-box',NULL,NULL,'kg',11.00," + t + ",NULL),"
                + "(12,'B12','plan-pack',100,NULL,'盒',NULL," + t + ",NULL)");
        // (9) plan unit is 'kg' while the batch writes ' 千克 ': trimmed literals differ -> efficiency NULL.
    }

    private static Map<Long, Row> seededSnapshot() {
        Map<Long, Row> m = new LinkedHashMap<>();
        String[] units = {"kg", "盒", "kg", "kg", "kg", "kg", "kg", "kg", " 千克 ", "kg", "kg", "盒"};
        for (int i = 1; i <= 12; i++) {
            m.put((long) i, new Row(null, i == 11 ? null : new BigDecimal("100"), null, units[i - 1], "B" + i, STAMP));
        }
        // batch 3 was seeded with 100.01
        m.put(3L, new Row(null, new BigDecimal("100.01"), null, "kg", "B3", STAMP));
        return m;
    }

    private static Map<Long, Row> batches(Statement s) throws Exception {
        Map<Long, Row> m = new LinkedHashMap<>();
        try (ResultSet r = s.executeQuery("SELECT id,planned_unit,planned_quantity,efficiency,unit,batch_number,updated_at"
                + " FROM production_batches ORDER BY id")) {
            while (r.next()) {
                m.put(r.getLong(1), new Row(r.getString(2), r.getBigDecimal(3), r.getBigDecimal(4), r.getString(5),
                        r.getString(6), r.getTimestamp(7)));
            }
        }
        return m;
    }

    private static void run(Statement s) throws Exception {
        String sql;
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).as("migration text must be non-empty and contain the DO block").contains("UPDATE production_batches");
        s.execute(sql);
    }

    private interface Body {
        void accept(Connection c, Statement s) throws Exception;
    }

    private static void withSchema(boolean withTables, Body body) throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "batch_pu_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            try {
                s.execute("SET search_path TO " + schema);
                if (withTables) {
                    s.execute("CREATE TABLE production_plans(id varchar(191) PRIMARY KEY, planned_quantity numeric(10,2),"
                            + " planned_unit varchar(32))");
                    s.execute("CREATE TABLE production_batches(id bigint PRIMARY KEY, batch_number varchar(50),"
                            + " production_plan_id varchar(191), planned_quantity numeric(12,2), planned_unit varchar(20),"
                            + " unit varchar(20) NOT NULL, efficiency numeric(5,2), updated_at timestamp NOT NULL,"
                            + " deleted_at timestamp)");
                }
                body.accept(c, s);
            } finally {
                try (Statement d = c.createStatement()) {
                    d.execute("SET search_path TO public");
                    d.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }
}
