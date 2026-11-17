package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-17 F006 数字文员「给演示·华东食材供应商下个采购单」: 抽参给的是「华东食材供应商」(丢了「演示·」),
 * 工具按编号查不到就说「供应商 华东食材供应商 不存在」。真的: 工具、实体解析器 (本厂主数据规范化匹配);
 * 桩: 仓库。
 */
@DisplayName("供应商下单检查: 名字带「·」前缀时不说不存在")
class SupplierCanOrderCheckNameResolutionTest {

    private static final String FACTORY = "F006";
    private static final String FULL = "演示·华东食材供应商";

    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final FactoryEntityDirectoryRepository directory = mock(FactoryEntityDirectoryRepository.class);
    private final SupplierCanOrderCheckTool tool = new SupplierCanOrderCheckTool();

    SupplierCanOrderCheckNameResolutionTest() {
        ReflectionTestUtils.setField(tool, "supplierRepository", suppliers);
        SupplierQualificationRepository quals = mock(SupplierQualificationRepository.class);
        when(quals.findByFactoryIdAndSupplierIdAndStatusIn(anyString(), anyString(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(tool, "qualificationRepository", quals);
        ReflectionTestUtils.setField(tool, "entityResolver", new FactoryEntityResolver(directory,
                mock(com.cretas.aims.repository.intent.FlywheelMasterDataRepository.class)));
        when(directory.findSupplierRows(FACTORY)).thenReturn(List.<Object[]>of(
                new Object[]{"SUP-7", FULL, null, "S007", null, null, null, true},
                new Object[]{"SUP-8", "演示·华南冻品供应商", null, "S008", null, null, null, true}));
        when(suppliers.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        Supplier s = new Supplier();
        s.setId("SUP-7");
        s.setName(FULL);
        when(suppliers.findByIdAndFactoryId("SUP-7", FACTORY)).thenReturn(Optional.of(s));
    }

    private Map<String, Object> run(String supplierId) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("supplierId", supplierId);
        return tool.doExecute(FACTORY, params, Map.of());
    }

    @Test
    @DisplayName("丢了前缀的名字 → 列出包含它的候选让人选, 不说「不存在」, 也不替人选")
    void strippedNameListsTheFullNameAsACandidate() throws Exception {
        Map<String, Object> result = run("华东食材供应商");
        String message = String.valueOf(result.get("message"));
        assertThat(message).contains(FULL).doesNotContain("不存在").contains("没有替你选");
        assertThat(String.valueOf(result)).doesNotContain("SUPPLIER_NOT_FOUND");
    }

    @Test
    @DisplayName("完整名字 (带「演示·」) → 唯一命中, 按它的编号做资质检查")
    void fullNameResolvesUniquely() throws Exception {
        Map<String, Object> result = run(FULL);
        assertThat(String.valueOf(result)).contains("SUP-7").doesNotContain("SUPPLIER_NOT_FOUND");
    }

    @Test
    @DisplayName("阴性对照: 一个候选都没有 → 照旧说不存在")
    void unknownNameIsStillNotFound() throws Exception {
        Map<String, Object> result = run("西北粮油公司");
        assertThat(String.valueOf(result)).contains("SUPPLIER_NOT_FOUND");
    }
}
