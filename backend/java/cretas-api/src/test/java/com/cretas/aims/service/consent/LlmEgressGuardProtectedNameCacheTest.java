package com.cretas.aims.service.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保密产品名查询缓存 (同一工厂 {@link LlmEgressGuard#PROTECTED_NAME_TTL} 内复用):
 * 一条消息过好几处出口时只查一次库; 各厂互不串; 查库失败不缓存, 照样拒绝发送。
 */
class LlmEgressGuardProtectedNameCacheTest {

    private ProcessSecretSampleFilter filter;
    private LlmEgressGuard guard;

    @BeforeEach
    void setUp() {
        filter = mock(ProcessSecretSampleFilter.class);
        guard = new LlmEgressGuard(new ObjectMapper(), filter);
    }

    @Test
    void severalOutletsInOneMessageLookUpOnce() {
        when(filter.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));

        for (int i = 0; i < 4; i++) {
            assertThat(guard.texts("site-" + i, "F006", "秘制酱牛肉入库").text(0)).doesNotContain("秘制酱牛肉");
        }

        verify(filter, times(1)).protectedProductNames("F006");
    }

    @Test
    void factoriesDoNotShareCachedNames() {
        when(filter.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        when(filter.protectedProductNames("F007")).thenReturn(Set.of());

        assertThat(guard.texts("t", "F006", "秘制酱牛肉入库").text(0)).doesNotContain("秘制酱牛肉");
        assertThat(guard.texts("t", "F007", "秘制酱牛肉入库").text(0)).contains("秘制酱牛肉");
    }

    @Test
    void aFailedLookupIsNotCachedAndStillRefuses() {
        when(filter.protectedProductNames("F006"))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Set.of("秘制酱牛肉"));

        assertThat(guard.texts("t", "F006", "秘制酱牛肉入库").refused()).isTrue();
        assertThat(guard.texts("t", "F006", "秘制酱牛肉入库").text(0)).doesNotContain("秘制酱牛肉");

        verify(filter, times(2)).protectedProductNames("F006");
    }
}
