package com.cretas.aims.controller.vision;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.FactoryService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Factory assembly shares the SOP service; no models or training run in Java. */
@RestController
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/vision-training")
public class PlatformVisionTrainingController {
    private final PythonSmartBIClient python;
    private final FactoryService factories;

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

    private ApiResponse<JsonNode> forward(String factoryId, HttpServletRequest request,
                                          VisionTrainingAction action, String projectId, JsonNode body) {
        // The existing assembly interceptor establishes this type from the
        // verified platform namespace and active account. Same-ID factory
        // users cannot enter this path.
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .filter(value -> value.isPlatformAdmin() && "super_admin".equals(value.role()))
                .orElseThrow(() -> new BusinessException(403, "仅已验证的平台管理员可以设置工厂视觉训练"));
        if (factoryId == null || !factoryId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) {
            throw new BusinessException(400, "目标工厂编号无效");
        }
        if (projectId != null && !projectId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new BusinessException(400, "训练项目编号无效");
        }
        FactoryDTO target = factories.getFactoryById(factoryId);
        if (target == null || !factoryId.equals(target.getId())) throw new BusinessException(404, "目标工厂不存在");
        try {
            JsonNode data = python.callPlatformVisionTraining(factoryId, actor.id(), action, projectId, body);
            return ApiResponse.success("视觉训练流程已读回", data);
        } catch (IllegalStateException exception) {
            throw new BusinessException(503, "视觉训练服务未完成请求，请稍后重试");
        }
    }
}
