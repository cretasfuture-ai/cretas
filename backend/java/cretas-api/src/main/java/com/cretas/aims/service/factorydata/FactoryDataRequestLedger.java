package com.cretas.aims.service.factorydata;

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
 * 回执台账 {@code factory_data_requests}: 每次预演 / 导出 / 删除申请 / 撤销 / 删除一行。
 *
 * <p>这张表故意 ⛔ 不带外键指向 factories —— 工厂删掉之后它还要在, 证明「删过了、删了多少」。
 * 只存编号、计数、哈希和时间, 不存业务内容 (目录类别 RECEIPT: 不导出、不删除)。
 * 主库连接池关闭了自动提交, 这里每次写完显式 commit。
 */
@Component
public class FactoryDataRequestLedger {

    public enum Type { PREVIEW, EXPORT, ERASURE_REQUEST, ERASURE_CANCEL, ERASURE }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public record Row(long id, Type type, String factoryId, Status status, Long relatedRequestId,
                      LocalDateTime notBefore, String summaryJson, String packageSha256, Long packageBytes,
                      String errorMessage, LocalDateTime createdAt, LocalDateTime completedAt, String triggerSource) {}

    private static final String COLUMNS = "id, request_type, factory_id, status, related_request_id, not_before, "
            + "summary::text, package_sha256, package_bytes, error_message, created_at, completed_at, trigger_source";

    private final DataSource dataSource;
    private final Clock clock;

    @Autowired   // 另有一个包内构造器给测试用; 不标的话 Spring 找不到该用哪个 (2026-10-26 Fresh DB 启动即失败)
    public FactoryDataRequestLedger(@Qualifier("primaryDataSource") DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    FactoryDataRequestLedger(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public long start(Type type, String factoryId, String factoryName, String actorType, long actorId,
                      String actorRole) {
        return start(type, factoryId, factoryName, actorType, actorId, actorRole, null, null);
    }

    public long start(Type type, String factoryId, String factoryName, String actorType, long actorId,
                      String actorRole, Long relatedRequestId, LocalDateTime notBefore) {
        return start(type, factoryId, factoryName, actorType, actorId, actorRole, relatedRequestId, notBefore,
                FactoryDataService.MANUAL_TRIGGER);
    }

    public long start(Type type, String factoryId, String factoryName, String actorType, long actorId,
                      String actorRole, Long relatedRequestId, LocalDateTime notBefore, String triggerSource) {
        String sql = "INSERT INTO factory_data_requests (request_type, factory_id, factory_name, actor_type, "
                + "actor_id, actor_role, status, related_request_id, not_before, created_at, trigger_source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type.name());
                ps.setString(2, factoryId);
                ps.setString(3, factoryName);
                ps.setString(4, actorType);
                ps.setLong(5, actorId);
                ps.setString(6, actorRole);
                ps.setString(7, Status.RUNNING.name());
                if (relatedRequestId == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, relatedRequestId);
                ps.setTimestamp(9, notBefore == null ? null : Timestamp.valueOf(notBefore));
                ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.setString(11, triggerSource);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void finish(long id, Status status, String summaryJson, String packageSha256, Long packageBytes,
                       String error) {
        String sql = "UPDATE factory_data_requests SET status = ?, summary = CAST(? AS jsonb), package_sha256 = ?, "
                + "package_bytes = ?, error_message = ?, completed_at = ? WHERE id = ?";
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setString(2, summaryJson);
                ps.setString(3, packageSha256);
                if (packageBytes == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, packageBytes);
                ps.setString(5, error == null ? null : truncate(error, 2000));
                ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.setLong(7, id);
                if (ps.executeUpdate() != 1) throw new IllegalStateException("回执行不存在: " + id);
                return null;
            }
        });
    }

    public Optional<Row> find(long id) {
        List<Row> rows = query("SELECT " + COLUMNS + " FROM factory_data_requests WHERE id = ?", id);
        return rows.stream().findFirst();
    }

    /** 某厂的全部回执, 新的在前。 */
    public List<Row> list(String factoryId) {
        return query("SELECT " + COLUMNS + " FROM factory_data_requests WHERE factory_id = ? ORDER BY id DESC",
                factoryId);
    }

    /**
     * 该厂当前有效的删除申请: 最近一条成功登记的 ERASURE_REQUEST, 且没有被撤销、也没有已成功的删除指向它。
     */
    public Optional<Row> findActiveErasureRequest(String factoryId) {
        List<Row> rows = query("SELECT " + COLUMNS + " FROM factory_data_requests r WHERE r.factory_id = ? "
                + "AND r.request_type = 'ERASURE_REQUEST' AND r.status = 'SUCCEEDED' AND NOT EXISTS ("
                + "  SELECT 1 FROM factory_data_requests x WHERE x.related_request_id = r.id AND x.status = 'SUCCEEDED' "
                + "  AND x.request_type IN ('ERASURE_CANCEL', 'ERASURE')) ORDER BY r.id DESC LIMIT 1", factoryId);
        return rows.stream().findFirst();
    }

    private List<Row> query(String sql, Object param) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Row> out = new ArrayList<>();
                    while (rs.next()) {
                        long related = rs.getLong(5);
                        boolean relatedNull = rs.wasNull();
                        long bytes = rs.getLong(9);
                        boolean bytesNull = rs.wasNull();
                        out.add(new Row(rs.getLong(1), Type.valueOf(rs.getString(2)), rs.getString(3),
                                Status.valueOf(rs.getString(4)), relatedNull ? null : related, ts(rs, 6),
                                rs.getString(7), rs.getString(8), bytesNull ? null : bytes, rs.getString(10),
                                ts(rs, 11), ts(rs, 12), rs.getString(13)));
                    }
                    return out;
                }
            }
        });
    }

    private static LocalDateTime ts(ResultSet rs, int i) throws SQLException {
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
            throw new IllegalStateException("回执台账读写失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
