package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.service.execution.ToolDispatchService;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只有可选参数的只读查询工具, 默认不会让大模型从原话里抽参 (ToolDispatchService 只对声明了
 * {@link ToolDispatchService#EXTRACT_OPTIONAL_FLAG} 的工具抽, 避免每次多一次模型调用)。
 * 成本 / 毛利 / 出成率这类问法常带产品名 (「卤牛肉单位成本」), 不抽就会答成全厂汇总 (2026-11-15 财务复跑 Q01/Q02/Q16)。
 * 日期键 schema 带 format:date, 由 LlmParameterGrounding 丢掉原话里没说过的日期; 产品名不靠落地校验, 由工具拿本厂在用产品目录解析 (完全同名优先, 多个包含匹配时给候选不合计)。
 */
final class ReportToolSchemas {
    private ReportToolSchemas() {}

    /** 本厂在用产品里名称匹配 keyword 的: 有完全同名 (忽略大小写) 只返回它, 否则返回所有包含匹配。 */
    static List<ProductType> matchProducts(ProductTypeRepository repo, String factoryId, String keyword) {
        List<ProductType> types = repo.findByFactoryIdAndIsActiveTrue(factoryId);
        List<ProductType> exact = new ArrayList<>();
        List<ProductType> contains = new ArrayList<>();
        if (types != null) {
            String k = keyword.toLowerCase(java.util.Locale.ROOT);
            for (ProductType t : types) {
                if (t.getId() == null || t.getName() == null) continue;
                String name = t.getName().trim().toLowerCase(java.util.Locale.ROOT);
                if (name.equals(k)) exact.add(t);
                else if (name.contains(k)) contains.add(t);
            }
        }
        return exact.isEmpty() ? contains : exact;
    }

    /** 候选不是唯一一个时的说明文案 (0 个 = 没找到; 多个 = 列候选, 不合计); 唯一时返回 null。 */
    static String productResolutionMessage(List<ProductType> candidates, String keyword, String what) {
        if (candidates.size() == 1) return null;
        if (candidates.isEmpty()) {
            return "没有找到名称含「" + keyword + "」的在用产品，无法" + what + "。请确认产品名。";
        }
        List<String> names = new ArrayList<>();
        for (ProductType t : candidates) names.add(t.getName());
        return "「" + keyword + "」匹配到 " + names.size() + " 个产品：" + String.join("、", names)
                + "。请说清楚要看哪一个（说完整产品名），我不把它们合计成一个数。";
    }

    /** 「全部 / 各 / 所有产品」这类范围词: 说的是「不限产品」, 不是产品名。 */
    private static final java.util.Set<String> SCOPE_WORDS = java.util.Set.of(
            "全部", "所有", "全部产品", "所有产品", "各", "各个", "各产品", "各个产品", "每个", "每个产品", "每种", "每款",
            "全部sku", "所有sku", "各sku", "每个sku", "sku", "产品", "全厂", "全厂产品", "整体", "总体", "汇总", "合计", "全部品种", "所有品种");

    /** 产品名里不会出现、问句 / 分析词里才会出现的片段: 带了它就是把整句话 (或一段问法) 当成了产品名。 */
    private static final java.util.regex.Pattern QUESTION_FRAGMENT = java.util.regex.Pattern.compile(
            "哪个|哪些|哪款|哪种|多少|排序|排名|占比|毛利|成本|最高|最低|最多|最少|怎么|是什么|吗|[？?]");

    /**
     * 大模型抽参给的 productName 先过这一道: 不是产品名的 (范围词 / 整句问句) 一律当作「没指定产品」(返回 null),
     * 由工具走全量 / 排名路径; 是真产品名的原样返回 (去掉首尾空白), 之后再由 {@link #matchProducts} 在目录里解析。
     * 判断是确定性的 (不靠模型每次是否抽对), 所以财务号 / 管理员号对同一句话结果一致。
     */
    static String productKeyword(String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.isEmpty()) return null;
        String core = k.replaceAll("[的\\s]", "").toLowerCase(java.util.Locale.ROOT);
        if (core.isEmpty() || SCOPE_WORDS.contains(core)) return null;
        if (QUESTION_FRAGMENT.matcher(k).find() || k.length() > 40) return null;
        return k;
    }

    static Map<String, Object> withOptionalExtraction(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(ToolDispatchService.EXTRACT_OPTIONAL_FLAG, true);
        return copy;
    }
}
