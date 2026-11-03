package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>工厂级保密配置</b> (Steve 2026-11-03, {@link FactorySecrecyConfig}): 一律按<b>策略所属的工厂</b>判
 * (出口裁剪对每一行的工厂分别调用 {@link #protectedProductTypeIds}, 不按请求者的工厂整份跳过)。</p>
 * <ul>
 *   <li>工艺保密 (a) 关: 该厂 A 档产品当作没有保密策略 —— {@link #protectedProductTypeIds} 不含它们,
 *       {@link #decide} 返回 NOT_PROTECTED。策略与授权留在库里不动, 重新开启后原样生效。</li>
 *   <li>客户侧保管 (e) 与 a 无关: e 开着时 B 档产品照旧受保护 (即使 a 关); e 关时 B 档按 A 档处理 (a 也关则不保护)。</li>
 *   <li>平台看不到配方 (b) 只在 a 开时起作用: a 开、b 关时平台账号对该厂按 FULL 处理 (照样记审计),
 *       本厂无权账号照旧被遮。</li>
 *   <li>训练数据过滤 (d): {@link #factoriesWithProtectedProducts} (写全局共享内容时比对的全厂并集) 每次都算,
 *       只剔除 d 关闭的工厂。</li>
 * </ul>
 */
@Service
public class ProcessSecretAccessService {

    private static final Logger log = LoggerFactory.getLogger(ProcessSecretAccessService.class);

    static final Duration PROTECTED_SET_TTL = Duration.ofSeconds(15);

    public enum Outcome { NOT_PROTECTED, ALLOW, DENY }

    /** 「平台看不到配方」(b) 关时平台账号无需授权就能做的动作: 只有看 (完整查看 / 导出)。 */
    static final java.util.Set<ProcessSecretAction> PLATFORM_VIEW_ACTIONS =
            java.util.EnumSet.of(ProcessSecretAction.VIEW_FULL, ProcessSecretAction.EXPORT);

    public record Decision(Outcome outcome, ProcessSecretStorageMode storageMode, String reason) {
        public boolean permitted() {
            return outcome != Outcome.DENY;
        }

        public boolean isProtected() {
            return outcome != Outcome.NOT_PROTECTED;
        }
    }

    /** 库里登记的保密策略 (不看开关); 开关在读出口时按工厂判 ({@link FactorySecrecyConfig} 自带 10 秒缓存)。 */
    private record PolicyEntry(String productTypeId, ProcessSecretStorageMode storageMode) { }

    /** {@code generation}: 读之前那一刻的失效代数; 读完之后代数变了 (期间有人 invalidate) 的条目不算数。 */
    private record CachedSet(List<PolicyEntry> policies, long loadedAtMillis, long generation) { }

    private record CachedFlag(boolean value, long loadedAtMillis, long generation) { }

    private final ProcessSecretPolicyRepository policyRepository;
    private final ProcessSecretGrantRepository grantRepository;
    private final ProcessSecretAuditRecorder auditRecorder;
    private final Map<String, CachedSet> protectedByFactory = new ConcurrentHashMap<>();
    /** 每家工厂的失效代数 (invalidate 时 +1): 防止「提交前读到旧值、提交后才写回缓存」的读者把旧名单放回去。 */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> generations = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong globalGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile CachedFlag noPolicyAnywhere;
    /** 单测直接 new 时每一项都当开启 (= 配置化之前的行为); Spring 下由 setter 注入真实实例 (没有这个 bean 的切片测试保持全开)。 */
    private FactorySecrecyConfig secrecy = FactorySecrecyConfig.allOn();

    public ProcessSecretAccessService(ProcessSecretPolicyRepository policyRepository,
                                      ProcessSecretGrantRepository grantRepository,
                                      ProcessSecretAuditRecorder auditRecorder) {
        this.policyRepository = policyRepository;
        this.grantRepository = grantRepository;
        this.auditRecorder = auditRecorder;
    }

    @Autowired(required = false)
    public void setFactorySecrecyConfig(FactorySecrecyConfig secrecy) {
        this.secrecy = secrecy;
    }

    /**
     * 该工厂受保护的产品 ID (只读快照)。空集合 = 本厂没有生效的保密策略, 调用方可走快路径。
     * 按<b>本厂</b>的开关过滤: A 档看工艺保密 (a); B 档 (客户侧保管) 看 e, e 关时退回按 a。
     */
    public Set<String> protectedProductTypeIds(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return Set.of();
        List<PolicyEntry> policies = policies(factoryId);
        if (policies.isEmpty()) return Set.of();
        boolean processSecret = secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET);
        boolean customerHeld = secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD);
        return policies.stream()
                .filter(p -> effectiveMode(p.storageMode(), processSecret, customerHeld) != null)
                .map(PolicyEntry::productTypeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 库里登记了保密策略、但因该厂工艺保密 (a) / 客户侧保管 (e) 关着而<b>不生效</b>的产品。与
     * {@link #protectedProductTypeIds} 合起来就是全部策略产品 —— 只给训练过滤 (d) 的名单用 (Steve 2026-11-03 Q1=A:
     * 训练剔除保密产品名只看 d, 不看 a)。⛔ 不用于「谁能看配方」。
     */
    public Set<String> switchedOffPolicyProductTypeIds(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return Set.of();
        List<PolicyEntry> policies = policies(factoryId);
        if (policies.isEmpty()) return Set.of();
        boolean processSecret = secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET);
        boolean customerHeld = secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD);
        return policies.stream()
                .filter(p -> effectiveMode(p.storageMode(), processSecret, customerHeld) == null)
                .map(PolicyEntry::productTypeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private long generation(String factoryId) {
        return generations.computeIfAbsent(factoryId, k -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    private List<PolicyEntry> policies(String factoryId) {
        long now = System.currentTimeMillis();
        long generation = generation(factoryId);
        CachedSet cached = protectedByFactory.get(factoryId);
        if (cached != null && cached.generation() == generation
                && now - cached.loadedAtMillis() < PROTECTED_SET_TTL.toMillis()) {
            return cached.policies();
        }
        List<PolicyEntry> loaded = policyRepository.findByFactoryIdOrderByProductTypeIdAsc(factoryId).stream()
                .map(p -> new PolicyEntry(p.getProductTypeId(), p.getStorageMode()))
                .toList();
        // 记下读之前的代数: 读的过程中被 invalidate 过, 这一条在下次读时作废 (不会把旧名单放回去)
        protectedByFactory.put(factoryId, new CachedSet(loaded, now, generation));
        return loaded;
    }

    /**
     * 库里任何工厂都没有工艺保密策略 (缓存 {@link #PROTECTED_SET_TTL}; 任一工厂 invalidate 时作废)。
     * 只给 HTTP 出口的快路径用: 为 true 时任何一行都不可能受保护。读失败 → false (照常走逐行判断)。
     */
    public boolean noProcessSecretPolicyAnywhere() {
        long now = System.currentTimeMillis();
        long generation = globalGeneration.get();
        CachedFlag cached = noPolicyAnywhere;
        if (cached != null && cached.generation() == generation
                && now - cached.loadedAtMillis() < PROTECTED_SET_TTL.toMillis()) {
            return cached.value();
        }
        boolean none;
        try {
            none = policyRepository.count() == 0;
        } catch (RuntimeException e) {
            return false;
        }
        noPolicyAnywhere = new CachedFlag(none, now, generation);
        return none;
    }

    /** 开关作用之后的档位; null = 不保护。B 档只在 e 开时是 B 档, e 关时退回 A 档 (仍要看 a)。 */
    static ProcessSecretStorageMode effectiveMode(ProcessSecretStorageMode stored, boolean processSecret,
                                                  boolean customerHeld) {
        if (stored == ProcessSecretStorageMode.CUSTOMER_HELD && customerHeld) {
            return ProcessSecretStorageMode.CUSTOMER_HELD;
        }
        return processSecret ? ProcessSecretStorageMode.CLOUD_RESTRICTED : null;
    }

    /**
     * 库里登记了任一保密策略的全部工厂 (每次现查, 不缓存), <b>不看任何保密开关 (a–f)</b>。只给「写所有工厂都看得到的共享配置」
     * (平台级意图、意图晋升 / 回滚) 用: 那是跨厂保护, Steve 的规矩是任何开关都不能削弱跨厂保护
     * (第四轮复审第 1 条: 以前共享配置走了下面按 d 剔除的那份, 某厂关了 d 它的保密名就能写进平台级意图)。
     * 查库出错照样抛给调用方, 调用方据此不写。
     */
    public Set<String> factoriesWithPolicyProducts() {
        return policyRepository.findAll().stream()
                .map(ProcessSecretPolicy::getFactoryId)
                .filter(f -> f != null && !f.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 有任一工艺保密产品、且训练数据过滤 (d) 开着的工厂 (每次现查, 不缓存)。只给「从训练样本学来的全局内容」
     * (全局说法 / 知识) 用 —— ⛔ 不给共享配置 (平台级意图), 那里用 {@link #factoriesWithPolicyProducts}:
     * 写进全局的东西所有工厂都看得到, 要对每一家工厂的保密产品名都查一遍 (Steve 2026-10-29)。
     * ⛔ 并集每次都算, 只剔除 d 关闭的工厂 —— 不按写入者那一家的开关短路 (否则别厂的保密名会进全局行)。
     * 查库出错照样抛给调用方, 调用方据此不写全局; 开关读不到按开启 (不剔除)。
     */
    public Set<String> factoriesWithProtectedProducts() {
        return policyRepository.findAll().stream()
                .map(ProcessSecretPolicy::getFactoryId)
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .filter(f -> secrecy.isOn(f, SecrecyItem.TRAINING_FILTER))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 平台统计 (跨工厂今日产量) 要排除的产品: 生效中的保密产品里平台看不到的那些
     * —— B 档 (e 开) 对任何人都不给; A 档在「平台看不到配方」(b) 开时不给。
     */
    public Set<String> hiddenFromPlatform(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return Set.of();
        List<PolicyEntry> policies = policies(factoryId);
        if (policies.isEmpty()) return Set.of();
        boolean processSecret = secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET);
        boolean customerHeld = secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD);
        boolean platformBlock = secrecy.isOn(factoryId, SecrecyItem.PLATFORM_RECIPE_BLOCK);
        return policies.stream()
                .filter(p -> {
                    ProcessSecretStorageMode mode = effectiveMode(p.storageMode(), processSecret, customerHeld);
                    return mode == ProcessSecretStorageMode.CUSTOMER_HELD
                            || (mode == ProcessSecretStorageMode.CLOUD_RESTRICTED && platformBlock);
                })
                .map(PolicyEntry::productTypeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 无完整查看权时的档位 ({@link ProcessSecretPayloadScrubber#degradedLevel}) 加上 b:
     * 「平台看不到配方」关着的工厂, 平台账号看 FULL。
     */
    public ProcessSecretPayloadScrubber.Level degradedLevel(AuthenticatedActor actor, String factoryId) {
        // 归属不清的内容: 该厂还有生效的 B 档产品时, 平台照旧看不到 (B 档对任何人都不给)
        if (platformSeesFull(actor, factoryId) && !hasEffectiveCustomerHeld(factoryId)) {
            return ProcessSecretPayloadScrubber.Level.FULL;
        }
        return ProcessSecretPayloadScrubber.degradedLevel(actor, factoryId);
    }

    /**
     * 平台账号、该厂工艺保密 (a) 开着、且「平台看不到配方」(b) 关着。b 只在 a 开时有意义;
     * B 档 (e 开) 在 {@link #decide} 里先于本判断拒绝, 平台照样看不到。
     */
    boolean platformSeesFull(AuthenticatedActor actor, String factoryId) {
        return actor != null && actor.isPlatformAdmin() && factoryId != null
                && secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET)
                && !secrecy.isOn(factoryId, SecrecyItem.PLATFORM_RECIPE_BLOCK);
    }

    private boolean hasEffectiveCustomerHeld(String factoryId) {
        return policies(factoryId).stream().anyMatch(p -> p.storageMode() == ProcessSecretStorageMode.CUSTOMER_HELD)
                && secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD);
    }

    public void invalidate(String factoryId) {
        if (factoryId == null) return;
        bump(factoryId);
        // 写策略的事务提交前, 并发读可能把旧名单重新载入缓存; 提交后再作废一次 (复审第三轮)。
        // 代数 (审查 #2): 提交前就开始读、提交后才写回缓存的读者, 它写回的条目带着旧代数, 下次读时作废。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            bump(factoryId);
                        }
                    });
        }
    }

    private void bump(String factoryId) {
        generations.computeIfAbsent(factoryId, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        globalGeneration.incrementAndGet();
        protectedByFactory.remove(factoryId);
    }

    public Decision decide(AuthenticatedActor actor, String factoryId, String productTypeId, ProcessSecretAction action) {
        if (factoryId == null || productTypeId == null) {
            return new Decision(Outcome.NOT_PROTECTED, null, null);
        }
        // 判定永远查真表 (唯一索引, 一行); 缓存名单只给响应裁剪的快路径用
        Optional<ProcessSecretPolicy> policy = policyRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (policy.isEmpty()) return new Decision(Outcome.NOT_PROTECTED, null, null);
        // 工厂级开关按策略所属的工厂判 (Steve 2026-11-03): a 关 → A 档不保护; e 关 → B 档退回 A 档
        ProcessSecretStorageMode mode = effectiveMode(policy.get().getStorageMode(),
                secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET), secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD));
        if (mode == null) return new Decision(Outcome.NOT_PROTECTED, null, null);
        return decideWithPolicy(actor, factoryId, policy.get(), mode, action);
    }

    private Decision decideWithPolicy(AuthenticatedActor actor, String factoryId, ProcessSecretPolicy policy,
                                      ProcessSecretStorageMode mode, ProcessSecretAction action) {
        if (actor == null) {
            return new Decision(Outcome.DENY, mode, "未识别的调用者");
        }
        if (mode == ProcessSecretStorageMode.CUSTOMER_HELD) {
            return new Decision(Outcome.DENY, mode, "该产品核心工艺由客户侧保存，云端不提供也不接收完整工艺");
        }
        // b 关只放开「看」(完整查看 / 导出); 编辑等写动作照旧要授权 (审查: b 的说明只承诺看配方与成本、做 AI 分析)
        if (PLATFORM_VIEW_ACTIONS.contains(action) && platformSeesFull(actor, factoryId)) {
            return new Decision(Outcome.ALLOW, mode, "本厂未开启「平台看不到配方」");
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
     * 不含配比、只给本厂账号的工艺元信息 (例如报工表「哪些工序配了调料」): 本厂工厂用户直接放行
     * (与响应裁剪的「物料 + 本单总量」档同一判据); 平台 / 他厂 / 识别不出的身份对受保护产品按完整查看判,
     * 无权 403 + 审计 (独立审查 V2.1 L3)。
     */
    public void requireFactoryUserOrFull(AuthenticatedActor actor, String factoryId, String productTypeId,
                                         String resourceRef) {
        if (degradedLevel(actor, factoryId) == ProcessSecretPayloadScrubber.Level.TOTALS_ONLY) {
            return;
        }
        require(actor, factoryId, productTypeId, ProcessSecretAction.VIEW_FULL, resourceRef);
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
        // 工厂级开关 (按该厂判): a 关时 A 档的负责人 / 授权不再保护任何东西; e 开时 B 档负责人 (登记工艺包的人) 仍要保护
        boolean processSecret = secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET);
        boolean customerHeld = secrecy.isOn(factoryId, SecrecyItem.CUSTOMER_HELD);
        if (!processSecret && !customerHeld) return;
        boolean holder = processSecret
                ? policyRepository.existsByFactoryIdAndOwnerUserId(factoryId, targetUserId)
                        || grantRepository.countActiveForGrantee(factoryId, ProcessSecretGranteeType.FACTORY_USER,
                                targetUserId, LocalDateTime.now(ProcessSecretPolicyService.BUSINESS_ZONE)) > 0
                : policyRepository.findByFactoryIdOrderByProductTypeIdAsc(factoryId).stream()
                        .anyMatch(p -> p.getStorageMode() == ProcessSecretStorageMode.CUSTOMER_HELD
                                && targetUserId.equals(p.getOwnerUserId()));
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
            if ("ALLOW".equals(decision) && action == ProcessSecretAction.VIEW_FULL) {
                rememberAllowedInRequest(factoryId, productTypeId);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("[process-secret] audit write failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    static final String ALLOWED_IN_REQUEST = ProcessSecretAccessService.class.getName() + ".allowedViewFull";

    /**
     * 本次 HTTP 请求里是否已经为该产品记过「完整查看放行」。出口裁剪据此不重复记
     * (同一次打开配方页, 拦截器记一条就够, 访问记录里不出现两条)。
     */
    public boolean viewFullAllowedInRequest(String factoryId, String productTypeId) {
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) return false;
        Object seen = attributes.getAttribute(ALLOWED_IN_REQUEST,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return seen instanceof Set<?> set && set.contains(factoryId + "|" + productTypeId);
    }

    @SuppressWarnings("unchecked")
    private static void rememberAllowedInRequest(String factoryId, String productTypeId) {
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        Object seen = attributes.getAttribute(ALLOWED_IN_REQUEST,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        Set<String> set;
        if (seen instanceof Set<?> existing) {
            set = (Set<String>) existing;
        } else {
            set = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            attributes.setAttribute(ALLOWED_IN_REQUEST, set,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }
        set.add(factoryId + "|" + productTypeId);
    }
}
