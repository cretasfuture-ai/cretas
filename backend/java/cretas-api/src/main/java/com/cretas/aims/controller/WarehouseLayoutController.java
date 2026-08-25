package com.cretas.aims.controller;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
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

    private final FactoryFeatureConfigRepository featureConfigRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
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
    @RequireRole({"factory_super_admin", "warehouse_manager"})
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
            String result = pythonLLMClient.visionChat(bytes,
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
        if (location == null || location.isBlank() || location.length() > 100) {
            throw new BusinessException(400, "请选择有效的储存位置");
        }
        var batch = finishedGoodsBatchRepository.findById(batchId)
                .filter(item -> factoryId.equals(item.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "未找到当前工厂的成品批次"));
        batch.setStorageLocation(location.trim());
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
    }
}
