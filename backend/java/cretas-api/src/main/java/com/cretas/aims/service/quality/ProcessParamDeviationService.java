package com.cretas.aims.service.quality;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.ProcessParamDeviationDTO;
import com.cretas.aims.entity.workprocess.ProcessParamCheck;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工艺参数偏离汇总 (只读)。
 *
 * <h2>数据从哪来</h2>
 * 报工时落库的判定快照 {@code production_reports.custom_fields.paramChecks}: 每项
 * {@code {key,label,unit,value,standardValue,min,max,status}}, status = OK / LOW / HIGH, 按<b>报工当时</b>的标准判。
 * 两条写入入口同一形状:
 * <ul>
 *   <li>App 产出报工 ({@code YieldReportServiceImpl.withProcessParams}): 一次报工一条记录;</li>
 *   <li>web 工序表 / 文员逐道录入 ({@code ClerkProcessEntryServiceImpl.processEntryCustomFields}): 同一道工序
 *       <b>最多写三条</b>报工 (调料 SEASONING / 人工 LABOR / 辅助 YIELD), 每条都带同一份快照。
 *       直接数会把一次填报算成两三次, 所以按 {@code (batch_id, processEntryStepKey)} 去重, 每次填报只算一次。</li>
 * </ul>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>分母 = 「填了该参数、有判定快照」的次数 (快照只在配了上下限且填了数字时才产出), 不是全部报工次数;</li>
 *   <li>偏离 = status 为 LOW / HIGH; OK 不算;</li>
 *   <li>汇总不受 status 筛选影响 (筛了偏离再算偏离率分母就没了), status 只用于明细;</li>
 *   <li>工艺保密: 与厂级出成率汇总同口径, 调用者无完整查看权的保密产品 (及牵涉它们的计划) 不进汇总也不进明细。</li>
 * </ul>
 *
 * <h2>为什么 jsonb 在 Java 里解</h2>
 * 仓库查询只把 custom_fields 整段当文本取回 (LIKE 预筛), 解析在这里: 同一份代码在 PG 与测试库 H2 上得到同一个结果,
 * 不依赖 PG 专有的 jsonb 函数。解析失败直接抛错, 不跳过、不补默认。
 */
@Service
@RequiredArgsConstructor
public class ProcessParamDeviationService {

    /** 默认窗口: 最近 30 天 (含今天)。 */
    public static final int DEFAULT_WINDOW_DAYS = 30;
    /** 最大跨度 (天, 含两端), 约一个季度。 */
    public static final int MAX_SPAN_DAYS = 92;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ProductionReportRepository reportRepo;
    private final ProcessSecretAggregateExclusions aggregateExclusions;
    private final ObjectMapper objectMapper;

    /** 筛选条件 (除 status 外; status 只作用于明细)。 */
    public record Filter(LocalDate startDate, LocalDate endDate, String processName, String productTypeId,
                         String batchNumber) {
    }

    @Transactional(readOnly = true)
    public ProcessParamDeviationDTO.SummaryView summary(String factoryId, Filter filter) {
        ProcessParamDeviationDTO.Window window = resolveWindow(filter.startDate(), filter.endDate());
        List<Check> checks = loadChecks(factoryId, window, filter);

        Map<String, Acc> groups = new LinkedHashMap<>();
        for (Check c : checks) {
            Acc acc = groups.computeIfAbsent((c.processName == null ? "" : c.processName) + "\u0000" + c.paramKey,
                    k -> new Acc(c.processName, c.paramKey));
            acc.add(c);
        }
        List<ProcessParamDeviationDTO.SummaryRow> rows = new ArrayList<>();
        long totalDeviations = 0;
        for (Acc a : groups.values()) {
            long deviations = a.high + a.low;
            totalDeviations += deviations;
            rows.add(ProcessParamDeviationDTO.SummaryRow.builder()
                    .processName(a.processName).paramKey(a.paramKey).paramLabel(a.label).unit(a.unit)
                    .reportCount(a.total).deviationCount(deviations).highCount(a.high).lowCount(a.low)
                    .deviationRate(new BigDecimal(deviations).multiply(HUNDRED)
                            .divide(new BigDecimal(a.total), 1, RoundingMode.HALF_UP))
                    .build());
        }
        rows.sort(Comparator
                .comparing(ProcessParamDeviationDTO.SummaryRow::getDeviationRate).reversed()
                .thenComparing(Comparator.comparingLong(ProcessParamDeviationDTO.SummaryRow::getReportCount).reversed())
                .thenComparing(r -> r.getProcessName() == null ? "" : r.getProcessName())
                .thenComparing(ProcessParamDeviationDTO.SummaryRow::getParamKey));
        return ProcessParamDeviationDTO.SummaryView.builder()
                .window(window).totalChecked(checks.size()).totalDeviations(totalDeviations).rows(rows).build();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcessParamDeviationDTO.DetailRow> details(String factoryId, Filter filter, String status,
                                                                     Integer page, Integer size) {
        String wanted = normalizeStatus(status);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        ProcessParamDeviationDTO.Window window = resolveWindow(filter.startDate(), filter.endDate());

        List<Check> deviations = new ArrayList<>();
        for (Check c : loadChecks(factoryId, window, filter)) {
            if (!c.isDeviation()) continue;
            if (wanted != null && !wanted.equals(c.status)) continue;
            deviations.add(c);
        }
        // 新的在前; 同一次报工内按快照里的参数顺序 (loadChecks 已按此顺序产出, 排序稳定)
        deviations.sort(Comparator
                .comparing((Check c) -> c.reportedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> c.reportId, Comparator.reverseOrder()));

        long total = deviations.size();
        int from = (int) Math.min((long) (pageNo - 1) * pageSize, total);
        int to = (int) Math.min(from + (long) pageSize, total);
        List<ProcessParamDeviationDTO.DetailRow> content = new ArrayList<>();
        for (Check c : deviations.subList(from, to)) {
            content.add(c.toDetail());
        }
        return PageResponse.of(content, pageNo, pageSize, total);
    }

    // ------------------------------------------------------------------ 窗口 / 状态

    static ProcessParamDeviationDTO.Window resolveWindow(LocalDate startDate, LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期")
                    .withHint("请调整时间范围, 开始日期要在结束日期之前");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_SPAN_DAYS) {
            throw new BusinessException(400, "时间范围最多 " + MAX_SPAN_DAYS + " 天, 当前选了 " + span + " 天")
                    .withHint("请缩短时间范围, 或分几段分别查看");
        }
        return ProcessParamDeviationDTO.Window.builder().startDate(start).endDate(end).build();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!ProcessParamCheck.STATUS_LOW.equals(s) && !ProcessParamCheck.STATUS_HIGH.equals(s)) {
            throw new BusinessException(400, "状态只能是 LOW (偏低) 或 HIGH (偏高), 收到: " + status)
                    .withHint("不选状态则同时显示偏高和偏低");
        }
        return s;
    }

    // ------------------------------------------------------------------ 取数 + 去重 + 筛选

    private List<Check> loadChecks(String factoryId, ProcessParamDeviationDTO.Window window, Filter f) {
        ProcessSecretAggregateExclusions.Scope hidden =
                aggregateExclusions.forCurrentCaller(factoryId, "process param deviation");
        Set<String> hiddenProducts = new HashSet<>(hidden.productTypeIds());
        Set<String> hiddenPlans = new HashSet<>(hidden.planIds());
        boolean hasHiddenScope = !hiddenProducts.isEmpty() || !hiddenPlans.isEmpty();
        String wantProcess = blankToNull(f.processName());
        String wantProduct = blankToNull(f.productTypeId());
        String wantBatch = blankToNull(f.batchNumber());

        List<Map<String, Object>> rows = reportRepo.findParamCheckReports(
                factoryId, window.getStartDate(), window.getEndDate());
        Set<String> seenSteps = new HashSet<>();
        List<Check> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long reportId = asLong(row.get("report_id"));
            JsonNode fields = parse(reportId, asString(row.get("custom_fields_json")));
            JsonNode paramChecks = fields.get("paramChecks");
            if (paramChecks == null || !paramChecks.isArray() || paramChecks.isEmpty()) {
                continue;   // LIKE 预筛命中了别处的同名文本: 不是快照
            }
            String productTypeId = asString(row.get("product_type_id"));
            String planId = asString(row.get("plan_id"));
            String resolvedProductTypeId = asString(row.get("resolved_product_type_id"));
            String resolvedPlanId = asString(row.get("resolved_plan_id"));
            if ((resolvedProductTypeId != null && hiddenProducts.contains(resolvedProductTypeId))
                    || (resolvedPlanId != null && hiddenPlans.contains(resolvedPlanId))) {
                continue;   // 工艺保密: 无权的保密产品不进
            }
            if (hasHiddenScope && isBlank(resolvedProductTypeId) && isBlank(resolvedPlanId)) {
                continue;   // 无法可靠归属时宁可少读, 不能把历史脏快照泄给受限调用者
            }
            // web 工序表一道工序写多条报工, 每条带同一份快照: 每次填报只算一次
            Long batchId = asLong(row.get("batch_id"));
            String stepKey = textOrNull(fields.get("processEntryStepKey"));
            if (batchId != null && stepKey != null && !seenSteps.add(batchId + "|" + stepKey)) {
                continue;
            }
            String processName = firstNonBlank(asString(row.get("work_process_name")),
                    textOrNull(fields.get("processEntryProcessName")), asString(row.get("process_category")));
            String batchNumber = asString(row.get("batch_number"));
            if (wantProcess != null && !wantProcess.equals(processName)) continue;
            if (wantProduct != null && !wantProduct.equals(productTypeId)) continue;
            if (wantBatch != null && (batchNumber == null
                    || !batchNumber.toLowerCase(Locale.ROOT).contains(wantBatch.toLowerCase(Locale.ROOT)))) continue;

            Long workerId = asLong(row.get("worker_id"));
            String reporterName = firstNonBlank(asString(row.get("reporter_name")),
                    asString(row.get("worker_full_name")), asString(row.get("worker_username")));
            for (JsonNode node : paramChecks) {
                result.add(Check.of(reportId, node, row, batchId, batchNumber, productTypeId, processName,
                        workerId, reporterName));
            }
        }
        return result;
    }

    private JsonNode parse(Long reportId, String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("报工 " + reportId + " 的 custom_fields 为空, 但查询判定它带工艺参数快照");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("报工 " + reportId + " 的 custom_fields 不是 JSON 对象");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("报工 " + reportId + " 的 custom_fields 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 内部结构

    /** 一次报工里一个参数的判定 (已去重)。 */
    private static final class Check {
        Long reportId;
        LocalDate reportDate;
        LocalDateTime reportedAt;
        Long batchId;
        String batchNumber;
        String productTypeId;
        String productName;
        String processName;
        String paramKey;
        String label;
        String unit;
        BigDecimal value;
        BigDecimal standardValue;
        BigDecimal min;
        BigDecimal max;
        String status;
        Long reporterId;
        String reporterName;

        boolean isDeviation() {
            return ProcessParamCheck.STATUS_LOW.equals(status) || ProcessParamCheck.STATUS_HIGH.equals(status);
        }

        static Check of(Long reportId, JsonNode node, Map<String, Object> row, Long batchId, String batchNumber,
                        String productTypeId, String processName, Long workerId, String reporterName) {
            if (node == null || !node.isObject() || textOrNull(node.get("key")) == null) {
                throw new IllegalStateException("报工 " + reportId + " 的工艺参数快照有一项没有 key: " + node);
            }
            Check c = new Check();
            c.reportId = reportId;
            c.reportDate = asDate(row.get("report_date"));
            c.reportedAt = asDateTime(row.get("created_at"));
            c.batchId = batchId;
            c.batchNumber = batchNumber;
            c.productTypeId = productTypeId;
            c.productName = asString(row.get("product_name"));
            c.processName = processName;
            c.paramKey = textOrNull(node.get("key"));
            c.label = textOrNull(node.get("label"));
            c.unit = textOrNull(node.get("unit"));
            c.value = decimalOrNull(node.get("value"));
            c.standardValue = decimalOrNull(node.get("standardValue"));
            c.min = decimalOrNull(node.get("min"));
            c.max = decimalOrNull(node.get("max"));
            c.status = textOrNull(node.get("status"));
            c.reporterId = workerId;
            c.reporterName = reporterName;
            return c;
        }

        ProcessParamDeviationDTO.DetailRow toDetail() {
            return ProcessParamDeviationDTO.DetailRow.builder()
                    .reportId(reportId).reportDate(reportDate).reportedAt(reportedAt)
                    .batchId(batchId).batchNumber(batchNumber)
                    .productTypeId(productTypeId).productName(productName).processName(processName)
                    .paramKey(paramKey).paramLabel(label).measuredValue(value).unit(unit)
                    .standardValue(standardValue).rangeMin(min).rangeMax(max).status(status)
                    .reporterId(reporterId).reporterName(reporterName)
                    .build();
        }
    }

    /** 「工序 + 参数」累加器。label / unit 取最近一次 (行按报工 id 升序进来, 后写覆盖)。 */
    private static final class Acc {
        final String processName;
        final String paramKey;
        String label;
        String unit;
        long total;
        long high;
        long low;

        Acc(String processName, String paramKey) {
            this.processName = processName;
            this.paramKey = paramKey;
        }

        void add(Check c) {
            total++;
            if (ProcessParamCheck.STATUS_HIGH.equals(c.status)) high++;
            else if (ProcessParamCheck.STATUS_LOW.equals(c.status)) low++;
            if (c.label != null) label = c.label;
            if (c.unit != null) unit = c.unit;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String s = node.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("工艺参数快照里的数值不是数字: " + node, e);
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static LocalDate asDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(v.toString());
    }

    private static LocalDateTime asDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
