package com.cretas.aims.service.platform;

import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.dto.platform.PlatformFactoryMenuDTO;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.permission.PermissionModuleCatalog;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

@Service
@RequiredArgsConstructor
public class PlatformFactoryMenuService {
    // Generated from the actual Web menu tree. A Web contract test rejects drift.
    private static final Map<String, Set<String>> MENU_PATHS = loadMenuPaths();
    private static final Set<String> MODULES = MENU_PATHS.keySet();
    private final FactoryService factoryService;
    private final FactoryFeatureConfigRepository repository;
    private final JwtUtil jwtUtil;
    private final FactoryFeatureMenuWriteLock writeLock;

    public PlatformFactoryMenuDTO.Config get(String factoryId) {
        FactoryDTO factory = factoryService.getFactoryById(factoryId);
        Map<String, FactoryFeatureConfig> rows = repository.findByFactoryIdAndDeletedAtIsNull(factoryId).stream()
                .filter(r -> MODULES.contains(webModuleId(r.getModuleId())) || "smartbi".equals(r.getModuleId()))
                .collect(Collectors.toMap(r -> webModuleId(r.getModuleId()), r -> r,
                        (a, b) -> "restaurant_buy".equals(b.getModuleId()) ? b : a));
        List<PlatformFactoryMenuDTO.Module> modules = MODULES.stream().sorted().map(id -> {
            FactoryFeatureConfig row = rows.get(id);
            if (row == null && "analytics".equals(id)) row = rows.get("smartbi");
            Map<String,Object> c = row == null || row.getConfig() == null ? Map.of() : row.getConfig();
            boolean visible = !"analytics".equals(id) || !"FACTORY".equalsIgnoreCase(factory.getType());
            if ("analytics".equals(id) && c.containsKey("showInSidebar")) visible = Boolean.TRUE.equals(c.get("showInSidebar"));
            if (c.containsKey("menuVisible")) visible = Boolean.TRUE.equals(c.get("menuVisible"));
            List<String> disabled = list(c.get("disabledMenuPaths"));
            boolean configured = c.containsKey("menuVisible") || c.containsKey("showInSidebar") || c.containsKey("disabledMenuPaths");
            return PlatformFactoryMenuDTO.Module.builder().moduleId(id).moduleName(moduleLabel(id)).visible(visible).configured(configured).disabledMenuPaths(disabled).build();
        }).toList();
        return PlatformFactoryMenuDTO.Config.builder().factoryId(factory.getId()).factoryName(factory.getName()).modules(modules).build();
    }

    @Transactional
    public PlatformFactoryMenuDTO.Config update(String factoryId, String moduleId, PlatformFactoryMenuDTO.Update input) {
        writeLock.lock(factoryId);
        if (!MODULES.contains(moduleId)) throw new BusinessException(400, "不支持的网页模块: " + moduleId);
        if (input.getVisible() == null) throw new BusinessException(400, "缺少菜单显示状态");
        List<String> disabled = input.getDisabledMenuPaths() == null ? List.of() : new ArrayList<>(input.getDisabledMenuPaths());
        Set<String> allowedPaths = MENU_PATHS.getOrDefault(moduleId, Set.of());
        if (disabled.stream().anyMatch(p -> p == null || !allowedPaths.contains(p))) {
            throw new BusinessException(400, "存在不受支持的菜单路径");
        }
        factoryService.getFactoryById(factoryId);
        String storageId = storageModuleId(moduleId);
        Optional<FactoryFeatureConfig> existing = repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, storageId);
        if (existing.isEmpty() && "analytics".equals(moduleId)) {
            existing = repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, "smartbi");
        }
        FactoryFeatureConfig row = existing.orElseGet(() -> FactoryFeatureConfig.builder().factoryId(factoryId).moduleId(storageId).moduleName(moduleLabel(moduleId)).enabled(true).config(new HashMap<>()).build());
        Map<String,Object> merged = new HashMap<>(row.getConfig() == null ? Map.of() : row.getConfig());
        merged.put("menuVisible", input.getVisible()); merged.put("disabledMenuPaths", disabled);
        if ("analytics".equals(moduleId)) merged.put("showInSidebar", input.getVisible());
        row.setConfig(merged); repository.save(row);
        return get(factoryId);
    }

    public PlatformFactoryMenuDTO.ViewContext issueViewContext(String factoryId, Long actorId, String username, String role) {
        if (factoryId == null || !factoryId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) {
            throw new BusinessException(400, "工厂标识不符合可审计查看范围，无法签发查看凭证");
        }
        FactoryDTO factory = factoryService.getFactoryById(factoryId);
        String token = jwtUtil.generatePlatformFactoryViewToken(actorId, username, role, factoryId);
        return PlatformFactoryMenuDTO.ViewContext.builder().factoryId(factoryId).factoryName(factory.getName()).factoryType(factory.getType()).accessToken(token).expiresIn(900).readOnly(true).build();
    }
    private static List<String> list(Object value) { return value instanceof Collection<?> c ? c.stream().filter(Objects::nonNull).map(String::valueOf).toList() : List.of(); }

    private static Map<String, Set<String>> loadMenuPaths() {
        try (InputStream input = PlatformFactoryMenuService.class.getResourceAsStream("/platform/factory-menu-paths.json")) {
            if (input == null) throw new IllegalStateException("Missing Web factory menu catalog");
            Map<String, List<String>> raw = new ObjectMapper().readValue(input, new TypeReference<>() {});
            return raw.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        } catch (IOException error) {
            throw new UncheckedIOException("Invalid Web factory menu catalog", error);
        }
    }

    private static String moduleLabel(String id) {
        return switch (id) {
            case "restaurantFinance" -> "餐饮财务";
            case "restaurantHr" -> "餐饮人事";
            case "restaurantMarketing" -> "餐饮营销";
            case "restaurantOps" -> "餐饮运营";
            case "restaurantProcurement" -> "餐饮采购";
            default -> PermissionModuleCatalog.label(id);
        };
    }

    // The Web permission name exceeds the existing 20-character storage limit.
    // This menu-only alias avoids changing business module IDs or schema.
    private static String storageModuleId(String id) {
        return "restaurantProcurement".equals(id) ? "restaurant_buy" : id;
    }

    private static String webModuleId(String id) {
        return "restaurant_buy".equals(id) ? "restaurantProcurement" : id;
    }
}
