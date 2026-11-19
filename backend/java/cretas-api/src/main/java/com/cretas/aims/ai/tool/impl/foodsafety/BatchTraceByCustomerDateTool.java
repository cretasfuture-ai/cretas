package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.TraceabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 按 出货日期 + 客户 + 产品 反查出货 → 成品批次 → 原料批次 Tool (食品召回 / 溯源首步).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"鲜湘缘餐厅 5/18 那批卤猪蹄是哪批"</li>
 *   <li>"9 月 20 号发给上海连锁餐饮的酱卤牛肉是哪批原料做的"</li>
 *   <li>"X 客户上周三吃的是哪批"</li>
 * </ul>
 *
 * <p>客户可以只给名称 (customerName, 模糊匹配, 句式 "发给/卖给/给 X 的" 里的 X 原样传入),
 * 不再强迫用户提供内部编号; 有 customerId 时按编号。产品名可选, 给了就只看该产品。
 * 日期容忍 ISO / "9月20号" / "9/20", 缺年份按今年 (晚于今天则上一年)。
 *
 * <p>查到出货后顺着成品批次号继续查原料批次 (复用 {@link TraceabilityService#getFullTrace}),
 * 查空时回复写明按哪个日期 / 客户 / 产品查的, 并给该客户最近有出货的日期, 不编数据。
 *
 * <p>read-only. Intent Code: {@code BATCH_TRACE_BY_CUSTOMER}
 */
@Slf4j
@Component
public class BatchTraceByCustomerDateTool extends AbstractBusinessTool {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_CANDIDATE_NAMES = 5;
    private static final double FUZZY_THRESHOLD = 0.5;

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TraceabilityService traceabilityService;

    @Override
    public String getToolName() {
        return "batch_trace_by_customer_date";
    }

    @Override
    public String getDescription() {
        return "按 出货日期 + 客户 + 产品 反查出货单 → 成品批次 → 原料批次 (溯源 / 食品召回首步). "
                + "LLM 触发: '鲜湘缘餐厅 5/18 那批卤猪蹄是哪批' / '9月20号发给上海连锁餐饮的酱卤牛肉是哪批原料做的' / "
                + "'按客户日期追溯批次'. 客户名在问句里就填 customerName (原样传, 不要反问客户), "
                + "产品名填 productName. read-only. "
                + "返回该客户在指定日期收到的出货记录、成品批次号及其原料批次.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户编号 (可选). 已知编号时填; 只知道名称就填 customerName");
        properties.put("customerId", customerId);

        Map<String, Object> customerName = new HashMap<>();
        customerName.put("type", "string");
        customerName.put("description",
                "客户名称 (可选, 模糊匹配). 句式「发给/卖给/给 X 的」里的 X 原样填这里, 问句里有就不要反问用户");
        properties.put("customerName", customerName);

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称 (可选). 问句里提到产品就填, 只看该产品的出货");
        properties.put("productName", productName);

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "出货日期 YYYY-MM-DD (必填). 用户只说月日时年份按今年");
        properties.put("date", date);

        schema.put("properties", properties);
        schema.put("required", List.of("date"));
        // 日期齐了也要抽客户: 只认 required 时问句里的客户名到不了工具, 工具反问「哪个客户」(见 ToolParameterGroups)
        schema.put(com.cretas.aims.ai.tool.ToolParameterGroups.ANY_OF_KEY,
                List.of(List.of("customerId", "customerName")));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("date");
    }

    /** 日期必填; 客户编号和客户名称至少要有一个 (问句里已有的槽位不再反问)。 */
    @Override
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        List<String> missing = new ArrayList<>(super.validateRequiredParams(params));
        if (isBlank(params.get("customerId")) && isBlank(params.get("customerName"))) {
            missing.add("customerName");
        }
        return missing;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("customerName".equals(paramName)) {
            return "请问是发给哪个客户的？说客户名称就行。";
        }
        if ("date".equals(paramName)) {
            return "请问是哪一天的出货？说日期就行。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerIdParam = trimToNull(getString(params, "customerId"));
        String customerNameParam = trimToNull(getString(params, "customerName"));
        String productName = trimToNull(getString(params, "productName"));
        String dateStr = getString(params, "date");

        LocalDate date = parseDate(dateStr, LocalDate.now(ZONE));
        if (date == null) {
            throw new IllegalArgumentException("date 格式非法 (期望 YYYY-MM-DD): " + dateStr);
        }

        log.info("batch_trace_by_customer_date — factory={} customerId={} customerName={} product={} date={}",
                factoryId, customerIdParam, customerNameParam, productName, date);

        // 1. 解析客户 (编号 → 名称模糊)
        Customer customer = null;
        if (customerIdParam != null) {
            customer = findById(factoryId, customerIdParam);
        }
        // 模型有时把名称塞进 customerId: 编号查不到就当名称
        String nameKeyword = customerNameParam != null ? customerNameParam
                : (customer == null ? customerIdParam : null);
        if (customer == null && nameKeyword != null) {
            List<Customer> candidates = matchCustomers(factoryId, nameKeyword);
            if (candidates.size() > 1) {
                return ambiguousCustomer(nameKeyword, candidates);
            }
            if (candidates.size() == 1) {
                customer = candidates.get(0);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", date.toString());
        data.put("customerQuery", nameKeyword != null ? nameKeyword : customerIdParam);
        data.put("productQuery", productName);

        if (customer == null) {
            data.put("shipmentCount", 0);
            data.put("batchNumbers", List.of());
            data.put("shipments", List.of());
            return buildSimpleResult(String.format(
                    "ℹ️ 没有找到名称接近「%s」的客户, 无法按 %s 查出货。请确认客户名称是否正确",
                    nameKeyword != null ? nameKeyword : customerIdParam, date), data);
        }
        String customerId = customer.getId();
        String customerName = customer.getName();
        data.put("customerId", customerId);
        data.put("customerName", customerName);

        // 2. 该客户全部出货 → 日期 (+ 产品) 过滤
        List<ShipmentRecord> all = shipmentRecordRepository
                .findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(factoryId, customerId);
        List<ShipmentRecord> forProduct = all.stream()
                .filter(s -> productName == null || productMatches(productName, s.getProductName()))
                .collect(Collectors.toList());
        List<ShipmentRecord> onDate = forProduct.stream()
                .filter(s -> s.getShipmentDate() != null && s.getShipmentDate().equals(date))
                .collect(Collectors.toList());

        if (onDate.isEmpty()) {
            List<String> recentDates = forProduct.stream()
                    .map(ShipmentRecord::getShipmentDate)
                    .filter(d -> d != null)
                    .map(LocalDate::toString)
                    .distinct()
                    .limit(5)
                    .collect(Collectors.toList());
            data.put("shipmentCount", 0);
            data.put("batchNumbers", List.of());
            data.put("shipments", List.of());
            data.put("recentShipmentDates", recentDates);
            data.put("actionHint", "/sales/shipments?customerId=" + customerId + "&date=" + date);
            return buildSimpleResult(emptyMessage(customerName, date, productName, recentDates), data);
        }

        List<Map<String, Object>> shipments = new ArrayList<>();
        List<String> batchNumbers = new ArrayList<>();
        for (ShipmentRecord s : onDate) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipmentNumber", s.getShipmentNumber());
            row.put("batchNumber", s.getBatchNumber());
            row.put("productName", s.getProductName());
            row.put("quantity", s.getQuantity());
            row.put("unit", s.getUnit());
            shipments.add(row);
            if (s.getBatchNumber() != null && !s.getBatchNumber().isBlank()
                    && !batchNumbers.contains(s.getBatchNumber())) {
                batchNumbers.add(s.getBatchNumber());
            }
        }

        // 3. 成品批次 → 原料批次
        StringBuilder message = new StringBuilder();
        message.append(String.format("📦 %s 在 %s %s收到 %d 笔出货, 涉及 %d 个成品批次%s",
                nameForDisplay(customerName), date, productName == null ? "" : "「" + productName + "」",
                onDate.size(), batchNumbers.size(),
                batchNumbers.isEmpty() ? "（出货单没登记批次号）" : ": " + String.join(", ", batchNumbers)));

        List<Map<String, Object>> materialTrace = new ArrayList<>();
        for (String batch : batchNumbers) {
            TraceabilityDTO.FullTraceResponse trace = null;
            try {
                trace = traceabilityService.getFullTrace(factoryId, batch);
            } catch (Exception ex) {
                log.warn("getFullTrace failed batch={}: {}", batch, ex.getMessage());
            }
            List<TraceabilityDTO.MaterialInfo> materials = trace == null ? null : trace.getMaterials();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("batchNumber", batch);
            List<Map<String, Object>> mats = new ArrayList<>();
            message.append("\n成品批次 ").append(batch);
            if (trace == null) {
                message.append(" 暂时没查到追溯记录。");
            } else if (materials == null || materials.isEmpty()) {
                message.append(" 没有登记投料的原料批次。");
            } else {
                message.append(" 用了 ").append(materials.size()).append(" 个原料批次：");
                for (TraceabilityDTO.MaterialInfo m : materials) {
                    Map<String, Object> mr = new LinkedHashMap<>();
                    mr.put("materialName", m.getMaterialName());
                    mr.put("batchNumber", m.getBatchNumber());
                    mr.put("supplierName", m.getSupplierName());
                    mats.add(mr);
                    message.append("\n• ").append(m.getMaterialName() == null ? "未命名原料" : m.getMaterialName())
                            .append("（批次 ").append(m.getBatchNumber() == null ? "未登记" : m.getBatchNumber())
                            .append("，供应商 ").append(m.getSupplierName() == null ? "未登记" : m.getSupplierName())
                            .append("）");
                }
            }
            entry.put("materials", mats);
            materialTrace.add(entry);
        }

        data.put("shipmentCount", onDate.size());
        data.put("batchNumbers", batchNumbers);
        data.put("shipments", shipments);
        data.put("materialTrace", materialTrace);
        data.put("actionHint", "/sales/shipments?customerId=" + customerId + "&date=" + date);
        return buildSimpleResult(message.toString(), data);
    }

    // ── 客户解析 ──

    private Customer findById(String factoryId, String customerId) {
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt == null ? null : opt.orElse(null);
        } catch (Exception ex) {
            log.warn("findById failed customer={} factory={}: {}", customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    /** 名称匹配: 先精确, 再互相包含, 再按字符二元组相似度 (≥阈值, 取最高分; 并列时返回并列全体)。 */
    List<Customer> matchCustomers(String factoryId, String keyword) {
        List<Customer> all = customerRepository.findByFactoryId(factoryId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        String k = normalizeName(keyword);
        List<Customer> exact = all.stream()
                .filter(c -> c.getName() != null && normalizeName(c.getName()).equals(k))
                .collect(Collectors.toList());
        if (!exact.isEmpty()) {
            return exact;
        }
        List<Customer> contains = all.stream()
                .filter(c -> c.getName() != null && !k.isEmpty()
                        && (normalizeName(c.getName()).contains(k) || k.contains(normalizeName(c.getName()))))
                .collect(Collectors.toList());
        if (!contains.isEmpty()) {
            return contains;
        }
        double best = 0;
        List<Customer> bestList = new ArrayList<>();
        for (Customer c : all) {
            if (c.getName() == null) continue;
            double score = bigramDice(k, normalizeName(c.getName()));
            if (score < FUZZY_THRESHOLD) continue;
            if (score > best + 1e-9) {
                best = score;
                bestList = new ArrayList<>(List.of(c));
            } else if (Math.abs(score - best) <= 1e-9) {
                bestList.add(c);
            }
        }
        return bestList;
    }

    private Map<String, Object> ambiguousCustomer(String keyword, List<Customer> candidates) {
        List<String> names = candidates.stream().map(Customer::getName).filter(n -> n != null)
                .limit(MAX_CANDIDATE_NAMES).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerQuery", keyword);
        data.put("candidateNames", names);
        data.put("shipmentCount", 0);
        return buildSimpleResult(String.format(
                "ℹ️ 名称接近「%s」的客户有 %d 个: %s。请说全称, 我再按日期查出货",
                keyword, candidates.size(), String.join("、", names)), data);
    }

    static String normalizeName(String s) {
        return s == null ? "" : s.replaceAll("[\\s　（）()·.。,，、-]", "").toLowerCase();
    }

    static double bigramDice(String a, String b) {
        Set<String> x = bigrams(a);
        Set<String> y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return 2.0 * inter.size() / (x.size() + y.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    static boolean productMatches(String wanted, String actual) {
        if (actual == null) return false;
        String w = normalizeName(wanted);
        String a = normalizeName(actual);
        return !w.isEmpty() && (a.contains(w) || w.contains(a));
    }

    // ── 日期 ──

    private static final Pattern CN_MONTH_DAY = Pattern.compile("^(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?$");
    private static final Pattern SLASH = Pattern.compile("^(?:(\\d{4})[/.-])?(\\d{1,2})[/.-](\\d{1,2})$");

    /** 支持 ISO、yyyy/M/d、M月d日/号、M/d、M-d; 缺年份取今年, 若晚于今天则取上一年。解析不了返回 null。 */
    static LocalDate parseDate(String raw, LocalDate today) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignore) {
            // 继续宽松解析
        }
        Matcher m = CN_MONTH_DAY.matcher(s);
        if (!m.matches()) {
            m = SLASH.matcher(s);
            if (!m.matches()) return null;
        }
        try {
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(1)), month, day);
            }
            LocalDate d = LocalDate.of(today.getYear(), month, day);
            return d.isAfter(today) ? d.minusYears(1) : d;
        } catch (java.time.DateTimeException | NumberFormatException ex) {
            return null;
        }
    }

    // ── 文案 ──

    static String emptyMessage(String customerName, LocalDate date, String productName, List<String> recentDates) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ℹ️ %s 在 %s 没有%s出货记录", nameForDisplay(customerName), date,
                productName == null ? "" : "「" + productName + "」"));
        sb.append("（已按 日期=").append(date).append("、客户=").append(nameForDisplay(customerName));
        if (productName != null) sb.append("、产品=").append(productName);
        sb.append(" 查询）。");
        if (!recentDates.isEmpty()) {
            sb.append("该客户").append(productName == null ? "" : "该产品").append("最近的出货日期: ")
                    .append(String.join("、", recentDates)).append("。");
        }
        sb.append("请确认日期是否正确");
        return sb.toString();
    }

    private static String nameForDisplay(String name) {
        return (name != null && !name.isBlank()) ? name : "该客户";
    }

    private static boolean isBlank(Object o) {
        return o == null || (o instanceof String s && s.trim().isEmpty());
    }

    private static String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
