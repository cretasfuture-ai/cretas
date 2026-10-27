package com.cretas.aims.service.tenantbackup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 套餐 / 改动留痕 / 执行记录三张表 (V20261103_91)。与整厂导出的回执台账同一写法:
 * 主库连接池关闭了自动提交, 每次写完显式 commit。
 */
@Component
public class TenantBackupRepository {

    public enum Frequency {
        DAILY(1), WEEKLY(7), MONTHLY(30);

        private final int days;

        Frequency(int days) { this.days = days; }

        public int days() { return days; }
    }

    public enum KeyHolder { PLATFORM, CUSTOMER }

    public enum Source { AUTO_NEW_FACTORY, PLATFORM }

    public enum RunStatus { RUNNING, SUCCEEDED, FAILED, SKIPPED }

    /** 保留期只能是这几档 (表上有同样的 CHECK)。 */
    public static final List<Integer> RETENTION_DAYS = List.of(7, 30, 90, 365);

    public record Plan(String factoryId, boolean enabled, Frequency frequency, int retentionDays,
                       boolean includeAttachments, KeyHolder keyHolder, String customerRecipient, Source source,
                       Long updatedByAdminId, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record Run(long id, String factoryId, String triggerSource, String actorType, Long actorId,
                      RunStatus status, LocalDateTime startedAt, LocalDateTime finishedAt, Long exportRequestId,
                      String keyHolder, String attachmentsMode, Integer retentionDays, String objectKey,
                      Long packageBytes, Long encryptedBytes, String encryptedSha256, String errorMessage) {}

    public record PlanChange(long id, String factoryId, String actorType, Long actorId, String actorRole,
                             String beforePlan, String afterPlan, String reason, LocalDateTime changedAt) {}

    /** 平台列表的一行: 工厂 + 套餐 (可能没有) + 最近一次执行 / 最近一次成功。 */
    public record FactoryOverview(String factoryId, String factoryName, LocalDateTime factoryCreatedAt, Plan plan,
                                  Run lastRun, Run lastSuccess) {}

    private static final String PLAN_COLUMNS = "factory_id, enabled, frequency, retention_days, include_attachments, "
            + "key_holder, customer_recipient, source, updated_by_admin_id, created_at, updated_at";
    private static final String RUN_COLUMNS = "id, factory_id, trigger_source, actor_type, actor_id, status, started_at, "
            + "finished_at, export_request_id, key_holder, attachments_mode, retention_days, object_key, package_bytes, "
            + "encrypted_bytes, encrypted_sha256, error_message";

    private final DataSource dataSource;
    private final Clock clock;

    @Autowired
    public TenantBackupRepository(@Qualifier("primaryDataSource") DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    TenantBackupRepository(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    // -------------------------------------------------------------------- plans

    public Optional<Plan> findPlan(String factoryId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + PLAN_COLUMNS + " FROM tenant_backup_plans WHERE factory_id = ?")) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(plan(rs)) : Optional.<Plan>empty();
                }
            }
        });
    }

    public List<Plan> listPlans() {
        return inTx(conn -> {
            List<Plan> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + PLAN_COLUMNS + " FROM tenant_backup_plans ORDER BY factory_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(plan(rs));
            }
            return out;
        });
    }

    /**
     * 写套餐并在同一事务里留痕。{@code beforeJson} 为 null 表示新建。
     */
    public void savePlan(Plan plan, String beforeJson, String afterJson, String actorType, Long actorId,
                         String actorRole, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tenant_backup_plans (" + PLAN_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (factory_id) DO UPDATE SET enabled = EXCLUDED.enabled, "
                            + "frequency = EXCLUDED.frequency, retention_days = EXCLUDED.retention_days, "
                            + "include_attachments = EXCLUDED.include_attachments, key_holder = EXCLUDED.key_holder, "
                            + "customer_recipient = EXCLUDED.customer_recipient, source = EXCLUDED.source, "
                            + "updated_by_admin_id = EXCLUDED.updated_by_admin_id, updated_at = EXCLUDED.updated_at")) {
                ps.setString(1, plan.factoryId());
                ps.setBoolean(2, plan.enabled());
                ps.setString(3, plan.frequency().name());
                ps.setInt(4, plan.retentionDays());
                ps.setBoolean(5, plan.includeAttachments());
                ps.setString(6, plan.keyHolder().name());
                ps.setString(7, plan.customerRecipient());
                ps.setString(8, plan.source().name());
                if (plan.updatedByAdminId() == null) ps.setNull(9, Types.BIGINT); else ps.setLong(9, plan.updatedByAdminId());
                ps.setTimestamp(10, Timestamp.valueOf(now));
                ps.setTimestamp(11, Timestamp.valueOf(now));
                ps.executeUpdate();
            }
            insertChange(conn, plan.factoryId(), actorType, actorId, actorRole, beforeJson, afterJson, reason, now);
            return null;
        });
    }

    /**
     * 给 {@code createdAfter} 之后新建、还没有套餐的工厂补一行默认套餐 (每天 × 7 天, 平台钥匙, 只存附件清单),
     * 并留痕。返回补上的工厂编号。⛔ 早于这个时刻的工厂不碰 (口径: 现有工厂由平台逐个开)。
     */
    public List<String> enableDefaultPlanForNewFactories(LocalDateTime createdAfter, String afterJson) {
        LocalDateTime now = LocalDateTime.now(clock);
        return inTx(conn -> {
            List<String> added = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tenant_backup_plans (" + PLAN_COLUMNS + ") "
                            + "SELECT f.id, TRUE, 'DAILY', 7, FALSE, 'PLATFORM', NULL, 'AUTO_NEW_FACTORY', NULL, ?, ? "
                            + "FROM factories f WHERE f.created_at >= ? "
                            + "AND NOT EXISTS (SELECT 1 FROM tenant_backup_plans p WHERE p.factory_id = f.id) "
                            + "ON CONFLICT (factory_id) DO NOTHING RETURNING factory_id")) {
                ps.setTimestamp(1, Timestamp.valueOf(now));
                ps.setTimestamp(2, Timestamp.valueOf(now));
                ps.setTimestamp(3, Timestamp.valueOf(createdAfter));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) added.add(rs.getString(1));
                }
            }
            for (String factoryId : added) {
                insertChange(conn, factoryId, "SYSTEM", null, null, null, afterJson, "新建工厂默认赠送「每天 × 7 天」", now);
            }
            return added;
        });
    }

    public List<PlanChange> listChanges(String factoryId, int limit) {
        return inTx(conn -> {
            List<PlanChange> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, factory_id, actor_type, actor_id, actor_role, before_plan::text, after_plan::text, "
                            + "reason, changed_at FROM tenant_backup_plan_changes WHERE factory_id = ? "
                            + "ORDER BY changed_at DESC, id DESC LIMIT ?")) {
                ps.setString(1, factoryId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PlanChange(rs.getLong(1), rs.getString(2), rs.getString(3), longOrNull(rs, 4),
                                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), time(rs, 9)));
                    }
                }
            }
            return out;
        });
    }

    private static void insertChange(Connection conn, String factoryId, String actorType, Long actorId,
                                     String actorRole, String beforeJson, String afterJson, String reason,
                                     LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tenant_backup_plan_changes (factory_id, actor_type, actor_id, actor_role, before_plan, "
                        + "after_plan, reason, changed_at) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)")) {
            ps.setString(1, factoryId);
            ps.setString(2, actorType);
            if (actorId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, actorId);
            ps.setString(4, actorRole);
            ps.setString(5, beforeJson);
            ps.setString(6, afterJson);
            ps.setString(7, reason == null ? null : truncate(reason, 500));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            ps.executeUpdate();
        }
    }

    // --------------------------------------------------------------------- runs

    public long startRun(String factoryId, String triggerSource, String actorType, Long actorId, RunStatus status,
                         Plan plan, String attachmentsMode, String error) {
        LocalDateTime now = LocalDateTime.now(clock);
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tenant_backup_runs (factory_id, trigger_source, actor_type, actor_id, status, started_at, "
                            + "finished_at, key_holder, attachments_mode, retention_days, error_message) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, factoryId);
                ps.setString(2, triggerSource);
                ps.setString(3, actorType);
                if (actorId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, actorId);
                ps.setString(5, status.name());
                ps.setTimestamp(6, Timestamp.valueOf(now));
                ps.setTimestamp(7, status == RunStatus.RUNNING ? null : Timestamp.valueOf(now));
                ps.setString(8, plan == null ? null : plan.keyHolder().name());
                ps.setString(9, attachmentsMode);
                if (plan == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, plan.retentionDays());
                ps.setString(11, error == null ? null : truncate(error, 2000));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void finishRun(long id, RunStatus status, Long exportRequestId, String objectKey, Long packageBytes,
                          Long encryptedBytes, String encryptedSha256, String error) {
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tenant_backup_runs SET status = ?, finished_at = ?, export_request_id = ?, object_key = ?, "
                            + "package_bytes = ?, encrypted_bytes = ?, encrypted_sha256 = ?, error_message = ? "
                            + "WHERE id = ? AND status = 'RUNNING'")) {
                ps.setString(1, status.name());
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                if (exportRequestId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, exportRequestId);
                ps.setString(4, objectKey);
                if (packageBytes == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, packageBytes);
                if (encryptedBytes == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, encryptedBytes);
                ps.setString(7, encryptedSha256);
                ps.setString(8, error == null ? null : truncate(error, 2000));
                ps.setLong(9, id);
                if (ps.executeUpdate() != 1) throw new IllegalStateException("执行记录不存在或已结束: " + id);
            }
            return null;
        });
    }

    /** 超过 {@code olderThan} 还停在 RUNNING 的记录 (进程中途被重启): 标 FAILED, 返回条数。 */
    public int failStaleRuns(LocalDateTime olderThan) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tenant_backup_runs SET status = 'FAILED', finished_at = ?, "
                            + "error_message = '执行中断 (进程重启或超时), 未完成' WHERE status = 'RUNNING' AND started_at < ?")) {
                ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.setTimestamp(2, Timestamp.valueOf(olderThan));
                return ps.executeUpdate();
            }
        });
    }

    public Optional<Run> findRun(long id) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + RUN_COLUMNS + " FROM tenant_backup_runs WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(run(rs)) : Optional.<Run>empty();
                }
            }
        });
    }

    public List<Run> listRuns(String factoryId, int limit) {
        return inTx(conn -> {
            List<Run> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + RUN_COLUMNS + " FROM tenant_backup_runs WHERE factory_id = ? "
                            + "ORDER BY started_at DESC, id DESC LIMIT ?")) {
                ps.setString(1, factoryId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(run(rs));
                }
            }
            return out;
        });
    }

    /** 最近一次成功 (任何触发来源)。 */
    public Optional<Run> lastSuccess(String factoryId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + RUN_COLUMNS + " FROM tenant_backup_runs WHERE factory_id = ? AND status = 'SUCCEEDED' "
                            + "ORDER BY started_at DESC, id DESC LIMIT 1")) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(run(rs)) : Optional.<Run>empty();
                }
            }
        });
    }

    public boolean hasRunning(String factoryId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM tenant_backup_runs WHERE factory_id = ? AND status = 'RUNNING' LIMIT 1")) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** 平台列表: 全部工厂 (含没有套餐的), 加上最近一次执行与最近一次成功。 */
    public List<FactoryOverview> overview() {
        return inTx(conn -> {
            List<FactoryOverview> out = new ArrayList<>();
            String sql = "SELECT f.id, f.name, f.created_at, "
                    + "p.factory_id, p.enabled, p.frequency, p.retention_days, p.include_attachments, p.key_holder, "
                    + "p.customer_recipient, p.source, p.updated_by_admin_id, p.created_at, p.updated_at, "
                    + "lr.id AS lr_id, ls.id AS ls_id "
                    + "FROM factories f LEFT JOIN tenant_backup_plans p ON p.factory_id = f.id "
                    + "LEFT JOIN LATERAL (SELECT id FROM tenant_backup_runs r WHERE r.factory_id = f.id "
                    + "  ORDER BY started_at DESC, id DESC LIMIT 1) lr ON TRUE "
                    + "LEFT JOIN LATERAL (SELECT id FROM tenant_backup_runs r WHERE r.factory_id = f.id "
                    + "  AND r.status = 'SUCCEEDED' ORDER BY started_at DESC, id DESC LIMIT 1) ls ON TRUE "
                    + "ORDER BY f.id";
            List<long[]> runIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Plan plan = rs.getString(4) == null ? null : new Plan(rs.getString(4), rs.getBoolean(5),
                            Frequency.valueOf(rs.getString(6)), rs.getInt(7), rs.getBoolean(8),
                            KeyHolder.valueOf(rs.getString(9)), rs.getString(10), Source.valueOf(rs.getString(11)),
                            longOrNull(rs, 12), time(rs, 13), time(rs, 14));
                    out.add(new FactoryOverview(rs.getString(1), rs.getString(2), time(rs, 3), plan, null, null));
                    Long lr = longOrNull(rs, 15);
                    Long ls = longOrNull(rs, 16);
                    runIds.add(new long[]{lr == null ? -1 : lr, ls == null ? -1 : ls});
                }
            }
            for (int i = 0; i < out.size(); i++) {
                FactoryOverview o = out.get(i);
                out.set(i, new FactoryOverview(o.factoryId(), o.factoryName(), o.factoryCreatedAt(), o.plan(),
                        runById(conn, runIds.get(i)[0]), runById(conn, runIds.get(i)[1])));
            }
            return out;
        });
    }

    public Optional<String> factoryName(String factoryId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM factories WHERE id = ?")) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /** 调度用的平台管理员: 必须存在且 active, 返回其平台角色。 */
    public Optional<String> activePlatformAdminRole(long adminId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT platform_role FROM platform_admins WHERE id = ? AND status = 'active'")) {
                ps.setLong(1, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    private Run runById(Connection conn, long id) throws SQLException {
        if (id < 0) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + RUN_COLUMNS + " FROM tenant_backup_runs WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? run(rs) : null;
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private static Plan plan(ResultSet rs) throws SQLException {
        return new Plan(rs.getString(1), rs.getBoolean(2), Frequency.valueOf(rs.getString(3)), rs.getInt(4),
                rs.getBoolean(5), KeyHolder.valueOf(rs.getString(6)), rs.getString(7), Source.valueOf(rs.getString(8)),
                longOrNull(rs, 9), time(rs, 10), time(rs, 11));
    }

    private static Run run(ResultSet rs) throws SQLException {
        int retention = rs.getInt(12);
        Integer retentionDays = rs.wasNull() ? null : retention;
        return new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), longOrNull(rs, 5),
                RunStatus.valueOf(rs.getString(6)), time(rs, 7), time(rs, 8), longOrNull(rs, 9), rs.getString(10),
                rs.getString(11), retentionDays, rs.getString(13), longOrNull(rs, 14), longOrNull(rs, 15),
                rs.getString(16), rs.getString(17));
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime time(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toLocalDateTime();
    }

    private interface Work<T> { T run(Connection conn) throws SQLException; }

    private <T> T inTx(Work<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工厂备份表读写失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
