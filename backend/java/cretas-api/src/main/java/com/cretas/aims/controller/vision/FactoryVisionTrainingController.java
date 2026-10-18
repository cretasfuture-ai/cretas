package com.cretas.aims.controller.vision;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.RequestUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

/** Ongoing quality work; independent of the platform factory assembly feature. */
@RestController
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "quality_manager"})
@RequireModule("quality_inspection")
@RequestMapping("/api/mobile/{factoryId}/vision-training")
public class FactoryVisionTrainingController {
    private final PythonSmartBIClient python;
    private final UserRepository users;

    @GetMapping("/templates")
    public ApiResponse<JsonNode> templates(@PathVariable String factoryId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.TEMPLATES, null, null);
    }
    @GetMapping("/recipes")
    public ApiResponse<JsonNode> recipes(@PathVariable String factoryId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.RECIPES, null, null);
    }
    @PostMapping("/preview")
    public ApiResponse<JsonNode> preview(@PathVariable String factoryId, @RequestBody JsonNode body, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.PREVIEW, null, body);
    }
    @PostMapping("/drafts")
    public ApiResponse<JsonNode> save(@PathVariable String factoryId, @RequestBody JsonNode body, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.SAVE_DRAFT, null, body);
    }
    @GetMapping("/drafts")
    public ApiResponse<JsonNode> drafts(@PathVariable String factoryId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.LIST_DRAFTS, null, null);
    }
    @GetMapping("/drafts/{projectId}")
    public ApiResponse<JsonNode> draft(@PathVariable String factoryId, @PathVariable String projectId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.DRAFT, projectId, null);
    }
    @GetMapping("/drafts/{projectId}/plan")
    public ApiResponse<JsonNode> plan(@PathVariable String factoryId, @PathVariable String projectId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.PLAN, projectId, null);
    }
    @GetMapping("/drafts/{projectId}/jobs")
    public ApiResponse<JsonNode> jobs(@PathVariable String factoryId, @PathVariable String projectId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.JOBS, projectId, null);
    }
    @PostMapping("/drafts/{projectId}/jobs")
    public ApiResponse<JsonNode> enqueue(@PathVariable String factoryId, @PathVariable String projectId,
                                         @RequestBody JsonNode body, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.ENQUEUE_JOB, projectId, body);
    }

    @GetMapping("/drafts/{projectId}/preparation")
    public ApiResponse<JsonNode> preparation(@PathVariable String factoryId, @PathVariable String projectId, HttpServletRequest request) {
        return forward(factoryId, request, VisionTrainingAction.PREPARATION, projectId, null);
    }

    @PostMapping("/drafts/{projectId}/preparation/{operation}")
    public ApiResponse<JsonNode> prepare(@PathVariable String factoryId, @PathVariable String projectId,
            @PathVariable String operation, @RequestBody JsonNode body, HttpServletRequest request) {
        VisionTrainingAction action = switch (operation) {
            case "photos" -> VisionTrainingAction.PREPARATION_PHOTOS;
            case "image" -> VisionTrainingAction.PREPARATION_IMAGE;
            case "review" -> VisionTrainingAction.PREPARATION_REVIEW;
            case "standard" -> VisionTrainingAction.PREPARATION_STANDARD;
            case "submit" -> VisionTrainingAction.PREPARATION_SUBMIT;
            case "export" -> VisionTrainingAction.PREPARATION_EXPORT;
            default -> throw new BusinessException(404, "没有这项照片准备操作");
        };
        return forward(factoryId, request, action, projectId, body);
    }

    private ApiResponse<JsonNode> forward(String factoryId, HttpServletRequest request,
                                         VisionTrainingAction action, String projectId, JsonNode body) {
        long id = RequestUserContext.currentUserId(request);
        // JwtAuthInterceptor owns these attributes. Namespace/factory checks precede
        // users.id lookup so a platform actor never falls back to a same-ID member.
        Object role = request.getAttribute("role");
        if (id <= 0 || factoryId == null || !factoryId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || !factoryId.equals(request.getAttribute("factoryId"))
                || !(role instanceof String verifiedRole) || !Set.of("factory_super_admin", "quality_manager").contains(verifiedRole)
                || AuthenticatedActor.current(request).map(AuthenticatedActor::isPlatformAdmin).orElse(false)) {
            throw new BusinessException(403, "请使用本厂管理员或质量负责人账号访问视觉训练");
        }
        User member = users.findById(id).filter(user -> Boolean.TRUE.equals(user.getIsActive())
                && factoryId.equals(user.getFactoryId()) && verifiedRole.equals(user.getRoleCode()))
                .orElseThrow(() -> new BusinessException(403, "当前账号已失去本厂视觉训练权限"));
        if (projectId != null && !projectId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new BusinessException(400, "训练项目编号无效");
        }
        try {
            return ApiResponse.success("本厂视觉训练流程已读回", python.callFactoryVisionTraining(factoryId,
                    member.getId(), verifiedRole, action, projectId, body));
        } catch (IllegalStateException exception) {
            throw new BusinessException(503, "视觉训练服务未完成请求，请稍后重试");
        }
    }
}
