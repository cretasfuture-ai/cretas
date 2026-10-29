package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MaterialPackagingHierarchyService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 原料采购包装换算 Controller.
 *
 * 每条包装规则直接换算到唯一库存基本单位，例如 1 箱 = 10 kg、1 桶 = 18.5 kg。
 * 旧二/三级字段继续读写，供历史调用方兼容。
 *
 * @since 2026-05-06
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/material-packaging")
@RequiredArgsConstructor
@Tag(name = "原料包装换算", description = "采购包装单位到库存基本单位的多规则换算")
public class MaterialPackagingHierarchyController {

    private final MaterialPackagingHierarchyService service;
    private final MobileService mobileService;

    @GetMapping
    @Operation(summary = "列出工厂全部原料包装换算", description = "用于报表/批量管理")
    public ApiResponse<List<MaterialPackagingHierarchyDTO>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        return ApiResponse.success(service.listByFactory(factoryId));
    }

    @GetMapping("/by-material/{materialTypeId}")
    @Operation(summary = "查询某原料的包装换算", description = "无配置返回 success=true, data=null")
    public ApiResponse<MaterialPackagingHierarchyDTO> getByMaterial(
            @PathVariable String factoryId,
            @PathVariable String materialTypeId,
            @RequestParam(required = false) String supplierMaterialId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        // supplierMaterialId 可选: 带上就顺便把每条规格对应的供应商采购价推导出来
        // (采购单表单在供应商没配采购规格时会回落到这条路径, 见 PurchasePriceDerivation)。
        // S5-12: 推导出来的是供应商采购价 —— 看不到采购价的人连推导都不做 (与没传 supplierMaterialId 一样)。
        String priceSource = supplierMaterialId != null && canViewPurchasePrices(httpRequest) ? supplierMaterialId : null;
        return ApiResponse.success(
                service.getByMaterialTypeId(factoryId, materialTypeId, priceSource).orElse(null));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.security.PriceVisibility priceVisibility;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    /** 配置的采购读 (PriceVisibility); 组件缺失或认不出调用人一律按看不到 (fail closed)。 */
    private boolean canViewPurchasePrices(jakarta.servlet.http.HttpServletRequest request) {
        Object raw = request.getAttribute("userId");
        Long id = raw instanceof Long l ? l : raw instanceof Integer i ? i.longValue()
                : raw instanceof String str && !str.isBlank() ? Long.valueOf(str) : null;
        if (id == null || priceVisibility == null || userRepository == null) return false;
        return userRepository.findById(id)
                .map(u -> priceVisibility.canView(u, com.cretas.aims.security.PriceSensitive.PURCHASE))
                .orElse(false);
    }

    // 缺陷 46 (2026-09-02): 与 RawMaterialTypeController 同一批 —— 加 warehouse:read_write,
    // 使仓储角色新建/编辑原料类型时一并保存包装规格不再 403 (原来只认 production:read_write)。
    // 缺陷 46 follow-up (2026-09-04, owner 拍板): 「只让仓储主管改」—— 与
    // RawMaterialTypeController 的 create/update/delete 同一批加 @RequireRole, 见那边
    // createMaterialType 上的完整注释。角色白名单必须与那边逐字节相同。
    @RequirePermission({"production:read_write", "warehouse:read_write"})
    @PutMapping("/by-material/{materialTypeId}")
    @Operation(summary = "保存某原料的包装换算 (upsert)", description = "支持多条包装单位直接换算到库存基本单位")
    @RequireRole({"warehouse_manager", "factory_super_admin"})
    public ApiResponse<MaterialPackagingHierarchyDTO> upsert(
            @PathVariable String factoryId,
            @PathVariable String materialTypeId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody @Valid MaterialPackagingHierarchyDTO dto) {
        Long userId = extractUserId(authorization);
        return ApiResponse.success(service.upsert(factoryId, materialTypeId, dto, userId));
    }

    // 缺陷 46 (2026-09-02): 同上方 upsert —— 加 warehouse:read_write。
    // 缺陷 46 follow-up (2026-09-04): 同上方 upsert —— 加 @RequireRole。
    @RequirePermission({"production:read_write", "warehouse:read_write"})
    @DeleteMapping("/by-material/{materialTypeId}")
    @Operation(summary = "删除某原料的包装换算", description = "软删除")
    @RequireRole({"warehouse_manager", "factory_super_admin"})
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String materialTypeId) {
        service.deleteByMaterialTypeId(factoryId, materialTypeId);
        return ApiResponse.success();
    }

    private Long extractUserId(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) {
            return null;
        }
        try {
            String token = TokenUtils.extractToken(authorization);
            return mobileService.getUserFromToken(token).getId();
        } catch (Exception e) {
            log.warn("无法从 token 提取 userId: {}", e.getMessage());
            return null;
        }
    }
}
