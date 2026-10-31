package com.cretas.aims.controller;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.annotation.RequirePermission;
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
        // ⚠️ 2026-08-27: 位置判据抽成了 WarehouseLocationCatalog(受控词表, Owner 裁定)。
        //    这里传**真实实现**而不是 mock —— 它读的正是本测试已经在打桩的同一个
        //    repository, 所以既不用另写一套桩, 也不会把判据本身桩成恒真。
        controller = new WarehouseLayoutController(
                repository, finishedGoodsRepository,
                new com.cretas.aims.service.warehouse.WarehouseLocationCatalog(repository),
                visionClient, new ObjectMapper());
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
        when(visionClient.visionChatWarehouse(any(byte[].class), eq("image/png"), anyString()))
                .thenReturn("{\"zones\":[{\"name\":\"冷藏区\",\"prefix\":\"LC\"}],\"racks\":[]}");

        Map<String, Object> recognized = controller.recognizeLayout("F006", Map.of(
                "imageBase64", Base64.getEncoder().encodeToString(image), "contentType", "image/png")).getData();

        assertThat(recognized).containsKeys("zones", "racks");
        verify(visionClient).visionChatWarehouse(any(byte[].class), eq("image/png"), anyString());
    }

    @Test
    void layoutEditingKeepsRoleGateWhileRecognitionUsesWarehouseWritePermission() throws Exception {
        RequireRole saveRoles = WarehouseLayoutController.class
                .getMethod("saveLayout", String.class, Map.class).getAnnotation(RequireRole.class);
        var recognition = WarehouseLayoutController.class.getMethod("recognizeLayout", String.class, Map.class);
        RequirePermission recognitionPermission = recognition.getAnnotation(RequirePermission.class);

        assertThat(saveRoles.value()).containsExactlyInAnyOrder("factory_super_admin", "warehouse_manager");
        assertThat(recognition.getAnnotation(RequireRole.class)).isNull();
        assertThat(recognitionPermission).isNotNull();
        assertThat(recognitionPermission.value()).containsExactly("warehouse:write");
    }

    /** 让 F006 拥有一份配置了 LC01/LC02 的仓储布局。 */
    private void givenConfiguredLocations(String... codes) {
        List<Map<String, Object>> locations = java.util.Arrays.stream(codes)
                .map(code -> Map.<String, Object>of("id", code, "zoneId", "z", "code", code, "name", code))
                .toList();
        FactoryFeatureConfig config = new FactoryFeatureConfig();
        config.setConfig(Map.of(
                "zones", List.of(Map.of("id", "z", "name", "冷藏区", "prefix", "LC")),
                "locations", locations,
                "floors", List.of()));
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.of(config));
    }

    @Test
    void warehouseStaffCanRelocateOwnFactoryFinishedGoodsWithoutChangingQuantity() {
        givenConfiguredLocations("LC01", "LC02");
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
        givenConfiguredLocations("LC01", "LC02");
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId("OTHER");
        when(finishedGoodsRepository.findById("fg-1")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "LC02")))
                .isInstanceOf(BusinessException.class);
        verify(finishedGoodsRepository, never()).save(any());
    }

    /**
     * 2026-08-25 生产实测: 传一个本厂没配置过的位置串, 接口返回 200 并真的写进
     * storage_location —— 只校验了「非空 + 长度」。这正是 F006 成品批次上躺着
     * "Workflow terminal output warehouse receipt" 这种来源描述而不是位置编码的
     * 通道; 后果是位置筛选与「某位置有几批」在生产数据上恒为 0。
     */
    @Test
    void rejectsRelocationToALocationThatIsNotConfiguredForThisFactory() {
        givenConfiguredLocations("LC01", "LC02");

        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1",
                Map.of("storageLocation", "ZZ99-NOT-CONFIGURED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本厂仓储配置中");
        // 拒绝必须发生在读批次之前, 且一行都不许写
        verify(finishedGoodsRepository, never()).findById(anyString());
        verify(finishedGoodsRepository, never()).save(any());
    }

    /**
     * 一个位置都没配的工厂: fail closed。
     * ⛔ 不能因为「还没配置」就放行任意字符串 —— 那等于把校验退回上线前的状态。
     */
    @Test
    void rejectsRelocationWhenTheFactoryHasNoLayoutConfiguredAtAll() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "LC01")))
                .isInstanceOf(BusinessException.class);
        verify(finishedGoodsRepository, never()).save(any());
    }

    // ───────────────────────── 平面图设计器 (2026-08-28) ─────────────────────────

    private Map<String, Object> layoutWithFloor(Map<String, Object> floor) {
        return Map.of(
                "zones", List.of(Map.of("id", "cold", "name", "冷冻库", "prefix", "LD")),
                "locations", List.of(),
                "floors", List.of(floor));
    }

    /**
     * 🔴 这条钉的是「加字段不用动后端」这个前提本身。
     *
     * 整份 layout 存进 factory_feature_config.config, saveLayout 走的是
     * new HashMap<>(layout) —— 没有白名单剔除。zoneShapes / annotations 以及
     * 将来任何新字段都必须原样回来; 一旦有人在这里加了字段白名单, 这条会红。
     */
    @Test
    @SuppressWarnings("unchecked")
    void unknownAndNewFloorFieldsSurviveTheSaveRoundTripUntouched() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.empty());
        Map<String, Object> floor = Map.of(
                "id", "f1", "name", "1 层", "racks", List.of(),
                "zoneShapes", List.of(Map.of("zoneId", "cold", "x", 10, "y", 20, "width", 300, "height", 200)),
                "annotations", List.of(Map.of("id", "a1", "kind", "text", "x", 5, "y", 6,
                        "width", 160, "height", 40, "text", "卸货口")),
                "somethingTheBackendHasNeverHeardOf", "keep-me");

        Map<String, Object> saved = controller.saveLayout("F006", layoutWithFloor(floor)).getData();

        Map<String, Object> savedFloor = ((List<Map<String, Object>>) saved.get("floors")).get(0);
        assertThat(savedFloor).isEqualTo(floor);
        assertThat(savedFloor.get("somethingTheBackendHasNeverHeardOf")).isEqualTo("keep-me");
        verify(repository).save(any(FactoryFeatureConfig.class));
    }

    /**
     * 体量护栏。前端已经各自设了上限, 但**接口是公开的** ——
     * 2026-08-25 那次 `ZZ99-NOT-CONFIGURED` 返回 200 就是「只有前端拦」的代价。
     */
    @Test
    void rejectsAnAnnotationFloodThatWouldBlowUpTheSharedJsonbConfig() {
        List<Map<String, Object>> flood = new java.util.ArrayList<>();
        for (int index = 0; index < 301; index++) {
            flood.add(Map.of("id", "a" + index, "kind", "rect", "x", 0, "y", 0, "width", 10, "height", 10));
        }
        Map<String, Object> floor = Map.of("id", "f1", "name", "1 层", "racks", List.of(), "annotations", flood);

        assertThatThrownBy(() -> controller.saveLayout("F006", layoutWithFloor(floor)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标注数量");
        verify(repository, never()).save(any(FactoryFeatureConfig.class));
    }

    @Test
    void rejectsASingleStrokeCarryingAnUnboundedNumberOfPoints() {
        List<Integer> points = new java.util.ArrayList<>();
        for (int index = 0; index < 1001; index++) points.add(index);
        Map<String, Object> floor = Map.of("id", "f1", "name", "1 层", "racks", List.of(),
                "annotations", List.of(Map.of("id", "a1", "kind", "freehand",
                        "x", 0, "y", 0, "width", 10, "height", 10, "points", points)));

        assertThatThrownBy(() -> controller.saveLayout("F006", layoutWithFloor(floor)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("折线点数");
    }

    @Test
    void rejectsAFloorGeometryThatPointsAtNoZoneAtAll() {
        // 结构层的每一块面积都必须指向一个区域 —— 没有 zoneId 的是自由图形,
        // 它属于标注层, ⛔ 不许混进结构层(混进去之后「这批货在哪个区」会命中它)。
        Map<String, Object> floor = Map.of("id", "f1", "name", "1 层", "racks", List.of(),
                "zoneShapes", List.of(Map.of("x", 1, "y", 2, "width", 3, "height", 4)));

        assertThatThrownBy(() -> controller.saveLayout("F006", layoutWithFloor(floor)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须关联一个储存区域");
    }

    @Test
    void rejectsAnAnnotationKindTheRendererCannotDraw() {
        Map<String, Object> floor = Map.of("id", "f1", "name", "1 层", "racks", List.of(),
                "annotations", List.of(Map.of("id", "a1", "kind", "hologram", "x", 0, "y", 0, "width", 10, "height", 10)));

        assertThatThrownBy(() -> controller.saveLayout("F006", layoutWithFloor(floor)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标注类型无效");
    }

    /**
     * 阴性对照 —— 没有它，上面四条「一律拒绝」也能全绿。
     *
     * 存量数据（楼层只有 racks + background，没有这两个新字段）必须照旧存得进去；
     * 而 zoneShapes / annotations 指向一个**已经不存在**的区域时也不许拒绝：
     * 那是「区域删了、图上孤块还在」的可恢复中间态，在这里 fail closed 会让用户
     * 连保存都做不了。
     */
    @Test
    void stillAcceptsLegacyFloorsAndOrphanGeometryFromADeletedZone() {
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull("F006", "WAREHOUSE_LAYOUT"))
                .thenReturn(Optional.empty());
        Map<String, Object> legacy = Map.of("id", "f0", "name", "1 层", "racks", List.of(), "background", "data:image/png;base64,AAA");
        Map<String, Object> orphan = Map.of("id", "f1", "name", "2 层", "racks", List.of(),
                "zoneShapes", List.of(Map.of("zoneId", "a-zone-that-was-deleted", "x", 1, "y", 2, "width", 3, "height", 4)),
                "annotations", List.of(Map.of("id", "a1", "kind", "freehand", "x", 0, "y", 0,
                        "width", 9, "height", 9, "points", List.of(0, 0, 9, 9))));
        Map<String, Object> layout = Map.of(
                "zones", List.of(Map.of("id", "cold", "name", "冷冻库", "prefix", "LD")),
                "locations", List.of(),
                "floors", List.of(legacy, orphan));

        assertThat(controller.saveLayout("F006", layout).getData()).containsEntry("floors", layout.get("floors"));
        verify(repository).save(any(FactoryFeatureConfig.class));
    }

    /** 阴性对照: 空串/超长仍然按原来的「请选择有效的储存位置」拒绝, 且不去读配置。 */
    @Test
    void stillRejectsBlankAndOverlongLocationsBeforeLookingAtTheLayout() {
        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择有效的储存位置");
        assertThatThrownBy(() -> controller.moveFinishedGoods("F006", "fg-1", Map.of("storageLocation", "X".repeat(300))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择有效的储存位置");
        verify(finishedGoodsRepository, never()).save(any());
    }
}
