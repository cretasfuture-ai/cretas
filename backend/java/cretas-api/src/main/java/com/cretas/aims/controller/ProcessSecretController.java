package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretExternalService;
import com.cretas.aims.service.processsecret.ProcessSecretPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工艺保密管理 (方案 N01–N03) 与 B 档客户侧工艺包契约 (N06–N08)。
 * 谁能调用由 service 判定 (负责人 / 首次启用的工厂总监 / 本厂账号), 不依赖角色头衔放行。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/process-secrets")
@Tag(name = "工艺保密", description = "产品级工艺保密策略、限时授权、访问审计与客户侧工艺包")
public class ProcessSecretController {

    private final ProcessSecretPolicyService policyService;
    private final ProcessSecretExternalService externalService;

    public ProcessSecretController(ProcessSecretPolicyService policyService,
                                   ProcessSecretExternalService externalService) {
        this.policyService = policyService;
        this.externalService = externalService;
    }

    public record PolicyRequest(ProcessSecretStorageMode storageMode, Long ownerUserId, Long expectedPolicyVersion) { }

    @GetMapping("/policies")
    @Operation(summary = "本厂已设保密的产品 (只含策略元信息)")
    public ApiResponse<List<ProcessSecretPolicyService.PolicyView>> listPolicies(
            @PathVariable String factoryId, HttpServletRequest request) {
        return ApiResponse.success(policyService.listPolicies(actor(request), factoryId));
    }

    @PutMapping("/policies/{productTypeId}")
    @Operation(summary = "启用或修改产品保密策略 (首次: 工厂总监指定负责人; 之后: 仅负责人)")
    public ApiResponse<ProcessSecretPolicyService.PolicyView> upsertPolicy(
            @PathVariable String factoryId, @PathVariable String productTypeId,
            @RequestBody PolicyRequest body, HttpServletRequest request) {
        if (body == null) throw new BusinessException(400, "请求体不能为空");
        return ApiResponse.success(policyService.upsertPolicy(actor(request), factoryId, productTypeId,
                body.storageMode(), body.ownerUserId(), body.expectedPolicyVersion()));
    }

    @DeleteMapping("/policies/{productTypeId}")
    @Operation(summary = "关闭产品保密 (仅负责人; 全部授权同时撤销)")
    public ApiResponse<Void> disablePolicy(
            @PathVariable String factoryId, @PathVariable String productTypeId,
            @RequestParam Long expectedPolicyVersion, HttpServletRequest request) {
        policyService.disablePolicy(actor(request), factoryId, productTypeId, expectedPolicyVersion);
        return ApiResponse.success();
    }

    @GetMapping("/policies/{productTypeId}/grants")
    @Operation(summary = "该产品的授权记录 (仅负责人)")
    public ApiResponse<List<ProcessSecretGrant>> listGrants(
            @PathVariable String factoryId, @PathVariable String productTypeId, HttpServletRequest request) {
        return ApiResponse.success(policyService.listGrants(actor(request), factoryId, productTypeId));
    }

    @PostMapping("/policies/{productTypeId}/grants")
    @Operation(summary = "签发限时授权 (仅负责人; 查看/导出/编辑分开勾选; 必填用途与到期时间)")
    public ApiResponse<ProcessSecretGrant> createGrant(
            @PathVariable String factoryId, @PathVariable String productTypeId,
            @RequestBody ProcessSecretPolicyService.GrantRequest body, HttpServletRequest request) {
        return ApiResponse.success(policyService.createGrant(actor(request), factoryId, productTypeId, body));
    }

    @PostMapping("/grants/{grantId}/revoke")
    @Operation(summary = "撤销授权 (仅负责人; 立即生效)")
    public ApiResponse<ProcessSecretGrant> revokeGrant(
            @PathVariable String factoryId, @PathVariable Long grantId, HttpServletRequest request) {
        return ApiResponse.success(policyService.revokeGrant(actor(request), factoryId, grantId));
    }

    @GetMapping("/policies/{productTypeId}/audit-events")
    @Operation(summary = "访问与管理审计 (仅负责人; 不含工艺正文)")
    public ApiResponse<List<ProcessSecretAccessEvent>> listAuditEvents(
            @PathVariable String factoryId, @PathVariable String productTypeId,
            @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        return ApiResponse.success(policyService.listEvents(actor(request), factoryId, productTypeId, limit));
    }

    // ------------------------------------------------------------------ B 档

    @PostMapping("/external-packages")
    @Operation(summary = "登记客户侧工艺包版本 (仅负责人; 仅 CUSTOMER_HELD 产品; 只收引用与执行端公钥)")
    public ApiResponse<ProcessSecretExternalService.PackageView> registerPackage(
            @PathVariable String factoryId, @RequestBody ProcessSecretExternalService.PackageRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(externalService.registerPackage(actor(request), factoryId, body));
    }

    @GetMapping("/external-packages")
    @Operation(summary = "客户侧工艺包与云端历史副本状态")
    public ApiResponse<ProcessSecretExternalService.PackageStatusView> packageStatus(
            @PathVariable String factoryId, @RequestParam String productTypeId, HttpServletRequest request) {
        return ApiResponse.success(externalService.packageStatus(actor(request), factoryId, productTypeId));
    }

    @PostMapping("/external-results")
    @Operation(summary = "接收客户侧执行端签名的批次结果 (幂等; 待核验; 不直接改账)")
    public ApiResponse<ProcessSecretExternalService.ResultView> submitResult(
            @PathVariable String factoryId, @RequestBody ProcessSecretExternalService.ResultRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(externalService.submitResult(actor(request), factoryId, body));
    }

    private static AuthenticatedActor actor(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
    }
}
