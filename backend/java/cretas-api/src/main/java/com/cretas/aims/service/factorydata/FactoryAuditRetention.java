package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计 / 留痕表在删除工厂时挪进留存区 {@code factory_erasure_audit_retention} (口径 Q3: 保留 6 个月)。
 *
 * <p>只留谁 (actor)、何时 (occurred_at)、做了什么 (action)、对象编号 (object_ref), 其余内容字段
 * (请求参数、IP、旧值新值、错误信息……) 一律不进留存区。每张审计表各写一条取数表达式;
 * 目录里登记为 AUDIT 却没有这里的表达式 ⇒ 删除直接报错, ⛔ 不静默丢弃。
 */
public final class FactoryAuditRetention {

    public static final int RETENTION_MONTHS = 6;
    public static final String TABLE = "factory_erasure_audit_retention";

    /** 每张审计表: 行键 / 谁 / 何时 / 做了什么 / 对象编号 的 SQL 表达式 (表别名 t)。 */
    record Mapping(String rowKey, String actor, String occurredAt, String action, String objectRef) {}

    static final Map<String, Mapping> MAPPINGS = Map.of(
            "platform_data_access_log", new Mapping("t.id::text",
                    "concat_ws(' ', t.actor_type || ':' || t.actor_user_id, t.actor_username)",
                    "t.created_at",
                    "concat_ws(' ', t.http_method, t.request_path)",
                    "'factory:' || t.target_factory_id"),
            "operation_logs", new Mapping("t.id::text",
                    "concat_ws(' ', 'user:' || t.user_id, t.username)",
                    "t.created_at",
                    "concat_ws('/', t.module, t.action) || CASE WHEN t.success = false THEN ' (失败)' ELSE '' END",
                    "concat_ws(':', t.entity_type, t.entity_id)"),
            "tool_execution_audit_events", new Mapping("t.id::text",
                    "concat_ws(':', t.principal_type, t.principal_id)",
                    "t.started_at",
                    "concat_ws(' ', t.tool_name, coalesce(t.outcome_status, t.state))",
                    "t.id::text"),
            "process_secret_access_events", new Mapping("t.id::text",
                    "concat_ws(':', t.actor_type, t.actor_id::text)",
                    "t.created_at",
                    "concat_ws('/', t.action, t.decision)",
                    "concat_ws(' ', 'product:' || t.product_type_id, t.resource_ref)"),
            "smart_bi_llm_egress_audit", new Mapping("t.id::text",
                    "'call_site:' || t.call_site",
                    "t.created_at",
                    "concat_ws('/', t.provider, t.model) || ' status=' || coalesce(t.status_code::text, '')",
                    "t.id::text"));

    public record Row(Database database, String table, String rowKey, String actor, LocalDateTime occurredAt,
                      String action, String objectRef) {}

    private FactoryAuditRetention() {}

    /** 从源库读出本厂在这张审计表里的行, 只取四个字段。 */
    static List<Row> read(Connection conn, Database db, String schema, FactoryDataScope.TableScope ts, String factoryId)
            throws SQLException {
        Mapping m = MAPPINGS.get(ts.table());
        if (m == null) {
            throw new IllegalStateException("审计表 " + ts.table() + " 没有留存取数表达式 (FactoryAuditRetention.MAPPINGS)");
        }
        FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, ts, factoryId, "t.");
        String sql = "SELECT " + m.rowKey() + ", " + m.actor() + ", " + m.occurredAt() + ", " + m.action() + ", "
                + m.objectRef() + " FROM " + FactoryDataScopeResolver.q(schema) + "." + FactoryDataScopeResolver.q(ts.table())
                + " t WHERE " + where.sql();
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            FactoryDataScopeResolver.bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp at = rs.getTimestamp(3);
                    rows.add(new Row(db, ts.table(), rs.getString(1), cut(rs.getString(2)),
                            at == null ? null : at.toLocalDateTime(), cut(rs.getString(4)), cut(rs.getString(5))));
                }
            }
        }
        return rows;
    }

    /** 写进主库留存区 (幂等: 同一删除申请 + 同一源行只留一条)。 */
    static int write(Connection mainConn, long erasureRequestId, String factoryId, List<Row> rows,
                     LocalDateTime now) throws SQLException {
        // 与回执台账一样不带 schema 限定 (主库默认 schema)
        String sql = "INSERT INTO " + TABLE
                + " (erasure_request_id, factory_id, source_database, source_table, source_row_key, actor, occurred_at,"
                + " action, object_ref, retain_until, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (erasure_request_id, source_database, source_table, source_row_key) DO NOTHING";
        int n = 0;
        try (PreparedStatement ps = mainConn.prepareStatement(sql)) {
            for (Row r : rows) {
                ps.setLong(1, erasureRequestId);
                ps.setString(2, factoryId);
                ps.setString(3, r.database().name());
                ps.setString(4, r.table());
                ps.setString(5, r.rowKey());
                ps.setString(6, r.actor());
                ps.setTimestamp(7, r.occurredAt() == null ? null : Timestamp.valueOf(r.occurredAt()));
                ps.setString(8, r.action());
                ps.setString(9, r.objectRef());
                ps.setTimestamp(10, Timestamp.valueOf(now.plusMonths(RETENTION_MONTHS)));
                ps.setTimestamp(11, Timestamp.valueOf(now));
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
        }
        return n;
    }

    private static String cut(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }
}
