package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.service.unit.*;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import org.hibernate.Session;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.cretas.aims.service.indicator.strategy.impl.FactoryActualProductionMetrics.Metric.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Hibernate/PostgreSQL query execution. All fixture writes are connection-local TEMP tables. */
class FactoryActualMetricsPostgresTest {
    @Test void reversedSettlementAndDeletedOutputDoNotDuplicateReplacementCostOrQuality() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(url != null, "Explicit local PostgreSQL fixture URL required");
        DisposablePostgresTargetGuard.requireSafeUrl(url);
        Configuration configuration = new Configuration()
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "assembly_executor")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .setProperty("hibernate.show_sql", "false");
        try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            try {
                session.doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("CREATE TEMP TABLE production_batches (id bigint, batch_number text, actual_quantity numeric, good_quantity numeric, unit text, defect_quantity numeric, factory_id text, production_plan_id text, deleted_at timestamp, batch_type text, status text, end_time timestamp) ON COMMIT DROP");
                        statement.execute("CREATE TEMP TABLE production_plans (id text, factory_id text, status text, deleted_at timestamp) ON COMMIT DROP");
                        statement.execute("CREATE TEMP TABLE production_settlements (id text, production_plan_id text, factory_id text, defect_count_outcome text, defect_quantity numeric, quantity_unit text, deleted_at timestamp) ON COMMIT DROP");
                        statement.execute("CREATE TEMP TABLE production_settlement_output_lines (settlement_id text, factory_id text, deleted_at timestamp) ON COMMIT DROP");
                        statement.execute("INSERT INTO pg_temp.production_plans VALUES ('PA','FA','COMPLETED',null),('PB','FA','COMPLETED',null),('PC','FB','COMPLETED',null),('PD','FA','IN_PROGRESS',null)");
                        statement.execute("INSERT INTO pg_temp.production_batches VALUES (1,'A',9,8,'kg',1,'FA','PA',null,null,'COMPLETED','2026-10-16 10:00'),(2,'B',11,10,'kg',1,'FA','PB',null,null,'COMPLETED','2026-10-16 23:59'),(3,'FOREIGN',1,1,'kg',0,'FB','PC',null,null,'COMPLETED','2026-10-16 10:00'),(4,'WIP',1,1,'kg',0,'FA','PA',null,'CLERK_WIP','COMPLETED','2026-10-16 10:00'),(5,'DELETED',1,1,'kg',0,'FA','PA','2026-10-16',null,'COMPLETED','2026-10-16 10:00'),(6,'UNSETTLED',1,1,'kg',0,'FA','PD',null,null,'COMPLETED','2026-10-16 10:00'),(7,'OUTSIDE',1,1,'kg',0,'FA',null,null,null,'COMPLETED','2026-10-17 00:00')");
                        statement.execute("INSERT INTO pg_temp.production_settlements VALUES ('OLD','PA','FA','COUNTED',99,'kg','2026-10-16 09:00'),('ACTIVE','PA','FA','COUNTED',1,'kg',null),('B-ACTIVE','PB','FA','COUNTED',1,'kg',null)");
                        statement.execute("INSERT INTO pg_temp.production_settlement_output_lines VALUES ('OLD','FA',null),('ACTIVE','FA',null),('ACTIVE','FA','2026-10-16 09:00'),('B-ACTIVE','FA',null)");
                    }
                });
                YieldReportService reporting = mock(YieldReportService.class);
                OrderCostBreakdownService costing = mock(OrderCostBreakdownService.class);
                UnitContractService units = mock(UnitContractService.class);
                for (long id : List.of(1L, 2L)) {
                    String code = id == 1 ? "A" : "B";
                    BigDecimal output = new BigDecimal(id == 1 ? "8" : "10");
                    when(reporting.getYield("FA", id)).thenReturn(BatchYieldDTO.builder()
                            .firstStepInput(BigDecimal.TEN).firstStepInputUnit("kg").lastStepOutput(output)
                            .lastStepOutputUnit("kg").lastStepOutputInFirstUnit(output).complete(true).inProgress(false).build());
                    when(costing.computeByBatch("FA", code, false)).thenReturn(OrderCostBreakdownDTO.builder()
                            .hasData(true).costComplete(true).calculationStatus("COMPLETE")
                            .totalCost(new BigDecimal("100")).convertedOutputKg(output).build());
                }
                when(units.describe("FA", "kg")).thenReturn(Optional.of(new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4)));
                when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenAnswer(call ->
                        new UnitConversionResult(UnitConversionStatus.IDENTITY, call.getArgument(0), "kg", "kg", List.of(), null, null, null));
                FactoryActualProductionMetrics metrics = new FactoryActualProductionMetrics(reporting, costing, units);
                ReflectionTestUtils.setField(metrics, "entityManager", session);
                LocalDate day = LocalDate.of(2026, 10, 16);
                assertThat(metrics.compute("FA", day, day, TOTAL_COST)).isEqualByComparingTo("200");
                assertThat(metrics.compute("FA", day, day, GOOD_OUTPUT_RATE)).isEqualByComparingTo("90");
                assertThat(metrics.compute("FA", day, day, YIELD_RATE)).isEqualByComparingTo("90");
                assertThat(metrics.compute("FA", day.plusDays(2), day.plusDays(2), TOTAL_COST)).isNull();
                verify(reporting, never()).getYield(eq("FA"), longThat(id -> id > 2));
            } finally {
                transaction.rollback();
            }
        }
    }
}
