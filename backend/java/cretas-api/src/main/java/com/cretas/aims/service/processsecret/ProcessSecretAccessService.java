package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工艺保密的唯一判定点。所有入口 (HTTP 拦截、响应裁剪、AI 工具、导出) 都问它, 不各写一套。
 *
 * <p>判定顺序:</p>
 * <ol>
 *   <li>产品没有保密策略 → {@link Outcome#NOT_PROTECTED}, 行为与改造前一致。</li>
 *   <li>B 档 ({@link ProcessSecretStorageMode#CUSTOMER_HELD}) → 完整查看 / 导出 / 编辑对任何人一律拒绝:
 *       云端不该有、也不再接收完整工艺。</li>
 *   <li>A 档: 保密负责人本人 (同厂工厂用户) 放行; 否则必须有同产品、同一被授权人、
 *       未到期未撤销、且覆盖该动作的授权。</li>
 *   <li>其他一律拒绝。⛔ factory_super_admin / permission_admin / 平台 bypass 角色都不因头衔放行。</li>
 * </ol>
 *
 * <p>授权不缓存 (撤销即时生效); 只缓存「哪个工厂有哪些受保护产品」这份很小的名单,
 * 本进程写策略时立即失效, 其余按 {@link #PROTECTED_SET_TTL} 过期。</p>
 */
@Service
public class ProcessSecretAccessService {

    private static final Logger log = LoggerFactory.getLogger(ProcessSecretAccessService.class);

    static final Duration PROTECTED_SET_TTL = Duration.ofSeconds(15);

    public enum Outcome { NOT_PROTECTED, ALLOW, DENY }

    public record Decision(Outcome outcome, ProcessSecretStorageMode storageMode, String reason) {
        public boolean permitted() {
            return outcome != Outcome.DENY;
        }

        public boolean isProtected() {
            return outcome != Outcome.NOT_PROTECTED;
        }
    }

    private record CachedSet(Set<String> productTypeIds, long loadedAtMillis) { }

    private final ProcessSecretPolicyRepository policyRepository;
    private final ProcessSecretGrantRepository grantRepository;
    private final ProcessSecretAuditRecorder auditRecorder;
    private final Map<String, CachedSet> protectedByFactory = new ConcurrentHashMap<>();

    public ProcessSecretAccessService(ProcessSecretPolicyRepository policyRepository,
                                      ProcessSecretGrantRepository grantRepository,
                                      ProcessSecretAuditRecorder auditRecorder) {
        this.policyRepository = policyRepository;
        this.grantRepository = grantRepository;
        this.auditRecorder = auditRecorder;
    }

    /** 该工厂受保护的产品 ID (只读快照)。空集合 = 本厂没有任何保密策略, 调用方可走快路径。 */
    public Set<String> protectedProductTypeIds(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return Set.of();
        long now = System.currentTimeMillis();
        CachedSet cached = protectedByFactory.get(factoryId);
        if (cached != null && now - cached.loadedAtMillis() < PROTECTED_SET_TTL.toMillis()) {
            return cached.productTypeIds();
        }
        Set<String> loaded = policyRepository.findByFactoryIdOrderByProductTypeIdAsc(factoryId).stream()
                .map(ProcessSecretPolicy::getProductTypeId)
                .collect(Collectors.toUnmodifiableSet());
        protectedByFactory.put(factoryId, new CachedSet(loaded, now));
        return loaded;
    }

    public void invalidate(String factoryId) {
        if (factoryId == null) return;
        protectedByFactory.remove(factoryId);
        // 写策略的事务提交前, 并发读可能把旧名单重新载入缓存; 提交后再清一次 (复审第三轮)
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            protectedByFactory.remove(factoryId);
                        }
                    });
        }
    }

    public Decision decide(AuthenticatedActor actor, String factoryId, String productTypeId, ProcessSecretAction action) {
        if (factoryId == null || productTypeId == null) {
            return new Decision(Outcome.NOT_PROTECTED, null, null);
        }
        // 判定永远查真表 (唯一索引, 一行); 缓存名单只给响应裁剪的快路径用
        Optional<ProcessSecretPolicy> policy = policyRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (policy.isEmpty()) return new Decision(Outcome.NOT_PROTECTED, null, null);
        return decideWithPolicy(actor, factoryId, policy.get(), action);
    }

    private Decision decideWithPolicy(AuthenticatedActor actor, String factoryId, ProcessSecretPolicy policy,
                                      ProcessSecretAction action) {
        ProcessSecretStorageMode mode = policy.getStorageMode();
        if (actor == null) {
            return new Decision(Outcome.DENY, mode, "未识别的调用者");
        }
        if (mode == ProcessSecretStorageMode.CUSTOMER_HELD) {
            return new Decision(Outcome.DENY, mode, "该产品核心工艺由客户侧保存，云端不提供也不接收完整工艺");
        }
        if (isOwner(actor, factoryId, policy)) {
            return new Decision(Outcome.ALLOW, mode, "保密负责人");
        }
        ProcessSecretGranteeType granteeType = granteeTypeOf(actor, factoryId);
        if (granteeType != null) {
            boolean granted = grantRepository.findActive(factoryId, policy.getProductTypeId(), granteeType,
                            actor.id(), LocalDateTime.now(ProcessSecretPolicyService.BUSINESS_ZONE)).stream()
                    .anyMatch(grant -> grant.allows(action));
            if (granted) return new Decision(Outcome.ALLOW, mode, "有效授权");
        }
        return new Decision(Outcome.DENY, mode, "该产品工艺已设为保密，需要保密负责人授权");
    }

    static boolean isOwner(AuthenticatedActor actor, String factoryId, ProcessSecretPolicy policy) {
        return actor != null
                && !actor.isPlatformAdmin()
                && factoryId.equals(actor.factoryId())
                && policy.getOwnerUserId() != null
                && policy.getOwnerUserId().equals(actor.id());
    }

    /** 工厂用户只认本厂; 平台人员只能凭 PLATFORM_ADMIN 类型的具名授权。 */
    static ProcessSecretGranteeType granteeTypeOf(AuthenticatedActor actor, String factoryId) {
        if (actor.isPlatformAdmin()) return ProcessSecretGranteeType.PLATFORM_ADMIN;
        return factoryId.equals(actor.factoryId()) ? ProcessSecretGranteeType.FACTORY_USER : null;
    }

    /**
     * 判定 + 审计, 拒绝时抛 403。受保护产品的放行同样落审计; 审计写不进去时按拒绝处理
     * (严格模式: 没有凭证就不放出完整工艺)。
     */
    public void require(AuthenticatedActor actor, String factoryId, String productTypeId,
                        ProcessSecretAction action, String resourceRef) {
        Decision decision = decide(actor, factoryId, productTypeId, action);
        if (!decision.isProtected()) return;
        boolean recorded = audit(factoryId, productTypeId, actor, action,
                decision.permitted() ? "ALLOW" : "DENY", decision.reason(), resourceRef);
        if (!decision.permitted()) {
            throw new BusinessException(403, decision.reason());
        }
        if (!recorded) {
            log.error("[process-secret] audit write failed, denying {} on {}/{}", action, factoryId, productTypeId);
            throw new BusinessException(403, "工艺访问审计暂不可用，已暂停完整工艺访问");
        }
    }

    /**
     * 多产品接口 (批量成本、导出) 的过滤。{@code explicit} = 调用方点名要的: 任一无权直接 403;
     * 否则 (例如「全厂导出」) 静默排除无权的受保护产品, 并逐个记 DENY 审计。
     */
    public List<String> filterPermitted(AuthenticatedActor actor, String factoryId, List<String> productTypeIds,
                                        ProcessSecretAction action, boolean explicit, String resourceRef) {
        if (productTypeIds == null || productTypeIds.isEmpty()) return List.of();
        List<String> permitted = new ArrayList<>(productTypeIds.size());
        for (String productTypeId : productTypeIds) {
            if (productTypeId == null) continue;
            if (explicit) {
                require(actor, factoryId, productTypeId, action, resourceRef);
                permitted.add(productTypeId);
                continue;
            }
            Decision decision = decide(actor, factoryId, productTypeId, action);
            if (!decision.isProtected()) {
                permitted.add(productTypeId);
            } else if (decision.permitted()) {
                if (!audit(factoryId, productTypeId, actor, action, "ALLOW",
                        decision.reason(), resourceRef)) {
                    continue;
                }
                permitted.add(productTypeId);
            } else {
                audit(factoryId, productTypeId, actor, action, "DENY", decision.reason() + "（已从结果中排除）",
                        resourceRef);
            }
        }
        return permitted;
    }

    /**
     * 审查 H3: 管理员 (工厂总监 / HR / 平台) 重置某人的密码后就能以他的身份登录。
     * 对保密负责人和持有有效授权的人, 只允许本人改自己的凭证; 管理员代改一律拒绝并审计。
     */
    public void requireAdminMayChangeCredentials(AuthenticatedActor operator, String factoryId, Long targetUserId) {
        if (targetUserId == null || factoryId == null) return;
        boolean self = operator != null && !operator.isPlatformAdmin()
                && factoryId.equals(operator.factoryId()) && targetUserId.equals(operator.id());
        if (self) return;
        boolean holder = policyRepository.existsByFactoryIdAndOwnerUserId(factoryId, targetUserId)
                || grantRepository.countActiveForGrantee(factoryId, ProcessSecretGranteeType.FACTORY_USER, targetUserId,
                        LocalDateTime.now(ProcessSecretPolicyService.BUSINESS_ZONE)) > 0;
        if (!holder) return;
        audit(factoryId, null, operator, ProcessSecretAction.MANAGE_GRANT, "DENY",
                "管理员代改保密负责人/被授权人的凭证", "user:" + targetUserId);
        throw new BusinessException(403, "该账号是工艺保密负责人或持有工艺授权，不能由管理员代为重置密码，请本人修改");
    }

    /** 写审计; 失败只返回 false (不吞掉判定), 由调用方决定是否 fail-closed。 */
    public boolean audit(String factoryId, String productTypeId, AuthenticatedActor actor,
                         ProcessSecretAction action, String decision, String reason, String resourceRef) {
        try {
            auditRecorder.record(factoryId, productTypeId, actor, action, decision, reason, resourceRef);
            return true;
        } catch (RuntimeException e) {
            log.error("[process-secret] audit write failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
