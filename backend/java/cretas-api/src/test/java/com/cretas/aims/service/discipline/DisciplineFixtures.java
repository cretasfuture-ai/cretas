package com.cretas.aims.service.discipline;

import com.cretas.aims.service.canvas.ThresholdResolverService;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试夹具: 用一张 key → 值 的表模拟阈值中心 (所有工厂同一份), 产出真实的 {@link FactoryDisciplinePolicy}。
 * 表里没有的 key 返回调用方兜底 = 迁移默认值, 与生产「只有全局默认行」时一致。
 */
public final class DisciplineFixtures {

    private DisciplineFixtures() {
    }

    public static FactoryDisciplinePolicy policy(Map<String, String> values) {
        ThresholdResolverService t = mock(ThresholdResolverService.class);
        when(t.getInteger(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            String v = values.get(inv.getArgument(1, String.class));
            return v != null ? Integer.valueOf(v.trim()) : inv.getArgument(2, Integer.class);
        });
        when(t.getString(anyString(), anyString(), any())).thenAnswer(inv -> {
            String v = values.get(inv.getArgument(1, String.class));
            return v != null ? v : inv.getArgument(2, String.class);
        });
        when(t.getBigDecimal(anyString(), anyString(), any())).thenAnswer(inv -> {
            String v = values.get(inv.getArgument(1, String.class));
            return v != null ? new BigDecimal(v.trim()) : inv.getArgument(2, BigDecimal.class);
        });
        return new FactoryDisciplinePolicy(t);
    }

    /** 全部默认 (= 迁移的全局默认行)。 */
    public static FactoryDisciplinePolicy defaults() {
        return policy(Map.of());
    }
}
