package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保密产品名缓存 ({@link ProcessSecretSampleFilter}): 一条消息过好几处外发出口时只查一次库;
 * 改保密设置 (保密产品集合变了) 立即生效 —— 审查指出放在守卫里的按工厂缓存会让新设的保密名晚 10 秒才脱敏;
 * 各厂互不串; 查库失败不缓存, 照样抛出 (外发据此拒绝)。
 */
class ProcessSecretNameCacheContractTest {

    private ProcessSecretAccessService access;
    private ProductTypeRepository products;
    private ProcessSecretSampleFilter filter;

    private static ProductType product(String id, String factoryId, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setName(name);
        return p;
    }

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        products = mock(ProductTypeRepository.class);
        filter = new ProcessSecretSampleFilter(access, products);
        when(access.protectedProductTypeIds("F006")).thenReturn(Set.of("p1"));
        when(products.findAllById(Set.of("p1"))).thenReturn(List.of(product("p1", "F006", "秘制酱牛肉")));
    }

    @Test
    void severalOutletsInOneMessageQueryNamesOnce() {
        for (int i = 0; i < 4; i++) {
            assertThat(filter.protectedProductNames("F006")).containsExactly("秘制酱牛肉");
        }
        verify(products, times(1)).findAllById(anyIterable());
    }

    @Test
    void newlyProtectedProductTakesEffectImmediately() {
        assertThat(filter.protectedProductNames("F006")).containsExactly("秘制酱牛肉");

        // 改保密设置: accessService 的集合立即变了 (它自己的缓存在写策略时失效)
        when(access.protectedProductTypeIds("F006")).thenReturn(Set.of("p1", "p2"));
        when(products.findAllById(Set.of("p1", "p2"))).thenReturn(List.of(
                product("p1", "F006", "秘制酱牛肉"), product("p2", "F006", "古法熏鸡")));

        assertThat(filter.protectedProductNames("F006")).containsExactlyInAnyOrder("秘制酱牛肉", "古法熏鸡");
    }

    @Test
    void factoriesDoNotShareNames() {
        // 同一组产品 id (F007 的策略里误配了别厂的 id): 名单按工厂过滤, 缓存不能把 F006 的结果给 F007
        when(access.protectedProductTypeIds("F007")).thenReturn(Set.of("p1"));

        assertThat(filter.protectedProductNames("F006")).containsExactly("秘制酱牛肉");
        assertThat(filter.protectedProductNames("F007")).isEmpty();
    }

    @Test
    void aFailedLookupIsNotCached() {
        when(products.findAllById(Set.of("p1")))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(product("p1", "F006", "秘制酱牛肉")));

        assertThatThrownBy(() -> filter.protectedProductNames("F006")).isInstanceOf(IllegalStateException.class);
        assertThat(filter.protectedProductNames("F006")).containsExactly("秘制酱牛肉");
        verify(products, times(2)).findAllById(anyIterable());
    }
}
