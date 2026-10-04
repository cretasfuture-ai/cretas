package com.cretas.aims.service.execution;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 追问解读 (2026-10-04): 「好了」「下一个」「再加十公斤」「批了吧」这种短句, 单看原话猜不准,
 * 交给 LLM 猜经常猜错 (实测: 开始工序后的「好了」被猜成告警确认, 「下一个」被猜成什么也不做的
 * CONTEXT_CONTINUE)。这里用 App 带回来的「上一步做了什么」({@link LastActionContract}) 加一张
 * <b>小的、可逐条审的规则表</b>确定性地解读。
 *
 * <p>⛔ 解读结果<b>从不直接执行</b>: 调用方 (SSE) 只把它变成一个「您是要 X 吗？」的确认按钮,
 * 用户点了才走正常的显式意图执行 (权限 / 审批 / 业态门 / W0 写确认照旧)。所以这里宁可不命中
 * —— 不命中时正常识别流程原样运行。
 *
 * <h3>规则表</h3>
 * <pre>
 * 规则 id                    上一步                               这一句 (类)       → 意图 (参数)
 * wp-done-after-start        WORK_PROCESS_TASK_START              DONE (可带数量)   → WORK_PROCESS_TASK_COMPLETE {taskId, actualQuantity?}
 * wp-next-after-complete     WORK_PROCESS_TASK_COMPLETE           NEXT              → WORK_PROCESS_TASK_START {}
 * qty-change-same-intent     MATERIAL_BATCH_USE/CONSUME/          QTY_CHANGE        → 同一意图 {原参数, quantity 调整后}
 *                            RESERVE/RELEASE (params 带 quantity)
 * po-approve-after-purchase  采购单列表/详情/PURCHASE_ORDER_*       APPROVE           → PURCHASE_ORDER_APPROVE {action=approve, orderId?}
 *                            (不含 PURCHASE_ORDER_APPROVE 自身)
 * leave-approve-after-query  HR_LEAVE_REQUEST_QUERY               APPROVE           → HR_LEAVE_APPROVE {decision=APPROVE, leaveRequestId?}
 * leave-reject-after-query   HR_LEAVE_REQUEST_QUERY               REJECT            → HR_LEAVE_APPROVE {decision=REJECT, leaveRequestId?}
 * </pre>
 * 采购单<b>没有</b>驳回规则: {@code purchase_order_approve} 的 action 只有 submit/approve/cancel,
 * 财务驳回只能在工作台「待我审批」处理 —— 「驳回」在采购单之后不命中, 交给正常流程。
 * id 只在上一步恰好只列出一条时才带上; 多条时不猜, 交给执行时的缺参追问。
 */
public final class FollowUpResolver {

    /** 确认按钮参数 / 响应 metadata 里记规则 id 的键 (App 点按钮时原样转进 context)。 */
    public static final String FOLLOW_UP_RULE_KEY = "followUpRule";

    public static final String RULE_WP_DONE = "wp-done-after-start";
    public static final String RULE_WP_NEXT = "wp-next-after-complete";
    public static final String RULE_QTY_CHANGE = "qty-change-same-intent";
    public static final String RULE_PO_APPROVE = "po-approve-after-purchase";
    public static final String RULE_LEAVE_APPROVE = "leave-approve-after-query";
    public static final String RULE_LEAVE_REJECT = "leave-reject-after-query";

    /** 只解读短句: 长句里信息多, 交给正常识别。 */
    static final int MAX_UTTERANCE_CHARS = 12;

    /** 解读结果: 规则 id、目标意图、要随确认按钮带上的参数、给人看的一句参数摘要。 */
    public record FollowUp(String ruleId, String intentCode, Map<String, Object> params, String summary) { }

    // ---------- 话语类 ----------

    private static final String NUM = "([0-9]{1,6}(?:\\.[0-9]{1,3})?|[零一二两三四五六七八九十百千]{1,6})";
    private static final String UNIT = "(公斤|千克|kg|KG|Kg|斤|克|g|吨|箱|袋|件|个|包|桶|瓶|盒|份|筐|盘)?";
    private static final String QTY_LEAD = "(?:一共|总共|共|产量|数量是?|做了)?";
    private static final String HAN = "\\u4e00-\\u9fa5";

    /** 「好了 / 做完了 / 包装完了」; X完了 的 X 另行排除「用完 / 卖完 / 没完」这类不是完工的说法。 */
    private static final String DONE_CORE =
            "(好了|好啦|做好了|做完了|弄好了|弄完了|干完了|完成了|完成啦|完工了|完事了|搞定了?|搞好了|结束了|收工了"
                    + "|([" + HAN + "]{1,5})完(?:成)?了)";
    private static final Pattern DONE = Pattern.compile(
            "^(?:" + QTY_LEAD + NUM + UNIT + "[，,]?)?" + DONE_CORE + "(?:[，,]?" + QTY_LEAD + NUM + UNIT + ")?$");
    // DONE 的分组: 1=前数量 2=前单位 3=核心 4=X完了的X 5=后数量 6=后单位
    private static final Pattern DONE_X_EXCLUDED = Pattern.compile("[用卖吃没花烧喝坏不未别零一二两三四五六七八九十百千]");

    private static final Pattern NEXT = Pattern.compile(
            "^(?:下一个|下一道|下一个工序|下一道工序|下个|下个工序|接着来|继续下一个|继续下一道|开始下一个|做下一个)吧?$");

    private static final Pattern QTY_ADD = Pattern.compile(
            "^(?:再加|再来|再多|加上|多加|加|多)" + NUM + UNIT + "(?:吧|就行了?)?$");
    private static final Pattern QTY_SUB = Pattern.compile(
            "^(?:少|减|减去|减掉|少来|少要)" + NUM + UNIT + "(?:吧|就行了?)?$");
    private static final Pattern QTY_SET_PREFIX = Pattern.compile(
            "^(?:改成|改为|换成)" + NUM + UNIT + "(?:吧|就行了?)?$");
    private static final Pattern QTY_SET_SUFFIX = Pattern.compile(
            "^" + NUM + UNIT + "(?:就行了?|就够了|就可以了?)$");

    private static final String WHO = "([" + HAN + "A-Za-z0-9\\-]{0,8}?)";
    private static final Pattern APPROVE = Pattern.compile(
            "^" + WHO + "(?:批了吧?|批吧|批准了?吧?|同意了?吧?|通过吧?|可以批|准了|都批了|全批了|全部通过|批一下)$");
    private static final Pattern REJECT = Pattern.compile(
            "^" + WHO + "(?:驳回吧?|拒绝吧?|拒了吧?|不批了?|不同意|不通过|打回吧?|退回吧?)$");
    /** 前缀里带否定 (「不批了」「别批」) 或陈述完成 (「已经批了」「刚批了」) 不算同意。 */
    private static final Pattern APPROVE_PREFIX_EXCLUDED = Pattern.compile("[不别没未莫勿已早刚]");
    private static final Pattern REJECT_PREFIX_EXCLUDED = Pattern.compile("[不别没未莫勿已早刚]");

    // ---------- 上一步 ----------

    private static final Map<String, String> QTY_INTENTS = Map.of(
            "MATERIAL_BATCH_USE", "material_batch_use",
            "MATERIAL_BATCH_CONSUME", "material_batch_consume",
            "MATERIAL_BATCH_RESERVE", "material_batch_reserve",
            "MATERIAL_BATCH_RELEASE", "material_batch_release");
    private static final Set<String> PURCHASE_TOOLS = Set.of("purchase_order_list", "purchase_order_detail");
    private static final Set<String> KG_UNITS = Set.of("公斤", "千克", "kg", "KG", "Kg");

    public Optional<FollowUp> resolve(String userInput, Map<String, Object> lastAction) {
        return resolve(userInput, lastAction, System.currentTimeMillis());
    }

    public Optional<FollowUp> resolve(String userInput, Object lastAction, long nowMs) {
        String text = normalize(userInput);
        if (text.isEmpty() || text.length() > MAX_UTTERANCE_CHARS) {
            return Optional.empty();
        }
        Optional<LastActionContract.Sanitized> sanitized = LastActionContract.sanitize(lastAction, nowMs);
        if (sanitized.isEmpty()) {
            return Optional.empty();
        }
        LastActionContract.Sanitized last = sanitized.get();
        Map<String, Object> p = last.params();

        if (last.is("WORK_PROCESS_TASK_START", "work_process_task_start")) {
            Matcher m = DONE.matcher(text);
            if (m.matches() && (m.group(4) == null || !DONE_X_EXCLUDED.matcher(m.group(4)).find())) {
                Map<String, Object> out = new LinkedHashMap<>();
                copy(p, out, "taskId");
                String num = m.group(1) != null ? m.group(1) : m.group(5);
                if (num != null) {
                    BigDecimal qty = parseNumber(num);
                    if (qty == null) {
                        return Optional.empty();
                    }
                    out.put("actualQuantity", LastActionContract.normalize(qty));
                }
                return Optional.of(new FollowUp(RULE_WP_DONE, "WORK_PROCESS_TASK_COMPLETE", out,
                        summarize(out, m.group(2) != null ? m.group(2) : m.group(6))));
            }
        }

        if (last.is("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete") && NEXT.matcher(text).matches()) {
            return Optional.of(new FollowUp(RULE_WP_NEXT, "WORK_PROCESS_TASK_START", new LinkedHashMap<>(), ""));
        }

        for (Map.Entry<String, String> qtyIntent : QTY_INTENTS.entrySet()) {
            if (last.is(qtyIntent.getKey(), qtyIntent.getValue())) {
                Optional<FollowUp> qty = quantityChange(text, qtyIntent.getKey(), p);
                if (qty.isPresent()) {
                    return qty;
                }
            }
        }

        if (isPurchaseContext(last)) {
            Matcher approve = APPROVE.matcher(text);
            if (!REJECT.matcher(text).matches() && approve.matches()
                    && !APPROVE_PREFIX_EXCLUDED.matcher(approve.group(1)).find()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", "approve");
                copy(p, out, "orderId");
                return Optional.of(new FollowUp(RULE_PO_APPROVE, "PURCHASE_ORDER_APPROVE", out, summarize(out, null)));
            }
        }

        if (last.is("HR_LEAVE_REQUEST_QUERY", "leave_request_query")) {
            Matcher reject = REJECT.matcher(text);
            Matcher approve = APPROVE.matcher(text);
            String decision = null;
            String ruleId = null;
            if (reject.matches() && !REJECT_PREFIX_EXCLUDED.matcher(reject.group(1)).find()) {
                decision = "REJECT";
                ruleId = RULE_LEAVE_REJECT;
            } else if (!reject.matches() && approve.matches()
                    && !APPROVE_PREFIX_EXCLUDED.matcher(approve.group(1)).find()) {
                decision = "APPROVE";
                ruleId = RULE_LEAVE_APPROVE;
            }
            if (decision != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("decision", decision);
                copy(p, out, "leaveRequestId");
                return Optional.of(new FollowUp(ruleId, "HR_LEAVE_APPROVE", out, summarize(out, null)));
            }
        }
        return Optional.empty();
    }

    private Optional<FollowUp> quantityChange(String text, String intentCode, Map<String, Object> p) {
        BigDecimal base = LastActionContract.toDecimal(p.get("quantity"));
        if (base == null || base.signum() <= 0) {
            return Optional.empty();
        }
        Matcher m;
        BigDecimal result;
        if ((m = QTY_ADD.matcher(text)).matches()) {
            BigDecimal n = parseNumber(m.group(1));
            result = n == null ? null : base.add(n);
        } else if ((m = QTY_SUB.matcher(text)).matches()) {
            BigDecimal n = parseNumber(m.group(1));
            result = n == null ? null : base.subtract(n);
        } else if ((m = QTY_SET_PREFIX.matcher(text)).matches() || (m = QTY_SET_SUFFIX.matcher(text)).matches()) {
            result = parseNumber(m.group(1));
        } else {
            return Optional.empty();
        }
        if (result == null || result.signum() <= 0 || !unitCompatible(m.group(2), p.get("unit"))) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : p.entrySet()) {
            if (!LastActionContract.ID_LIST_KEYS.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        out.put("quantity", LastActionContract.normalize(result));
        return Optional.of(new FollowUp(RULE_QTY_CHANGE, intentCode, out,
                summarize(out, m.group(2) != null ? m.group(2) : null)));
    }

    private static boolean isPurchaseContext(LastActionContract.Sanitized last) {
        String code = last.intentCode();
        if ("PURCHASE_ORDER_APPROVE".equals(code) || "purchase_order_approve".equals(last.toolName())) {
            return false;   // 刚批完一张, 「批了」不是再批同一张
        }
        if (code.startsWith("PURCHASE_ORDER") || (last.toolName() != null && PURCHASE_TOOLS.contains(last.toolName()))) {
            return true;
        }
        boolean pendingList = "APPROVAL_PENDING_QUERY".equals(code)
                || "approval_pending_query".equals(last.toolName());
        return pendingList && (last.params().containsKey("orderId") || last.params().containsKey("orderIds"));
    }

    private static boolean unitCompatible(String spoken, Object known) {
        if (spoken == null || !(known instanceof String k)) {
            return true;
        }
        if (KG_UNITS.contains(spoken) && KG_UNITS.contains(k)) {
            return true;
        }
        return spoken.equals(k);
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    private static String summarize(Map<String, Object> params, String spokenUnit) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, "任务", params.get("taskId"));
        appendPart(sb, "采购单", params.get("orderId"));
        appendPart(sb, "请假单", params.get("leaveRequestId"));
        appendPart(sb, "批次", params.get("batchId"));
        Object qty = params.containsKey("quantity") ? params.get("quantity") : params.get("actualQuantity");
        if (qty != null) {
            Object unit = params.get("unit") != null ? params.get("unit") : spokenUnit;
            appendPart(sb, "数量", qty + (unit != null ? " " + unit : ""));
        }
        if ("REJECT".equals(params.get("decision"))) {
            appendPart(sb, "决定", "驳回");
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String label, Object value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("，");
        }
        sb.append(label).append(' ').append(value);
    }

    /** 去掉空白与首尾的非问号标点; 问号留着 —— 「好了？」是在问, 不是在说做完了。 */
    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String t = input.replaceAll("[\\s\\u3000]+", "");
        return t.replaceAll("^[，,。.！!~～、]+", "").replaceAll("[，,。.！!~～、]+$", "");
    }

    /** 阿拉伯数字或中文数字 (十=10, 二十=20, 一百五=150, 两=2, 一百零五=105)。不合规返回 null。 */
    static BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.')) {
            BigDecimal v = LastActionContract.toDecimal(raw);
            return v == null || v.signum() <= 0 ? null : v;
        }
        Long v = parseChinese(raw);
        return v == null || v <= 0 ? null : BigDecimal.valueOf(v);
    }

    static Long parseChinese(String s) {
        long total = 0;
        long digit = -1;          // 尚未落位的那个数字
        long lastUnit = 10_000;   // 单位必须严格递减
        boolean zeroAfterUnit = false;
        boolean previousWasUnit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = "零一二三四五六七八九".indexOf(c);
            if (c == '两') {
                d = 2;
            }
            if (d >= 0) {
                if (digit >= 0) {
                    return null;              // 「一二」这种连写不认
                }
                if (d == 0) {
                    if (!previousWasUnit) {
                        return null;
                    }
                    zeroAfterUnit = true;
                    previousWasUnit = false;
                    continue;
                }
                digit = d;
                previousWasUnit = false;
                continue;
            }
            long unit = switch (c) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                default -> -1;
            };
            if (unit < 0 || unit >= lastUnit) {
                return null;
            }
            if (digit < 0) {
                if (unit != 10 || total != 0) {
                    return null;              // 只有开头的「十」可以省略「一」
                }
                digit = 1;
            }
            total += digit * unit;
            digit = -1;
            lastUnit = unit;
            zeroAfterUnit = false;
            previousWasUnit = true;
        }
        if (digit >= 0) {
            // 口语省略末位单位: 一百五 = 150, 一千二 = 1200; 有「零」时是个位: 一百零五 = 105
            boolean colloquial = lastUnit >= 100 && lastUnit != 10_000 && !zeroAfterUnit;
            total += colloquial ? digit * (lastUnit / 10) : digit;
        }
        return total;
    }
}
