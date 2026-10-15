package com.cretas.aims.service.unit;

/**
 * 一次 SKU 规格 → 换算图同步的结果。
 *
 * <p>存在的理由是 <b>2026-08-12 实测</b>: 建厂落库(ai-initialize/apply)里那句
 * {@code catch (Exception e) { result.getFailedPackagingConversions().add(...) }}
 * 写着「不阻断建厂」, 实际上一条都没挡住 —— 被 catch 的
 * {@link com.cretas.aims.exception.BusinessException} 是从一个
 * {@code @Transactional} 方法里抛出来的, Spring 在异常离开内层事务时就把
 * <b>共享事务标成了 rollback-only</b>。外层提交时炸
 * {@code UnexpectedRollbackException}, 整次建厂全量回滚, 用户拿到的是一句
 * 通用的「操作未能完成，请刷新后重试」, 而日志里那行还写着「不阻断建厂」。
 *
 * <p>⛔ 判据: <b>想「接住」一个异常, 就不能让它先穿过事务边界</b>。
 * 所以冲突不再用异常表达 —— 单 SKU 编辑那两个入口(它们本来就该报错给正在
 * 编辑的人看)继续用 {@link ProductSpecificationConversionSyncService#synchronize}
 * 拿异常, 批量建厂改用
 * {@link ProductSpecificationConversionSyncService#trySynchronize} 拿这个返回值。
 *
 * @param changed      是否真的新建/改写/退役了行 —— false 表示「本来就是这样」, 不是失败
 * @param errorCode    失败码; null = 没失败
 * @param errorMessage 失败原因原文(给人看的), null = 没失败
 */
public record SpecificationSyncOutcome(boolean changed, String errorCode, String errorMessage) {

    private static final SpecificationSyncOutcome UNCHANGED =
            new SpecificationSyncOutcome(false, null, null);
    private static final SpecificationSyncOutcome APPLIED =
            new SpecificationSyncOutcome(true, null, null);

    public static SpecificationSyncOutcome unchanged() {
        return UNCHANGED;
    }

    /** ⚠️ 不能叫 changed() —— record 组件 {@code changed} 已经占了这个签名。 */
    public static SpecificationSyncOutcome applied() {
        return APPLIED;
    }

    public static SpecificationSyncOutcome failure(String errorCode, String errorMessage) {
        return new SpecificationSyncOutcome(false, errorCode, errorMessage);
    }

    public boolean failed() {
        return errorCode != null;
    }
}
