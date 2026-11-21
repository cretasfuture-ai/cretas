package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.yield.YieldAnalysisService;
import com.cretas.aims.service.shortage.CostDeviationThresholdService;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 批次出成率排行 (只读, 对应意图 REPORT_BATCH_YIELD_RANK) —— 「哪个批次出成率最低」「各批次出成率排序」。
 *
 * <p>出成率 = 良品数量 ÷ 实际产出数量 × 100, 由批次自己的两个数量<b>现算</b>, 不读 yield_rate 列
 * (该列历史上有冻结旧值的问题, 见 {@link ProductionBatch#calculateMetrics()} 注释)。良品或实际数量缺失/为 0 的批次
 * 算不出, 不进排行, 单独报个数 (不当 0 也不当 100)。只统计已完成批次。工艺保密而无权查看的产品批次整批不进排行并说明。
 */
@Component
public class ReportBatchYieldRankTool extends AbstractBusinessTool {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 30;
    private static final int MAX_EXPLICIT_LOSS_WINDOW_DAYS = 90;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private ProductionBatchRepository batchRepository;

    @Autowired
    private YieldAnalysisService yieldAnalysisService;

    @Autowired
    private MaterialBalanceService materialBalanceService;

    /**
     * 「发现的问题」页损耗异常的告警阈值来源; 问句里的「损耗异常」与它同一个线 (BP 问题 28)。
     * 只读阈值配置数值与来源 (受控数据闸 INTERNAL), 不经 CostDeviationScanService (它还返回偏差业务数据)。
     */
    @Autowired(required = false)
    private CostDeviationThresholdService costDeviationThresholdService;

    @Autowired(required = false)
    private ProcessSecretLlmCostScope llmCostScope = ProcessSecretLlmCostScope.standalone();

    @Override
    public String getToolName() {
        return "report_batch_yield_rank";
    }

    @Override
    public String getDescription() {
        return "查询批次出成率排行（只读）：期间内已完成批次按出成率(良品÷实际产出)从低到高或从高到低排序，"
                + "附出成率柱图；可按产品名筛选。算不出出成率的批次（缺良品/实际数量）不进排行并说明个数。"
                + "适用：哪个批次出成率最低/最高、各批次出成率排序、出成率差的批次。"
                + "也能按工序答：view=loss_anomaly 最近有哪些损耗异常（批次/工序/标准vs实际偏差）、"
                + "view=process 某工序（processName，模糊匹配）近期出成率与趋势、"
                + "view=process_compare 本月对比上月各工序出成率、掉在哪道工序。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return ReportToolSchemas.withOptionalExtraction(Map.of(
                "type", "object",
                "properties", Map.of(
                        "startDate", Map.of("type", "string", "format", "date", "description", "开始日期 yyyy-MM-dd，默认最近 30 天"),
                        "endDate", Map.of("type", "string", "format", "date", "description", "结束日期 yyyy-MM-dd，默认今天"),
                        "period", Map.of("type", "string", "description", "快捷周期: last_week / this_week / this_month / last_month；与日期同时给时以日期为准"),
                        "productName", Map.of("type", "string", "description", "产品名（可选，模糊匹配）"),
                        "order", Map.of("type", "string", "description", "lowest=出成率最低在前(默认) / highest=最高在前"),
                        "limit", Map.of("type", "integer", "description", "返回前几名，默认 10，最多 30"),
                        "view", Map.of("type", "string", "description",
                                "batch=批次排行(默认) / loss_anomaly=损耗异常的批次工序 / process=某工序近期出成率 / process_compare=本期对比上期各工序出成率"),
                        "processName", Map.of("type", "string", "description", "工序名（可选，模糊匹配，如 熟制杀菌）")
                ),
                "required", List.of()));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    protected LocalDate today() {
        return LocalDate.now();
    }

    /** 校验用户给出的完整损耗查询区间。报工按整段读入，起止日都计入天数。 */
    public String explicitLossAnomalyWindowRejection(LocalDate start, LocalDate end) {
        LocalDate today = today();
        if (end.isAfter(today)) {
            return "结束日期不能晚于今天（" + today + "）。请调整日期后重试。";
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_EXPLICIT_LOSS_WINDOW_DAYS) {
            return "损耗异常查询区间过长，请缩短到 " + MAX_EXPLICIT_LOSS_WINDOW_DAYS + " 天以内，或分段查询。";
        }
        return null;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        LocalDate today = today();
        String startText = blank(getString(params, "startDate"));
        String endText = blank(getString(params, "endDate"));
        String period = blank(getString(params, "period"));
        LocalDate start;
        LocalDate end;
        if (startText != null || endText != null) {
            end = endText != null ? parse(endText) : today;
            start = startText != null ? parse(startText) : end.minusDays(29);
        } else if (period != null) {
            LocalDate[] r = ReportCostQueryTool.periodRange(period, today);
            start = r[0];
            end = r[1];
        } else {
            end = today;
            start = today.minusDays(29);
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        String keyword = ReportToolSchemas.productKeyword(getString(params, "productName"));
        View view = resolveView(factoryId, params, start, end, today);
        if (view == View.LOSS_ANOMALY && end.isAfter(today) && !start.isAfter(today)) {
            // 「本周」等预处理给到周日，只查询到今天。
            end = today;
        }
        String windowNotice = null;
        if (view == View.LOSS_ANOMALY && isBareRecentWindow(params, startText, endText, period, start, end, today)) {
            // 全局「最近」= 含今天 7 天, 对低频的报工 / 损耗异常太窄 (BP 问题 28: F006 最后一天报工 11-05, 7 天窗口一条都没有);
            // 只对损耗异常视图放宽到 30 天, 与工具默认和「发现的问题」扫描回看天数一致; 用户给了日期/期间的不动
            start = today.minusDays(29);
            end = today;
            windowNotice = "「最近」按近 30 天（" + start + " 至 " + end + "）查。";
        }
        if (view != View.BATCH) {
            return processView(view, factoryId, params, startText != null || endText != null, period, start, end, today,
                    resolveProductScope(factoryId, keyword, today), windowNotice);
        }
        boolean lowestFirst = !"highest".equalsIgnoreCase(getString(params, "order", "lowest"));
        int limit = DEFAULT_LIMIT;
        Object limitRaw = params.get("limit");
        if (limitRaw instanceof Number n && n.intValue() > 0) {
            limit = Math.min(n.intValue(), MAX_LIMIT);
        }

        ProcessSecretLlmCostScope.Excluded secret = llmCostScope == null
                ? ProcessSecretLlmCostScope.Excluded.NONE : llmCostScope.forCurrentCaller(factoryId);
        List<ProductionBatch> batches = batchRepository.findBatchesInDateRange(
                factoryId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        List<Map<String, Object>> ranked = new ArrayList<>();
        int completed = 0;
        int noYield = 0;
        int withheld = 0;
        if (batches != null) {
            for (ProductionBatch b : batches) {
                if (b.getStatus() != ProductionBatchStatus.COMPLETED) continue;
                if ("CLERK_WIP".equals(b.getBatchType())) continue;
                if (keyword != null && (b.getProductName() == null
                        || !b.getProductName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))) continue;
                if (secret.coversBatch(b.getProductTypeId(), b.getProductionPlanId())) {
                    withheld++;
                    continue;
                }
                completed++;
                BigDecimal good = b.getGoodQuantity();
                BigDecimal actual = b.getActualQuantity();
                if (good == null || actual == null || actual.signum() <= 0) {
                    noYield++;
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchNumber", b.getBatchNumber());
                row.put("productName", b.getProductName());
                row.put("actualQuantity", actual);
                row.put("goodQuantity", good);
                row.put("unit", b.getUnit());
                // 键名 yieldRate (百分数) 在 ProcessSecretPayloadScrubber.SINGLE_RUN_ACTUAL_FIELDS: AI 回答按汇总视图处理,
                // 无工艺保密授权的调用者由裁剪器按配比类隐藏; 保密产品批次在上面已按调用者整批剔除 (双层)。
                row.put("yieldRate", good.multiply(HUNDRED).divide(actual, 2, RoundingMode.HALF_UP));
                ranked.add(row);
            }
        }
        Comparator<Map<String, Object>> byYield = Comparator.comparing(r -> (BigDecimal) r.get("yieldRate"));
        ranked.sort(lowestFirst ? byYield : byYield.reversed());
        List<Map<String, Object>> shown = ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("order", lowestFirst ? "lowest" : "highest");
        data.put("completedBatchCount", completed);
        data.put("rankedBatchCount", ranked.size());
        data.put("unrankedBatchCount", noYield);
        data.put("withheldBatchCount", withheld);
        data.put("rows", shown);
        data.put("basis", "出成率 = 良品数量 ÷ 实际产出数量 × 100，由批次数量现算；只统计已完成批次");
        if (!shown.isEmpty()) {
            List<String> names = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (Map<String, Object> r : shown) {
                names.add(String.valueOf(r.get("batchNumber")));
                values.add((BigDecimal) r.get("yieldRate"));
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("tooltip", Map.of("trigger", "axis"));
            option.put("grid", Map.of("left", 50, "right", 20, "bottom", 80, "containLabel", true));
            option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 30)));
            option.put("yAxis", Map.of("type", "value", "name", "%", "max", 100));
            option.put("series", List.of(Map.of("type", "bar", "name", "出成率(%)", "data", values)));
            data.put("charts", List.of(Map.of("type", "bar",
                    "title", "批次出成率（%，" + (lowestFirst ? "最低" : "最高") + "前 " + shown.size() + "）",
                    "option", option)));
        }
        String text = message(shown, ranked.size(), completed, noYield, withheld, start, end, lowestFirst, keyword);
        if (completed == 0 && withheld == 0) {
            // 窗口 (默认的、或「最近」这类对话补的) 没数据: 不让用户以为「没有批次」, 说明最近一次已完成批次的日期 (往前看一年)
            LocalDate latest = latestCompletedDate(factoryId, today, keyword);
            data.put("latestCompletedBatchDate", latest == null ? null : latest.toString());
            text = latest == null
                    ? text + "\n近一年内也没有已完成的批次记录。"
                    : text + "\n最近一次已完成批次是 " + latest + "，想看那段时间可以说「" + latest.getMonthValue() + "月出成率最低的批次」。";
        }
        return buildSimpleResult(text, data);
    }

    enum View { BATCH, LOSS_ANOMALY, PROCESS, PROCESS_COMPARE }

    private static String said(Map<String, Object> params) {
        Object o = params.get("originalUserInput") != null ? params.get("originalUserInput") : params.get("userInput");
        return o == null ? "" : o.toString();
    }

    /**
     * 选视图: 参数 view 优先; 没给 (只有路由命中, 没抽参) 时读原话 —— 「损耗」→ 损耗异常; 「为什么掉/掉在哪/对比」→ 本期对比上期;
     * 带工序名 (参数, 或原话里出现本厂某道工序名) 且问出成率 → 该工序出成率; 其余保持批次排行 (「出成率异常的批次」等老问法不变)。
     */
    private View resolveView(String factoryId, Map<String, Object> params, LocalDate start, LocalDate end, LocalDate today) {
        String v = blank(getString(params, "view"));
        if (v != null) {
            switch (v.toLowerCase(Locale.ROOT)) {
                case "loss_anomaly": return View.LOSS_ANOMALY;
                case "process": return View.PROCESS;
                case "process_compare": return View.PROCESS_COMPARE;
                case "batch": return View.BATCH;
                default: break;
            }
        }
        String text = said(params);
        boolean yieldAsked = text.contains("出成率") || text.contains("出成") || text.contains("得率");
        if (text.contains("物料平衡") || (text.contains("损耗") && text.contains("异常"))) return View.LOSS_ANOMALY;
        // 「出成率和损耗率最低的批次」是批次排行 (原话带排名词 + 批次/出成率), 不因出现「损耗」二字进损耗异常视图
        boolean rankAsked = text.contains("最低") || text.contains("最高") || text.contains("最差") || text.contains("最好")
                || text.contains("排行") || text.contains("排序") || text.contains("排名");
        if (text.contains("损耗") && !(rankAsked && (yieldAsked || text.contains("批次")))) return View.LOSS_ANOMALY;
        if (yieldAsked && (text.contains("为什么") || text.contains("为啥") || text.contains("掉") || text.contains("下降")
                || text.contains("降了") || text.contains("对比") || text.contains("比上月") || text.contains("哪道工序")
                || text.contains("哪个工序"))) {
            return View.PROCESS_COMPARE;
        }
        if (blank(getString(params, "processName")) != null) return View.PROCESS;
        if (yieldAsked && text.contains("工序")) return View.PROCESS;
        if (yieldAsked && matchedProcessName(factoryId, text, today) != null) return View.PROCESS;
        return View.BATCH;
    }

    /** 「最近」后面跟了数量 (最近7天 / 最近三个月 / 最近2周): 用户给了明确期间, 不属于笼统的「最近」。 */
    private static final java.util.regex.Pattern RECENT_WITH_COUNT = java.util.regex.Pattern.compile(
            "最近\\s*[0-9０-９一二两三四五六七八九十几半]");

    /**
     * 窗口是不是全局预处理对笼统「最近」补的那 7 天: 原话有「最近」且没跟数量、没说期间词 (本月/上月/本周/某月某日),
     * 没带 period, 且日期正好是 今天-6 ~ 今天。用户明确给的日期、期间一律不是。
     */
    static boolean isBareRecentWindow(Map<String, Object> params, String startText, String endText, String period,
                                      LocalDate start, LocalDate end, LocalDate today) {
        if (period != null || startText == null || endText == null) return false;
        if (!start.equals(today.minusDays(6)) || !end.equals(today)) return false;
        String text = said(params);
        if (!text.contains("最近") || RECENT_WITH_COUNT.matcher(text).find()) return false;
        // 「最近」之外还说了别的期间: 以用户说的为准 (预处理哪个范围排第一不由这里猜)
        return !java.util.regex.Pattern.compile("本月|这个月|上个月|上月|本周|这周|上周|今年|去年|本季|[0-9０-９]+\\s*[月日号]|[0-9０-９]+\\s*天").matcher(text).find();
    }

    /** 原话里出现的本厂工序名 (近一年有按工序报工的工序; 名字至少 2 个字, 取最长的命中)。 */
    private String matchedProcessName(String factoryId, String text, LocalDate today) {
        if (yieldAnalysisService == null || text.isBlank()) return null;
        java.util.List<com.cretas.aims.dto.yield.ProcessYieldAggDTO> all =
                yieldAnalysisService.aggregateByProcess(factoryId, today.minusDays(365), today, null);
        String best = null;
        if (all != null) {
            for (com.cretas.aims.dto.yield.ProcessYieldAggDTO a : all) {
                String n = a.getProcessName();
                if (n != null && n.trim().length() >= 2 && text.contains(n.trim()) && (best == null || n.length() > best.length())) {
                    best = n.trim();
                }
            }
        }
        return best;
    }

    private Map<String, Object> processView(View view, String factoryId, Map<String, Object> params, boolean explicitDates,
                                            String period, LocalDate start, LocalDate end, LocalDate today,
                                            ProcessYieldViews.ProductScope scope, String windowNotice) throws Exception {
        String text = said(params);
        String processKeyword = blank(getString(params, "processName"));
        if (processKeyword == null && view != View.LOSS_ANOMALY) processKeyword = matchedProcessName(factoryId, text, today);
        int limit = DEFAULT_LIMIT;
        if (params.get("limit") instanceof Number n && n.intValue() > 0) limit = Math.min(n.intValue(), MAX_LIMIT);
        String[] msg = new String[1];
        Map<String, Object> data;
        ProcessYieldViews.AlertLine alert = alertLine(factoryId);
        if (view == View.LOSS_ANOMALY) {
            data = ProcessYieldViews.lossAnomaly(materialBalanceService, factoryId, start, end, limit, processKeyword, scope, msg,
                    alert, windowNotice);
        } else if (view == View.PROCESS) {
            data = ProcessYieldViews.processTrend(yieldAnalysisService, materialBalanceService, factoryId, start, end,
                    processKeyword, scope, msg, alert);
        } else {
            LocalDate s = start;
            LocalDate e = end;
            if (!explicitDates && period == null) {
                // 没说期间: 比较 = 本月 vs 上月; 原话说「上个月」则 上月 vs 再上月
                LocalDate[] r = ReportCostQueryTool.periodRange(text.contains("上个月") || text.contains("上月") ? "last_month" : "this_month", today);
                s = r[0];
                e = r[1];
            }
            LocalDate ps;
            LocalDate pe;
            if (s.getDayOfMonth() == 1 && s.getMonth() == e.getMonth() && s.getYear() == e.getYear()) {
                ps = s.minusMonths(1);
                pe = s.minusDays(1);
            } else {
                long days = java.time.temporal.ChronoUnit.DAYS.between(s, e) + 1;
                pe = s.minusDays(1);
                ps = pe.minusDays(days - 1);
            }
            data = ProcessYieldViews.processCompare(yieldAnalysisService, materialBalanceService, factoryId, s, e, ps, pe,
                    processKeyword, scope, msg, alert);
        }
        Map<String, Object> result = buildSimpleResult(msg[0], data);
        if (view == View.LOSS_ANOMALY && params.containsKey(TimeRangeFilter.SAID)) {
            result.put(TimeRangeFilter.APPLIED, Map.of("from", start.toString(), "to", end.toString()));
        }
        return result;
    }

    /**
     * 「发现的问题」损耗(物料平衡)告警阈值与来源: 工厂配的 (FACTORY) 优先, 其次系统默认 (DEFAULT), 都没有 = NONE
     * (NONE 时用物料平衡自己的阈值, 回答里写明用的是哪个; 默认值标明是默认, 不冒充本厂配置)。
     */
    private ProcessYieldViews.AlertLine alertLine(String factoryId) {
        if (costDeviationThresholdService == null) return ProcessYieldViews.AlertLine.NONE;
        CostDeviationThresholdService.BalanceAlert a = costDeviationThresholdService.balanceAlert(factoryId);
        return a == null || a.pct() == null ? ProcessYieldViews.AlertLine.NONE
                : new ProcessYieldViews.AlertLine(a.pct(), a.source());
    }

    /**
     * 产品名 → 工序出成率聚合要的产品类型 ID (近一年里名称含该词的批次): 唯一对应才筛; 一个都没有 (none) 整体为空并说明;
     * 对应多个类型时聚合不筛、回复里明说 (异常批次仍按产品名筛)。
     */
    private ProcessYieldViews.ProductScope resolveProductScope(String factoryId, String keyword, LocalDate today) {
        if (keyword == null) return ProcessYieldViews.ProductScope.ALL;
        List<ProductionBatch> wide = batchRepository.findBatchesInDateRange(
                factoryId, today.minusDays(365).atStartOfDay(), today.plusDays(1).atStartOfDay());
        Set<String> ids = new LinkedHashSet<>();
        boolean any = false;
        boolean nullId = false;
        if (wide != null) {
            for (ProductionBatch b : wide) {
                if ("CLERK_WIP".equals(b.getBatchType()) || b.getProductName() == null
                        || !b.getProductName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) continue;
                any = true;
                if (b.getProductTypeId() == null) nullId = true; else ids.add(b.getProductTypeId());
            }
        }
        if (!any) return new ProcessYieldViews.ProductScope(keyword, null, true);
        if (ids.size() == 1 && !nullId) return new ProcessYieldViews.ProductScope(keyword, ids.iterator().next(), false);
        return new ProcessYieldViews.ProductScope(keyword, null, false);
    }

    private LocalDate latestCompletedDate(String factoryId, LocalDate today, String keyword) {
        List<ProductionBatch> wide = batchRepository.findBatchesInDateRange(
                factoryId, today.minusDays(365).atStartOfDay(), today.plusDays(1).atStartOfDay());
        LocalDate latest = null;
        if (wide != null) {
            for (ProductionBatch b : wide) {
                if (b.getStatus() != ProductionBatchStatus.COMPLETED || "CLERK_WIP".equals(b.getBatchType())) continue;
                if (keyword != null && (b.getProductName() == null
                        || !b.getProductName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))) continue;
                java.time.LocalDateTime t = b.getEndTime() != null ? b.getEndTime() : b.getUpdatedAt();
                if (t == null) continue;
                if (latest == null || t.toLocalDate().isAfter(latest)) latest = t.toLocalDate();
            }
        }
        return latest;
    }

    private static String message(List<Map<String, Object>> shown, int rankedCount, int completed, int noYield,
                                  int withheld, LocalDate start, LocalDate end, boolean lowestFirst, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(keyword != null ? "「" + keyword + "」" : "")
                .append(" 已完成批次 ").append(completed).append(" 个");
        if (shown.isEmpty()) {
            sb.append("，没有能算出出成率的批次（需要良品和实际产出数量）。");
        } else {
            sb.append("，其中 ").append(rankedCount).append(" 个算得出出成率。出成率").append(lowestFirst ? "最低" : "最高")
                    .append("的 ").append(shown.size()).append(" 个批次：");
            int i = 0;
            for (Map<String, Object> r : shown) {
                sb.append("\n").append(++i).append(". ").append(r.get("batchNumber")).append("（").append(r.get("productName"))
                        .append("）：出成率 ").append(r.get("yieldRate")).append("%，良品 ").append(stripped(r.get("goodQuantity")))
                        .append(" / 实际 ").append(stripped(r.get("actualQuantity"))).append(r.get("unit") == null ? "" : String.valueOf(r.get("unit")));
            }
        }
        if (noYield > 0) {
            sb.append("\n另有 ").append(noYield).append(" 个批次缺良品或实际产出数量，算不出出成率，未进排行。");
        }
        if (withheld > 0) {
            sb.append("\n另有 ").append(withheld).append(" 个批次涉及工艺保密产品，当前账号无权查看，未计入。");
        }
        sb.append("\n口径：出成率 = 良品数量 ÷ 实际产出数量；只统计已完成批次。");
        return sb.toString();
    }

    private static String stripped(Object v) {
        return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd: " + s);
        }
    }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public java.util.Set<String> getDomainTags() {
        return java.util.Set.of("report", "production");
    }
}
