package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.AIRequestDTO;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.annotation.RateLimit.LimitType;
import com.cretas.aims.service.platform.PlatformFactoryAiAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台专用 AI 分析入口 (Steve 2026-11-04)。工厂只取路径, 非流式; 判定都在
 * {@link PlatformFactoryAiAnalysisService}。工厂侧的 {@code /ai/analysis/cost/*} 不受影响。
 */
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/ai-analysis")
public class PlatformFactoryAiAnalysisController {

    private final PlatformFactoryAiAnalysisService service;

    public PlatformFactoryAiAnalysisController(PlatformFactoryAiAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/cost/time-range")
    @RateLimit(count = 6, period = 60, limitType = LimitType.USER, message = "平台 AI 分析请求过于频繁，请稍后再试")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> analyzeTimeRange(
            @PathVariable String factoryId,
            @Valid @RequestBody AIRequestDTO.TimeRangeAnalysisRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.analyzeTimeRange(request, factoryId, body));
    }
}
