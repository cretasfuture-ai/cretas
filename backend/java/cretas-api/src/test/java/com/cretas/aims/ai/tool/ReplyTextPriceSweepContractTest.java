package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERP-S6-MSG-TEXT-PRICE-LEAK-001 同因扫描闸: 把金额写进回复文字的地方, 必须与字段同一个可见性判定。
 *
 * <ol>
 *   <li>结果格式化器 (formattedText): 只能经 {@code ResultFormatterService.formatAndSet(response, viewer)}
 *       —— 它从 {@code PriceFieldResponseAdvice.maskedViewFor} 渲染; 生产代码不许直接调 {@code format(...)}。</li>
 *   <li>SSE 推送的 result 不经过 HTTP 响应层, {@code sendSseEvent} 必须走 {@code priceMaskedWire}。</li>
 *   <li>工具自己写文字: ai/tool/impl 下每个把 ¥ / 万元 / …元 写进字符串的文件都登记在 {@link #REVIEWED},
 *       新增一个没登记的就红; 登记为 {@link Verdict#CHECKED} 的必须真的调用
 *       {@code replyShowsAmount / replyShowsAmounts / replyShowsPriceSpec} (删掉判定就红)。</li>
 * </ol>
 */
class ReplyTextPriceSweepContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");
    private static final Path TOOLS = MAIN.resolve("ai/tool/impl");

    enum Verdict {
        /** 文字里的金额按同名字段的可见性写 (AbstractTool.replyShowsAmount / replyShowsPriceSpec)。 */
        CHECKED,
        /** 返回与 REST 端点同一份类型化 DTO、门槛相同: 字段与文字按 REST 口径 (登记的标识必须仍在文件里)。 */
        SAME_AS_REST,
        /** 工具内自带价格权限判定 (登记的判定标识必须仍在文件里)。 */
        OWN_GATE,
        /** 金额由上游 (Python gold 服务按 X-User-Role) 剥掉后才到这里; 本 PR 未逐字段核对。 */
        UPSTREAM_STRIPPED,
        /** 不是给用户看的金额: 工具描述 / 给大模型的格式示例 / 回显调用者自己刚输入的数。 */
        NOT_PRICE_SENSITIVE,
        /** 餐饮线 (2026-10-08 Steve 裁定废弃, 不再开发): 没有逐字段判定, 单独跟进。 */
        FOLLOW_UP_RESTAURANT
    }

    record Entry(Verdict verdict, String note) {
    }

    private static final Map<String, Entry> REVIEWED = new LinkedHashMap<>();

    private static void reviewed(String path, Verdict verdict, String note) {
        REVIEWED.put(path, new Entry(verdict, note));
    }

    static {
        // ── CHECKED: 本 PR 起按字段判定 ──
        reviewed("crm/CustomerPriceMemoryQueryTool.java", Verdict.CHECKED, "unitPrice");
        reviewed("foodsafety/RecallLossEstimateTool.java", Verdict.CHECKED, "totalEstimatedLoss…brandLoss");
        reviewed("foodsafety/RegulatoryReportGenerateTool.java", Verdict.CHECKED, "estimatedLoss");
        reviewed("hr/ExpenseRequestQueryTool.java", Verdict.CHECKED, "total / 类别 / amount");
        reviewed("pricing/PricingTestCalculateTool.java", Verdict.CHECKED, "originalPrice / finalPrice / totalDiscount");
        reviewed("purchase/ProcurementOrderCreateTool.java", Verdict.CHECKED, "estimatedUnitPrice / estimatedBudget");
        reviewed("report/ReportCostVarianceTool.java", Verdict.SAME_AS_REST, "sameVisibilityAsRest(");
        reviewed("report/SkuGrossMarginTool.java", Verdict.CHECKED, "revenue / cost / price / unitCost");
        reviewed("workdesk/AccountsReceivableAgingTool.java", Verdict.CHECKED, "totalAmount / highRiskAmount");
        reviewed("workdesk/BalanceSheetQueryTool.java", Verdict.CHECKED, "totalAssets / totalLiabilities / totalEquity");
        reviewed("workdesk/CashflowStatementQueryTool.java", Verdict.CHECKED, "四项净现金流");
        reviewed("workdesk/CommissionPendingTotalTool.java", Verdict.CHECKED, "totalAmount");
        reviewed("workdesk/IncomeStatementQueryTool.java", Verdict.CHECKED, "利润表五项");
        reviewed("workdesk/OpportunityFunnelStatsTool.java", Verdict.CHECKED, "activeValue / activeExpected / totalValue");
        reviewed("workdesk/RequisitionCreateTool.java", Verdict.CHECKED, "estimatedUnitPrice / estimatedBudget");
        reviewed("workdesk/WageCostSummaryTool.java", Verdict.CHECKED, "totalAmount / totalPieceRate / totalHourly / totalOvertime");
        reviewed("workflow/ApprovalPendingQueryTool.java", Verdict.CHECKED, "PO 金额按 PriceSensitive.PURCHASE");
        // ── OWN_GATE: 工具自带判定 (note = 判定标识) ──
        // ── NOT_PRICE_SENSITIVE ──
        reviewed("print/PrintTemplateCreateFromAITool.java", Verdict.NOT_PRICE_SENSITIVE, "给大模型的格式示例 ¥1,234.50, 不是数据");
        reviewed("rules/RuleTestEvaluateTool.java", Verdict.NOT_PRICE_SENSITIVE, "工具描述里的规则举例");
    }

    /** 非注释行里, 字符串字面量含 ¥ / 万元 / 以「元」结尾。 */
    private static final Pattern MONEY_LITERAL = Pattern.compile("\"[^\"\\n]*(?:¥|万元)|元\"");
    private static final Pattern CHECK_CALL = Pattern.compile("replyShows(?:Amounts?|PriceSpec)\\(");

    @Test
    void everyToolThatWritesMoneyIntoTextIsReviewed() throws IOException {
        Set<String> found = moneyTextFiles();
        // Dedicated restaurant implementations were removed; keep an exact non-vacuous denominator.
        assertThat(found).as("工厂目录金额文本审计分母").hasSize(19);

        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(REVIEWED.keySet());
        Set<String> stale = new TreeSet<>(REVIEWED.keySet());
        stale.removeAll(found);
        assertThat(unreviewed).as("新出现的「金额写进文字」工具: 用 replyShowsAmount(context, 字段键) 判定后登记为 CHECKED").isEmpty();
        assertThat(stale).as("登记了但已不再写金额文字的文件: 从 REVIEWED 删掉").isEmpty();

        Map<Verdict, Integer> totals = new EnumMap<>(Verdict.class);
        REVIEWED.values().forEach(e -> totals.merge(e.verdict(), 1, Integer::sum));
        System.out.println("[reply-text-price-sweep] files=" + found.size() + " " + totals);
        assertThat(totals.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(found.size());
    }

    @Test
    void checkedToolsReallyAskTheFieldDecision() throws IOException {
        Map<String, String> missing = new TreeMap<>();
        for (Map.Entry<String, Entry> e : REVIEWED.entrySet()) {
            String src = Files.readString(TOOLS.resolve(e.getKey()), StandardCharsets.UTF_8);
            switch (e.getValue().verdict()) {
                case CHECKED -> {
                    if (!CHECK_CALL.matcher(src).find()) missing.put(e.getKey(), "no replyShowsAmount/PriceSpec call");
                }
                case OWN_GATE, SAME_AS_REST -> {
                    if (!src.contains(e.getValue().note())) missing.put(e.getKey(), "gate token gone: " + e.getValue().note());
                }
                default -> { }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void replyTextIsRenderedOnlyFromTheMaskedView() throws IOException {
        String impl = Files.readString(MAIN.resolve("service/impl/ResultFormatterServiceImpl.java"), StandardCharsets.UTF_8);
        assertThat(impl).as("formatAndSet 从同一个判定取视图再渲染").contains("priceView.maskedViewFor(");
        String sse = Files.readString(MAIN.resolve("service/execution/SseStreamingService.java"), StandardCharsets.UTF_8);
        assertThat(sse).as("SSE result 推送前按看的人遮蔽")
                .contains(".data(priceMaskedWire(eventName, data),")
                .contains("MediaType.APPLICATION_JSON")
                .contains("private Object priceMaskedWire(String eventName, Object data)")
                .contains("objectMapper.valueToTree(response)");

        // 持有 ResultFormatterService 的生产类不许绕过 formatAndSet 直接 format(...)
        Pattern field = Pattern.compile("ResultFormatterService\\s+(\\w+)\\s*[;=]");
        Map<String, Integer> directCalls = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (p.endsWith("ResultFormatterServiceImpl.java") || !src.contains("ResultFormatterService")) continue;
                Matcher m = field.matcher(src);
                while (m.find()) {
                    int n = countMatches(src, m.group(1) + ".format(");
                    if (n > 0) directCalls.put(MAIN.relativize(p).toString(), n);
                }
            }
        }
        assertThat(directCalls).as("直接 format(...) 会从未遮蔽的数据渲染文字").isEmpty();
    }

    private static Set<String> moneyTextFiles() throws IOException {
        try (Stream<Path> files = Files.walk(TOOLS)) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                            .map(String::strip)
                            .filter(l -> !(l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")))
                            .anyMatch(l -> MONEY_LITERAL.matcher(l).find());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> TOOLS.relativize(p).toString().replace('\\', '/')).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static int countMatches(String src, String needle) {
        int n = 0;
        for (int i = src.indexOf(needle); i >= 0; i = src.indexOf(needle, i + 1)) n++;
        return n;
    }
}
