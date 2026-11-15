package com.cretas.aims.controller;

import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * 本机采集推送外部系统只读快照 (DECISIONS 十八 M6: 采集在本机, 凭据不出本机, 只推快照)。
 *
 * <p>不走用户 JWT: 凭证是平台签发、绑定在授权行上的专用推送令牌 ({@value #TOKEN_HEADER}),
 * 令牌决定能写哪家工厂哪个系统, 只能写 {@code external_source_snapshots}; 授权暂停即拒收 (暂停同时作废凭证)。
 * 路径放在 {@code /api/public/} 下只是为了网关两个域名都转得到 Java、且不被 JWT 拦截器要求登录 ——
 * 鉴权在 {@link ExternalIntegrationService#push} 里, 没有令牌 = 401。</p>
 *
 * <p>审查低1: 按 IP 限流; 先看令牌格式、再按 {@link ExternalIntegrationService#MAX_PUSH_BODY_BYTES} 有界读取请求体,
 * 之后才反序列化 —— 匿名请求不能让服务端先解析一整份大 JSON。
 * 审查低5: 同一时点并发推送撞唯一约束时, 在事务外重试一次, 第二次按已存在的行判幂等 (duplicate) 或 409。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/public/external-snapshots")
public class ExternalSnapshotPushController {

    public static final String TOKEN_HEADER = "X-Cretas-Snapshot-Token";

    private final ExternalIntegrationService service;
    private final ObjectMapper objectMapper;

    public ExternalSnapshotPushController(ExternalIntegrationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/push")
    @RateLimit(count = 30, period = 60, limitType = RateLimit.LimitType.IP, message = "推送过于频繁，请稍后再试")
    public ApiResponse<ExternalIntegrationService.PushResult> push(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        if (token == null || token.isBlank() || !token.trim().startsWith("cxs_")) {
            throw new BusinessException(401, "推送凭证缺失或无效");
        }
        ExternalIntegrationService.PushRequest body = readBody(request);
        try {
            return ApiResponse.success(service.push(token, body));
        } catch (DataIntegrityViolationException concurrent) {
            log.info("外部快照并发推送撞唯一约束, 重试一次按已存在的行判定: {}", concurrent.getClass().getSimpleName());
            return ApiResponse.success(service.push(token, body));
        }
    }

    private ExternalIntegrationService.PushRequest readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        int limit = ExternalIntegrationService.MAX_PUSH_BODY_BYTES;
        if (declared > limit) throw tooLarge(limit);
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(limit + 1);
        }
        if (bytes.length > limit) throw tooLarge(limit);
        if (bytes.length == 0) throw new BusinessException(400, "请求体为空");
        try {
            return objectMapper.readValue(bytes, ExternalIntegrationService.PushRequest.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "请求体不是合法的快照 JSON");
        }
    }

    private static BusinessException tooLarge(int limit) {
        return new BusinessException(413, "请求体超过 " + limit + " 字节，拒收（请减少行数或分天推送）");
    }
}
