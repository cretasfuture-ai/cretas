package com.cretas.aims.service.gate;

/**
 * 会拦出库 / 领料 / 投料 / 开工的闸清单 (诊断卡的闸名来源, 也是「闸数 = 已接诊断 + 登记不接」的唯一账本)。
 *
 * <p>新增一道拦截必须在这里登记, {@code GateCatalogTest} 会数: 每道闸要么 {@link #diagnosed()} 并有测试断言结构化原因,
 * 要么 {@link #exemptReason()} 写明为什么不接。
 */
public enum GateId {
    MATERIAL_BATCH_STATE("原料批次状态", null),
    SEMI_QC_PENDING("半成品待检", null),
    SEMI_QC_REJECTED("半成品质检不合格", null),
    WIP_QC("在制批次待检/不合格", null),
    FG_QC_PENDING("成品待检", null),
    FG_NOT_SALEABLE("成品状态不可售", null),
    FG_EXPIRED("成品已过期", null),
    INSUFFICIENT_BALANCE("库存/余额不足", null),
    INTERMEDIATE_OWNERSHIP("半成品客户归属", null),
    MATERIAL_OWNERSHIP("原料客户归属", null),
    SALES_FG_OWNERSHIP("成品客户归属", null),
    WORKFLOW_UPSTREAM("上游工序未产出", null),
    SHORTAGE_START("缺料开工", null),
    BLEND_RATIO("配比偏差", null),
    TIME_LIMIT("投料时限", null),
    REWORK_RATIO("回料占比超限", null),
    CCP_DEVIATION_HOLD("CCP 偏离扣留", "不是独立拒绝点: 偏离只把批次置为待检, 拒绝时由 SEMI_QC_PENDING / WIP_QC / FG_QC_PENDING / MATERIAL_BATCH_STATE 诊断; 持有记录没有成因字段, 诊断里不区分是需检还是偏离, 解开方式相同 (质检放行页处理偏离后放行)"),
    REWORK_PURITY("返工纯度", "只在返工配料建议里给出判定 (Verdict), 不抛拒绝, 没有拒绝提示可诊断"),
    OWNERSHIP_CONTEXT_MISSING("缺归属上下文", "数据缺失型拒绝 (计划/批次缺客户归属), 解开方式是补数据而非操作, 由各归属闸的 CONTEXT_REQUIRED 码覆盖");

    private final String label;
    private final String exemptReason;

    GateId(String label, String exemptReason) {
        this.label = label;
        this.exemptReason = exemptReason;
    }

    public String label() { return label; }
    public String exemptReason() { return exemptReason; }
    public boolean diagnosed() { return exemptReason == null; }
}
