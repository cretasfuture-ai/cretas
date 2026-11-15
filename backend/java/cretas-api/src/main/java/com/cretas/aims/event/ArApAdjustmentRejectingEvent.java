package com.cretas.aims.event;

/**
 * 应收 / 应付调整<b>即将被驳回</b> (同一事务里、加锁之前同步发出)。
 *
 * <p>为什么在加锁之前发: 订阅方 (委外: {@code OutsourceService#onApAdjustmentRejecting}) 要先锁委外单、
 * 再经 {@code recordAdjustment} 锁工厂, 与委外退货 (锁单 → 挂调整锁工厂) 同一加锁顺序, 不会互相死锁。
 * 驳回本身之后失败 (状态不是待审 / 四眼不过) ⇒ 整个事务回滚, 订阅方补的东西一起撤掉。</p>
 *
 * <p>订阅方必须按 sourceType 过滤, 不认识的来源直接忽略。</p>
 */
public record ArApAdjustmentRejectingEvent(String factoryId, String transactionId, String sourceType,
                                           String sourceId, Long rejectedBy) {
}
