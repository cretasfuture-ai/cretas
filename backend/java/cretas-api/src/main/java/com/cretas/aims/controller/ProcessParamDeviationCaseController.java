package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.shortage.ProcessParamDeviationCaseDTO;
import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.shortage.ProcessParamDeviationCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** Source-specific handling API; generic shortage actions intentionally do not resolve this source type. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/quality/process-param-deviation/cases")
@Tag(name = "工艺参数偏离异常", description = "普通 LOW/HIGH 工艺参数偏离的处理闭环")
public class ProcessParamDeviationCaseController {

    private final ProcessParamDeviationCaseService service;

    public record AssignRequest(Long ownerUserId, Long version) { }

    public record StartVerifyRequest(Long version) { }

    public record ResolveRequest(ProcessParamDeviationCaseDetail.Resolution resolution, String note, Long version) { }

    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    @GetMapping
    @Operation(summary = "工艺参数偏离异常列表", description = "默认只显示未关闭的来源专用处理实例")
    public ApiResponse<List<ProcessParamDeviationCaseDTO.CaseView>> list(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "true") boolean openOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success("获取成功", service.list(factoryId, openOnly, page, size));
    }

    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    @GetMapping("/assignees")
    @Operation(summary = "工艺参数偏离异常负责人选项", description = "仅返回本厂有效用户的安全标识和角色，不返回联系方式")
    public ApiResponse<List<ProcessParamDeviationCaseService.Assignee>> assignees(
            @PathVariable String factoryId) {
        return ApiResponse.success("获取成功", service.assignees(factoryId));
    }

    @RequirePermission({"quality:read", "quality:read_write", "production:read", "production:read_write"})
    @GetMapping("/{caseId}")
    @Operation(summary = "工艺参数偏离异常详情", description = "只返回当前调用者可见的报工快照")
    public ApiResponse<ProcessParamDeviationCaseDTO.DetailView> detail(@PathVariable String factoryId,
                                                                         @PathVariable String caseId) {
        return ApiResponse.success("获取成功", service.detail(factoryId, caseId));
    }

    @RequirePermission({"quality:read_write", "production:read_write"})
    @PostMapping("/{caseId}/assign")
    @Operation(summary = "指定异常负责人")
    public ApiResponse<Void> assign(@PathVariable String factoryId, @PathVariable String caseId,
                                    @RequestBody AssignRequest body, HttpServletRequest request) {
        service.assign(factoryId, caseId, body.ownerUserId(), version(body.version()), actor(request, factoryId));
        return ApiResponse.success("已指定", null);
    }

    @RequirePermission({"quality:read_write", "production:read_write"})
    @PostMapping("/{caseId}/start-verify")
    @Operation(summary = "开始核查异常")
    public ApiResponse<Void> startVerify(@PathVariable String factoryId, @PathVariable String caseId,
                                         @RequestBody StartVerifyRequest body, HttpServletRequest request) {
        service.startVerify(factoryId, caseId, version(body.version()), actor(request, factoryId));
        return ApiResponse.success("已开始核查", null);
    }

    @RequirePermission({"quality:read_write", "production:read_write"})
    @PostMapping("/{caseId}/resolve")
    @Operation(summary = "填写处理结果并关闭异常", description = "OK/数据刷新不会自动关闭, 由负责人明确提交结果")
    public ApiResponse<Void> resolve(@PathVariable String factoryId, @PathVariable String caseId,
                                     @RequestBody ResolveRequest body, HttpServletRequest request) {
        service.resolve(factoryId, caseId, body.resolution(), body.note(), version(body.version()),
                actor(request, factoryId));
        return ApiResponse.success("已关闭", null);
    }

    private static long version(Long version) {
        if (version == null) {
            throw new com.cretas.aims.exception.BusinessException(400, "缺少异常版本, 请刷新后重试");
        }
        return version;
    }

    private static ActorRef actor(HttpServletRequest request, String factoryId) {
        AuthenticatedActor current = AuthenticatedActor.current(request)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(401, "无法识别当前操作人"));
        if (current.isPlatformAdmin()) {
            if (!current.hasFactoryBypass()) {
                throw new com.cretas.aims.exception.BusinessException(403, "当前平台角色不能处理工厂的工艺参数异常");
            }
        } else if (!factoryId.equals(current.factoryId())) {
            throw new com.cretas.aims.exception.BusinessException(403, "登录账号不属于该工厂");
        }
        return new ActorRef(current.type(), current.id());
    }
}
