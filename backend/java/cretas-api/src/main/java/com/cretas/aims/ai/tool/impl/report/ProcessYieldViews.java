package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.service.yield.YieldAnalysisService;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 出成率「按工序」三种问法的取数与文字 (BP 问题 28, 2026-11-18), 由 {@link ReportBatchYieldRankTool} 调用:
 * <ul>
 *   <li>损耗异常: 工序物料平衡里未解释差额超过黄线的 (批次, 工序), 批次号原样;</li>
 *   <li>某工序近期出成率: 均值 (工序合计 产出÷投入)、与上一段同长窗口的变化、异常批次数;</li>
 *   <li>本月 vs 上月: 各工序出成率变化, 点出下降最多的工序及其异常批次。</li>
 * </ul>
 * 数据全部来自已有的两个服务 (工序出成率聚合 {@link YieldAnalysisService}、物料平衡 {@link MaterialBalanceService}),
 * 保密产品排除、单位不可比留空都沿用它们, 这里不再复制公式。
 * 口径: 出成率 = 工序产出合计 ÷ 投入合计; 损耗率 = 1 − 出成率 (YieldAnalysisService 的 wastageRate, 不低于 0);
 * 异常批次 = 该批次该工序的未解释差额占投入 &gt; 黄线 (默认 5%, 超 15% 为红, 按本厂物料平衡阈值)。
 */
final class ProcessYieldViews {
    private ProcessYieldViews() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final String BASIS = "口径：出成率 = 工序产出合计 ÷ 投入合计；损耗率 = 1 − 出成率；"
            + "异常批次 = 该批次该工序的物料平衡未解释差额占投入超过黄线（默认 5%，超 15% 为红，按本厂配置）。";

    /**
     * 本次回答实际用的异常线写进口径: 与「发现的问题」页同一个本厂损耗告警阈值时写明来源,
     * 没配置才是物料平衡默认 (BP 问题 28: 两处口径不一致, 页上 3% 已立单, 这里 5% 答没有异常)。
     */
    static String basis(Balance b) {
        if (!b.fromAlertThreshold()) {
            return BASIS;
        }
        return "口径：出成率 = 工序产出合计 ÷ 投入合计；损耗率 = 1 − 出成率；"
                + "异常批次 = 该批次该工序的物料平衡未解释差额占投入超过 " + pct(b.warnPct())
                + "（本厂「发现的问题」损耗告警阈值，与问题页同一个来源）。";
    }

    /** 一条异常 (批次, 工序)。 */
    record Abnormal(String batchNumber, String productName, String processName, String level,
                    BigDecimal input, BigDecimal output, String unit, BigDecimal deviationPct) {
        Map<String, Object> toMap(BigDecimal warnPct) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchNumber", batchNumber);
            m.put("productName", productName);
            m.put("processName", processName);
            m.put("level", level);
            m.put("standardDeviationLimit", warnPct);
            m.put("deviationPct", deviationPct);
            m.put("inputQuantity", input);
            m.put("outputQuantity", output);
            m.put("unit", unit);
            return m;
        }
    }

    /** 问话里的产品条件: 物料平衡按产品名筛; 工序出成率聚合服务只认产品类型 ID, 能唯一对应才筛, 否则如实说明未筛。 */
    record ProductScope(String keyword, String typeId, boolean none) {
        static final ProductScope ALL = new ProductScope(null, null, false);

        boolean active() { return keyword != null; }

        /** 出成率聚合没能按产品筛 (对应多个产品类型): 要在回复里明说。 */
        boolean yieldUnfiltered() { return keyword != null && typeId == null && !none; }

        String note() {
            if (none) return "\n近一年没有找到名称含「" + keyword + "」的批次，没有可汇总的数据。";
            return yieldUnfiltered()
                    ? "\n注意：「" + keyword + "」对应多个产品类型，工序出成率汇总未按「" + keyword + "」筛选（异常批次已按产品名筛选）。"
                    : "";
        }

        boolean productMatches(String productName) {
            return keyword == null || (productName != null
                    && productName.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)));
        }
    }

    static List<ProcessYieldAggDTO> aggregate(YieldAnalysisService yield, String factoryId, LocalDate s, LocalDate e,
                                              ProductScope scope) {
        if (scope.none()) return new ArrayList<>();
        return yield.aggregateByProcess(factoryId, s, e, scope.typeId());
    }

    record Balance(BigDecimal warnPct, BigDecimal redPct, List<Abnormal> abnormal, int incomparableRows,
                   boolean fromAlertThreshold) {}

    static Balance balance(MaterialBalanceService service, String factoryId, LocalDate start, LocalDate end,
                           ProductScope scope) {
        return balance(service, factoryId, start, end, scope, null);
    }

    /**
     * @param alertPct 本厂「发现的问题」损耗告警阈值 (百分数, 如 3); 非空时以它为异常线, 与问题页同一个来源;
     *                 null = 没配置, 用本厂物料平衡阈值 (默认 5%)。BP 问题 28: 问题页对熟制杀菌偏离 3.7% (阈值 3%) 已立单,
     *                 这里却按 5% 答「没有异常」。
     */
    static Balance balance(MaterialBalanceService service, String factoryId, LocalDate start, LocalDate end,
                           ProductScope scope, BigDecimal alertPct) {
        MaterialBalanceService.BatchBalance bb = service.blocksByBatch(factoryId, start, end, alertPct);
        List<Abnormal> out = new ArrayList<>();
        int incomparable = 0;
        for (MaterialBalanceTableDTO.PlanBlock block : bb.blocks()) {
            String batch = block.getBatchNumbers() == null || block.getBatchNumbers().isEmpty()
                    ? null : block.getBatchNumbers().get(0);
            if (batch == null || block.getRows() == null || !scope.productMatches(block.getProductName())) continue;
            for (MaterialBalanceTableDTO.Row r : block.getRows()) {
                if ("WARN".equals(r.getLevel()) || "RED".equals(r.getLevel())) {
                    out.add(new Abnormal(batch, block.getProductName(), r.getProcessName(), r.getLevel(),
                            r.getInput(), r.getOutput(), r.getUnit(), r.getUnexplainedPct()));
                } else if ("UNIT_INCOMPARABLE".equals(r.getLevel()) || "NO_INPUT".equals(r.getLevel())) {
                    incomparable++;
                }
            }
        }
        out.sort(Comparator.comparing((Abnormal a) -> a.deviationPct() == null ? BigDecimal.ZERO : a.deviationPct()).reversed());
        return new Balance(bb.warnPct(), bb.redPct(), out, incomparable, alertPct != null);
    }

    static boolean nameMatches(String processName, String keyword) {
        if (processName == null || keyword == null) return false;
        String a = processName.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        String k = keyword.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (a.isEmpty() || k.isEmpty()) return false;
        return a.contains(k) || (a.length() >= 2 && k.contains(a));
    }

    /**
     * 异常批次的工序 (abnormalProcess) 算不算 rowProcess 这一行的: 精确同名先算; 有别的已知工序名与它精确同名时,
     * 不再模糊归给 rowProcess (「杀菌」与「熟制杀菌」互相包含, 不能让同一条异常批次记两次); 都没精确命中才模糊。
     */
    static boolean attributes(String abnormalProcess, String rowProcess, java.util.Collection<String> knownNames) {
        if (abnormalProcess == null || rowProcess == null) return false;
        if (normalize(abnormalProcess).equals(normalize(rowProcess))) return true;
        for (String n : knownNames) {
            if (n != null && normalize(n).equals(normalize(abnormalProcess))) return false;
        }
        return nameMatches(abnormalProcess, rowProcess);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    }

    /** 用户给的工序关键词: 有已知工序名精确等于它就只认精确的, 否则模糊。 */
    static boolean keywordMatches(String processName, String keyword, java.util.Collection<String> knownNames) {
        for (String n : knownNames) {
            if (n != null && normalize(n).equals(normalize(keyword))) {
                return processName != null && normalize(processName).equals(normalize(keyword));
            }
        }
        return nameMatches(processName, keyword);
    }

    private static List<String> names(List<ProcessYieldAggDTO> aggs, Balance bal) {
        List<String> out = new ArrayList<>();
        if (aggs != null) for (ProcessYieldAggDTO a : aggs) out.add(a.getProcessName());
        for (Abnormal a : bal.abnormal()) out.add(a.processName());
        return out;
    }

    static BigDecimal pp(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b).setScale(1, RoundingMode.HALF_UP);
    }

    static String pct(BigDecimal v) {
        return v == null ? "算不出" : v.stripTrailingZeros().toPlainString() + "%";
    }

    // ---------------- 损耗异常 ----------------
    static Map<String, Object> lossAnomaly(MaterialBalanceService service, String factoryId, LocalDate start,
                                           LocalDate end, int limit, String processKeyword, ProductScope scope,
                                           String[] textOut, BigDecimal alertPct) {
        Balance b = balance(service, factoryId, start, end, scope, alertPct);
        List<String> known = names(null, b);
        List<Abnormal> all = new ArrayList<>();
        for (Abnormal a : b.abnormal()) {
            if (processKeyword == null || keywordMatches(a.processName(), processKeyword, known)) all.add(a);
        }
        List<Abnormal> shown = all.size() > limit ? all.subList(0, limit) : all;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Abnormal a : shown) rows.add(a.toMap(b.warnPct()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("view", "loss_anomaly");
        data.put("productKeyword", scope.keyword());
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("abnormalCount", all.size());
        data.put("incomparableCount", b.incomparableRows());
        data.put("rows", rows);
        data.put("basis", basis(b));

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(scope.active() ? "「" + scope.keyword() + "」" : "")
                .append(processKeyword != null ? "「" + processKeyword + "」" : "");
        if (all.isEmpty()) {
            sb.append(" 没有损耗异常的批次工序（未解释差额都在 ").append(pct(b.warnPct())).append(" 以内）。");
        } else {
            sb.append(" 共 ").append(all.size()).append(" 个批次工序损耗异常（未解释差额占投入超过 ")
                    .append(pct(b.warnPct())).append("），偏差最大的 ").append(shown.size()).append(" 个：");
            int i = 0;
            for (Abnormal a : shown) {
                sb.append("\n").append(++i).append(". ").append(a.batchNumber()).append("（").append(a.productName())
                        .append("）· ").append(a.processName()).append("：标准 ≤ ").append(pct(b.warnPct()))
                        .append("，实际偏差 ").append(pct(a.deviationPct())).append("（")
                        .append("RED".equals(a.level()) ? "红" : "黄").append("），投入 ")
                        .append(strip(a.input())).append(" → 产出 ").append(strip(a.output()))
                        .append(a.unit() == null ? "" : a.unit());
            }
        }
        if (b.incomparableRows() > 0) {
            sb.append("\n另有 ").append(b.incomparableRows()).append(" 个批次工序单位不可比或没登记投入，没算偏差。");
        }
        sb.append("\n").append(basis(b));
        textOut[0] = sb.toString();
        return data;
    }

    // ---------------- 某工序近期出成率 ----------------
    static Map<String, Object> processTrend(YieldAnalysisService yield, MaterialBalanceService balance, String factoryId,
                                            LocalDate start, LocalDate end, String processKeyword, ProductScope scope,
                                            String[] textOut, BigDecimal alertPct) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        List<ProcessYieldAggDTO> curAll = aggregate(yield, factoryId, start, end, scope);
        List<ProcessYieldAggDTO> prev = aggregate(yield, factoryId, prevStart, prevEnd, scope);
        Balance bal = balance(balance, factoryId, start, end, scope, alertPct);
        List<String> known = names(curAll, bal);
        known.addAll(names(prev, bal));
        List<ProcessYieldAggDTO> cur = filter(curAll, processKeyword, known);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProcessYieldAggDTO c : cur) {
            ProcessYieldAggDTO p = find(prev, c.getProcessName());
            Map<String, Object> row = processRow(c);
            row.put("previousAvgYieldRate", p == null ? null : p.getConversionRate());
            row.put("yieldRateChange", p == null ? null : pp(c.getConversionRate(), p.getConversionRate()));
            long n = bal.abnormal().stream().filter(a -> attributes(a.processName(), c.getProcessName(), known)).count();
            row.put("abnormalBatchCount", n);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("view", "process");
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("comparedWith", Map.of("startDate", prevStart.toString(), "endDate", prevEnd.toString()));
        data.put("processKeyword", processKeyword);
        data.put("productKeyword", scope.keyword());
        data.put("productFilteredYield", scope.active() && !scope.yieldUnfiltered());
        data.put("rows", rows);
        data.put("basis", basis(bal));

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(scope.active() ? "「" + scope.keyword() + "」" : "")
                .append(processKeyword != null ? "「" + processKeyword + "」" : "");
        if (rows.isEmpty()) {
            sb.append(" 没有工序出成率记录（需要该期间有按工序的报工投入和产出）。");
        } else {
            sb.append(" 工序出成率：");
            for (Map<String, Object> r : rows) {
                sb.append("\n- ").append(r.get("processName")).append("：");
                if (r.get("avgYieldRate") == null) {
                    sb.append("投入产出单位不可比，算不出出成率");
                } else {
                    sb.append("出成率 ").append(pct((BigDecimal) r.get("avgYieldRate")))
                            .append("，损耗率 ").append(pct((BigDecimal) r.get("avgLossRate")));
                    BigDecimal ch = (BigDecimal) r.get("yieldRateChange");
                    sb.append(ch == null ? "，上一段同长期间无可比数据"
                            : "，较上一段（" + prevStart + " 至 " + prevEnd + "）"
                            + (ch.signum() >= 0 ? "上升 " : "下降 ") + ch.abs().toPlainString() + " 个百分点");
                }
                sb.append("，覆盖 ").append(r.get("batchCount")).append(" 个批次，异常批次 ")
                        .append(r.get("abnormalBatchCount")).append(" 个");
            }
            appendAbnormalLines(sb, bal, rows.stream().map(r -> String.valueOf(r.get("processName"))).toList(), known);
        }
        sb.append(scope.note());
        sb.append("\n").append(basis(bal));
        textOut[0] = sb.toString();
        return data;
    }

    // ---------------- 本月 vs 上月: 掉在哪道工序 ----------------
    static Map<String, Object> processCompare(YieldAnalysisService yield, MaterialBalanceService balance, String factoryId,
                                              LocalDate start, LocalDate end, LocalDate prevStart, LocalDate prevEnd,
                                              String processKeyword, ProductScope scope, String[] textOut,
                                              BigDecimal alertPct) {
        List<ProcessYieldAggDTO> curAll = aggregate(yield, factoryId, start, end, scope);
        List<ProcessYieldAggDTO> prev = aggregate(yield, factoryId, prevStart, prevEnd, scope);
        Balance bal = balance(balance, factoryId, start, end, scope, alertPct);
        List<String> known = names(curAll, bal);
        known.addAll(names(prev, bal));
        List<ProcessYieldAggDTO> cur = filter(curAll, processKeyword, known);
        List<Map<String, Object>> rows = new ArrayList<>();
        int notComparable = 0;
        for (ProcessYieldAggDTO c : cur) {
            ProcessYieldAggDTO p = find(prev, c.getProcessName());
            Map<String, Object> row = processRow(c);
            row.put("previousAvgYieldRate", p == null ? null : p.getConversionRate());
            row.put("previousAvgLossRate", p == null ? null : p.getWastageRate());
            row.put("yieldRateChange", p == null ? null : pp(c.getConversionRate(), p.getConversionRate()));
            if (row.get("yieldRateChange") == null) notComparable++;
            rows.add(row);
        }
        // 下降最多在前; 算不出变化的排最后
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("yieldRateChange"),
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, Object> worst = null;
        if (!rows.isEmpty() && rows.get(0).get("yieldRateChange") != null
                && ((BigDecimal) rows.get(0).get("yieldRateChange")).signum() < 0) {
            worst = rows.get(0);
        }
        List<Map<String, Object>> worstBatches = new ArrayList<>();
        if (worst != null) {
            String wn = String.valueOf(worst.get("processName"));
            for (Abnormal a : bal.abnormal()) {
                if (attributes(a.processName(), wn, known)) worstBatches.add(a.toMap(bal.warnPct()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("view", "process_compare");
        data.put("productKeyword", scope.keyword());
        data.put("productFilteredYield", scope.active() && !scope.yieldUnfiltered());
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("comparedWith", Map.of("startDate", prevStart.toString(), "endDate", prevEnd.toString()));
        data.put("rows", rows);
        data.put("biggestDropProcess", worst == null ? null : worst.get("processName"));
        data.put("biggestDropBatches", worstBatches);
        data.put("notComparableProcessCount", notComparable);
        data.put("basis", basis(bal));

        StringBuilder sb = new StringBuilder();
        sb.append(scope.active() ? "「" + scope.keyword() + "」" : "").append("本期 ").append(start).append(" 至 ").append(end).append(" 对比上期 ").append(prevStart).append(" 至 ")
                .append(prevEnd).append(" 的工序出成率：");
        if (rows.isEmpty()) {
            sb.append("\n本期没有工序出成率记录，无法比较。");
        } else {
            for (Map<String, Object> r : rows) {
                BigDecimal ch = (BigDecimal) r.get("yieldRateChange");
                sb.append("\n- ").append(r.get("processName")).append("：本期 ").append(pct((BigDecimal) r.get("avgYieldRate")))
                        .append(" / 上期 ").append(pct((BigDecimal) r.get("previousAvgYieldRate")));
                sb.append(ch == null ? "（不可比，未参与排序）"
                        : "，" + (ch.signum() >= 0 ? "上升 " : "下降 ") + ch.abs().toPlainString() + " 个百分点");
            }
            if (worst == null) {
                sb.append("\n没有出现出成率下降的工序（或下降无法比较）。");
            } else {
                sb.append("\n掉得最多的是「").append(worst.get("processName")).append("」，下降 ")
                        .append(((BigDecimal) worst.get("yieldRateChange")).abs().toPlainString()).append(" 个百分点。");
                if (worstBatches.isEmpty()) {
                    sb.append("该工序本期没有物料平衡异常的批次。");
                } else {
                    sb.append("该工序本期异常批次：");
                    int i = 0;
                    for (Map<String, Object> m : worstBatches) {
                        if (++i > 10) break;
                        sb.append("\n  ").append(i).append(". ").append(m.get("batchNumber")).append("（").append(m.get("productName"))
                                .append("）偏差 ").append(pct((BigDecimal) m.get("deviationPct"))).append("，标准 ≤ ")
                                .append(pct(bal.warnPct()));
                    }
                }
            }
        }
        sb.append(scope.note());
        sb.append("\n").append(basis(bal));
        textOut[0] = sb.toString();
        return data;
    }

    private static void appendAbnormalLines(StringBuilder sb, Balance bal, List<String> processNames,
                                            List<String> known) {
        List<Abnormal> hit = new ArrayList<>();
        for (Abnormal a : bal.abnormal()) {
            for (String n : processNames) {
                if (attributes(a.processName(), n, known)) { hit.add(a); break; }
            }
        }
        if (hit.isEmpty()) return;
        sb.append("\n异常批次（标准 ≤ ").append(pct(bal.warnPct())).append("）：");
        int i = 0;
        for (Abnormal a : hit) {
            if (++i > 10) break;
            sb.append("\n  ").append(i).append(". ").append(a.batchNumber()).append("（").append(a.productName()).append("）· ")
                    .append(a.processName()).append("：偏差 ").append(pct(a.deviationPct()));
        }
    }

    private static Map<String, Object> processRow(ProcessYieldAggDTO c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("processName", c.getProcessName());
        // 键名在 ProcessSecretPayloadScrubber.AGGREGATE_ACTUAL_FIELDS: 跨批汇总, 无工艺保密授权者由裁剪器按配比类隐藏
        row.put("avgYieldRate", c.getConversionRate());
        row.put("avgLossRate", c.getWastageRate());
        row.put("inputQuantity", c.getInputQuantity());
        row.put("outputQuantity", c.getOutputQuantity());
        row.put("unit", c.getInputUnit());
        row.put("batchCount", c.getBatchCount());
        row.put("unitComparable", c.getUnitComparable());
        return row;
    }

    private static List<ProcessYieldAggDTO> filter(List<ProcessYieldAggDTO> all, String keyword, List<String> known) {
        List<ProcessYieldAggDTO> out = new ArrayList<>();
        if (all == null) return out;
        for (ProcessYieldAggDTO a : all) {
            if (keyword == null || keywordMatches(a.getProcessName(), keyword, known)) out.add(a);
        }
        return out;
    }

    private static ProcessYieldAggDTO find(List<ProcessYieldAggDTO> list, String name) {
        if (list == null || name == null) return null;
        for (ProcessYieldAggDTO a : list) {
            if (name.equals(a.getProcessName())) return a;
        }
        return null;
    }

    private static String strip(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
