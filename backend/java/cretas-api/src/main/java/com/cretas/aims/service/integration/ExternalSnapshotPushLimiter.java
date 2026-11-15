package com.cretas.aims.service.integration;

import com.cretas.aims.exception.RateLimitExceededException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部快照推送的限流: <b>按授权行分桶</b> (工厂 × 外部系统), 只在凭证校验通过之后计数。
 *
 * <p>复审中-新1: 不能按 IP 限 —— {@code RateLimitAspect} 取 X-Forwarded-For 第一段 (客户端可伪造),
 * 47 网关 {@code /api/public/} 又不设 X-Real-IP, 不带 XFF 的请求在 Java 看来都是 127.0.0.1 共用一个桶;
 * 计数又在凭证校验之前, 匿名请求每分钟 30 次就能让 08:00 的正常推送拿到 429。
 * 现在: 无效凭证直接 401, 不占任何桶 (32 字节随机凭证猜不出来, 无效请求的代价只是一次索引查询);
 * 合法凭证只会被同一授权自己的请求挡住。固定窗口, 每个 JVM 一份 (蓝绿同一时刻只有一槽 active)。</p>
 */
@Component
public class ExternalSnapshotPushLimiter {

    public static final int PUSHES_PER_WINDOW = 30;
    public static final Duration WINDOW = Duration.ofMinutes(1);
    static final String MESSAGE = "本授权推送过于频繁，请稍后再试";

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(10_000)
            .build();

    /** 记一次; 本授权在当前窗口内超过 {@link #PUSHES_PER_WINDOW} 次则 429。 */
    public void acquire(ExternalIntegrationService.PushPrincipal principal) {
        AtomicInteger counter = counters.get(principal.bucketKey(), k -> new AtomicInteger());
        if (counter.incrementAndGet() > PUSHES_PER_WINDOW) {
            throw new RateLimitExceededException(MESSAGE);
        }
    }
}
