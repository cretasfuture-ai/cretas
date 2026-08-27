package com.cretas.aims.mapper;

import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

/**
 * 造一个协作者接齐了的 {@link MaterialBatchMapper}。
 *
 * <p>{@code MaterialBatchMapper} 是 {@code @Component}，{@code locationCatalog} 走字段注入。
 * 测试里 {@code new MaterialBatchMapper()} 时 Spring 不在场，那个字段是 {@code null}，
 * 而 {@code toEntity} <b>无条件</b>调它（{@code updateEntity} 那一处则有 null 守卫，
 * 两处不对称）⇒ 直接 new 出来的 mapper 一调 {@code toEntity} 就 NPE。
 * 2026-08-27 夜间全量 6 条红即此。</p>
 *
 * <h4>⚠️ 为什么是真的 catalog 而不是 {@code mock(WarehouseLocationCatalog.class)}</h4>
 *
 * <p>裸 mock 的 {@code requireConfiguredLocation} 对<b>任何</b>输入都返回 {@code null}，
 * 于是「填了一个本厂没配置的位置」会被悄悄咽掉 —— 而那正是 {@code WarehouseLocationCatalog}
 * 存在的全部理由。真对象对 {@code null} 输入同样返回 {@code null} 且<b>不碰仓库</b>
 * （所以仓库桩不需要任何 stubbing），对非 {@code null} 输入则如实 fail closed。</p>
 *
 * <p>⇒ 以后有测试真的给 {@code storageLocation} 赋值时，它会得到真实行为，不是一个假的通过。</p>
 */
final class MaterialBatchMapperTestSupport {

    private MaterialBatchMapperTestSupport() {
    }

    /** 协作者接齐的 mapper —— 直接 new 出来的那个会在 toEntity 上 NPE。 */
    static MaterialBatchMapper wiredMapper() {
        MaterialBatchMapper mapper = new MaterialBatchMapper();
        ReflectionTestUtils.setField(mapper, "locationCatalog",
                new WarehouseLocationCatalog(mock(FactoryFeatureConfigRepository.class)));
        return mapper;
    }
}
