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

    /** 追问结果: message 为正文, data 带 status=NEED_MORE_INFO 与候选批次 (批次号原样, 不改写)。 */
    public static Map<String, Object> clarify(ProductionBatchRepository repository, String factoryId,
            String purpose) {
        Map<String, Object> resultShell = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ProductionBatch b : repository.findByFactoryId(factoryId, PageRequest.of(0, CANDIDATE_LIMIT)).getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNumber", b.getBatchNumber());
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
                sb.append("• ").append(c.get("batchNumber"));
                if (!parts.isEmpty()) sb.append("（").append(String.join("，", parts)).append("）");
                sb.append('\n');
            }
            sb.append("直接回复其中一个批次号即可。");
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
