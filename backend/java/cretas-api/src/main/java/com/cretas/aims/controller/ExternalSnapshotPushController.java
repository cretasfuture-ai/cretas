package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机采集推送外部系统只读快照 (DECISIONS 十八 M6: 采集在本机, 凭据不出本机, 只推快照)。
 *
 * <p>不走用户 JWT: 凭证是平台签发、绑定在授权行上的专用推送令牌 ({@value #TOKEN_HEADER}),
 * 令牌决定能写哪家工厂哪个系统, 只能写 {@code external_source_snapshots}; 授权暂停即拒收。
 * 路径放在 {@code /api/public/} 下只是为了网关两个域名都转得到 Java、且不被 JWT 拦截器要求登录 ——
 * 鉴权在 {@link ExternalIntegrationService#push} 里, 没有令牌 = 401。</p>
 */
@RestController
@RequestMapping("/api/public/external-snapshots")
@RequiredArgsConstructor
public class ExternalSnapshotPushController {

    public static final String TOKEN_HEADER = "X-Cretas-Snapshot-Token";

    private final ExternalIntegrationService service;

    @PostMapping("/push")
    public ApiResponse<ExternalIntegrationService.PushResult> push(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody ExternalIntegrationService.PushRequest body) {
        return ApiResponse.success(service.push(token, body));
    }
}
