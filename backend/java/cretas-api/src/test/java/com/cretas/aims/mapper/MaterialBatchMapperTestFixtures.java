package com.cretas.aims.mapper;

import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MaterialBatchMapper} 的手工装配 —— 只有一份。
 *
 * <p>#184（储存位置改成受控词表）给 mapper 加了 {@code @Autowired WarehouseLocationCatalog}
 * 字段注入。生产上由 Spring 注入，而手工 {@code new MaterialBatchMapper()} 的单测拿到的是
 * {@code null}，于是 {@code toEntity} 在 {@code setStorageLocation} 那一行 NPE。
 * 2026-08-27 夜间棘轮实测：6 条红全是这一条。</p>
 *
 * <p>⚠️ 装的是<b>真</b> {@link WarehouseLocationCatalog}，不是它的 mock。mock 会让
 * 「填了一个本厂没配过的位置」悄悄通过，而那正是 #184 要拦的东西 —— 补桩把闸补没了，
 * 比红着更糟。这里给它一个<b>空配置</b>：null/空位置照旧合法（未分配），
 * 任何非空位置一律被拒（fail closed），与生产判据逐字同源。</p>
 *
 * <p>⛔ 不要把这段复制进第二个测试类 —— 那就是「同一个东西两份，它一定会漂」。</p>
 */
final class MaterialBatchMapperTestFixtures {

    private MaterialBatchMapperTestFixtures() {
    }

    /** 带真判据、空仓储配置的 mapper（fail closed）。 */
    static MaterialBatchMapper mapperWithRealLocationCatalog() {
        FactoryFeatureConfigRepository featureConfigRepository = mock(FactoryFeatureConfigRepository.class);
        when(featureConfigRepository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        MaterialBatchMapper mapper = new MaterialBatchMapper();
        ReflectionTestUtils.setField(mapper, "locationCatalog",
                new WarehouseLocationCatalog(featureConfigRepository));
        return mapper;
    }
}
