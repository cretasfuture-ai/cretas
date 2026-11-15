package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature config endpoints accessible by authenticated App users.
 * GET: load module configs for the current factory.
 * PUT: admin can adjust configs (Issue #718 — @RequireRole added 2026-05-17).
 *
 * <p>工厂级保密配置 (Steve 2026-11-03): GET 列表末尾多一行只读的 {@code moduleId = "secrecy"},
 * 带 {@code secrecy: {CODE: bool}} (生效值, 读不到按开启)。真值在平台专属表 factory_secrecy_settings,
 * 工厂端写不了: PUT {@code /secrecy} 一律 403, 任何模块的配置里带 {@code secrecy} 键也 403;
 * 库里即使有人写了 moduleId = "secrecy" 的行也不下发 (GET 只认平台表)。前端读取一律判断 {@code === true}。</p>
 *
 * <p>外部软件接口授权 (统一原型迁移 P10): 同样的只读伪模块 {@code moduleId = "integrations"},
 * 带 {@code integrations: {blacklake: {authorized: true, lastCollectedAt, stale}}} (只列 ACTIVE 的系统, 读不到 = 空)。
 * 真值在平台专属表 factory_external_integrations; 工厂端 PUT {@code /integrations} 或配置里带 {@code integrations} 键一律 403。</p>
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/feature-config")
@RequiredArgsConstructor
public class FeatureConfigController {

    private static final Set<String> PLATFORM_OWNED_MENU_FIELDS = Set.of("menuVisible", "showInSidebar", "disabledMenuPaths");

    /** 只读的保密配置伪模块 (平台专属表, 工厂端写不了)。 */
    public static final String SECRECY_MODULE_ID = "secrecy";
    public static final String SECRECY_KEY = "secrecy";
    /** 只读的外部软件授权伪模块 (平台专属表, 工厂端写不了)。 */
    public static final String INTEGRATIONS_MODULE_ID = "integrations";
    public static final String INTEGRATIONS_KEY = "integrations";
    private static final java.util.Set<String> READ_ONLY_MODULES = java.util.Set.of(SECRECY_MODULE_ID, INTEGRATIONS_MODULE_ID);

    private final FactoryFeatureConfigRepository featureConfigRepository;
    private final FactoryFeatureMenuWriteLock writeLock;
    private final FactorySecrecyConfig secrecyConfig;
    private final ExternalIntegrationService externalIntegrations;

    @GetMapping
    public ApiResponse<List<Object>> getAll(@PathVariable String factoryId) {
        List<Object> out = new ArrayList<>();
        for (FactoryFeatureConfig config : featureConfigRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)) {
            if (!READ_ONLY_MODULES.contains(config.getModuleId())) out.add(config);
        }
        out.add(secrecyRow(factoryId));
        out.add(integrationsRow(factoryId));
        return ApiResponse.success(out);
    }

    /** 只读保密配置行: {@code secrecy} 在顶层和 {@code config} 里各放一份 (同一个 map; RN 只保留 config)。 */
    Map<String, Object> secrecyRow(String factoryId) {
        Map<String, Boolean> secrecy = secrecyConfig.effectiveByCode(factoryId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("factoryId", factoryId);
        row.put("moduleId", SECRECY_MODULE_ID);
        row.put("moduleName", "保密配置（平台设置，只读）");
        row.put("enabled", true);
        row.put("readOnly", true);
        row.put("config", Map.of(SECRECY_KEY, secrecy));
        row.put(SECRECY_KEY, secrecy);
        return row;
    }

    /** 只读外部软件授权行: 与保密行同一形状 ({@code integrations} 在顶层和 {@code config} 里各一份)。 */
    Map<String, Object> integrationsRow(String factoryId) {
        Map<String, Object> integrations = externalIntegrations.featureConfigView(factoryId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("factoryId", factoryId);
        row.put("moduleId", INTEGRATIONS_MODULE_ID);
        row.put("moduleName", "外部软件接口授权（平台设置，只读）");
        row.put("enabled", true);
        row.put("readOnly", true);
        row.put("config", Map.of(INTEGRATIONS_KEY, integrations));
        row.put(INTEGRATIONS_KEY, integrations);
        return row;
    }

    @GetMapping("/{moduleId}")
    public ApiResponse<Object> getOne(
            @PathVariable String factoryId,
            @PathVariable String moduleId) {
        if (SECRECY_MODULE_ID.equals(moduleId)) {
            return ApiResponse.success(secrecyRow(factoryId));
        }
        if (INTEGRATIONS_MODULE_ID.equals(moduleId)) {
            return ApiResponse.success(integrationsRow(factoryId));
        }
        return featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId)
                .map(cfg -> ApiResponse.<Object>success(cfg))
                .orElseThrow(() -> new BusinessException(404, "未找到该模块的功能配置: " + moduleId)
                        .withHint("请确认模块 ID 是否正确，或该模块是否已在本工厂启用"));
    }

    @PutMapping("/{moduleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactoryFeatureConfig> update(
            @PathVariable String factoryId,
            @PathVariable String moduleId,
            @RequestBody Map<String, Object> config) {
        if (SECRECY_MODULE_ID.equals(moduleId) || (config != null && config.containsKey(SECRECY_KEY))) {
            throw new BusinessException(403, "保密配置只能由平台在「工厂管理」里修改");
        }
        if (INTEGRATIONS_MODULE_ID.equals(moduleId) || (config != null && config.containsKey(INTEGRATIONS_KEY))) {
            throw new BusinessException(403, "外部软件接口授权只能由平台在「工厂管理」里修改");
        }
        writeLock.lock(factoryId);
        var existing = featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId);
        if (existing.isEmpty()) {
            throw new BusinessException(400, "未找到该模块的功能配置: " + moduleId)
                    .withHint("请确认模块 ID 是否正确，或该模块是否已在本工厂启用");
        }
        FactoryFeatureConfig cfg = existing.get();
        Map<String, Object> previous = cfg.getConfig() == null ? Map.of() : cfg.getConfig();
        if (config.keySet().stream().anyMatch(PLATFORM_OWNED_MENU_FIELDS::contains)) {
            throw new BusinessException(403, "菜单显示配置只能由平台菜单管理接口修改");
        }
        // Legacy full-replacement callers must not be able to delete platform-owned
        // menu fields simply by sending an older payload without those keys.
        Map<String, Object> merged = new java.util.HashMap<>(config);
        PLATFORM_OWNED_MENU_FIELDS.forEach(key -> { if (previous.containsKey(key)) merged.put(key, previous.get(key)); });
        cfg.setConfig(merged);
        featureConfigRepository.save(cfg);
        return ApiResponse.success(cfg);
    }
}
