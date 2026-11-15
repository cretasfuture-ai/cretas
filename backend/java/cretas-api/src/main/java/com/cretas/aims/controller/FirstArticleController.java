package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionView;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgePreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgeRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.PolicyRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.PolicyView;
import com.cretas.aims.dto.quality.FirstArticleDtos.ReasonRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardPreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardView;
import com.cretas.aims.dto.quality.FirstArticleDtos.TaskRow;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.service.quality.firstarticle.FirstArticleService;
import com.cretas.aims.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
 * 工序首检 (原型统一 E 线 BL17)。设计: docs/specs/process-first-article-inspection-design.md。
 *
 * <p>权限 (服务端, {@code PermissionInterceptor} 执行; 由 FirstArticleControllerPermissionContractTest 钉住):
 * <ul>
 *   <li>读 (开关 / 标准 / 工序首检表 / 首检单): 质量或生产的读权限;</li>
 *   <li>送检: 生产或质量的写权限 (产线组长送首件);</li>
 *   <li><b>判定 / 判定预览 / 作废、维护标准: 只认质量写权限 {@code quality:read_write}</b>;</li>
 *   <li>改工厂开关: 工厂超管 / 权限管理员 (与工厂总设置同口径)。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/first-article")
@Tag(name = "工序首检", description = "工序首件检验: 开关、标准、送检、判定")
@RequireModule("quality_inspection")
public class FirstArticleController {

    private final FirstArticleService service;

    @GetMapping("/policy")
    @Operation(summary = "本厂首检开关 (没配置 = 关)")
    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    public ApiResponse<PolicyView> policy(@PathVariable String factoryId) {
        return ApiResponse.success(service.getPolicy(factoryId));
    }

    @PutMapping("/policy")
    @Operation(summary = "改本厂首检开关: OFF 关 / WARN 提示 / BLOCK 拦截完工")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<PolicyView> updatePolicy(@PathVariable String factoryId, @RequestBody PolicyRequest body) {
        return ApiResponse.success("已保存", service.updatePolicy(factoryId, body == null ? null : body.mode(),
                SecurityUtils.currentUserId()));
    }

    @GetMapping("/standards")
    @Operation(summary = "首检标准列表")
    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    public ApiResponse<List<StandardView>> standards(@PathVariable String factoryId,
                                                     @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(service.listStandards(factoryId, includeInactive));
    }

    @PostMapping("/standards/preview")
    @Operation(summary = "保存首检标准前预览 (不写库)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<StandardPreview> previewStandard(@PathVariable String factoryId,
                                                        @RequestBody StandardRequest body) {
        return ApiResponse.success(service.previewStandard(factoryId, body));
    }

    @PostMapping("/standards")
    @Operation(summary = "保存首检标准 (id 为空新建, 否则修订)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<StandardView> saveStandard(@PathVariable String factoryId, @RequestBody StandardRequest body) {
        return ApiResponse.success("已保存", service.saveStandard(factoryId, body, SecurityUtils.currentUserId()));
    }

    @PostMapping("/standards/{id}/deactivate")
    @Operation(summary = "停用首检标准 (写原因)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<StandardView> deactivateStandard(@PathVariable String factoryId, @PathVariable Long id,
                                                        @RequestBody ReasonRequest body) {
        return ApiResponse.success("已停用", service.deactivateStandard(factoryId, id,
                body == null ? null : body.reason(), SecurityUtils.currentUserId()));
    }

    @GetMapping("/tasks")
    @Operation(summary = "工序首检表: 要首检的工序任务及首检状态")
    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    public ApiResponse<PageResponse<TaskRow>> tasks(@PathVariable String factoryId,
                                                   @RequestParam(defaultValue = "true") boolean onlyInProgress,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listTasks(factoryId, onlyInProgress, page, size));
    }

    @GetMapping("/inspections")
    @Operation(summary = "首检单列表")
    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    public ApiResponse<PageResponse<InspectionView>> inspections(
            @PathVariable String factoryId,
            @RequestParam(required = false) FirstArticleInspection.Status status,
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listInspections(factoryId, status, taskId, page, size));
    }

    @GetMapping("/inspections/{id}")
    @Operation(summary = "首检单详情")
    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    public ApiResponse<InspectionView> inspection(@PathVariable String factoryId, @PathVariable Long id) {
        return ApiResponse.success(service.getInspection(factoryId, id));
    }

    @PostMapping("/inspections")
    @Operation(summary = "送检 (开一张待判定的首检单)")
    @RequirePermission({"production:read_write", "quality:read_write"})
    public ApiResponse<InspectionView> request(@PathVariable String factoryId, @RequestBody InspectionRequest body) {
        return ApiResponse.success("已送检", service.requestInspection(factoryId, body, SecurityUtils.currentUserId()));
    }

    @PostMapping("/inspections/{id}/judge/preview")
    @Operation(summary = "判定预览: 服务端逐项算结论 (不写库)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<JudgePreview> previewJudge(@PathVariable String factoryId, @PathVariable Long id,
                                                  @RequestBody JudgeRequest body) {
        return ApiResponse.success(service.previewJudge(factoryId, id, body));
    }

    @PostMapping("/inspections/{id}/judge")
    @Operation(summary = "判定首检: 合格 / 不合格 (只认质量写权限)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<InspectionView> judge(@PathVariable String factoryId, @PathVariable Long id,
                                             @RequestBody JudgeRequest body) {
        return ApiResponse.success("已判定", service.judge(factoryId, id, body, SecurityUtils.currentUserId()));
    }

    @PostMapping("/inspections/{id}/void")
    @Operation(summary = "作废首检单 (写原因, 作废后可重新送检)")
    @RequirePermission({"quality:read_write"})
    public ApiResponse<InspectionView> voidInspection(@PathVariable String factoryId, @PathVariable Long id,
                                                      @RequestBody ReasonRequest body) {
        return ApiResponse.success("已作废", service.voidInspection(factoryId, id,
                body == null ? null : body.reason(), SecurityUtils.currentUserId()));
    }
}
