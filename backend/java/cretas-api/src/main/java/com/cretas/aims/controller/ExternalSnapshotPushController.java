package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.integration.ExternalSnapshotPushLimiter;
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
 * <p>顺序 (审查低1 + 复审中-新1): 先验凭证 ({@link ExternalIntegrationService#authenticatePush}, 无效 = 401, 不读请求体、
 * 不占限流桶) → 按授权行限流 ({@link ExternalSnapshotPushLimiter}, ⛔ 不按 IP: X-Forwarded-For 可伪造、网关不设 X-Real-IP)
 * → 按 {@link ExternalIntegrationService#MAX_PUSH_BODY_BYTES} 有界读取请求体 → 反序列化 → 写入。
 * 审查低5: 同一时点并发推送撞唯一约束时, 在事务外重试一次, 第二次按已存在的行判幂等 (duplicate) 或 409。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/public/external-snapshots")
public class ExternalSnapshotPushController {

    public static final String TOKEN_HEADER = "X-Cretas-Snapshot-Token";

    private final ExternalIntegrationService service;
    private final ObjectMapper objectMapper;
    private final ExternalSnapshotPushLimiter limiter;

    public ExternalSnapshotPushController(ExternalIntegrationService service, ObjectMapper objectMapper,
                                          ExternalSnapshotPushLimiter limiter) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.limiter = limiter;
    }

    @PostMapping("/push")
    public ApiResponse<ExternalIntegrationService.PushResult> push(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        limiter.acquire(service.authenticatePush(token));
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
