package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.platform.PlatformAiAnalysisGrantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台 AI 分析入口的授权管理: 只有库里有效的平台超级管理员能调, 开通 / 撤销必须写理由。 */
@RestController
@RequestMapping("/api/platform/ai-analysis/grants")
public class PlatformAiAnalysisGrantController {

    private final PlatformAiAnalysisGrantService service;

    public PlatformAiAnalysisGrantController(PlatformAiAnalysisGrantService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PlatformAiAnalysisGrantService.GrantsView> list(HttpServletRequest request) {
        return ApiResponse.success(service.list(service.requireManager(request)));
    }

    @PostMapping
    public ApiResponse<PlatformAiAnalysisGrantService.GrantView> grant(
            @RequestBody PlatformAiAnalysisGrantService.GrantRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.grant(service.requireManager(request), body));
    }

    /** 撤销用 POST: 理由必填, 放在请求体里。 */
    @PostMapping("/revoke")
    public ApiResponse<Void> revoke(@RequestBody PlatformAiAnalysisGrantService.GrantRequest body,
                                    HttpServletRequest request) {
        service.revoke(service.requireManager(request), body);
        return ApiResponse.success();
    }
}
