package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Gold finance-summary HTTP client.
 *
 * Reads from the Python Gold layer's /api/smartbi/gold/finance-summary
 * endpoint — the Silver+Gold pipeline landed in v1 Phase B v0. This
 * client is the building block for the future Java-side READ_FROM=GOLD
 * cutover in FinanceAnalysisServiceImpl: legacy path stays, Java also
 * calls Gold, logs divergence, and eventually flips.
 *
 * This class today is WIRED BUT NOT CALLED — FinanceAnalysisServiceImpl
 * hasn't been modified. Keeping it as a standalone Spring bean means
 * enabling shadow-read later is a small wire-up change, not a new
 * infrastructure task.
 *
 * Auth: reuses INTERNAL_API_SECRET + X-Factory-Id pattern that
 * PythonSmartBIClient already uses (see auth_middleware.py line 116-144).
 * Gold routes enforce tenant scope via JWT OR this internal-secret path.
 *
 * @since 2026-04-22
 */
@Slf4j
@Component
public class GoldFinanceClient {

    private final PythonSmartBIConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http;
    private final PythonSmartBIClient pythonSmartBIClient;

    /**
     * Read the same env var that JwtAuthInterceptor + PythonSmartBIClient use
     * so there's exactly one secret to rotate. Empty = run without the header
     * (Gold route will then require JWT instead).
     */
    private String internalSecret = System.getenv("INTERNAL_API_SECRET") != null
            ? System.getenv("INTERNAL_API_SECRET") : "";

    public GoldFinanceClient(PythonSmartBIConfig config) {
        this(config, null);
    }

    @Autowired
    public GoldFinanceClient(
            PythonSmartBIConfig config,
            PythonSmartBIClient pythonSmartBIClient) {
        this.config = config;
        this.pythonSmartBIClient = pythonSmartBIClient;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Resolve the current request user's role so the internal Java→Python call
     * can forward it as X-User-Role. Python's RBAC money-strip
     * (_apply_rbac_strip) needs the real role — price-view roles
     * (factory_super_admin / finance_manager / sales_manager / ...) see revenue;
     * everyone else gets money fields nulled.
     *
     * Reads the {@code role} request attribute set by
     * {@link com.cretas.aims.config.JwtAuthInterceptor} from the JWT claim
     * (Cretas auth is request-attribute based, NOT Spring SecurityContext — see
     * AttachmentPermissionResolver). Returns null when there is no request
     * context (e.g. a scheduled job) — Python then strips money, the safe
     * default. No DB lookup: the role is already on the request.
     */
    private String currentUserRole() {
        return currentRequestAttribute("role");
    }

    private String currentUserId() {
        return currentRequestAttribute("userId");
    }

    private String currentRequestAttribute(String attributeName) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                HttpServletRequest req = servletAttrs.getRequest();
                Object value = req.getAttribute(attributeName);
                return value != null ? value.toString() : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("current request identity resolve failed");
            return null;
        }
    }

    @PostConstruct
    public void init() {
        log.info("GoldFinanceClient initialized; python_url={} enabled={} has_secret={}",
                config.getUrl(), config.isEnabled(), !internalSecret.isEmpty());
    }

    /**
     * Fetch the finance_summary dict from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be ≥ startDate
     * @param topNStores max top-stores ranking to include (1..100)
     * @return parsed JSON as a Map keyed by Python's snake_case field names:
     *         total_revenue, bill_count, avg_bill_value, store_count, day_count,
     *         top_stores (List of {store_id, store_name, revenue, bill_count})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchFinanceSummary(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topNStores
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topNStores < 1 || topNStores > 100) {
            throw new IllegalArgumentException("topNStores must be 1..100");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/finance-summary")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n_stores", String.valueOf(topNStores))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold finance-summary HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold finance-summary factory={} range={}..{} rev={} bills={} in {}ms",
                    factoryId, startDate, endDate,
                    parsed.get("total_revenue"), parsed.get("bill_count"), elapsed);
            return parsed;
        }
    }

    /**
     * Fetch daily revenue/bill-count trend from Python Gold layer.
     *
     * Used to populate the 销售趋势 line chart on Dashboard 经营驾驶舱 + 分析概览.
     * Apr 22 Gold cutover only included KPI cards; charts stayed empty until
     * this method (Apr 25 Phase B4) wired the trend through.
     *
     * @return parsed JSON; key fields:
     *         - factory_id, start_date, end_date (echoes input)
     *         - points: List of {date, revenue, bill_count, avg_bill_value}
     *           one per date with activity, ordered ascending
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchDailyTrend(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/daily-trend")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold daily-trend HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object pts = parsed.get("points");
            int count = (pts instanceof java.util.List) ? ((java.util.List<?>) pts).size() : 0;
            log.debug("Gold daily-trend factory={} range={}..{} points={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch the trend bundle (dailyTrend + weekdayWeekend + monthlyTrend) from
     * Python Gold layer's {@code /api/smartbi/gold/trend-bundle} endpoint.
     *
     * <p>One round-trip for all three trend views over agg_daily. Used by
     * the trend-analysis tool to answer 营收趋势 / 同比环比 / 月度趋势 questions
     * in AI问答 — the COMPREHENSIVE_SYNTHESIS FactBook lacks a monthly
     * series so it cannot compute trend; this gold path can.
     *
     * <p><b>All-history</b>: when {@code startDate}/{@code endDate} are null the
     * date query params are omitted, so Python defaults to the factory's full
     * Gold span (mirrors the endpoint's "省略=全部历史" contract). Unlike
     * {@link #fetchDailyTrend} this method tolerates null bounds.
     *
     * <p><b>RBAC</b>: revenue fields (dailyTrend[].revenue, monthlyTrend[].revenue,
     * weekdayWeekend.weekdayAvg/weekendAvg) are monetary → Python money-strip nulls
     * them for non price-view roles. We forward X-User-Role so price-view roles see
     * revenue (per feedback_java_python_rbac_role_forward).
     *
     * @param factoryId tenant id
     * @param startDate inclusive window start; nullable (null → all history)
     * @param endDate   inclusive window end; nullable (null → all history)
     * @return parsed JSON; key fields:
     *         - factory_id, start_date, end_date (echoes input; null when all-history)
     *         - dailyTrend: List of {date, revenue, bill_count}, ascending
     *         - weekdayWeekend: {weekdayAvg, weekendAvg, weekdayDays, weekendDays}
     *         - monthlyTrend: List of {month: "YYYY-MM", revenue}, ascending
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTrendBundle(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl.Builder ub = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/trend-bundle")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId);
        // Omit date params when null so Python defaults to all history.
        if (startDate != null) {
            ub.addQueryParameter("start_date", startDate.toString());
        }
        if (endDate != null) {
            ub.addQueryParameter("end_date", endDate.toString());
        }
        HttpUrl url = ub.build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold trend-bundle HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object monthly = parsed.get("monthlyTrend");
            int months = (monthly instanceof java.util.List) ? ((java.util.List<?>) monthly).size() : 0;
            log.debug("Gold trend-bundle factory={} range={}..{} months={} in {}ms",
                    factoryId, startDate, endDate, months, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch top products by revenue from Python Gold layer.
     *
     * Delegates to the 5-arg overload with order="desc" for backward compatibility.
     * Existing callers (e.g. GoldDashboardBuilder) are unaffected.
     *
     * @return parsed JSON; key fields:
     *         - factory_id, start_month, end_month (rolled up to month grain)
     *         - top_products: List of {product_id, product_name, qty_sold,
     *           revenue, bill_count}, ordered by revenue DESC, max top_n
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTopProducts(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        return fetchTopProducts(factoryId, startDate, endDate, topN, "desc");
    }

    /**
     * Fetch top products by revenue from Python Gold layer, with explicit sort order.
     *
     * Used to populate the 产品类别占比 pie chart on Dashboard. Note that
     * dim_product.category is NULL for restaurant tenants ingested before
     * v1.3, so we fall back to top product names as the breakdown — still
     * useful as a "热销产品 Top N" donut.
     *
     * @param order sort direction: "asc" or "desc" (default "desc"); any other
     *              value is silently normalized to "desc"
     * @return parsed JSON; key fields:
     *         - factory_id, start_month, end_month (rolled up to month grain)
     *         - top_products: List of {product_id, product_name, qty_sold,
     *           revenue, bill_count}, ordered by revenue per {@code order}, max top_n
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchTopProducts(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN,
            String order
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 100) {
            throw new IllegalArgumentException("topN must be 1..100");
        }
        String normalizedOrder = ("asc".equals(order)) ? "asc" : "desc";

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/top-products")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .addQueryParameter("order", normalizedOrder)
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else total_revenue/avg_bill_value get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold top-products HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("top_products");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold top-products factory={} range={}..{} count={} order={} in {}ms",
                    factoryId, startDate, endDate, count, normalizedOrder, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch order-type mix (dine-in vs takeout vs delivery breakdown) from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @return parsed JSON with order type breakdown; key fields vary by tenant
     *         but typically include order_type_breakdown (List), total_revenue, bill_count
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchOrderTypeMix(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/order-type-mix")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold order-type-mix HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold order-type-mix factory={} range={}..{} in {}ms",
                    factoryId, startDate, endDate, elapsed);
            return parsed;
        }
    }

    /**
     * 按小时的营业强度分布 —— 回答「几点最忙」。
     *
     * <p>起止日期<b>可选</b>（与 order-type-mix 不同）：「几点最忙」通常问的是全期
     * 或近期规律，强制要求区间会把这个问题变成必须先追问日期。Python 端省略即取
     * 全部历史。
     *
     * <p>⚠️ 响应可能带 {@code hours_available=false} —— 那表示该区间的交易<b>没有
     * 记录开单时刻</b>（数据采集缺失），不是「这些时段没营业」。调用方必须把
     * {@code unavailable_reason} 透出去，不要渲染成 24 小时全 0。
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive；可为 null = 不限起点
     * @param endDate   inclusive；可为 null = 不限终点
     * @return parsed JSON: hours_available / hours / peak_hour / peak_bill_count
     */
    public Map<String, Object> fetchPeakHours(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }

        HttpUrl.Builder ub = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/peak-hours")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId);
        if (startDate != null) {
            ub.addQueryParameter("start_date", startDate.toString());
        }
        if (endDate != null) {
            ub.addQueryParameter("end_date", endDate.toString());
        }
        HttpUrl url = ub.build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold peak-hours HTTP " + resp.code() + " in " + elapsed + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold peak-hours factory={} range={}..{} in {}ms",
                    factoryId, startDate, endDate, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch staff performance ranking from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @param topN      max staff entries to return (1..50)
     * @return parsed JSON; key fields typically include staff_ranking
     *         (List of {staff_id, staff_name, revenue, bill_count, avg_bill_value})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchStaffRanking(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 50) {
            throw new IllegalArgumentException("topN must be 1..50");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/staff-ranking")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold staff-ranking HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("staff_ranking");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold staff-ranking factory={} range={}..{} count={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    /**
     * Fetch the actual date range of a factory's Gold data (agg_daily).
     *
     * <p>Used by the Gold-backed analysis resolver to default the
     * analysis window to the factory's real data span instead of an arbitrary
     * "last 7 days from today" window.
     *
     * <p>Response keys (Python Gold {@code data_range} function):
     * <ul>
     *   <li>{@code factory_id} — echoes input</li>
     *   <li>{@code min_date} — ISO {@code yyyy-MM-dd} string of the earliest row in
     *       {@code agg_daily}, or {@code null} when the factory has no Gold rows yet</li>
     *   <li>{@code max_date} — ISO {@code yyyy-MM-dd} string of the latest row, or
     *       {@code null} when no rows</li>
     *   <li>{@code day_count} — number of distinct dates with data (0 when no rows)</li>
     * </ul>
     *
     * <p>No money fields → no RBAC strip on the Python side → no X-User-Role needed;
     * still forwarded for consistency with other Gold calls.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @return parsed JSON as a Map with keys described above
     * @throws IOException if Gold is unreachable, returns non-2xx, or body doesn't parse
     */
    public Map<String, Object> fetchDataRange(String factoryId) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/data-range")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold data-range HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold data-range factory={} min_date={} max_date={} day_count={} in {}ms",
                    factoryId, parsed.get("min_date"), parsed.get("max_date"),
                    parsed.get("day_count"), elapsed);
            return parsed;
        }
    }

    /**
     * Fetch discount/promotion breakdown from Python Gold layer.
     *
     * @param factoryId factory (tenant) id — must match the caller's auth scope
     * @param startDate inclusive
     * @param endDate   inclusive; must be >= startDate
     * @param topN      max discount categories to return (1..100)
     * @return parsed JSON; key fields typically include discount_breakdown
     *         (List of {discount_type, discount_amount, bill_count, revenue_impact})
     * @throws IOException if Gold is unreachable, returns non-2xx, or the body
     *                    doesn't parse. Caller should log + fall through to legacy.
     */
    public Map<String, Object> fetchDiscountBreakdown(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int topN
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate > endDate");
        }
        if (topN < 1 || topN > 100) {
            throw new IllegalArgumentException("topN must be 1..100");
        }

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/discount-breakdown")
                .newBuilder()
                .addQueryParameter("factory_id", factoryId)
                .addQueryParameter("start_date", startDate.toString())
                .addQueryParameter("end_date", endDate.toString())
                .addQueryParameter("top_n", String.valueOf(topN))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward the request user's role so Python RBAC money-strip respects
            // price-view permission (else revenue/amount fields get nulled).
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold discount-breakdown HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object items = parsed.get("discount_breakdown");
            int count = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : 0;
            log.debug("Gold discount-breakdown factory={} range={}..{} count={} in {}ms",
                    factoryId, startDate, endDate, count, elapsed);
            return parsed;
        }
    }

    private static void requireFactory(String factoryId) {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
    }

    // P2 综合分析 (multi-dim synthesis) — POST /api/smartbi/synthesis/comprehensive
    //
    // Unlike the GET review/finance fetches, synthesis is a POST: the Python
    // engine aggregates review + finance/sales into a FactBook, asks the LLM for
    // a grounded narrative (redacted at egress, restored on return), fact-checks
    // it, and returns answer + charts + plan + fact_check. The window is optional
    // — when start/end are null the Python side defaults to the factory's Gold
    // data span (so the tool can pass the resolved [start,end] or let Python
    // resolve). X-User-Role forwarded so the gold finance pulls' RBAC sees the
    // caller's price-view permission (else revenue gets nulled).
    // =========================================================================

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /**
     * Fetch a comprehensive multi-dimension synthesis from the Python engine.
     *
     * @param factoryId tenant id
     * @param question  the user's free-form question (e.g. "综合分析评价和经营")
     * @param startDate inclusive window start; nullable (Python defaults to Gold data range)
     * @param endDate   inclusive window end; nullable
     * @return parsed JSON: answer (markdown), charts (List of chart configs), plan,
     *         fact_check, source, tokens
     * @throws IOException on transport / non-2xx / parse failure
     */
    public Map<String, Object> fetchComprehensiveSynthesis(
            String factoryId,
            String question,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        requireFactory(factoryId);
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question required");
        }

        java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("question", question);
        bodyMap.put("factory_id", factoryId);
        if (startDate != null) {
            bodyMap.put("start_date", startDate.toString());
        }
        if (endDate != null) {
            bodyMap.put("end_date", endDate.toString());
        }
        String json = objectMapper.writeValueAsString(bodyMap);

        Request.Builder reqBuilder = new Request.Builder()
                .url(config.getUrl() + "/api/smartbi/synthesis/comprehensive")
                .post(RequestBody.create(json, JSON_MEDIA));
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold synthesis HTTP " + resp.code() + " in " + elapsed + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            log.debug("Gold synthesis factory={} source={} tokens={} in {}ms",
                    factoryId, parsed.get("source"), parsed.get("tokens"), elapsed);
            return parsed;
        }
    }

    // =========================================================================
    // =========================================================================
    // 供应商价格预警 (邓总点名痛点) — GET /api/smartbi/gold/price-anomaly/detect
    //
    // Reuses Wave2 #53's detector (same agg_supplier_price history + ack/累计
    // 高风险 infra) via its baseline_mode=days mode — 邓总 locked the basis at
    // "自身 90 天移动均价". This client wraps that endpoint for the AI tool so
    // 老板 can ask "哪个供应商涨价了" in 智能问答, not only via the price-anomaly board.
    //
    // RBAC: deltaPct (率) + direction/riskLevel (威慑信号) stay visible; absolute
    // prices (oldPrice/newPrice/trailingAvg) are nulled Python-side for non
    // price-view roles. We forward X-User-Role so procurement roles see amounts
    // (per feedback_java_python_rbac_role_forward). The detect endpoint emits the
    // {success, data, message} envelope, so we unwrap to the data list here.
    // =========================================================================

    /**
     * Detect supplier price anomalies against a moving-average baseline.
     *
     * @param factoryId    tenant id (required)
     * @param baselineMode "days" (邓总 90-day moving avg) or "count" (legacy trailing-N)
     * @param windowDays   moving-average window in days for {@code days} mode (1..365);
     *                     ignored in {@code count} mode
     * @param epsilonPct   anomaly tolerance ε in percent (0..100); deviations within
     *                     ±ε of the baseline are NOT flagged
     * @return the list of anomaly rows (camelCase keys: normalizedName, ingredientName,
     *         supplierId, supplierName, unit, anomalyDeliveryDate, oldPrice, newPrice,
     *         trailingAvg, deltaPct, direction, consecutiveAnomalyCount, riskLevel);
     *         empty list when none / on a {@code success:false} envelope
     * @throws IOException on transport / non-2xx / parse failure
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPriceAnomalies(
            String factoryId,
            String baselineMode,
            int windowDays,
            double epsilonPct
    ) throws IOException {
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId required");
        }
        String mode = "count".equals(baselineMode) ? "count" : "days";
        if (windowDays < 1) windowDays = 1;
        if (windowDays > 365) windowDays = 365;
        if (epsilonPct < 0.0) epsilonPct = 0.0;
        if (epsilonPct > 100.0) epsilonPct = 100.0;

        HttpUrl url = HttpUrl.parse(config.getUrl() + "/api/smartbi/gold/price-anomaly/detect")
                .newBuilder()
                .addQueryParameter("baseline_mode", mode)
                .addQueryParameter("window_days", String.valueOf(windowDays))
                .addQueryParameter("epsilon_pct", String.valueOf(epsilonPct))
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (!internalSecret.isEmpty()) {
            reqBuilder.addHeader("X-Internal-Secret", internalSecret);
            reqBuilder.addHeader("X-Factory-Id", factoryId);
            // Forward role so Python RBAC keeps absolute prices for price-view roles.
            String userRole = currentUserRole();
            if (userRole != null && !userRole.isEmpty()) {
                reqBuilder.addHeader("X-User-Role", userRole);
            }
        }
        Request req = reqBuilder.build();

        long t0 = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                throw new IOException(
                        "Gold price-anomaly detect HTTP " + resp.code() + " in " + elapsed
                                + "ms: " + body);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            Map<String, Object> envelope = objectMapper.readValue(body, Map.class);
            Object ok = envelope.get("success");
            if (Boolean.FALSE.equals(ok)) {
                Object msg = envelope.get("message");
                throw new IOException("Gold price-anomaly detect success=false: " + msg);
            }
            Object data = envelope.get("data");
            List<Map<String, Object>> rows = (data instanceof List)
                    ? (List<Map<String, Object>>) data
                    : java.util.Collections.emptyList();
            log.debug("Gold price-anomaly detect factory={} mode={} window={} eps={} rows={} in {}ms",
                    factoryId, mode, windowDays, epsilonPct, rows.size(), elapsed);
            return rows;
        }
    }

}
