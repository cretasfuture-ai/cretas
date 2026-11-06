package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.util.ScaleBrandMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScaleAddDeviceVisionLockContractTest {
    private final DashScopeVisionClient vision = mock(DashScopeVisionClient.class);
    private final EquipmentRepository equipment = mock(EquipmentRepository.class);
    private final SchedulingResourceWriteLock lock = mock(SchedulingResourceWriteLock.class);
    private final ScaleAddDeviceVisionTool tool = new ScaleAddDeviceVisionTool();
    private final Map<String, Object> input = Map.of("name", "owned fixture", "cameraConfig", Map.of("imageBase64", "mock-image"));

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tool, "visionClient", vision);
        ReflectionTestUtils.setField(tool, "equipmentRepository", equipment);
        ReflectionTestUtils.setField(tool, "resourceWriteLock", lock);
        ReflectionTestUtils.setField(tool, "brandMatcher", mock(ScaleBrandMatcher.class));
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
    }

    @Test
    void recognitionCompletesBeforeMutexAndEveryEquipmentReadAndSaveAreInsideIt() throws Exception {
        when(vision.isAvailable()).thenReturn(true);
        when(vision.parseScaleImage("mock-image", "铭牌")).thenReturn(
                DashScopeVisionClient.ScaleRecognitionResult.builder().success(true).confidence(0.9).build());
        when(equipment.save(any(FactoryEquipment.class))).thenAnswer(call -> {
            FactoryEquipment row = call.getArgument(0); row.setId(17L); return row;
        });
        tool.doExecute("F006", input, Map.of());
        var order = inOrder(vision, lock, equipment);
        order.verify(vision).isAvailable();
        order.verify(vision).parseScaleImage("mock-image", "铭牌");
        order.verify(lock).lockFactory("F006");
        order.verify(equipment).countByFactoryId("F006");
        order.verify(equipment).existsByFactoryIdAndEquipmentCode("F006", "SCALE-0001");
        order.verify(equipment).save(any(FactoryEquipment.class));
    }

    @Test
    void failedRecognitionDoesNotLockOrReadFactoryResourceFacts() {
        when(vision.isAvailable()).thenReturn(true);
        when(vision.parseScaleImage("mock-image", "铭牌")).thenReturn(DashScopeVisionClient.ScaleRecognitionResult.error("test failure"));
        assertThatThrownBy(() -> tool.doExecute("F006", input, Map.of())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(lock, equipment);
    }
}
