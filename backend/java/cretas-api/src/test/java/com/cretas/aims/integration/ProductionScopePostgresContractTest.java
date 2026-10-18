package com.cretas.aims.integration;

import com.cretas.aims.repository.ProductionPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in PostgreSQL behavior proof for production-plan row visibility.
 * The service/JPA Specification is exercised by ProductionScopeContractTest; this gate
 * keeps the production PostgreSQL count/page/statistics semantics explicit, including
 * soft deletion and cross-factory negatives, in an isolated disposable schema.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionScopePostgresContractTest {

    @Test
    void creatorScopesFilterBeforePageCountAndStatistics() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        // An empty password is valid for the local assembly_executor PostgreSQL role.
        String password = optionalPasswordEnv("CRETAS_WORKFLOW_PG_PASSWORD");
        String schema = "production_scope_verify_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            connection.setSchema(schema);
            try {
                createFixture(statement);
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

                assertThat(ids(jdbc, "F1", new long[]{11L}, null, null, 50, 0))
                        .containsExactly("PG-OWN-DONE", "PG-OWN-PENDING");
                assertThat(ids(jdbc, "F1", new long[]{11L, 22L}, null, null, 50, 0))
                        .containsExactly("PG-OWN-DONE", "PG-OWN-PENDING", "PG-SUBORDINATE");

                long ownerCount = count(jdbc, "F1", new long[]{11L}, null, null);
                List<String> secondOwnerPage = ids(jdbc, "F1", new long[]{11L}, null, null, 1, 1);
                assertThat(ownerCount).isEqualTo(2L);
                assertThat(secondOwnerPage).containsExactly("PG-OWN-PENDING");

                assertThat(ids(jdbc, "F1", new long[]{99L}, null, null, 50, 0)).containsExactly("PG-OUTSIDE");
                assertThat(ids(jdbc, "F1", new long[]{88L}, null, null, 50, 0)).isEmpty();
                assertThat(count(jdbc, "F1", new long[]{}, null, null)).isZero();
                assertThat(count(jdbc, "F1", new long[]{11L}, "OWN-PENDING", "PENDING")).isEqualTo(1L);
                assertThat(ids(jdbc, "F1", new long[]{11L}, "OWN-PENDING", "PENDING", 50, 0))
                        .containsExactly("PG-OWN-PENDING");

                // Execute the exact native SQL declared by the production repository method.
                // This keeps JSONB containment, creator scope, factory scope and soft-delete
                // semantics coupled to the query that Spring Data executes in production.
                NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
                assertThat(sourceOrderIdsFromRepositoryQuery(namedJdbc, "F1", List.of(11L, 22L), "[\"SO-2\"]"))
                        .containsExactly("PG-SUBORDINATE", "PG-OWN-PENDING");
                assertThat(sourceOrderIdsFromRepositoryQuery(namedJdbc, "F1", List.of(99L), "[\"SO-2\"]"))
                        .isEmpty();

                Stats self = stats(jdbc, "F1", new long[]{11L}, "2026-10-01", "2026-10-31");
                assertThat(self.total()).isEqualTo(2L);
                assertThat(self.completed()).isEqualTo(1L);
                assertThat(self.pending()).isEqualTo(1L);
                assertThat(self.totalCost()).isEqualByComparingTo("7.00");

                Stats selfAndBelow = stats(jdbc, "F1", new long[]{11L, 22L},
                        "2026-10-01", "2026-10-31");
                assertThat(selfAndBelow.total()).isEqualTo(3L);
                assertThat(selfAndBelow.completed()).isEqualTo(2L);
                assertThat(selfAndBelow.pending()).isEqualTo(1L);
                assertThat(selfAndBelow.totalCost()).isEqualByComparingTo("10.00");

                // ALL has no creator predicate; factory, date and soft-delete predicates remain.
                assertThat(count(jdbc, "F1", null, null, null)).isEqualTo(4L);
                assertThat(count(jdbc, "F1", new long[]{11L}, null, null)).isEqualTo(2L);
                assertThat(count(jdbc, "F2", null, null, null)).isEqualTo(1L);
                assertThat(count(jdbc, "F1", new long[]{11L}, "CROSS-FACTORY", null)).isZero();
            } finally {
                connection.setSchema("public");
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private static List<String> sourceOrderIdsFromRepositoryQuery(
            NamedParameterJdbcTemplate jdbc, String factoryId, List<Long> creatorIds,
            String sourceOrderIdsJson) throws Exception {
        Method method = ProductionPlanRepository.class.getMethod(
                "findByFactoryIdAndCreatedByInAndSourceOrderIdsContaining",
                String.class, java.util.Collection.class, String.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("row-scoped source-order repository query").isNotNull();
        assertThat(query.nativeQuery()).as("source-order query must remain PostgreSQL native SQL").isTrue();
        assertThat(query.value())
                .contains("created_by IN (:creatorIds)", "source_order_ids @>", "deleted_at IS NULL");

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("creatorIds", creatorIds)
                .addValue("soIdJson", sourceOrderIdsJson);
        return jdbc.query(query.value(), parameters, (rs, row) -> rs.getString("id"));
    }

    private static void createFixture(Statement statement) throws Exception {
        statement.execute("CREATE TABLE production_plans ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "plan_number varchar(100) NOT NULL, created_by bigint NOT NULL,"
                + "status varchar(32) NOT NULL, planned_date date NOT NULL,"
                + "source_order_id varchar(64), source_order_ids jsonb,"
                + "actual_material_cost numeric(18,2), actual_labor_cost numeric(18,2),"
                + "created_at timestamp NOT NULL, deleted_at timestamp NULL)");
        statement.execute("INSERT INTO production_plans "
                + "(id,factory_id,plan_number,created_by,status,planned_date,source_order_id,source_order_ids,actual_material_cost,actual_labor_cost,created_at,deleted_at) VALUES"
                + " ('PG-OWN-DONE','F1','OWN-DONE',11,'COMPLETED','2026-10-10','SO-1','[\"SO-1\"]',5,2,'2026-10-01 00:00:01',NULL),"
                + " ('PG-OWN-PENDING','F1','OWN-PENDING',11,'PENDING','2026-10-10','SO-2','[\"SO-2\"]',0,0,'2026-10-01 00:00:02',NULL),"
                + " ('PG-SUBORDINATE','F1','SUBORDINATE',22,'COMPLETED','2026-10-10','SO-1','[\"SO-2\"]',3,0,'2026-10-01 00:00:03',NULL),"
                + " ('PG-OUTSIDE','F1','OUTSIDE',99,'COMPLETED','2026-10-10','SO-3','[\"SO-3\"]',100,0,'2026-10-01 00:00:04',NULL),"
                + " ('PG-CROSS','F2','CROSS-FACTORY',11,'COMPLETED','2026-10-10','SO-1','[\"SO-1\"]',100,0,'2026-10-01 00:00:05',NULL),"
                + " ('PG-DELETED','F1','DELETED',11,'COMPLETED','2026-10-10','SO-1','[\"SO-1\"]',1000,0,'2026-10-01 00:00:06',CURRENT_TIMESTAMP)");
    }

    private static long count(JdbcTemplate jdbc, String factory, long[] creators,
                              String keyword, String status) {
        QueryParts query = query(factory, creators, keyword, status);
        List<Object> args = new ArrayList<>(query.args());
        return jdbc.queryForObject("SELECT COUNT(*) " + query.fromWhere(), Long.class, args.toArray());
    }

    private static List<String> ids(JdbcTemplate jdbc, String factory, long[] creators,
                                    String keyword, String status, int limit, int offset) {
        QueryParts query = query(factory, creators, keyword, status);
        List<Object> args = new ArrayList<>(query.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT p.id " + query.fromWhere()
                        + " ORDER BY p.created_at ASC, p.id ASC LIMIT ? OFFSET ?",
                (rs, row) -> rs.getString(1), args.toArray());
    }

    private static Stats stats(JdbcTemplate jdbc, String factory, long[] creators,
                               String startDate, String endDate) {
        QueryParts query = query(factory, creators, null, null);
        List<Object> args = new ArrayList<>(query.args());
        args.add(java.sql.Date.valueOf(startDate));
        args.add(java.sql.Date.valueOf(endDate));
        MapRow row = jdbc.queryForObject("SELECT COUNT(*),"
                        + " COUNT(*) FILTER (WHERE p.status='COMPLETED'),"
                        + " COUNT(*) FILTER (WHERE p.status='PENDING'),"
                        + " COALESCE(SUM(CASE WHEN p.status='COMPLETED'"
                        + " THEN COALESCE(p.actual_material_cost,0)+COALESCE(p.actual_labor_cost,0) ELSE 0 END),0)"
                        + " FROM production_plans p" + query.whereWithoutFrom()
                        + " AND p.planned_date BETWEEN ? AND ?",
                (rs, ignored) -> new MapRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)),
                args.toArray());
        return new Stats(row.total(), row.completed(), row.pending(), row.totalCost());
    }

    private static QueryParts query(String factory, long[] creators, String keyword, String status) {
        StringBuilder where = new StringBuilder("FROM production_plans p WHERE p.factory_id=? AND p.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(factory);
        if (creators != null) {
            if (creators.length == 0) {
                where.append(" AND 1=0");
            } else {
                where.append(" AND p.created_by IN (");
                for (int i = 0; i < creators.length; i++) {
                    if (i > 0) where.append(',');
                    where.append('?');
                    args.add(creators[i]);
                }
                where.append(')');
            }
        }
        if (keyword != null) {
            where.append(" AND LOWER(p.plan_number) LIKE LOWER(CONCAT('%',?,'%'))");
            args.add(keyword);
        }
        if (status != null) {
            where.append(" AND p.status=?");
            args.add(status);
        }
        return new QueryParts(where.toString(), args);
    }

    private record QueryParts(String fromWhere, List<Object> args) {
        String whereWithoutFrom() {
            return fromWhere.substring(fromWhere.indexOf(" WHERE"));
        }
    }

    private record MapRow(long total, long completed, long pending, BigDecimal totalCost) {}

    private record Stats(long total, long completed, long pending, BigDecimal totalCost) {}

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required PostgreSQL verification environment variable: " + name);
        }
        return value;
    }

    private static String optionalPasswordEnv(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("Missing required PostgreSQL verification environment variable: " + name);
        }
        return value;
    }
}
