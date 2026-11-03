package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * 测试辅助: 训练样本名单的加载走 {@link FactoryNameDirectoryRepository#findProtectedProductRows} (原生查询, 含已软删除
 * 的保密产品, 复审 N2)。已有测试用 {@code ProductTypeRepository.findAllById} 的桩描述保密产品 —— 这里让原生查询的桩
 * 照同一份产品数据回答 (按工厂过滤, 与 SQL 的 {@code WHERE factory_id = :factoryId} 一致), 测试意图不变。
 */
public final class TrainingListTestSupport {

    private TrainingListTestSupport() {
    }

    public static FactoryNameDirectoryRepository directoryBackedBy(ProductTypeRepository products) {
        FactoryNameDirectoryRepository directory = mock(FactoryNameDirectoryRepository.class);
        org.mockito.Mockito.lenient().when(directory.findProtectedProductRows(anyString(), any())).thenAnswer(inv -> {
            String factoryId = inv.getArgument(0);
            Collection<String> ids = inv.getArgument(1);
            List<Object[]> rows = new ArrayList<>();
            Iterable<ProductType> found = products.findAllById(ids);
            if (found != null) {
                for (ProductType p : found) {
                    if (factoryId.equals(p.getFactoryId())) {
                        rows.add(new Object[]{p.getId(), p.getCode(), p.getName(), p.getCategory(), p.getProductCategory()});
                    }
                }
            }
            return rows;
        });
        return directory;
    }
}
