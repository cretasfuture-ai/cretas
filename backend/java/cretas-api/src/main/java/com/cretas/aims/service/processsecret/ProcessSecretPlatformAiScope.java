package com.cretas.aims.service.processsecret;

import com.cretas.aims.security.AuthenticatedActor;

import java.util.HashSet;
import java.util.Set;

/**
 * 平台 AI 分析入口的读取作用域 (Steve 2026-11-04: 「平台 AI 能 access 所有的, 不管是什么配置」)。
 *
 * <p>这是整条放行的唯一开关, 所以把它的边界写死:
 * <ul>
 *   <li>只有 {@code PlatformFactoryAiAnalysisService} 打开它 (源码闸钉着), 打开前已经过授权和留痕;</li>
 *   <li>只绑当前线程、一个平台账号、一家工厂: 别的线程、别的账号、别的工厂一律不算在内;</li>
 *   <li>不能嵌套, 用 try-with-resources 关: 分析抛异常也会清掉;</li>
 *   <li>它只被 {@link ProcessSecretAccessService#permitsPlatformAiRead} 读。⛔ {@code decide} / {@code permitsViewFull} /
 *       {@code platformSeesFull} / {@code hiddenFromPlatform} 都不看它, 平台账号走任何别的接口时结果不变。</li>
 * </ul>
 *
 * <p>客户侧保管 (B 档) 不受它影响: 云端没有完整工艺, 这里放行也读不到。
 */
public final class ProcessSecretPlatformAiScope implements AutoCloseable {

    private static final ThreadLocal<ProcessSecretPlatformAiScope> CURRENT = new ThreadLocal<>();

    private final Long actorId;
    private final String factoryId;
    private final Set<String> recordedProducts = new HashSet<>();

    private ProcessSecretPlatformAiScope(Long actorId, String factoryId) {
        this.actorId = actorId;
        this.factoryId = factoryId;
    }

    public static ProcessSecretPlatformAiScope open(AuthenticatedActor actor, String factoryId) {
        if (actor == null || !actor.isPlatformAdmin() || factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("platform AI scope needs a platform actor and a named factory");
        }
        if (CURRENT.get() != null) {
            throw new IllegalStateException("platform AI scope is already open on this thread");
        }
        ProcessSecretPlatformAiScope scope = new ProcessSecretPlatformAiScope(actor.id(), factoryId);
        CURRENT.set(scope);
        return scope;
    }

    /** 当前线程上覆盖这个账号、这家工厂的作用域; 没有就是 null。 */
    static ProcessSecretPlatformAiScope covering(AuthenticatedActor actor, String factoryId) {
        ProcessSecretPlatformAiScope scope = CURRENT.get();
        boolean covered = scope != null && actor != null && actor.isPlatformAdmin()
                && scope.actorId.equals(actor.id()) && scope.factoryId.equals(factoryId);
        return covered ? scope : null;
    }

    /** 这次分析里这个产品是否已经记过 (判定在一次请求里会走好几遍, 记录只要一条)。 */
    boolean alreadyRecorded(String productTypeId) {
        return recordedProducts.contains(productTypeId);
    }

    void markRecorded(String productTypeId) {
        recordedProducts.add(productTypeId);
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
