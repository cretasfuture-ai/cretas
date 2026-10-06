package com.cretas.aims.repository;

import com.cretas.aims.entity.config.FactoryConfiguration;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.VoucherExportRecord;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.finance.VoucherExportRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「取最新一条」的 @Query 在多条匹配时只返回一条, 不抛 NonUniqueResultException。
 *
 * <p>同形第三例 (2026-10-06, 会话记忆那条把 F006 操作员的 AI 对话打成 500) 之后的全仓扫描:
 * {@code @Query("... ORDER BY ...")} + 单值返回 + 没有 LIMIT 的还剩这两处。
 * prod 当时: 同一账期重复导出的凭证 5 组 (5 分钟内重复导出即触发); 同厂多个已发布配置 0 个。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class LatestRowQueriesReturnOneRowQueryValidationTest {

    @Autowired
    FactoryConfigurationRepository configs;

    @Autowired
    VoucherExportRecordRepository exports;

    private static String factory() {
        return "F-LATEST-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static FactoryConfiguration config(String factory, int version, String status) {
        return FactoryConfiguration.builder()
                .factoryId(factory).configVersion(version).status(status).createdBy(1L).build();
    }

    @Test
    void latestPublishedConfigIsTheHighestVersionEvenWhenSeveralArePublished() {
        String f = factory();
        configs.saveAllAndFlush(List.of(
                config(f, 1, "PUBLISHED"), config(f, 3, "PUBLISHED"), config(f, 4, "DRAFT")));

        Optional<FactoryConfiguration> latest = configs.findLatestPublished(f);
        assertThat(latest).map(FactoryConfiguration::getConfigVersion).contains(3);
        // 对照: 只有一条 / 没有
        String g = factory();
        configs.saveAllAndFlush(List.of(config(g, 2, "PUBLISHED")));
        assertThat(configs.findLatestPublished(g)).map(FactoryConfiguration::getConfigVersion).contains(2);
        assertThat(configs.findLatestPublished(factory())).isEmpty();
    }

    private static VoucherExportRecord export(String factory, LocalDate start, LocalDate end) {
        return VoucherExportRecord.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factory).exportType("VOUCHER").targetSystem(VoucherTargetSystem.KINGDEE)
                .periodStart(start).periodEnd(end).exportedBy(1L).build();
    }

    @Test
    void recentExportReturnsOneRowWhenTheSamePeriodWasExportedTwice() {
        String f = factory();
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        VoucherExportRecord first = export(f, start, end);
        VoucherExportRecord second = export(f, start, end);
        exports.saveAllAndFlush(List.of(first, second));

        Optional<VoucherExportRecord> recent = exports.findRecentExport(
                f, "VOUCHER", start, end, LocalDateTime.now().minusHours(1));
        assertThat(recent).isPresent();
        assertThat(recent.get().getId()).isIn(first.getId(), second.getId());
        // 对照: 别的账期 / 时间窗之外都查不到
        assertThat(exports.findRecentExport(f, "VOUCHER", start.plusMonths(1), end.plusMonths(1),
                LocalDateTime.now().minusHours(1))).isEmpty();
        assertThat(exports.findRecentExport(f, "VOUCHER", start, end, LocalDateTime.now().plusHours(1))).isEmpty();
    }
}
