package com.cretas.aims.controller;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Factory-owned warehouse positions and floor plans, stored in existing JSONB configuration. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/warehouse/layout")
@RequiredArgsConstructor
public class WarehouseLayoutController {

    private static final String MODULE_ID = "WAREHOUSE_LAYOUT";
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Z]{2,8}$");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    // 平面图体量护栏。与前端 warehouseLayoutModel.ts 里的常量同口径 ——
    // 两份会漂，所以下限放在这里（接口是权威），前端那份只是提前给用户提示。
    private static final int MAX_FLOORS = 50;
    private static final int MAX_ZONE_SHAPES_PER_FLOOR = 200;
    private static final int MAX_ANNOTATIONS_PER_FLOOR = 300;
    /** 单条折线/自由笔的坐标个数（扁平数组，2 个数字 = 1 个点）。 */
    private static final int MAX_ANNOTATION_POINTS = 1_000;
    private static final Set<String> ANNOTATION_KINDS =
            Set.of("rect", "ellipse", "polygon", "freehand", "text");

    private final FactoryFeatureConfigRepository featureConfigRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final WarehouseLocationCatalog locationCatalog;
    private final PythonLLMClient pythonLLMClient;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> getLayout(@PathVariable String factoryId) {
        return ApiResponse.success(featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, MODULE_ID)
                .map(FactoryFeatureConfig::getConfig)
                .orElseGet(() -> Map.of("zones", List.of(), "locations", List.of(), "floors", List.of())));
    }

    @PutMapping
    @RequireRole({"factory_super_admin", "warehouse_manager"})
    public ApiResponse<Map<String, Object>> saveLayout(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> layout) {
        validateLayout(layout);
        FactoryFeatureConfig config = featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, MODULE_ID)
                .orElseGet(() -> FactoryFeatureConfig.builder()
                        .factoryId(factoryId)
                        .moduleId(MODULE_ID)
                        .moduleName("仓储位置与平面图")
                        .enabled(true)
                        .build());
        config.setConfig(new HashMap<>(layout));
        featureConfigRepository.save(config);
        return ApiResponse.success(config.getConfig());
    }

    @PostMapping("/recognize")
    @RequirePermission("warehouse:write")
    public ApiResponse<Map<String, Object>> recognizeLayout(
            @PathVariable String factoryId,
            @RequestBody Map<String, String> request) {
        String image = request.get("imageBase64");
        if (image == null || image.isBlank() || image.length() > 12_000_000) {
            throw new BusinessException(400, "请上传不超过 8MB 的仓库平面图");
        }
        String encodedImage = image.contains(",") ? image.substring(image.indexOf(',') + 1) : image;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedImage);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "上传的图片格式无效");
        }
        String prompt = """
                请分析仓库平面图或手绘草图，识别储存区域、货架、出入口和通道。
                只返回合法 JSON：{"zones":[{"id":"zone-1","name":"冷藏区","prefix":"LC","x":5,"y":5,"width":45,"height":40}],
                "racks":[{"id":"rack-1","zoneId":"zone-1","name":"1号货架","x":10,"y":10,"width":12,"height":25,"levels":3}],
                "doors":[{"label":"入口","x":5,"y":90}],"confidence":0.8}。
                x、y、width、height 均使用 0 到 100 的百分比；不确定时保留合理低置信度，不要虚构图中没有的区域。
                """;
        try {
            String result = pythonLLMClient.visionChatWarehouse(factoryId, bytes,
                    request.getOrDefault("contentType", "image/jpeg"), prompt);
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(result);
            if (!matcher.find()) {
                throw new BusinessException(422, "AI 未能识别可用的仓库区域，请调整图片后重试");
            }
            Map<String, Object> recognized = objectMapper.readValue(matcher.group(), new TypeReference<>() {});
            return ApiResponse.success(recognized);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(503, "仓库平面图 AI 识别暂不可用，请稍后重试");
        }
    }

    @PutMapping("/finished-goods/{batchId}/location")
    @RequireRole({"factory_super_admin", "warehouse_manager", "warehouse_worker"})
    public ApiResponse<Map<String, String>> moveFinishedGoods(
            @PathVariable String factoryId,
            @PathVariable String batchId,
            @RequestBody Map<String, String> request) {
        String location = request.get("storageLocation");
        if (location == null || location.isBlank()) {
            throw new BusinessException(400, "请选择有效的储存位置");
        }
        // 2026-08-25 生产实测: 这里原来只校验「非空 + 长度」, 传
        // `ZZ99-NOT-CONFIGURED` 会返回 200 并真的写进 storage_location。
        // ⇒ 目标位置必须是本厂 WAREHOUSE_LAYOUT 里已配置的编码, 否则 fail closed。
        // ⚠️ 2026-08-27: 判据搬到 WarehouseLocationCatalog —— 它此前是本类的私有方法,
        //    而全后端有 8 处 setStorageLocation, 复制第二份就是形态 D。
        String trimmed = locationCatalog.requireConfiguredLocation(factoryId, location);
        var batch = finishedGoodsBatchRepository.findById(batchId)
                .filter(item -> factoryId.equals(item.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "未找到当前工厂的成品批次"));
        batch.setStorageLocation(trimmed);
        finishedGoodsBatchRepository.save(batch);
        return ApiResponse.success(Map.of("id", batchId, "storageLocation", batch.getStorageLocation()));
    }


    private void validateLayout(Map<String, Object> layout) {
        Object zones = layout.get("zones");
        if (!(zones instanceof List<?> zoneList)) {
            throw new BusinessException(400, "储存区域配置格式无效");
        }
        if (zoneList.size() > 100) {
            throw new BusinessException(400, "储存区域数量不能超过 100 个");
        }
        Map<String, String> zonePrefixes = new HashMap<>();
        Set<String> prefixes = new HashSet<>();
        for (Object item : zoneList) {
            if (!(item instanceof Map<?, ?> zone)) {
                throw new BusinessException(400, "储存区域配置格式无效");
            }
            Object prefix = zone.get("prefix");
            if (!(prefix instanceof String text) || !PREFIX_PATTERN.matcher(text).matches()) {
                throw new BusinessException(400, "储存区域前缀必须为 2 至 8 位大写英文字母");
            }
            Object zoneId = zone.get("id");
            if (zoneId instanceof String id && !id.isBlank()) zonePrefixes.put(id, text);
            if (!prefixes.add(text)) {
                throw new BusinessException(400, "储存区域英文前缀不能重复");
            }
        }
        Object locations = layout.get("locations");
        if (locations != null && (!(locations instanceof List<?> list) || list.size() > 20_000)) {
            throw new BusinessException(400, "储存位置配置格式无效或数量超过限制");
        }
        if (locations instanceof List<?> locationList) {
            Set<String> codes = new HashSet<>();
            for (Object item : locationList) {
                if (!(item instanceof Map<?, ?> location)
                        || !(location.get("code") instanceof String code)
                        || !(location.get("zoneId") instanceof String zoneId)
                        || !zonePrefixes.containsKey(zoneId)
                        || !code.startsWith(zonePrefixes.get(zoneId))) {
                    throw new BusinessException(400, "储存位置必须关联有效区域，并使用该区域的英文前缀");
                }
                if (!codes.add(code)) {
                    throw new BusinessException(400, "储存位置编码不能重复");
                }
            }
        }
        validateFloors(layout.get("floors"));
    }

    /**
     * 楼层平面图的体量护栏（2026-08-28 随平面图设计器加入）。
     *
     * <p>为什么必须有：整份 layout 是一次 PUT 全量写进 {@code factory_feature_config.config}
     * 这个 JSONB 列的，而平面图新增了两层可以「画多少有多少」的数据 ——
     * 结构层（区域几何）和标注层（自由图形/文字）。前端已经各自设了上限，但那是
     * 前端；接口是公开的，只有这里的判据算数（同 {@code ZZ99-NOT-CONFIGURED} 那次的教训：
     * 前端拦住了不等于接口拦住了）。
     *
     * <p>⛔ 这里**只管体量和形状**，不校验 {@code zoneId} 指向的区域是否存在 ——
     * 区域被删而几何还在是一种可恢复的中间态，前端会把它显示成「已删除的区域（孤块）」
     * 让用户自己删掉。在这里 fail closed 会让用户连保存都做不了，反而卡死。
     *
     * <p>⚠️ 未知字段一律放行：{@code saveLayout} 存的是整份请求体，
     * 这里不做白名单剔除，所以前端加字段不需要动后端（本次 zoneShapes / annotations
     * 就是这么加进去的）。
     */
    private void validateFloors(Object floors) {
        if (floors == null) return;
        if (!(floors instanceof List<?> floorList)) {
            throw new BusinessException(400, "楼层平面图配置格式无效");
        }
        if (floorList.size() > MAX_FLOORS) {
            throw new BusinessException(400, "楼层数量不能超过 " + MAX_FLOORS + " 个");
        }
        for (Object item : floorList) {
            if (!(item instanceof Map<?, ?> floor)) {
                throw new BusinessException(400, "楼层平面图配置格式无效");
            }
            validateZoneShapes(floor.get("zoneShapes"));
            validateAnnotations(floor.get("annotations"));
        }
    }

    private void validateZoneShapes(Object zoneShapes) {
        if (zoneShapes == null) return;
        if (!(zoneShapes instanceof List<?> shapes) || shapes.size() > MAX_ZONE_SHAPES_PER_FLOOR) {
            throw new BusinessException(400, "平面图上的区域数量超过限制");
        }
        for (Object item : shapes) {
            if (!(item instanceof Map<?, ?> shape)
                    || !(shape.get("zoneId") instanceof String zoneId)
                    || zoneId.isBlank()) {
                throw new BusinessException(400, "平面图上的区域必须关联一个储存区域");
            }
        }
    }

    private void validateAnnotations(Object annotations) {
        if (annotations == null) return;
        if (!(annotations instanceof List<?> marks) || marks.size() > MAX_ANNOTATIONS_PER_FLOOR) {
            throw new BusinessException(400, "单层标注数量不能超过 " + MAX_ANNOTATIONS_PER_FLOOR + " 条");
        }
        for (Object item : marks) {
            if (!(item instanceof Map<?, ?> annotation)
                    || !(annotation.get("kind") instanceof String kind)
                    || !ANNOTATION_KINDS.contains(kind)) {
                throw new BusinessException(400, "平面图标注类型无效");
            }
            Object points = annotation.get("points");
            if (points != null && (!(points instanceof List<?> list) || list.size() > MAX_ANNOTATION_POINTS)) {
                throw new BusinessException(400, "单条标注的折线点数超过限制");
            }
        }
    }
}
