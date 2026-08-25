package com.cretas.aims.controller;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseLayoutControllerTest {

    @Mock private FactoryFeatureConfigRepository repository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsRepository;
    @Mock private PythonLLMClient visionClient;
    private WarehouseLayoutController controller;

    @BeforeEach
    void setUp() {
        controller = new WarehouseLayoutController(repository, finishedGoodsRepository, visionClient, new ObjectMapper());
    }

    @Test
    void returnsEmptyFactoryLayoutBeforeConfiguration() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.empty());

        assertThat(controller.getLayout("F006").getData()).containsKeys("zones", "locations", "floors");
    }

    @Test
    void createsFactoryOwnedLayoutWithoutSchemaMigration() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.empty());
        Map<String, Object> layout = Map.of(
                "zones", List.of(Map.of("id", "cold", "name", "冷藏区", "prefix", "LC")),
                "locations", List.of(Map.of("zoneId", "cold", "code", "LC01")));

        assertThat(controller.saveLayout("F006", layout).getData()).containsEntry("zones", layout.get("zones"));
        verify(repository).save(any(FactoryFeatureConfig.class));
    }

    @Test
    void rejectsLowercaseZonePrefix() {
        Map<String, Object> layout = Map.of("zones", List.of(Map.of("name", "冷藏区", "prefix", "lc")));

        assertThatThrownBy(() -> controller.saveLayout("F006", layout)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsLocationWhosePrefixDoesNotBelongToItsZone() {
        Map<String, Object> layout = Map.of(
                "zones", List.of(Map.of("id", "cold", "name", "冷藏区", "prefix", "LC")),
                "locations", List.of(Map.of("zoneId", "cold", "code", "DK01")));

        assertThatThrownBy(() -> controller.saveLayout("F006", layout)).isInstanceOf(BusinessException.class);
    }

    @Test
    void recognitionUsesRealVisionClientAndReturnsStructuredZones() {
        byte[] image = new byte[] { 1, 2, 3 };
        when(visionClient.visionChat(any(byte[].class), eq("image/png"), anyString()))
                .thenReturn("{\"zones\":[{\"name\":\"冷藏区\",\"prefix\":\"LC\"}],\"racks\":[]}");

        Map<String, Object> recognized = controller.recognizeLayout("F006", Map.of(
                "imageBase64", Base64.getEncoder().encodeToString(image), "contentType", "image/png")).getData();

        assertThat(recognized).containsKeys("zones", "racks");
    }

    @Test
    void editingAndRecognitionAreLimitedToFactoryAdminAndWarehouseManager() throws Exception {
        RequireRole saveRoles = WarehouseLayoutController.class
                .getMethod("saveLayout", String.class, Map.class).getAnnotation(RequireRole.class);
        RequireRole recognitionRoles = WarehouseLayoutController.class
                .getMethod("recognizeLayout", String.class, Map.class).getAnnotation(RequireRole.class);

        assertThat(saveRoles.value()).containsExactlyInAnyOrder("factory_super_admin", "warehouse_manager");
        assertThat(recognitionRoles.value()).containsExactlyInAnyOrder("factory_super_admin", "warehouse_manager");
    }

    @Test
    void warehouseStaffCanRelocateOwnFactoryFinishedGoodsWithoutChangingQuantity() {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("fg-1");
        batch.setFactoryId("F006");
        when(finishedGoodsRepository.findById("fg-1")).thenReturn(Optional.of(batch));

        assertThat(controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "LC02"))
                .getData()).containsEntry("storageLocation", "LC02");
        verify(finishedGoodsRepository).save(batch);
    }

    @Test
    void finishedGoodsRelocationNeverCrossesFactories() {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId("OTHER");
        when(finishedGoodsRepository.findById("fg-1")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "LC02")))
                .isInstanceOf(BusinessException.class);
        verify(finishedGoodsRepository, never()).save(any());
    }
}
