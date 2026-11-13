package com.cretas.aims.service.production;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原料类型 main_category 为 null 时结单不能 NPE (原 Collectors.toMap 遇 null 值直接抛 NPE):
 * 必须给出中文原因和去哪补配置; 有大类的照常取。
 */
@DisplayName("结单批量取物料大类: 缺大类明确报错, 不 NPE")
class SettlementMainCategoryMissingContractTest {

    private static final String FACTORY_ID = "F006";
    private ProductionPlanServiceImpl service;
    private RawMaterialTypeRepository repo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        repo = mock(RawMaterialTypeRepository.class);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", repo);
    }

    private RawMaterialType type(String id, String name, String mainCategory) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setFactoryId(FACTORY_ID);
        t.setName(name);
        t.setMainCategory(mainCategory);
        return t;
    }

    @Test
    @DisplayName("main_category 为 null → 409 中文原因 + 补配置指引, 不是 NPE")
    void nullMainCategoryFailsLoudlyNotNpe() {
        when(repo.findByIdIn(anyCollection())).thenReturn(List.of(
                type("RM-1", "保水剂", null), type("RM-2", "盐", "辅料")));

        BusinessException ex = assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "batchResolveMaterialMainCategories", FACTORY_ID, Set.of("RM-1", "RM-2")));

        assertEquals("MATERIAL_MAIN_CATEGORY_MISSING", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("保水剂"), ex.getMessage());
        assertTrue(ex.getMessage().contains("缺少物料大类"), ex.getMessage());
    }

    @Test
    @DisplayName("阴性对照: 全部有大类时照常返回映射")
    void allCategorizedReturnsMap() {
        when(repo.findByIdIn(anyCollection())).thenReturn(List.of(
                type("RM-1", "猪肉", "原料"), type("RM-2", "盐", "辅料")));

        Map<String, String> map = ReflectionTestUtils.invokeMethod(
                service, "batchResolveMaterialMainCategories", FACTORY_ID, Set.of("RM-1", "RM-2"));

        assertEquals(Map.of("RM-1", "原料", "RM-2", "辅料"), map);
    }
}
