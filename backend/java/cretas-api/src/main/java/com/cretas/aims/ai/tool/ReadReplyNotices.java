package com.cretas.aims.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读查询回复开头要写明的默认值与限制 (Steve 2026-11-12 口径: 只读且默认不会误导的直接执行, 并写明用了什么默认;
 * 做不到的口径说清楚做不到, 同时给出最接近的结果)。
 *
 * <p>每条提示是一个 Map: {@code kind} + {@code text} + 该类提示自己的可观测字段, 成功的只读执行后放进响应
 * {@code metadata.readNotices} (评测据此计分), 同时按顺序加在 message / formattedText 开头。加前缀是幂等的:
 * 两个入口的结果格式化会重写 formattedText, 重写之后再加一次不会叠两份。
 */
public final class ReadReplyNotices {

    public static final String METADATA_KEY = "readNotices";

    /** 点名了工具不支持的口径: 按支持的口径执行。字段 parameter / requested / requestedLabel / applied / appliedLabel。 */
    public static final String UNSUPPORTED_OPTION = "UNSUPPORTED_OPTION";
    /** 没说时间: 按工具声明的默认最近 N 天查。字段 days / from / to。 */
    public static final String DEFAULT_TIME_RANGE = "DEFAULT_TIME_RANGE";
    /** 结果多于一页: 写明总条数与本页范围。字段 total / page / size。 */
    public static final String PAGED_RESULT = "PAGED_RESULT";

    private ReadReplyNotices() {
    }

    public static Map<String, Object> notice(String kind, String text, Map<String, Object> fields) {
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("kind", kind);
        notice.put("text", text);
        if (fields != null) fields.forEach(notice::putIfAbsent);
        return notice;
    }

    /** metadata.readNotices 里的提示文字 (按顺序); 没有时为空表。 */
    public static List<String> texts(Map<String, Object> metadata) {
        List<String> out = new ArrayList<>();
        if (metadata != null && metadata.get(METADATA_KEY) instanceof List<?> notices) {
            for (Object raw : notices) {
                if (raw instanceof Map<?, ?> notice && notice.get("text") instanceof String text && !text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    /** 把提示加在回复开头; 已经以这几句开头时原样返回 (幂等)。 */
    public static String prefix(String reply, List<String> texts) {
        if (texts == null || texts.isEmpty()) return reply;
        String head = String.join("\n", texts);
        if (reply == null || reply.isBlank()) return head;
        if (reply.startsWith(head)) return reply;
        return head + "\n" + reply;
    }
}
