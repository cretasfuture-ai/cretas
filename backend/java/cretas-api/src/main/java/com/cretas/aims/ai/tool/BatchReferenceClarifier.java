package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「批次号」参数其实是泛称（成品批次 / 这个批次 / 某批次）时的追问：不查库，回追问并列出最近几个批次让用户选。
 *
 * <p>LSMDEMO 2026-11-18 实测: 「从成品批次往回追」被模型把字面「成品批次」填进 batchNumber, 回「暂时没查到」。
 * 批次号没有默认值, 所以正确做法是列候选让选, 不是猜一个去查。
 *
 * <p>判据: 真实批次号一定含 ASCII 字母或数字; 整串不含任何 ASCII 字母数字的 (纯中文泛称) 不是批次号。
 */
public final class BatchReferenceClarifier {

    static final int CANDIDATE_LIMIT = 5;

    private BatchReferenceClarifier() {
    }

    /** 空白或不含任何 ASCII 字母数字的串, 不是批次号。 */
    public static boolean isGenericReference(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return true;
    }

    private static final String GENERATED_PREFIX = "PB-PLAN-";
    private static final int GENERATED_MIN_LENGTH = 24;
    private static final int TAIL = 5;
    private static final int RESOLVE_WINDOW = 50;
    private static final java.util.regex.Pattern SHORT_LABEL =
            java.util.regex.Pattern.compile("^(?:PB-PLAN-)?\\s*(?:…|\\.{2,3})\\s*([A-Za-z0-9]{3,})");

    /** 计划号时间戳+随机串生成的长批次号 (PB-PLAN-…); 人工录入的短号不是。 */
    static boolean isGeneratedLongNumber(String no) {
        return no != null && no.startsWith(GENERATED_PREFIX) && no.length() >= GENERATED_MIN_LENGTH;
    }

    /** 给用户看的批次标签: 生成的长号缩成「PB-PLAN-…末5位」, 其余原样。数据里的 batchNumber 仍是完整号。 */
    public static String displayLabel(String no) {
        if (!isGeneratedLongNumber(no)) {
            return no;
        }
        return GENERATED_PREFIX + "…" + no.substring(no.length() - TAIL);
    }

    /**
     * 用户用短标签 (PB-PLAN-…56643 / …56643) 回选时, 在最近批次里按末位还原成完整批次号;
     * 不是短标签、或对不上唯一一个长号时原样返回, 由下游照常处理。
     */
    public static String resolveShortLabel(ProductionBatchRepository repository, String factoryId, String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m = SHORT_LABEL.matcher(value.strip());
        if (!m.find()) {
            return value;
        }
        String tail = m.group(1);
        List<String> hits = new ArrayList<>();
        for (ProductionBatch b : repository.findByFactoryId(factoryId, PageRequest.of(0, RESOLVE_WINDOW)).getContent()) {
            String no = b.getBatchNumber();
            if (isGeneratedLongNumber(no) && no.endsWith(tail) && !hits.contains(no)) {
                hits.add(no);
            }
        }
        return hits.size() == 1 ? hits.get(0) : value;
    }

    /** 追问结果: message 为正文, data 带 status=NEED_MORE_INFO 与候选批次 (batchNumber 为完整号, label 为展示用短标签)。 */
    public static Map<String, Object> clarify(ProductionBatchRepository repository, String factoryId,
            String purpose) {
        Map<String, Object> resultShell = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ProductionBatch b : repository.findByFactoryId(factoryId, PageRequest.of(0, CANDIDATE_LIMIT)).getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNumber", b.getBatchNumber());
            row.put("label", displayLabel(b.getBatchNumber()));
            row.put("productName", b.getProductName());
            LocalDateTime when = b.getEndTime() != null ? b.getEndTime()
                    : b.getStartTime() != null ? b.getStartTime() : b.getCreatedAt();
            row.put("date", when == null ? null : when.toLocalDate().toString());
            candidates.add(row);
        }
        StringBuilder sb = new StringBuilder("请告诉我具体的批次号，我再").append(purpose).append("。");
        if (candidates.isEmpty()) {
            sb.append("目前没有查到可选的批次。");
        } else {
            sb.append("最近的批次有：\n");
            for (Map<String, Object> c : candidates) {
                List<String> parts = new ArrayList<>();
                if (c.get("productName") != null) parts.add(String.valueOf(c.get("productName")));
                if (c.get("date") != null) parts.add(String.valueOf(c.get("date")));
                sb.append("• ").append(c.get("label"));
                if (!parts.isEmpty()) sb.append("（").append(String.join("，", parts)).append("）");
                sb.append('\n');
            }
            sb.append("直接回复其中一个批次号（或上面的短标签）即可。");
        }
        resultShell.put("message", sb.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "NEED_MORE_INFO");
        data.put("needMoreInfo", true);
        data.put("missingParameters", List.of("batchNumber"));
        data.put("candidates", candidates);
        resultShell.put("data", data);
        return resultShell;
    }
}
