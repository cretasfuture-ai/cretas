package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读旧值写回的竞争, 用两个真线程排出交错 (审查跟进 3):
 * <ol>
 *   <li>读者 A 开始读, 在仓库读里停住 (手里是旧值);</li>
 *   <li>写方提交新值并 invalidate; 读者 B 读到新值并放进缓存;</li>
 *   <li>A 继续, 把旧值写回缓存 (盖掉 B 的新值);</li>
 *   <li>下一次读必须重新加载 (旧值带着旧代数, 作废), 拿到新值 —— 不能顶着旧值撑满缓存时长。</li>
 * </ol>
 * 覆盖 {@link FactorySecrecyConfig} 与 {@link ProcessSecretAccessService} 的保密名单 (policies())。
 */
class FactorySecrecyStaleReadRaceContractTest {

    static final String F = "F-RACE";
    static final String STALE_READER = "stale-reader";

    /** 读者 A 在仓库读里停住, 直到放行。 */
    static final class Gate {
        final CountDownLatch inRead = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void pauseIfStaleReader() throws InterruptedException {
            if (STALE_READER.equals(Thread.currentThread().getName())) {
                inRead.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).as("放行超时").isTrue();
            }
        }
    }

    static <T> T runInterleaved(Gate gate, java.util.concurrent.Callable<T> staleRead, Runnable writeAndFreshRead)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, STALE_READER));
        try {
            CompletableFuture<T> stale = CompletableFuture.supplyAsync(() -> {
                try {
                    return staleRead.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool);
            assertThat(gate.inRead.await(10, TimeUnit.SECONDS)).as("读者 A 没进到仓库读").isTrue();
            writeAndFreshRead.run();
            gate.release.countDown();
            return stale.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void configReloadsAfterAStaleReaderWritesBack() throws Exception {
        FactorySecrecySettingRepository repository = mock(FactorySecrecySettingRepository.class);
        List<FactorySecrecySetting> rows = new CopyOnWriteArrayList<>();
        Gate gate = new Gate();
        when(repository.findByFactoryId(anyString())).thenAnswer(inv -> {
            List<FactorySecrecySetting> snapshot = List.copyOf(rows);
            gate.pauseIfStaleReader();
            return snapshot;
        });
        FactorySecrecyConfig config = new FactorySecrecyConfig(repository,
                (org.springframework.transaction.PlatformTransactionManager) null);
        AtomicReference<Boolean> freshRead = new AtomicReference<>();

        Boolean staleValue = runInterleaved(gate,
                () -> config.isOn(F, SecrecyItem.EGRESS_REDACTION),
                () -> {
                    rows.add(FactorySecrecySetting.builder().factoryId(F).itemCode("EGRESS_REDACTION").enabled(true)
                            .updatedBy(1L).build());
                    config.invalidate(F);
                    freshRead.set(config.isOn(F, SecrecyItem.EGRESS_REDACTION));
                });

        assertThat(staleValue).as("读者 A 拿到的是旧值 (正常)").isFalse();
        assertThat(freshRead.get()).as("读者 B 读到新值").isTrue();
        assertThat(config.isOn(F, SecrecyItem.EGRESS_REDACTION)).as("A 写回的旧值作废, 重新加载").isTrue();
        verify(repository, times(3)).findByFactoryId(F);
    }

    @Test
    void protectedProductListReloadsAfterAStaleReaderWritesBack() throws Exception {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies,
                mock(ProcessSecretGrantRepository.class), mock(ProcessSecretAuditRecorder.class));
        List<ProcessSecretPolicy> stored = new CopyOnWriteArrayList<>();
        Gate gate = new Gate();
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenAnswer(inv -> {
            List<ProcessSecretPolicy> snapshot = List.copyOf(stored);
            gate.pauseIfStaleReader();
            return snapshot;
        });
        AtomicReference<java.util.Set<String>> freshRead = new AtomicReference<>();

        java.util.Set<String> staleValue = runInterleaved(gate,
                () -> access.protectedProductTypeIds(F),
                () -> {
                    stored.add(ProcessSecretPolicy.builder().factoryId(F).productTypeId("P-NEW")
                            .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(1L).createdBy(1L).build());
                    access.invalidate(F);
                    freshRead.set(access.protectedProductTypeIds(F));
                });

        assertThat(staleValue).as("读者 A 拿到的是旧名单 (空)").isEmpty();
        assertThat(freshRead.get()).as("读者 B 读到新保密产品").containsExactly("P-NEW");
        assertThat(access.protectedProductTypeIds(F)).as("A 写回的旧名单作废, 重新加载").containsExactly("P-NEW");
        verify(policies, times(3)).findByFactoryIdOrderByProductTypeIdAsc(F);
    }
}
