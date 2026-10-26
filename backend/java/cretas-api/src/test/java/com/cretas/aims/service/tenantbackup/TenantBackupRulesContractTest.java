package com.cretas.aims.service.tenantbackup;

import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Frequency;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.KeyHolder;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Plan;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Run;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.RunStatus;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Source;
import com.cretas.aims.service.tenantbackup.TenantBackupService.Health;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 到期判断 / 健康状态 / 文件名时间解析: 纯规则, 不连库。 */
class TenantBackupRulesContractTest {

    static final LocalDateTime TODAY_0120 = LocalDateTime.of(2026, 10, 28, 1, 20);

    @Test
    void dailyPlanIsDueOncePerDayAcrossTheThreeTicks() {
        Plan daily = plan(Frequency.DAILY, LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(TenantBackupService.isDue(daily, null, TODAY_0120)).isTrue();
        // 昨天 01:20 或昨天 05:20 (重试才成功) 的成功: 今天 01:20 都到期
        assertThat(TenantBackupService.isDue(daily, ok(TODAY_0120.minusDays(1)), TODAY_0120)).isTrue();
        assertThat(TenantBackupService.isDue(daily, ok(TODAY_0120.minusDays(1).plusHours(4)), TODAY_0120)).isTrue();
        // 今天 01:20 已成功: 04:20、05:20 不再跑
        assertThat(TenantBackupService.isDue(daily, ok(TODAY_0120), TODAY_0120.plusHours(3))).isFalse();
        assertThat(TenantBackupService.isDue(daily, ok(TODAY_0120), TODAY_0120.plusHours(4))).isFalse();
    }

    @Test
    void weeklyAndMonthlyWaitTheirInterval() {
        Plan weekly = plan(Frequency.WEEKLY, LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(TenantBackupService.isDue(weekly, ok(TODAY_0120.minusDays(6)), TODAY_0120)).isFalse();
        assertThat(TenantBackupService.isDue(weekly, ok(TODAY_0120.minusDays(7)), TODAY_0120)).isTrue();
        Plan monthly = plan(Frequency.MONTHLY, LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(TenantBackupService.isDue(monthly, ok(TODAY_0120.minusDays(29)), TODAY_0120)).isFalse();
        assertThat(TenantBackupService.isDue(monthly, ok(TODAY_0120.minusDays(30)), TODAY_0120)).isTrue();
    }

    @Test
    void healthDistinguishesNeverRunLateAndFailed() {
        LocalDateTime now = TODAY_0120;
        Plan daily = plan(Frequency.DAILY, now.minusHours(5));
        assertThat(TenantBackupService.health(null, null, null, now)).isEqualTo(Health.NOT_ENABLED);
        assertThat(TenantBackupService.health(daily, null, null, now)).isEqualTo(Health.WAITING_FIRST_RUN);
        Plan openedLongAgo = plan(Frequency.DAILY, now.minusDays(5));
        assertThat(TenantBackupService.health(openedLongAgo, null, null, now)).isEqualTo(Health.OVERDUE);
        assertThat(TenantBackupService.health(daily, ok(now.minusDays(3)), ok(now.minusDays(3)), now))
                .isEqualTo(Health.OVERDUE);
        assertThat(TenantBackupService.health(daily, ok(now.minusHours(1)), ok(now.minusHours(1)), now))
                .isEqualTo(Health.OK);
        Run failed = run(RunStatus.FAILED, now.minusHours(1));
        assertThat(TenantBackupService.health(daily, failed, ok(now.minusDays(1)), now)).isEqualTo(Health.LAST_FAILED);
    }

    @Test
    void objectTimestampIsReadFromTheFileNameOnly() {
        assertThat(TenantBackupService.takenAt("tenants/F006/F006_20261027_012001.zip.age"))
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 1, 20, 1));
        assertThat(TenantBackupService.takenAt("tenants/F006/notes.txt")).isNull();
        assertThat(TenantBackupService.takenAt("tenants/F006/F006_20261399_999999.zip.age")).isNull();
        assertThat(TenantBackupService.takenAt("tenants/F006/F006_20261027_012001.zip")).isNull();
    }

    @Test
    void onlyAgeX25519RecipientsAreAccepted() {
        assertThat(BackupEncryptor.validRecipient("age1ult429r6nqqe97lg385w8t70fqqlc6fwljqfx5smz77dggvm9pqsdxjg7j")).isTrue();
        assertThat(BackupEncryptor.validRecipient("age1ULT429")).isFalse();
        assertThat(BackupEncryptor.validRecipient("ssh-ed25519 AAAA")).isFalse();
        assertThat(BackupEncryptor.validRecipient(null)).isFalse();
    }

    private static Plan plan(Frequency f, LocalDateTime updatedAt) {
        return new Plan("F", true, f, 7, false, KeyHolder.PLATFORM, null, Source.PLATFORM, 1L, updatedAt, updatedAt);
    }

    private static Run ok(LocalDateTime at) {
        return run(RunStatus.SUCCEEDED, at);
    }

    private static Run run(RunStatus status, LocalDateTime at) {
        return new Run(1, "F", "BACKUP_SCHEDULER", "PLATFORM_ADMIN", 1L, status, at, at, 1L, "PLATFORM", "INDEX_ONLY",
                7, "k", 1L, 1L, "s", null);
    }
}
