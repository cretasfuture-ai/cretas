package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.LineCapacityConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S4 产线产能与产品所需设备 (工厂配置入口)。插单可行性只读这里写下的配置。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/scheduling/line-capacity")
@Tag(name = "产线产能与设备", description = "按产线 (可细到产品) 设定每小时产能及单位; 按产品设定所需设备")
public class LineCapacityConfigController {

    private final LineCapacityConfigService service;

    @Data
    public static class CapacityRequest {
        @NotBlank(message = "产线不能为空")
        private String productionLineId;
        /** 为空 = 该线通用。 */
        private String productTypeId;
        @NotNull(message = "每小时产能不能为空")
        @DecimalMin(value = "0.001", message = "每小时产能必须大于 0")
        private BigDecimal capacityPerHour;
        @NotBlank(message = "产能单位不能为空")
        private String capacityUnit;
    }

    @Data
    public static class EquipmentRequest {
        @NotNull(message = "设备列表不能为空 (清空请传空数组)")
        private List<Long> equipmentIds;
    }

    public record CapacityView(Long id, String productionLineId, String productTypeId, BigDecimal capacityPerHour,
                               String capacityUnit, String source, String actorType, Long configuredBy,
                               LocalDateTime effectiveFrom) {
        static CapacityView of(ProductionLineCapacityConfig c) {
            return new CapacityView(c.getId(), c.getProductionLineId(), c.getProductTypeId(), c.getCapacityPerHour(),
                    c.getCapacityUnit().name(), c.getSource().name(), c.getActorType().name(), c.getConfiguredBy(),
                    c.getEffectiveFrom());
        }
    }

    public record EquipmentView(Long id, String productTypeId, Long equipmentId, String source, Long configuredBy) {
        static EquipmentView of(ProductEquipmentRequirement r) {
            return new EquipmentView(r.getId(), r.getProductTypeId(), r.getEquipmentId(), r.getSource().name(),
                    r.getConfiguredBy());
        }
    }

    @RequireModule("scheduling")
    @GetMapping("/capacity")
    @Operation(summary = "本厂在用的产线产能配置")
    public ApiResponse<List<CapacityView>> listCapacity(@PathVariable String factoryId) {
        return ApiResponse.success("获取成功", service.listCapacity(factoryId).stream().map(CapacityView::of).toList());
    }

    @RequirePermission({"scheduling:read_write", "production:read_write"})
    @RequireModule("scheduling")
    @PutMapping("/capacity")
    @Operation(summary = "设定产线产能", description = "同线同产品已有配置时替换 (旧行留作审计), 新值立即生效")
    public ApiResponse<CapacityView> setCapacity(@PathVariable String factoryId,
                                                 @Valid @RequestBody CapacityRequest request,
                                                 HttpServletRequest http) {
        ProductionLineCapacityConfig saved = service.setCapacity(factoryId, request.getProductionLineId(),
                request.getProductTypeId(), request.getCapacityPerHour(), request.getCapacityUnit(),
                Source.FACTORY_CONFIG, factoryUser(http));
        return ApiResponse.success("保存成功", CapacityView.of(saved));
    }

    @RequirePermission({"scheduling:read_write", "production:read_write"})
    @RequireModule("scheduling")
    @DeleteMapping("/capacity/{configId}")
    @Operation(summary = "删除一条产线产能配置")
    public ApiResponse<Void> retireCapacity(@PathVariable String factoryId, @PathVariable Long configId) {
        service.retireCapacity(factoryId, configId);
        return ApiResponse.success("已删除", null);
    }

    @RequireModule("scheduling")
    @GetMapping("/equipment")
    @Operation(summary = "本厂各产品所需设备")
    public ApiResponse<List<EquipmentView>> listEquipment(@PathVariable String factoryId) {
        return ApiResponse.success("获取成功",
                service.listEquipmentRequirements(factoryId).stream().map(EquipmentView::of).toList());
    }

    @RequirePermission({"scheduling:read_write", "production:read_write"})
    @RequireModule("scheduling")
    @PutMapping("/equipment/{productTypeId}")
    @Operation(summary = "设定产品所需设备", description = "整体替换; 空数组 = 清空")
    public ApiResponse<List<EquipmentView>> replaceEquipment(@PathVariable String factoryId,
                                                             @PathVariable String productTypeId,
                                                             @Valid @RequestBody EquipmentRequest request,
                                                             HttpServletRequest http) {
        List<ProductEquipmentRequirement> saved = service.replaceEquipment(factoryId, productTypeId,
                request.getEquipmentIds(), Source.FACTORY_CONFIG, factoryUser(http));
        return ApiResponse.success("保存成功", saved.stream().map(EquipmentView::of).toList());
    }

    /** 工厂配置页的写入人是工厂用户; 拿不到 id 时返回 null, 服务层 401 且不写。 */
    private static ActorRef factoryUser(HttpServletRequest request) {
        Long id = userId(request);
        return id == null || id <= 0 ? null : new ActorRef(AuthenticatedActorType.FACTORY_USER, id);
    }

    private static Long userId(HttpServletRequest request) {
        Object v = request.getAttribute("userId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
