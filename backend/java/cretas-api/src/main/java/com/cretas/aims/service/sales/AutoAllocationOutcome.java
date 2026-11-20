package com.cretas.aims.service.sales;

/**
 * 子发运单创建时按先进先出预填批次的结果 (Q3, 2026-11-09)。
 *
 * <p>预填在子发运单创建的外层事务里跑。凑不齐时⛔ 不能抛业务异常: 异常穿过分配服务的事务代理会把外层事务标成
 * 只能回滚, 外层就算 catch 住, 提交时整张子单照样回滚 (rollback-only)。所以结果按值返回, 原因原样带给用户。
 *
 * @param allocated 已按发货行数量写好分配
 * @param code      没分配时的原因码 (如 BATCH_PACKAGING_SPEC_MISMATCH / FIFO_STOCK_SHORTFALL); 分配成功为 null
 * @param message   没分配时给用户看的话 (中文)
 * @param hint      下一步去哪里处理
 */
public record AutoAllocationOutcome(boolean allocated, String code, String message, String hint) {

    public static final String CODE_STOCK_SHORTFALL = "FIFO_STOCK_SHORTFALL";

    public static AutoAllocationOutcome done() {
        return new AutoAllocationOutcome(true, null, null, null);
    }

    public static AutoAllocationOutcome notAllocated(String code, String message, String hint) {
        return new AutoAllocationOutcome(false, code, message, hint);
    }
}
