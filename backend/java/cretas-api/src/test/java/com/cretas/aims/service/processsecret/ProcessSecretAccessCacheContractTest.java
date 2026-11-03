package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审查跟进 2a / 2b: 名单缓存与「库里有没有任何保密策略」缓存。
 * <ul>
 *   <li>查一个不存在 (或没有策略) 的工厂编号不会留下永久条目: 名单条目过期后被清掉, 代数表只在真实写 (invalidate) 时建条目;</li>
 *   <li>noProcessSecretPolicyAnywhere: 结果缓存、任一工厂 invalidate 后重查、查库出错返回 false 且不缓存。</li>
 * </ul>
 */
class ProcessSecretAccessCacheContractTest {

    ProcessSecretPolicyRepository policies;
    final AtomicLong nanos = new AtomicLong();
    final Ticker ticker = nanos::get;
    ProcessSecretAccessService access;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        access = new ProcessSecretAccessService(policies, mock(ProcessSecretGrantRepository.class),
                mock(ProcessSecretAuditRecorder.class), ticker);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicLong> generations() {
        return (Map<String, AtomicLong>) ReflectionTestUtils.getField(access, "generations");
    }

    @Test
    void lookingUpUnknownFactoriesLeavesNoPermanentEntry() {
        for (int i = 0; i < 50; i++) {
            assertThat(access.protectedProductTypeIds("F-GHOST-" + i)).isEmpty();
        }
        assertThat(generations()).as("读不建代数条目 (只有 invalidate 建)").isEmpty();
        assertThat(access.cachedFactoryCount()).as("名单缓存先留着 (15 秒内复用)").isEqualTo(50);

        nanos.addAndGet(ProcessSecretAccessService.PROTECTED_SET_TTL.plusSeconds(1).toNanos());
        assertThat(access.cachedFactoryCount()).as("过期后被清掉, 不是永久条目").isZero();
    }

    @Test
    void invalidateCreatesAGenerationAndForcesARereadForThatFactoryOnly() {
        ProcessSecretPolicy secret = new ProcessSecretPolicy();
        secret.setFactoryId("F-A");
        secret.setProductTypeId("P-1");
        secret.setStorageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        when(policies.findByFactoryIdOrderByProductTypeIdAsc("F-A")).thenReturn(List.of(), List.of(secret));

        assertThat(access.protectedProductTypeIds("F-A")).isEmpty();
        assertThat(access.protectedProductTypeIds("F-A")).as("缓存命中").isEmpty();
        verify(policies, times(1)).findByFactoryIdOrderByProductTypeIdAsc("F-A");

        access.invalidate("F-A");
        assertThat(generations()).containsOnlyKeys("F-A");
        assertThat(access.protectedProductTypeIds("F-A")).containsExactly("P-1");
        verify(policies, times(2)).findByFactoryIdOrderByProductTypeIdAsc("F-A");
    }

    @Test
    void noPolicyAnywhereIsCachedAndInvalidatedByAnyFactory() {
        when(policies.count()).thenReturn(0L, 1L);
        assertThat(access.noProcessSecretPolicyAnywhere()).isTrue();
        assertThat(access.noProcessSecretPolicyAnywhere()).as("缓存命中").isTrue();
        verify(policies, times(1)).count();

        access.invalidate("F-ANY");                       // 任一工厂写策略 → 全局判断作废
        assertThat(access.noProcessSecretPolicyAnywhere()).as("重查, 现在有策略了").isFalse();
        verify(policies, times(2)).count();
    }

    @Test
    void aFailedCountIsFalseAndNotCached() {
        when(policies.count()).thenThrow(new IllegalStateException("db down")).thenReturn(0L);
        assertThat(access.noProcessSecretPolicyAnywhere()).as("读失败 → false (照常逐行判断, 不走整份跳过)").isFalse();
        assertThat(access.noProcessSecretPolicyAnywhere()).as("失败没进缓存, 下次重查").isTrue();
        verify(policies, times(2)).count();
    }
}
