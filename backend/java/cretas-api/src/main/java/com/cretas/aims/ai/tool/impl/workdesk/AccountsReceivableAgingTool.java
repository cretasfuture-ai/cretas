package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.service.finance.ArApService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应收账龄分析 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>Wrap {@link ArApService#getAgingAnalysis} for CUSTOMER (AR). 返 6 桶账龄 (0-30 / 31-60 / 61-90 /
 * 91-180 / 181-365 / 365+ 天). 财务月结看应收老化情况.
 *
 * <p>读路径无副作用. 防呆 R2 — 含 bucket label + amount + count + 警告 (60+ 天高风险).
 *
 * <p>Intent Code: {@code ACCOUNTS_RECEIVABLE_AGING}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class AccountsReceivableAgingTool extends AbstractBusinessTool {

    @Autowired
    private ArApService arApService;

    /** 客户欠款排名读客户主数据余额 —— 与经营分析页 (ProductionReportServiceImpl.getFinanceReport) 同一数据源。 */
    @Autowired
    private com.cretas.aims.repository.CustomerRepository customerRepository;

    static final int RANK_TOP_N = 5;

    /** 财务行级范围 (仅本人 / 部门): 客户余额排名是全厂视图, 范围受限时不出 (账龄路径按 scopeOwners 收窄)。 */
    @Autowired
    private com.cretas.aims.service.finance.FinanceRowScopeQuery financeRowScopeQuery;

    @Override
    public String getToolName() {
        return "accounts_receivable_aging";
    }

    @Override
    public String getDescription() {
        return "应收查询. 默认按客户欠款(客户应收余额)从多到少排名, 回答'哪个客户欠款最多/欠款排名/应收多少'; "
                + "用户问'应收账龄' / '账龄分析' / '60 天以上欠款' / '坏账风险' / 'AR aging' / '应收老化' 时 view 填 aging "
                + "(按账龄分桶, 60+ 天为高风险). read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> view = new HashMap<>();
        view.put("type", "string");
        view.put("enum", List.of("owed_ranking", "aging"));
        view.put("description", "owed_ranking(默认)=客户欠款排名; aging=应收账龄分析");
        Map<String, Object> props = new HashMap<>();
        props.put("view", view);
        schema.put("properties", props);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("accounts_receivable_aging — factory={}", factoryId);
        if (!"aging".equalsIgnoreCase(getString(params, "view"))) {
            return doOwedRanking(factoryId, context);
        }

        List<Map<String, Object>> aging = arApService.getAgingAnalysis(factoryId, CounterpartyType.CUSTOMER);

        // 简单聚合: 60+ 天高风险总额
        java.math.BigDecimal highRiskAmount = java.math.BigDecimal.ZERO;
        long highRiskCount = 0;
        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        long totalCount = 0;
        for (Map<String, Object> row : aging) {
            // getAgingAnalysis 每个客户一行: total + 各账龄列 (旧写法按「每桶一行」读 amount/count/bucket, 恒为 0)
            java.math.BigDecimal rowTotal = decimal(row.get("total"));
            if (rowTotal.signum() <= 0) continue;
            totalAmount = totalAmount.add(rowTotal);
            totalCount++;
            java.math.BigDecimal old = decimal(row.get("days61_90")).add(decimal(row.get("days91_120")))
                    .add(decimal(row.get("days120plus")));
            if (old.signum() > 0) {
                highRiskAmount = highRiskAmount.add(old);
                highRiskCount++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("counterpartyType", "CUSTOMER");
        data.put("aging", aging);
        data.put("totalAmount", totalAmount);
        data.put("totalCount", totalCount);
        data.put("highRiskAmount", highRiskAmount);
        data.put("highRiskCount", highRiskCount);
        data.put("actionHint", "/finance/ar-ap?tab=aging&type=CUSTOMER");

        // 文字与 totalAmount / highRiskAmount 字段同进同出 (ERP-S6-MSG-TEXT-PRICE-LEAK-001)
        boolean showAmounts = replyShowsAmounts(context, "totalAmount", "highRiskAmount");
        String warning = highRiskCount > 0
                ? String.format(" 警告: 60+ 天高风险 %d 客户%s, 建议立即催收",
                        highRiskCount, showAmounts ? "共 ¥" + highRiskAmount : "")
                : "";
        // Sprint 12: emoji-free (B-end emoji=0) + ≥80-char message.
        StringBuilder msg = new StringBuilder();
        msg.append(showAmounts
                ? String.format("本月应收账龄汇总: 共 %d 客户 / ¥%s 待收", totalCount, totalAmount)
                : String.format("本月应收账龄汇总: 共 %d 客户待收 (金额按权限不显示)", totalCount));
        if (totalCount == 0) {
            // 流水里没有未结清应收 ≠ 客户不欠钱 (客户主数据余额是另一个口径, 见客户欠款排名): 如实说读的是什么
            msg.setLength(0);
            msg.append("应收账龄按应收流水统计, 当前流水里没有未结清的应收记录, 无法分账龄。客户欠款金额请问「哪个客户欠款最多」(按客户应收余额)。");
        } else {
            msg.append(warning);
            msg.append(". 建议: 1. 优先催收 60+ 天逾期客户; 2. 与客户对账确认; 3. 月底前完成催收回款。");
        }
        return buildSimpleResult(msg.toString(), data);
    }

    private static java.math.BigDecimal decimal(Object o) {
        return o instanceof Number ? new java.math.BigDecimal(o.toString()) : java.math.BigDecimal.ZERO;
    }

    /** 客户欠款排名: 客户应收余额 > 0 从多到少, 预收(余额 < 0)单独一句不混进应收。 */
    private Map<String, Object> doOwedRanking(String factoryId, Map<String, Object> context) {
        if (financeRowScopeQuery == null || financeRowScopeQuery.creators(factoryId) != null) {
            // creators != null = 财务范围不是全厂 (空列表 = 一行都看不到); 查不到范围按受限处理 (fail closed)
            Map<String, Object> restricted = new LinkedHashMap<>();
            restricted.put("mode", "OWED_RANKING");
            restricted.put("scopeRestricted", true);
            return buildSimpleResult("客户欠款排名需要全厂财务数据范围，当前账号的财务范围受限，不显示全厂客户欠款。"
                    + "可以问「应收账龄」查看你权限范围内的应收。", restricted);
        }
        List<com.cretas.aims.entity.Customer> owing = customerRepository.findCustomersWithOutstandingBalance(factoryId);
        java.math.BigDecimal prepaid = java.math.BigDecimal.ZERO;
        int prepaidCount = 0;
        for (com.cretas.aims.entity.Customer c : customerRepository.findByFactoryId(factoryId)) {
            if (c.getCurrentBalance() != null && c.getCurrentBalance().signum() < 0) {
                prepaid = prepaid.add(c.getCurrentBalance().abs());
                prepaidCount++;
            }
        }
        boolean showAmounts = replyShowsAmounts(context, "totalAmount", "highRiskAmount");
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (com.cretas.aims.entity.Customer c : owing) total = total.add(c.getCurrentBalance());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "OWED_RANKING");
        data.put("source", "客户应收余额 (与经营分析页同口径)");
        data.put("owingCustomerCount", owing.size());
        data.put("actionHint", "/finance/ar-ap?tab=aging&type=CUSTOMER");
        if (owing.isEmpty()) {
            data.put("ranking", Collections.emptyList());
            String none = "当前没有客户欠款（客户应收余额为 0）"
                    + (prepaidCount > 0 ? "；另有 " + prepaidCount + " 个客户预收款" + (showAmounts ? " ¥" + prepaid.setScale(2, java.math.RoundingMode.HALF_UP) : "") + "。" : "。");
            return buildSimpleResult(none, data);
        }
        List<Map<String, Object>> ranking = new java.util.ArrayList<>();
        StringBuilder msg = new StringBuilder();
        msg.append(showAmounts
                ? String.format("客户欠款共 %d 家、合计 ¥%s，欠款最多的是 %s。", owing.size(),
                        total.setScale(2, java.math.RoundingMode.HALF_UP), owing.get(0).getName())
                : String.format("共 %d 家客户有欠款，欠款最多的是 %s（金额按权限不显示）。", owing.size(), owing.get(0).getName()));
        int n = 0;
        for (com.cretas.aims.entity.Customer c : owing) {
            if (n++ >= RANK_TOP_N) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customerName", c.getName());
            if (showAmounts) row.put("balance", c.getCurrentBalance().setScale(2, java.math.RoundingMode.HALF_UP));
            ranking.add(row);
            msg.append("\n").append(n).append(". ").append(c.getName());
            if (showAmounts) msg.append("：¥").append(c.getCurrentBalance().setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (prepaidCount > 0) {
            msg.append("\n另有 ").append(prepaidCount).append(" 个客户预收款")
               .append(showAmounts ? " ¥" + prepaid.setScale(2, java.math.RoundingMode.HALF_UP) : "").append("（不计入欠款）。");
        }
        // 同一问常带「应付」「逾期」: 按挂账流水 (与账龄视图同源同范围) 补一段, 口径与上面的客户余额不同, 明说
        msg.append(ledgerOverdueAndPayableLine(factoryId, showAmounts));
        data.put("ranking", ranking);
        if (showAmounts) data.put("totalAmount", total);
        return buildSimpleResult(msg.toString(), data);
    }

    /** 逾期应收 + 应付 (挂账流水口径)。金额只在可见时写; 流水里没有记录就如实说没有, 不写成 0 元。 */
    private String ledgerOverdueAndPayableLine(String factoryId, boolean showAmounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n另按挂账流水（与账龄视图同口径，和上面的客户余额口径不同）：");
        java.math.BigDecimal[] ar = overdueSplit(arApService.getAgingAnalysis(factoryId, CounterpartyType.CUSTOMER));
        if (ar[2].signum() == 0) {
            sb.append("应收流水里没有已逾期的记录；");
        } else {
            sb.append("逾期应收（已过到期日）").append(ar[2].intValue()).append(" 家客户")
              .append(showAmounts ? "，共 ¥" + ar[1].setScale(2, java.math.RoundingMode.HALF_UP) : "").append("；");
        }
        List<Map<String, Object>> payRows = arApService.getAgingAnalysis(factoryId, CounterpartyType.SUPPLIER);
        java.math.BigDecimal[] ap = overdueSplit(payRows);
        long payCount = payRows == null ? 0 : payRows.stream().filter(r -> decimal(r.get("total")).signum() > 0).count();
        if (payCount == 0) {
            sb.append("应付流水里没有未结清的记录。");
        } else {
            sb.append("应付未结清 ").append(payCount).append(" 家供应商")
              .append(showAmounts ? "，共 ¥" + ap[0].setScale(2, java.math.RoundingMode.HALF_UP) : "")
              .append(ap[2].signum() > 0
                      ? "，其中已逾期 " + ap[2].intValue() + " 家" + (showAmounts ? " ¥" + ap[1].setScale(2, java.math.RoundingMode.HALF_UP) : "")
                      : "，没有已逾期的")
              .append("。");
        }
        return sb.toString();
    }

    /** 返回 [未结清合计, 逾期金额, 有逾期的对手数] (逾期 = 已过到期日, 即 1-30 天及更久各桶)。 */
    private static java.math.BigDecimal[] overdueSplit(List<Map<String, Object>> rows) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        java.math.BigDecimal overdue = java.math.BigDecimal.ZERO;
        long overdueParties = 0;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                java.math.BigDecimal rowTotal = decimal(row.get("total"));
                if (rowTotal.signum() <= 0) continue;
                total = total.add(rowTotal);
                java.math.BigDecimal late = decimal(row.get("days1_30")).add(decimal(row.get("days31_60")))
                        .add(decimal(row.get("days61_90"))).add(decimal(row.get("days91_120")))
                        .add(decimal(row.get("days120plus")));
                if (late.signum() > 0) {
                    overdue = overdue.add(late);
                    overdueParties++;
                }
            }
        }
        return new java.math.BigDecimal[]{total, overdue, java.math.BigDecimal.valueOf(overdueParties)};
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-10-29): the AI / Skill path admits exactly who the direct API admits
     * for the same data; pinned by GatedDataToolAiPathPermissionTest.
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("finance:read_write", "finance:read");
    }
}
