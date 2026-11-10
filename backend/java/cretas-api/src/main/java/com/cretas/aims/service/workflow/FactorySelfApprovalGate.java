package com.cretas.aims.service.workflow;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 工厂级「允许本人审批」开关的<b>唯一</b>判定处 (纪律排查 B1–B3 / B11 / B15, Owner 2026-11-10 裁定选 A)。
 *
 * <p>报工撤回、盘点差异、质检处置/特批、改价、退款原来都要求「审批人 ≠ 提交人」, 没有任何例外,
 * 一个人的工厂永远办不成。裁定: 加工厂开关, 默认关 (四眼照旧); 开了以后提交人可以自己审批,
 * 但每次都必须写原因, 审批记录上留痕 ({@code self_approved} + {@code self_approval_reason})。
 *
 * <p>调用方用法 (每个出口都一样, 三态):
 * <pre>
 *   Decision d = gate.decide(factoryId, actorIsMaker, reason);
 *   if (d.forbidden()) throw &lt;该出口原有的四眼错误&gt;;   // 开关关: 错误码与文案原样不变
 *   if (d.selfApproved()) { record.setSelfApproved(true); record.setSelfApprovalReason(d.reason()); }
 * </pre>
 * 开关开但没写原因时本类直接抛 422 {@link #REASON_REQUIRED}, 在任何写入之前。
 *
 * <p><b>读不到开关一律按关处理</b> (fail closed): 没有 settings 行、查询异常 —— 四眼照旧。
 * 本人审批是放宽, 拿不准时不放宽。
 *
 * <p>⚠️ 与 {@link SelfApprovalPolicy} 的关系: 那个类回答的是 OA 审批流 (采购/销售/调拨) 里
 * 「节点点名了此人 / 唯一超管」的例外, 依赖审批实例; 本类的出口都不走 OA 实例, 语义是工厂级开关。
 * 两者互不委托 (见 {@code SelfApprovalCarrierContractTest})。
 */
@Slf4j
@Component
public class FactorySelfApprovalGate {

    /** 开关开了、提交人自己审批、但没写原因。 */
    public static final String REASON_REQUIRED = "SELF_APPROVAL_REASON_REQUIRED";
    /** 原因太长 (与各审批原因字段的长度上限对齐)。 */
    public static final String REASON_TOO_LONG = "SELF_APPROVAL_REASON_TOO_LONG";
    public static final int MAX_REASON_LENGTH = 500;

    /** 四眼错误里给一人工厂指路用的提示。 */
    public static final String SWITCH_HINT = "如果本厂只有一个人能审批, 可请工厂管理员在「工厂配置」里打开「允许本人审批」(每次须写原因并留痕)";

    @Nullable
    private final FactorySettingsRepository factorySettingsRepository;

    /** 读开关用独立只读事务 (见 {@link IsolatedSettingsRead}); 为空 (手工构造的单测) 时直接读。 */
    @Nullable
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public FactorySelfApprovalGate(@Nullable FactorySettingsRepository factorySettingsRepository) {
        this(factorySettingsRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FactorySelfApprovalGate(@Nullable FactorySettingsRepository factorySettingsRepository,
                                   @Nullable org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.factorySettingsRepository = factorySettingsRepository;
        this.transactionManager = transactionManager;
    }

    /** 本厂是否打开了「允许本人审批」。读不到 = 关。 */
    public boolean isEnabled(String factoryId) {
        if (factorySettingsRepository == null || factoryId == null || factoryId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(IsolatedSettingsRead.read(transactionManager,
                    () -> factorySettingsRepository.findAllowSelfApprovalByFactoryId(factoryId)));
        } catch (RuntimeException e) {
            log.warn("allow_self_approval 读取失败, 按关闭处理 (四眼照旧): factoryId={}, err={}",
                    factoryId, e.getMessage());
            return false;
        }
    }

    /**
     * @param actorIsMaker 当前审批人是不是这张单的提交人/录入人 (由调用方按该出口原有判据算)
     * @param reason       本人审批原因 (开关开时必填); 不是本人时忽略
     */
    public Decision decide(String factoryId, boolean actorIsMaker, @Nullable String reason) {
        if (!actorIsMaker) {
            return Decision.NOT_SELF;
        }
        if (!isEnabled(factoryId)) {
            return Decision.FORBIDDEN;
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(422, "本厂已允许本人审批, 但本人审批必须填写原因")
                    .withCode(REASON_REQUIRED)
                    .withHint("请写明为什么由提交人本人审批 (例如: 本厂只有我一个人能审批)")
                    .withHintTarget("本人审批原因");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(400, "本人审批原因最多 " + MAX_REASON_LENGTH + " 字")
                    .withCode(REASON_TOO_LONG)
                    .withHintTarget("本人审批原因");
        }
        log.info("本人审批放行 (allow_self_approval=true): factoryId={}, reason={}", factoryId, trimmed);
        return new Decision(true, true, trimmed);
    }

    /**
     * 可选注入的调用方用: 没有注入 gate (手工构造的旧单测) 时按开关关处理 —— 本人即拦, 与改动前一致。
     */
    public static Decision decide(@Nullable FactorySelfApprovalGate gate, String factoryId,
                                  boolean actorIsMaker, @Nullable String reason) {
        if (gate == null) {
            return actorIsMaker ? Decision.FORBIDDEN : Decision.NOT_SELF;
        }
        return gate.decide(factoryId, actorIsMaker, reason);
    }

    /**
     * 判定结果。
     *
     * @param self    审批人是不是提交人
     * @param allowed 能不能继续审批
     * @param reason  本人审批时的原因 (已 trim), 其余为 null
     */
    public record Decision(boolean self, boolean allowed, @Nullable String reason) {
        public static final Decision NOT_SELF = new Decision(false, true, null);
        public static final Decision FORBIDDEN = new Decision(true, false, null);

        /** 本人审批且开关关 —— 调用方抛它原有的四眼错误。 */
        public boolean forbidden() {
            return self && !allowed;
        }

        /** 本人审批且已放行 —— 调用方在审批记录上留痕。 */
        public boolean selfApproved() {
            return self && allowed;
        }
    }
}
