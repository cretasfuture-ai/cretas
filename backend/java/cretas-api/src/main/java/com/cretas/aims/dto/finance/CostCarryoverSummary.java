package com.cretas.aims.dto.finance;

import java.math.BigDecimal;

/**
 * 结转成本 (期末 COGS 权责化) 小结 — carryCost 返回, 供结账流程 / 日志暴露。
 *
 * @param totalCogs        实际出货减已入库实物退回的净销售成本；负数表示退回多于本期出货，反向恢复库存，2位小数
 * @param costedItemCount  参与结转的发货明细行数 (有成本)
 * @param missingQuantity  兼容保留字段；当前成功结转必须成本完整，因此为0（不混加kg/盒）
 * @param missingItemCount 兼容保留字段；缺成本会拒绝整次结转，因此成功时为0
 */
public record CostCarryoverSummary(
        BigDecimal totalCogs,
        long costedItemCount,
        BigDecimal missingQuantity,
        long missingItemCount) {
}
