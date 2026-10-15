package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityCatalogView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityAcceptanceRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.OperationRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.SaveDraftRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.VersionView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.VersionDetailView;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.security.RequestUserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Factory-owned canonical blueprint API.  The URL deliberately stays under the
 * existing ai-initialize factory-self-service authorization boundary.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/ai-initialize/blueprints")
@RequiredArgsConstructor
public class CanonicalFactoryBlueprintController {

    private final CanonicalFactoryBlueprintService service;

    @PostMapping("/draft")
    @Operation(summary = "保存或创建权威建厂蓝图草稿")
    public ApiResponse<BlueprintResponse> saveDraft(
            @PathVariable String factoryId,
            @RequestBody SaveDraftRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.saveDraft(
                factoryId, request, RequestUserContext.currentActor(httpRequest)));
    }

    @GetMapping("/current")
    @Operation(summary = "读取当前权威建厂蓝图")
    public ApiResponse<BlueprintResponse> current(@PathVariable String factoryId) {
        return ApiResponse.success(service.current(factoryId));
    }

    @GetMapping("/catalog")
    @Operation(summary = "读取当前版本化能力与依赖目录")
    public ApiResponse<CapabilityCatalogView> catalog(
            @PathVariable String factoryId,
            @RequestParam(required = false) String catalogVersion) {
        return ApiResponse.success(service.catalog(factoryId, catalogVersion));
    }

    @GetMapping("/{blueprintId}/versions")
    @Operation(summary = "读取不可变发布版本历史")
    public ApiResponse<List<VersionView>> versions(
            @PathVariable String factoryId, @PathVariable String blueprintId) {
        return ApiResponse.success(service.versions(factoryId, blueprintId));
    }

    @GetMapping("/{blueprintId}/versions/{version}")
    @Operation(summary = "按版本读取不可变发布快照")
    public ApiResponse<VersionDetailView> version(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @PathVariable Integer version) {
        return ApiResponse.success(service.version(factoryId, blueprintId, version));
    }

    @PostMapping("/{blueprintId}/validate")
    @Operation(summary = "服务端验证蓝图依赖，业务写入为零")
    public ApiResponse<BlueprintResponse> validate(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.validate(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }

    @PostMapping("/{blueprintId}/simulate")
    @Operation(summary = "模拟蓝图影响与差异，业务写入为零")
    public ApiResponse<BlueprintResponse> simulate(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.simulate(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }

    @PostMapping("/{blueprintId}/publish")
    @Operation(summary = "发布不可变蓝图版本，不应用配置、不发布 Workflow")
    public ApiResponse<BlueprintResponse> publish(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.publish(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }

    @PostMapping("/{blueprintId}/apply")
    @Operation(summary = "显式确认后应用已发布蓝图；Workflow 仍需单独发布")
    public ApiResponse<BlueprintResponse> apply(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.apply(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }

    @PostMapping("/{blueprintId}/accept")
    @Operation(summary = "逐能力记录人工配置验收；不重写配置、不发布 Workflow")
    public ApiResponse<BlueprintResponse> accept(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody CapabilityAcceptanceRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.accept(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }

    @PostMapping("/{blueprintId}/recovery-plan")
    @Operation(summary = "生成失败回执对应的恢复计划，不伪装自动回滚")
    public ApiResponse<BlueprintResponse> recoveryPlan(
            @PathVariable String factoryId, @PathVariable String blueprintId,
            @RequestBody OperationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.recoveryPlan(factoryId, blueprintId, request,
                RequestUserContext.currentActor(httpRequest)));
    }
}
