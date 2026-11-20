package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.service.finance.ArApService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
    static final int COLLECT_TOP_N = 3;
    private static final int OVERDUE_CUSTOMER_LIMIT = 20;

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
                + "用户问'逾期应收' / '催收' / '先催哪几张单' 时 view 填 overdue (逾期客户明细 + 建议先催的单据); "
                + "用户问'应收账龄' / '账龄分析' / '60 天以上欠款' / '坏账风险' / 'AR aging' / '应收老化' 时 view 填 aging "
                + "(按账龄分桶, 60+ 天为高风险). read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> view = new HashMap<>();
        view.put("type", "string");
        view.put("enum", List.of("owed_ranking", "aging", "overdue"));
        view.put("description", "owed_ranking(默认)=客户欠款排名; aging=应收账龄分析; overdue=逾期应收与先催哪几张单(催收/逾期)");
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
        if ("overdue".equalsIgnoreCase(getString(params, "view"))) {
            return doOverdueCollection(factoryId, context);
        }
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
        String overdueBlock = overdueCollectionBlock(factoryId, showAmounts, data);
        StringBuilder msg = new StringBuilder();
        if (!overdueBlock.isEmpty()) {
            msg.append(overdueBlock).append("\n\n")
               .append("【客户应收余额口径（经营分析页，与上面逾期口径不同）】\n");
        }
        if (owing.isEmpty()) {
            data.put("ranking", Collections.emptyList());
            msg.append("当前没有客户欠款（客户应收余额为 0）")
               .append(prepaidCount > 0 ? "；另有 " + prepaidCount + " 个客户预收款" + (showAmounts ? " ¥" + prepaid.setScale(2, java.math.RoundingMode.HALF_UP) : "") + "。" : "。");
            if (!overdueBlock.isEmpty()) {
                msg.append(ledgerPayableLine(factoryId, showAmounts, false));
            }
            return buildSimpleResult(msg.toString(), data);
        }
        List<Map<String, Object>> ranking = new java.util.ArrayList<>();
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
        // 同一问常带「应付」: 按挂账流水 (与账龄视图同源同范围) 补一段, 口径与客户余额不同, 明说
        msg.append(ledgerPayableLine(factoryId, showAmounts, overdueBlock.isEmpty()));
        data.put("ranking", ranking);
        if (showAmounts) data.put("totalAmount", total);
        return buildSimpleResult(msg.toString(), data);
    }

    /** 逾期催收视图: 账龄页口径的逾期客户明细与优先单据，另附应付一句。 */
    private Map<String, Object> doOverdueCollection(String factoryId, Map<String, Object> context) {
        boolean showAmounts = replyShowsAmounts(context, "totalAmount", "highRiskAmount");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "OVERDUE_COLLECTION");
        data.put("source", "应收挂账流水 (与账龄页同口径)");
        data.put("actionHint", "/finance/ar-ap?tab=aging&type=CUSTOMER");
        String block = overdueCollectionBlock(factoryId, showAmounts, data);
        if (block.isEmpty()) {
            block = "应收流水里没有已逾期的记录，暂时没有需要催收的单据。";
        }
        return buildSimpleResult(block + ledgerPayableLine(factoryId, showAmounts, false), data);
    }

    /**
     * 「逾期应收客户 + 建议先催」文字块 (账龄页口径)。没有逾期单据返回空串。
     * 排序 = 逾期天数 x 未结清金额 从大到小, 取前 {@link #COLLECT_TOP_N} 张; 逐张写单号 / 客户 / 金额 / 逾期天数 / 理由。
     */
    private String overdueCollectionBlock(String factoryId, boolean showAmounts, Map<String, Object> data) {
        List<Map<String, Object>> docs = arApService.getOverdueReceivableDocuments(factoryId);
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> customerTotals = new LinkedHashMap<>();
        long maxDays = 0;
        BigDecimal maxAmt = BigDecimal.ZERO;
        for (Map<String, Object> d : docs) {
            BigDecimal outstanding = decimal(d.get("outstanding"));
            total = total.add(outstanding);
            customerTotals.merge((String) d.get("counterpartyName"), outstanding, BigDecimal::add);
            maxDays = Math.max(maxDays, ((Number) d.get("daysOverdue")).longValue());
            maxAmt = maxAmt.max(outstanding);
        }
        List<Map<String, Object>> sorted = new java.util.ArrayList<>(docs);
        sorted.sort((a, b) -> score(b).compareTo(score(a)));
        StringBuilder sb = new StringBuilder();
        sb.append("【逾期应收（账龄页口径：挂账流水里已过到期日的未结清单据）】\n");
        sb.append("逾期应收共 ").append(customerTotals.size()).append(" 家客户、").append(docs.size()).append(" 张单据");
        if (showAmounts) sb.append("，合计 ¥").append(total.setScale(2, java.math.RoundingMode.HALF_UP));
        sb.append("。");
        appendOverdueCustomers(sb, customerTotals, showAmounts, data);
        sb.append("\n\n建议先催（按逾期天数x金额排序，前 ")
          .append(Math.min(COLLECT_TOP_N, sorted.size())).append(" 张）：");
        List<Map<String, Object>> top = new java.util.ArrayList<>();
        int n = 0;
        for (Map<String, Object> d : sorted) {
            if (n >= COLLECT_TOP_N) break;
            n++;
            long days = ((Number) d.get("daysOverdue")).longValue();
            java.math.BigDecimal amt = decimal(d.get("outstanding"));
            boolean longest = days == maxDays;
            boolean biggest = amt.compareTo(maxAmt) == 0;
            String reason = longest && biggest && showAmounts ? "逾期最久且金额最大"
                    : longest ? "逾期最久"
                    : biggest && showAmounts ? "金额最大"
                    : "逾期 " + days + " 天且金额较大，综合排序靠前";
            Object docNo = d.get("transactionNumber") == null ? "（未编号）" : d.get("transactionNumber");
            sb.append("\n").append(n).append(". 单号 ").append(docNo)
              .append("，客户 ").append(d.get("counterpartyName"));
            if (showAmounts) sb.append("，金额 ¥").append(amt.setScale(2, java.math.RoundingMode.HALF_UP));
            sb.append("，逾期 ").append(days).append(" 天，理由：").append(reason);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transactionNumber", docNo);
            row.put("customerName", d.get("counterpartyName"));
            if (showAmounts) row.put("outstanding", amt.setScale(2, java.math.RoundingMode.HALF_UP));
            row.put("daysOverdue", days);
            row.put("reason", reason);
            top.add(row);
        }
        data.put("overdueDocumentCount", docs.size());
        data.put("overdueCustomerCount", customerTotals.size());
        if (showAmounts) data.put("overdueAmount", total);
        data.put("collectFirst", top);
        return sb.toString();
    }

    private static void appendOverdueCustomers(StringBuilder text,
                                               Map<String, BigDecimal> customerTotals,
                                               boolean showAmounts, Map<String, Object> data) {
        List<Map.Entry<String, BigDecimal>> customers = new ArrayList<>(customerTotals.entrySet());
        if (showAmounts) {
            customers.sort(Map.Entry.<String, BigDecimal>comparingByValue().reversed());
        }
        int shownCount = Math.min(customers.size(), OVERDUE_CUSTOMER_LIMIT);
        text.append("\n逾期客户明细").append(showAmounts ? "（按逾期金额排序）：" : "（金额按权限不显示）：");
        List<Map<String, Object>> rows = new ArrayList<>(shownCount);
        for (Map.Entry<String, BigDecimal> customer : customers.subList(0, shownCount)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customerName", customer.getKey());
            text.append("\n- ").append(customer.getKey());
            if (showAmounts) {
                BigDecimal amount = customer.getValue().setScale(2, RoundingMode.HALF_UP);
                row.put("outstanding", amount);
                text.append("：¥").append(amount);
            }
            rows.add(row);
        }
        int omittedCount = customers.size() - shownCount;
        if (omittedCount > 0) {
            text.append("\n另有 ").append(omittedCount).append(" 家逾期客户，可在应收账龄页查看。");
        }
        data.put("overdueCustomers", rows);
        data.put("omittedOverdueCustomerCount", omittedCount);
    }

    private static java.math.BigDecimal score(Map<String, Object> d) {
        return java.math.BigDecimal.valueOf(((Number) d.get("daysOverdue")).longValue())
                .multiply(decimal(d.get("outstanding")));
    }

    /** 应付 (挂账流水口径)。金额只在可见时写; 流水里没有记录就如实说没有, 不写成 0 元。 */
    private String ledgerPayableLine(String factoryId, boolean showAmounts, boolean noOverdueAr) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【应付（挂账流水口径）】");
        if (noOverdueAr) {
            sb.append("应收流水里没有已逾期的记录；");
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
