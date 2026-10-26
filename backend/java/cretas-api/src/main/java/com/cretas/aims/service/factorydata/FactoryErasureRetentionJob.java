package com.cretas.aims.service.factorydata;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 删除工厂后留下的审计记录到期自动清除 (口径 Q3: 6 个月)。每天一次:
 * <ol>
 *   <li>留存区 {@code factory_erasure_audit_retention} 里 retain_until 已过的行;</li>
 *   <li>已删除工厂在 {@code platform_data_access_log} 里 6 个月前的行 —— 删除操作本身 (预演、删除接口)
 *       也会被平台访问记录记一笔, 那几行在工厂删除之后才写入, 不在留存区里, 同样只留 6 个月。</li>
 * </ol>
 */
@Slf4j
@Component
public class FactoryErasureRetentionJob {

    private final DataSource dataSource;
    private final Clock clock;
    /** 业务表 (platform_data_access_log / factories) 所在 schema; 生产是 public, 测试用独立 schema。 */
    private final String schema;

    @Autowired   // 另有一个包内构造器给测试用; 不标的话 Spring 找不到该用哪个 (2026-10-26 Fresh DB 启动即失败)
    public FactoryErasureRetentionJob(@Qualifier("primaryDataSource") DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone(), "public");
    }

    FactoryErasureRetentionJob(DataSource dataSource, Clock clock, String schema) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.schema = schema;
    }

    public record Result(int retentionRows, int accessLogRows) {}

    @Scheduled(cron = "${cretas.factory-data.retention-cron:0 40 4 * * ?}")
    @SchedulerLock(name = "FactoryErasureRetentionJob.purgeExpired", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        Result r = purgeExpired();
        log.info("factory-data retention purge: retentionRows={} accessLogRows={}", r.retentionRows(), r.accessLogRows());
    }

    public Result purgeExpired() {
        LocalDateTime now = LocalDateTime.now(clock);
        try (Connection conn = dataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int a;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + FactoryAuditRetention.TABLE + " WHERE retain_until < ?")) {
                    ps.setTimestamp(1, Timestamp.valueOf(now));
                    a = ps.executeUpdate();
                }
                int b;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + FactoryDataScopeResolver.q(schema) + ".platform_data_access_log l "
                                + "WHERE l.created_at < ? AND l.target_factory_id IN ("
                                + "SELECT r.factory_id FROM factory_data_requests r WHERE r.request_type = 'ERASURE' "
                                + "AND r.status = 'SUCCEEDED') AND NOT EXISTS (SELECT 1 FROM "
                                + FactoryDataScopeResolver.q(schema) + ".factories f WHERE f.id = l.target_factory_id)")) {
                    ps.setTimestamp(1, Timestamp.valueOf(now.minusMonths(FactoryAuditRetention.RETENTION_MONTHS)));
                    b = ps.executeUpdate();
                }
                conn.commit();
                return new Result(a, b);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审计留存到期清除失败: " + e.getMessage(), e);
        }
    }
}
