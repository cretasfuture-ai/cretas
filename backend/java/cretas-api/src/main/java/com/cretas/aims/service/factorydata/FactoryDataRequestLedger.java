package com.cretas.aims.service.factorydata;

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
import java.util.Optional;

/**
 * 回执台账 {@code factory_data_requests}: 每次预演 / 导出 / 删除一行。
 *
 * <p>这张表故意 ⛔ 不带外键指向 factories —— 工厂删掉之后它还要在, 证明「删过了、删了多少」。
 * 只存编号、计数、哈希和时间, 不存业务内容 (目录类别 RECEIPT: 不导出、不删除)。
 * 主库连接池关闭了自动提交, 这里每次写完显式 commit。
 */
@Component
public class FactoryDataRequestLedger {

    public enum Type { PREVIEW, EXPORT, ERASURE }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public record Row(long id, Type type, String factoryId, Status status, String summaryJson,
                      LocalDateTime createdAt, LocalDateTime completedAt) {}

    private final DataSource dataSource;
    private final Clock clock;

    public FactoryDataRequestLedger(@Qualifier("primaryDataSource") DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    FactoryDataRequestLedger(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public long start(Type type, String factoryId, String factoryName, String actorType, long actorId,
                      String actorRole) {
        String sql = "INSERT INTO factory_data_requests (request_type, factory_id, factory_name, actor_type, "
                + "actor_id, actor_role, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type.name());
                ps.setString(2, factoryId);
                ps.setString(3, factoryName);
                ps.setString(4, actorType);
                ps.setLong(5, actorId);
                ps.setString(6, actorRole);
                ps.setString(7, Status.RUNNING.name());
                ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now(clock)));
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
        String sql = "SELECT id, request_type, factory_id, status, summary::text, created_at, completed_at "
                + "FROM factory_data_requests WHERE id = ?";
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Timestamp done = rs.getTimestamp(7);
                    return Optional.of(new Row(rs.getLong(1), Type.valueOf(rs.getString(2)), rs.getString(3),
                            Status.valueOf(rs.getString(4)), rs.getString(5), rs.getTimestamp(6).toLocalDateTime(),
                            done == null ? null : done.toLocalDateTime()));
                }
            }
        });
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
            throw new IllegalStateException("回执台账写入失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
