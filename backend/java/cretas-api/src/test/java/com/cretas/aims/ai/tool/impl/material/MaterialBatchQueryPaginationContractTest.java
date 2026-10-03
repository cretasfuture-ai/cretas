package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaterialBatchQueryPaginationContractTest {
    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void filteredPageKeepsEffectiveSizeRatherThanMatchingRowCount(int size) throws Exception {
        var service = mock(MaterialBatchService.class);
        var tool = new MaterialBatchQueryTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        var rows = List.of(MaterialBatchDTO.builder().materialTypeId("A").build(),
                MaterialBatchDTO.builder().materialTypeId("B").build());
        when(service.getMaterialBatchList(eq("F006"), any()))
                .thenReturn(PageResponse.of(rows, 1, size, 100L));
        Map<String, Object> params = new HashMap<>();
        params.put("materialTypeId", "A");
        if (size != 10) params.put("size", size); // 10 is the real tool default.
        Map<String, Object> result = tool.doExecute("F006", params, Map.of());
        assertThat((List<?>) result.get("content")).hasSize(1);
        assertThat(result.get("size")).isEqualTo(size);
        ArgumentCaptor<PageRequest> request = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).getMaterialBatchList(eq("F006"), request.capture());
        assertThat(request.getValue().getSize()).isEqualTo(size);
        assertThat(result.get("queryConditions")).isEqualTo(Map.of("materialTypeId", "A"));
    }
}
