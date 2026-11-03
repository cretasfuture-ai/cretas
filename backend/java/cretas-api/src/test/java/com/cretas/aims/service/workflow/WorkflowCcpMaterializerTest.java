package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowCcpMaterializerTest {

    private final HaccpCheckpointRepository repository = mock(HaccpCheckpointRepository.class);
    private final WorkflowCcpMaterializer materializer = new WorkflowCcpMaterializer(repository);

    private ProductProcessWorkflowDTO definition(List<Map<String, Object>> points) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-LUZHI");
        data.put("processName", "卤制");
        if (points != null) data.put("ccpPoints", points);
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.getNodes().add(new ProductProcessWorkflowDTO.Node(
                "process:1", "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data));
        return dto;
    }

    private Map<String, Object> point() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "ccp-1");
        p.put("name", "中心温度");
        p.put("hazardType", "BIOLOGICAL");
        p.put("criticalLimitMin", 75);
        p.put("criticalLimitMax", 95.5);
        p.put("unit", "℃");
        return p;
    }

    @Test
    void publishWritesCheckpointHangingOnTheWorkProcess() {
        when(repository.findByFactoryIdAndCheckpointCode(anyString(), anyString())).thenReturn(Optional.empty());
        int written = materializer.materialize("F006", "PT-1", definition(List.of(point())));

        assertEquals(1, written);
        ArgumentCaptor<HaccpCheckpoint> saved = ArgumentCaptor.forClass(HaccpCheckpoint.class);
        verify(repository).save(saved.capture());
        assertEquals("WP-LUZHI", saved.getValue().getWorkProcessId());
        assertEquals("F006", saved.getValue().getFactoryId());
        assertEquals(0, new BigDecimal("75").compareTo(saved.getValue().getCriticalLimitMin()));
        assertEquals(0, new BigDecimal("95.5").compareTo(saved.getValue().getCriticalLimitMax()));
        assertEquals("℃", saved.getValue().getUnit());
    }

    @Test
    void republishUpdatesTheSameRowInsteadOfCreatingADuplicate() {
        when(repository.findByFactoryIdAndCheckpointCode(anyString(), anyString())).thenReturn(Optional.empty());
        materializer.materialize("F006", "PT-1", definition(List.of(point())));
        ArgumentCaptor<HaccpCheckpoint> first = ArgumentCaptor.forClass(HaccpCheckpoint.class);
        verify(repository).save(first.capture());
        String code = first.getValue().getCheckpointCode();

        // 第二次: 用同一个 (产品, 节点, 控制点) 必须算出同一个编码, 且命中已有行
        when(repository.findByFactoryIdAndCheckpointCode("F006", code)).thenReturn(Optional.of(first.getValue()));
        materializer.materialize("F006", "PT-1", definition(List.of(point())));
        verify(repository, org.mockito.Mockito.times(2)).save(any());
        verify(repository, org.mockito.Mockito.times(2)).findByFactoryIdAndCheckpointCode("F006", code);
    }

    @Test
    void noCcpPointsWritesNothing() {
        assertEquals(0, materializer.materialize("F006", "PT-1", definition(null)));
        verify(repository, never()).save(any());
    }
}
