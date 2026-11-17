package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchListFilter;
import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.repository.intent.FlywheelMasterDataRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-17 F006「现在牛前腱还有多少库存？」回了全部 84 条批次 (含「未知原料」): 原料批次查询只认原料类型编号,
 * 说了原料名也不筛。真的: 工具、实体解析器; 桩: 服务与仓库。
 */
@DisplayName("原料批次查询: 说了原料名就按它筛, 不退回全部")
class MaterialBatchQueryMaterialNameTest {

    private static final String FACTORY = "F006";

    private final MaterialBatchService service = mock(MaterialBatchService.class);
    private final FactoryEntityDirectoryRepository directory = mock(FactoryEntityDirectoryRepository.class);
    private final MaterialBatchQueryTool tool = new MaterialBatchQueryTool();

    MaterialBatchQueryMaterialNameTest() {
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        ReflectionTestUtils.setField(tool, "entityResolver",
                new FactoryEntityResolver(directory, mock(FlywheelMasterDataRepository.class)));
        when(directory.findMaterialRows(FACTORY)).thenReturn(List.<Object[]>of(
                new Object[]{"RMT-1", "牛前腱", "M001", "肉类", "kg", true},
                new Object[]{"RMT-2", "牛腩", "M002", "肉类", "kg", true}));
        PageResponse<com.cretas.aims.dto.material.MaterialBatchDTO> page = new PageResponse<>();
        page.setContent(List.of());
        page.setTotalElements(0L);
        page.setTotalPages(0);
        page.setSize(10);
        when(service.getMaterialBatchList(eq(FACTORY), any(), any())).thenReturn(page);
    }

    private Map<String, Object> run(String materialName) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("materialName", materialName);
        return tool.doExecute(FACTORY, params, Map.of());
    }

    @Test
    @DisplayName("「牛前腱」→ 按它的原料类型筛 (分页之前交给数据库)")
    void materialNameFiltersByItsType() throws Exception {
        run("牛前腱");
        ArgumentCaptor<MaterialBatchListFilter> filter = ArgumentCaptor.forClass(MaterialBatchListFilter.class);
        verify(service).getMaterialBatchList(eq(FACTORY), any(), filter.capture());
        assertThat(filter.getValue().materialTypeId()).isEqualTo("RMT-1");
    }

    @Test
    @DisplayName("本厂没有这种原料 → 说没有, 不查全部批次")
    void unknownMaterialDoesNotListEverything() throws Exception {
        Map<String, Object> result = run("羊排");
        verify(service, never()).getMaterialBatchList(any(), any(), any());
        assertThat(String.valueOf(result.get("message"))).contains("羊排").contains("没有");
    }
}
