package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactorySecrecyConfig#isOn}: 没有行 = 默认 (关; f 跟随 a), f 三态, 工厂不明 / 读库失败一律开 (偏向保密),
 * 10 秒缓存、改了立即失效、失败不缓存。
 */
class FactorySecrecyConfigContractTest {

    static final String F = "F-SEC-1";

    private final FactorySecrecySettingRepository repository = mock(FactorySecrecySettingRepository.class);
    private final List<FactorySecrecySetting> rows = new ArrayList<>();
    private final FactorySecrecyConfig config = new FactorySecrecyConfig(repository, (org.springframework.transaction.PlatformTransactionManager) null);

    FactorySecrecyConfigContractTest() {
        when(repository.findByFactoryId(anyString())).thenAnswer(inv -> List.copyOf(rows));
    }

    private void set(SecrecyItem item, Boolean enabled) {
        rows.removeIf(r -> r.getItemCode().equals(item.name()));
        rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode(item.name()).enabled(enabled).updatedBy(1L).build());
        config.invalidate(F);
    }

    @Test
    void noRowMeansEveryItemIsOffAndTheAuditFollowsProcessSecret() {
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isFalse();
        }
        set(SecrecyItem.PROCESS_SECRET, true);
        assertThat(config.isOn(F, SecrecyItem.PROCESS_SECRET)).isTrue();
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("f 跟随 a").isTrue();
        assertThat(config.isOn(F, SecrecyItem.PLATFORM_RECIPE_BLOCK)).as("别的项不跟").isFalse();
    }

    @Test
    void theAuditItemIsTriState() {
        set(SecrecyItem.PROCESS_SECRET, false);
        set(SecrecyItem.AI_SECRECY_AUDIT, true);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("单独开").isTrue();
        set(SecrecyItem.PROCESS_SECRET, true);
        set(SecrecyItem.AI_SECRECY_AUDIT, false);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("单独关").isFalse();
        set(SecrecyItem.AI_SECRECY_AUDIT, null);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("跟随 (a 开)").isTrue();
        set(SecrecyItem.PROCESS_SECRET, false);
        assertThat(config.isOn(F, SecrecyItem.AI_SECRECY_AUDIT)).as("跟随 (a 关)").isFalse();
    }

    @Test
    void unknownFactoryIsTreatedAsOn() {
        for (String factory : new String[]{null, "", "  ", "PLATFORM", "platform"}) {
            for (SecrecyItem item : SecrecyItem.values()) {
                assertThat(config.isOn(factory, item)).as(factory + "/" + item).isTrue();
            }
        }
        assertThat(config.effectiveByCode("PLATFORM").values()).containsOnly(true);
    }

    @Test
    void aFailedReadIsTreatedAsOnAndIsNotCached() {
        when(repository.findByFactoryId(anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isTrue();
        }
        assertThat(config.effectiveByCode(F).values()).containsOnly(true);
        // 恢复后立即读到真值 (失败没进缓存)
        org.mockito.Mockito.doAnswer(inv -> List.copyOf(rows)).when(repository).findByFactoryId(anyString());
        assertThat(config.isOn(F, SecrecyItem.PROCESS_SECRET)).isFalse();
    }

    @Test
    void readsAreCachedPerFactoryAndAWriteInvalidatesThem() {
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isFalse();
        assertThat(config.isOn(F, SecrecyItem.TRAINING_FILTER)).isFalse();
        verify(repository, times(1)).findByFactoryId(F);
        // 库里变了但没清缓存: 10 秒内仍是旧值
        rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode("EGRESS_REDACTION").enabled(true).updatedBy(1L).build());
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isFalse();
        config.invalidate(F);
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).isTrue();
        verify(repository, times(2)).findByFactoryId(F);
    }

    /**
     * 审查 #2: 读者在写事务提交前开始读 (读到旧值), 写方提交后 invalidate, 读者这才把旧值写回缓存 ——
     * 带代数的缓存让这条旧值在下一次读时作废, 不会顶着旧值撑满 10 秒。
     */
    @Test
    void aReaderThatStartedBeforeTheWriteCannotPinTheOldValueInTheCache() {
        set(SecrecyItem.EGRESS_REDACTION, false);
        java.util.concurrent.atomic.AtomicBoolean interleave = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv -> {
            List<FactorySecrecySetting> old = List.copyOf(rows);   // 读者拿到的是旧行
            if (interleave.getAndSet(false)) {
                // 读的过程中写方提交了新值并 invalidate
                rows.removeIf(r -> r.getItemCode().equals("EGRESS_REDACTION"));
                rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode("EGRESS_REDACTION").enabled(true)
                        .updatedBy(1L).build());
                config.invalidate(F);
            }
            return old;
        }).when(repository).findByFactoryId(anyString());

        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).as("这一次读到的是旧值 (正常)").isFalse();
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).as("下一次不能再用那条旧缓存").isTrue();
    }

    /** 同一种交错打在工艺保密名单缓存上 (ProcessSecretAccessService.policies)。 */
    @Test
    void theProtectedProductCacheHasTheSameGuard() {
        com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository policies =
                mock(com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository.class);
        com.cretas.aims.service.processsecret.ProcessSecretAccessService access =
                new com.cretas.aims.service.processsecret.ProcessSecretAccessService(policies,
                        mock(com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository.class),
                        mock(com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder.class));
        com.cretas.aims.entity.processsecret.ProcessSecretPolicy secret = com.cretas.aims.entity.processsecret.ProcessSecretPolicy
                .builder().factoryId(F).productTypeId("P-NEW")
                .storageMode(com.cretas.aims.entity.processsecret.ProcessSecretStorageMode.CLOUD_RESTRICTED)
                .ownerUserId(1L).createdBy(1L).build();
        java.util.concurrent.atomic.AtomicBoolean interleave = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenAnswer(inv -> {
            if (interleave.getAndSet(false)) {
                access.invalidate(F);          // 读的过程中有人新设了保密产品并提交
                return List.of();              // 读者拿到的是旧名单 (空)
            }
            return List.of(secret);
        });
        assertThat(access.protectedProductTypeIds(F)).isEmpty();
        assertThat(access.protectedProductTypeIds(F)).as("下一次重读, 新保密产品生效").containsExactly("P-NEW");
    }

    /** 审查 #3: 工厂表里没有的工厂编号按开启处理 (不是按「没有配置行 = 关」); 存在性与配置同一条缓存。 */
    @Test
    void aFactoryThatDoesNotExistIsTreatedAsOn() {
        com.cretas.aims.repository.FactoryRepository factories = mock(com.cretas.aims.repository.FactoryRepository.class);
        when(factories.existsById(F)).thenReturn(true);
        when(factories.existsById("F-GHOST")).thenReturn(false);
        FactorySecrecyConfig checked = new FactorySecrecyConfig(repository, factories,
                (org.springframework.transaction.PlatformTransactionManager) null);
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(checked.isOn("F-GHOST", item)).as(item.name()).isTrue();
            assertThat(checked.isOn(F, item)).as("对照: 存在的工厂没有行 = 关 " + item).isFalse();
        }
        assertThat(checked.effectiveByCode("F-GHOST").values()).containsOnly(true);
        verify(factories, times(1)).existsById("F-GHOST");   // 进了同一条 10 秒缓存
    }

    @Test
    void allOnIsOnForEveryFactoryAndItem() {
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(FactorySecrecyConfig.allOn().isOn(F, item)).isTrue();
        }
    }
}
