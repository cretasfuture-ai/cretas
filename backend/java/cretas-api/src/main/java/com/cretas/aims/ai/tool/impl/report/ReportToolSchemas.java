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

    /** 指标 / 问句 / 时间 / 范围的「整词」: 只整词剥, 不逐字剥 (「汇总」不能剥成「汇」)。 */
    private static final java.util.regex.Pattern METRIC_WORDS = java.util.regex.Pattern.compile(
            "毛利率|毛利|成本|占比|占总|哪个产品|哪个|哪些|哪款|哪种|多少|排序|排名|最高|最低|最多|最少|怎么样|是什么|"
                    + "人工|原料|设备|单位|汇总|合计|全厂|整体|总体|"
                    + "这个月|上个月|本月|上月|本周|上周|这周|本季度|上季度|本年|全年|今年|去年|"
                    + "近[一二两三四五六七八九十0-9]+个?(?:月|天|周|年)|前[一二两三四五六七八九十0-9]+个?(?:月|天|周|年)|"
                    + "全部|所有|每个|各个|(?i:sku)|产品");

    /** 剥词后留在两头的虚词 (只在两头剥, 不碰中间: 「外婆的红烧肉」)。 */
    private static final java.util.regex.Pattern EDGE_PARTICLES = java.util.regex.Pattern.compile(
            "^[的和与了吗是总占率各\\s，,。？?！!]+|[的和与了吗是总占率各\\s，,。？?！!]+$");

    /**
     * 大模型抽参给的 productName 先过这一道 (确定性, 不靠模型每次抽对, 所以两个角色对同一句话结果一致):
     * <ol>
     *   <li>范围词 (全部 / 各 / 所有产品) = 不限产品 → null;</li>
     *   <li>有产品目录时, 原词先在本厂在用产品里精确 / 包含匹配 (命中就原样用, 不剥词), 或目录里恰有一个产品名整个出现在原文里 → 用该产品名;</li>
     *   <li>原词不带问句 / 指标词 → 原样当产品名;</li>
     *   <li>否则整词剥掉指标 / 时间 / 范围用语, 剩下的当产品名; 剥完为空或只剩范围词 = 不限产品 (null)。剩下不认识的词原样当产品名, 由工具如实回「没有找到」, ⛔ 不静默扩成全厂。</li>
     * </ol>
     */
    static String productKeyword(ProductTypeRepository repo, String factoryId, String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.isEmpty()) return null;
        String core = k.replaceAll("[的\\s]", "").toLowerCase(java.util.Locale.ROOT);
        if (core.isEmpty() || SCOPE_WORDS.contains(core)) return null;
        if (repo != null && factoryId != null) {
            if (!matchProducts(repo, factoryId, k).isEmpty()) return k;
            String named = uniqueCatalogNameInside(repo, factoryId, k);
            if (named != null) return named;
        }
        if (!QUESTION_FRAGMENT.matcher(k).find() && k.length() <= 40) return k;
        String rest = EDGE_PARTICLES.matcher(METRIC_WORDS.matcher(k).replaceAll("")).replaceAll("");
        String restCore = rest.toLowerCase(java.util.Locale.ROOT);
        if (restCore.isEmpty() || SCOPE_WORDS.contains(restCore)) return null;
        return rest.length() > 40 ? null : rest;
    }

    /** 测试 / 无目录场景: 同 {@link #productKeyword(ProductTypeRepository, String, String)} 不查目录。 */
    static String productKeyword(String raw) {
        return productKeyword(null, null, raw);
    }

    /** 目录里名字整个出现在原文里的产品: 去掉被更长名字包含的, 恰好剩一个就返回它的名字; 0 个或多个返回 null。 */
    private static String uniqueCatalogNameInside(ProductTypeRepository repo, String factoryId, String text) {
        List<ProductType> types = repo.findByFactoryIdAndIsActiveTrue(factoryId);
        if (types == null) return null;
        List<String> inside = new ArrayList<>();
        for (ProductType t : types) {
            String n = t.getName() == null ? "" : t.getName().trim();
            if (n.length() >= 2 && text.contains(n) && !inside.contains(n)) inside.add(n);
        }
        List<String> maximal = new ArrayList<>();
        for (String n : inside) {
            boolean covered = false;
            for (String other : inside) {
                if (!other.equals(n) && other.contains(n)) covered = true;
            }
            if (!covered) maximal.add(n);
        }
        return maximal.size() == 1 ? maximal.get(0) : null;
    }

    static Map<String, Object> withOptionalExtraction(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(ToolDispatchService.EXTRACT_OPTIONAL_FLAG, true);
        return copy;
    }
}
