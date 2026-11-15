package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.service.execution.ToolDispatchService;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只有可选参数的只读查询工具, 默认不会让大模型从原话里抽参 (ToolDispatchService 只对声明了
 * {@link ToolDispatchService#EXTRACT_OPTIONAL_FLAG} 的工具抽, 避免每次多一次模型调用)。
 * 成本 / 毛利 / 出成率这类问法常带产品名 (「卤牛肉单位成本」), 不抽就会答成全厂汇总 (2026-11-15 财务复跑 Q01/Q02/Q16)。
 * 日期键 schema 带 format:date, 由 LlmParameterGrounding 丢掉原话里没说过的日期; 产品名不靠落地校验,
 * 由 {@link #resolveProduct} 拿本厂在用产品目录解析 (#1767 第 3 轮口径表 B, 见 ProductKeywordTableContractTest)。
 */
final class ReportToolSchemas {
    private ReportToolSchemas() {}

    /** 产品名解析结果: 不限产品 / 锁定到目录里的产品 / 如实说没找到 / 无目录时按名称筛。 */
    enum ProductScope { ALL, MATCHED, NOT_FOUND, NAME_ONLY }

    /**
     * @param products    MATCHED 时锁定的产品 (目录顺序; 多个 = 原话里同时问到了几个产品)
     * @param keyword     NOT_FOUND 时是剥词后剩下的词; NAME_ONLY 时是按名称筛的关键词
     * @param suggestions NOT_FOUND 时名称里含 keyword 的在用产品名, 只做提示, ⛔ 不当答案
     * @param catalog     本次请求已加载的本厂在用产品 (null = 没有目录); 工具复用它, 不再查第二次
     * @param note        MATCHED 时问句里的产品名比命中的目录名多了字 (「卤牛肉片」命中「卤牛肉」): 回复开头必须写的说明, 否则 null
     */
    record ProductResolution(ProductScope scope, List<ProductType> products, String keyword,
                             List<String> suggestions, List<ProductType> catalog, String note) {

        boolean isAll() { return scope == ProductScope.ALL; }

        List<String> productNames() {
            List<String> names = new ArrayList<>();
            for (ProductType t : products) names.add(t.getName());
            return names;
        }

        Set<String> productIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (ProductType t : products) ids.add(t.getId());
            return ids;
        }

        /** 回复 / data 里写的产品: 锁定的产品名 (多个用「、」连), 或按名称筛的关键词; 不限产品为 null。 */
        String label() {
            return switch (scope) {
                case MATCHED -> String.join("、", productNames());
                case NOT_FOUND, NAME_ONLY -> keyword;
                case ALL -> null;
            };
        }

        /** 测试用的一行描述: 全厂 / 卤牛肉+酱鸭 / 未找到:牛油果 / 名称:xx。 */
        String describe() {
            return switch (scope) {
                case ALL -> "全厂";
                case MATCHED -> String.join("+", productNames());
                case NOT_FOUND -> "未找到:" + keyword;
                case NAME_ONLY -> "名称:" + keyword;
            };
        }

        /**
         * 工具该直接回给用户的说明 (null = 照常往下算)。
         * 没找到: 如实说「没有找到名称含「X」」; 名称里含 X 的产品只列出来请用户说完整名, ⛔ 不拿它们中的哪一个回答、不合计。
         * 同时问到多个产品而工具只能答一个产品口径 (singleProductOnly): 明确说「问到了 N 个产品」, ⛔ 不悄悄丢掉其中一个。
         */
        String refusalMessage(String what, boolean singleProductOnly) {
            if (scope == ProductScope.NOT_FOUND) {
                if (suggestions.isEmpty()) {
                    return "没有找到名称含「" + keyword + "」的在用产品（已停用的产品不在查询范围），无法" + what + "。请确认产品名。";
                }
                return "没有名为「" + keyword + "」的在用产品。名称里含「" + keyword + "」的有 " + suggestions.size() + " 个："
                        + String.join("、", suggestions) + "。请说清楚要看哪一个（说完整产品名），我不按猜的产品回答，也不把它们合计成一个数。";
            }
            if (scope == ProductScope.MATCHED && singleProductOnly && products.size() > 1) {
                return "问到了 " + products.size() + " 个产品：" + String.join("、", productNames())
                        + "。这项按单个产品统计，请一次问一个产品，我不把它们合计成一个数。";
            }
            return null;
        }

        /** 回复里给的候选名 (NOT_FOUND = 提示名; 多产品 = 问到的产品)。 */
        List<String> candidateNames() {
            return scope == ProductScope.NOT_FOUND ? suggestions
                    : scope == ProductScope.MATCHED && products.size() > 1 ? productNames() : List.of();
        }
    }

    /** 「全部 / 各 / 所有产品」这类范围词: 说的是「不限产品」, 不是产品名。 */
    private static final java.util.Set<String> SCOPE_WORDS = java.util.Set.of(
            "全部", "所有", "全部产品", "所有产品", "各", "各个", "各产品", "各个产品", "每个", "每个产品", "每种", "每款",
            "全部sku", "所有sku", "各sku", "每个sku", "sku", "产品", "全厂", "全厂产品", "整体", "总体", "汇总", "合计", "全部品种", "所有品种");

    /** 剥完只剩这些连接 / 修饰字 (「总」「和」「各」…) 也算没说产品。 */
    private static final java.util.regex.Pattern ONLY_JOINERS = java.util.regex.Pattern.compile("^[总各和与及占率的了是吗]+$");

    /** 产品名里不会出现、问句 / 分析词里才会出现的片段: 带了它就是把整句话 (或一段问法) 当成了产品名。 */
    private static final java.util.regex.Pattern QUESTION_FRAGMENT = java.util.regex.Pattern.compile(
            "哪个|哪些|哪款|哪种|多少|排序|排名|占比|毛利|成本|最高|最低|最多|最少|怎么|是什么|吗|[？?]");

    /** 指标 / 问句 / 时间 / 范围的「整词」: 只整词剥, 不逐字剥 (「汇总」不能剥成「汇」)。 */
    private static final java.util.regex.Pattern METRIC_WORDS = java.util.regex.Pattern.compile(
            "毛利率|毛利|成本|占比|占总|哪个产品|哪个|哪些|哪款|哪种|多少|排序|排名|最高|最低|最多|最少|怎么样|是什么|"
                    + "人工|原料|设备|单位|汇总|合计|全厂|整体|总体|"
                    + "差异|情况|数据|明细|趋势|变化|对比|分析|报表|统计|出成率|出成|合格率|单价|价格|售价|利润|收入|销量|产量|"
                    + "这个月|上个月|本月|上月|本周|上周|这周|本季度|上季度|本季|上季|上半年|下半年|本年|全年|今年|去年|今天|昨天|前天|最近|"
                    + "近[一二两三四五六七八九十0-9]+个?(?:月|天|周|年)|前[一二两三四五六七八九十0-9]+个?(?:月|天|周|年)|"
                    + "全部|所有|每个|各个|(?i:sku)|产品");

    /** 剥词后留在两头的虚词和标点 (只在两头剥, 不碰中间: 「外婆的红烧肉」; ⛔ 不剥「总 / 和」: 「总汇三明治」「和牛」)。 */
    private static final java.util.regex.Pattern EDGE_PARTICLES = java.util.regex.Pattern.compile(
            "^[的了吗是\\s，,。？?！!]+|[的了吗是\\s，,。？?！!]+$");

    /**
     * 大模型抽参给的 productName (常是一段原话) 按口径表 B 解析, 本厂目录只查一次:
     * <ol>
     *   <li>空 / 范围词 (全部 / 各 / 所有产品) → 不限产品;</li>
     *   <li>目录里名称<b>完整出现在原话里</b>的产品, 去掉被更长命中名包含的 (「卤牛肉」里的「牛肉」), 剩下的全部锁定 (可能多个);
     *       ⛔ 不做「原话里的词被目录名包含」的反向包含 (「成本」不是「低成本套餐」);</li>
     *   <li>没有完整命中: 整词剥掉指标 / 时间 / 范围 / 问句用语; 剩下为空或只剩范围词 → 不限产品; 否则如实「没有找到」, 不猜;</li>
     *   <li>没有目录 (repo / factoryId 为空): 只剥词, 剩下的按名称筛 (NAME_ONLY)。</li>
     * </ol>
     */
    static ProductResolution resolveProduct(ProductTypeRepository repo, String factoryId, String raw) {
        List<ProductType> catalog = null;
        if (repo != null && factoryId != null) {
            List<ProductType> loaded = repo.findByFactoryIdAndIsActiveTrue(factoryId);
            catalog = loaded == null ? List.of() : loaded;
        }
        return resolveProduct(catalog, raw);
    }

    /** 同上, 目录已由调用方加载 (null = 没有目录)。 */
    static ProductResolution resolveProduct(List<ProductType> catalog, String raw) {
        String k = raw == null ? "" : raw.trim();
        String core = k.replaceAll("[的\\s]", "").toLowerCase(Locale.ROOT);
        if (core.isEmpty() || SCOPE_WORDS.contains(core)) return all(catalog);
        if (catalog == null) {
            String keyword = productKeyword(k);
            return keyword == null ? all(null)
                    : new ProductResolution(ProductScope.NAME_ONLY, List.of(), keyword, List.of(), null, null);
        }

        String text = k.toLowerCase(Locale.ROOT);
        List<ProductType> hits = new ArrayList<>();
        for (ProductType t : catalog) {
            String n = normalizedName(t);
            if (n == null) continue;
            // 单字产品名只认整句就是它 (「鸭」不能命中所有带鸭的问句)
            if ((n.length() >= 2 && text.contains(n)) || core.equals(n)) hits.add(t);
        }
        List<ProductType> maximal = new ArrayList<>();
        for (ProductType t : hits) {
            String n = normalizedName(t);
            boolean covered = false;
            for (ProductType other : hits) {
                String o = normalizedName(other);
                if (o.length() > n.length() && o.contains(n)) covered = true;
            }
            if (!covered) maximal.add(t);
        }
        if (!maximal.isEmpty()) {
            return new ProductResolution(ProductScope.MATCHED, List.copyOf(maximal), null, List.of(), catalog,
                    substitutionNote(k, maximal));
        }

        String rest = stripQuestionWords(k);
        if (rest == null) return all(catalog);
        String restKey = rest.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (ProductType t : catalog) {
            String n = normalizedName(t);
            if (n != null && n.contains(restKey) && !suggestions.contains(t.getName().trim())) suggestions.add(t.getName().trim());
        }
        return new ProductResolution(ProductScope.NOT_FOUND, List.of(), rest, List.copyOf(suggestions), catalog, null);
    }

    /** 命中名后面紧跟的字到这里为止: 指标 / 时间 / 范围词, 或虚词、连接字、标点。 */
    private static final java.util.regex.Pattern AFTER_NAME_BOUNDARY = java.util.regex.Pattern.compile(
            METRIC_WORDS.pattern() + "|[的和与及跟了吗是，,。、；;？?！!\\s]");

    /**
     * organizer 裁定 (#1767 第 3 轮): 最长完整命中照用, 但问句里命中名后面还跟着别的字 (「卤牛肉片」命中「卤牛肉」, 剩下的「片」
     * 不是指标 / 范围 / 虚词) 时不能悄悄换产品, 回复开头明说「没有名为「卤牛肉片」的产品，下面按「卤牛肉」回答」。
     */
    private static String substitutionNote(String question, List<ProductType> matched) {
        String text = question.toLowerCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        for (ProductType t : matched) {
            String name = t.getName().trim();
            int at = text.indexOf(name.toLowerCase(Locale.ROOT));
            if (at < 0) continue;   // 单字名整句相等的情况
            String tail = question.substring(at + name.length());
            int end = tail.length();
            java.util.regex.Matcher m = AFTER_NAME_BOUNDARY.matcher(tail);
            if (m.find()) end = m.start();
            for (ProductType other : matched) {   // 「卤牛肉酱鸭」: 紧跟的是另一个命中名, 不算多出来的字
                int o = tail.toLowerCase(Locale.ROOT).indexOf(other.getName().trim().toLowerCase(Locale.ROOT));
                if (other != t && o >= 0 && o < end) end = o;
            }
            String extra = tail.substring(0, end).trim();
            if (!extra.isEmpty()) {
                notes.add("没有名为「" + name + extra + "」的产品，下面按「" + name + "」回答。");
            }
        }
        return notes.isEmpty() ? null : String.join("", notes);
    }

    /** 解析带了说明 (按更短的目录名回答) 时, 把说明加在工具回复开头; 没有说明原样返回。 */
    static Map<String, Object> withNote(Map<String, Object> result, String note) {
        if (note == null || result == null) return result;
        Map<String, Object> copy = new java.util.HashMap<>(result);
        Object msg = copy.get("message");
        copy.put("message", msg == null ? note : note + msg);
        return copy;
    }

    private static ProductResolution all(List<ProductType> catalog) {
        return new ProductResolution(ProductScope.ALL, List.of(), null, List.of(), catalog, null);
    }

    private static String normalizedName(ProductType t) {
        if (t == null || t.getId() == null || t.getName() == null) return null;
        String n = t.getName().trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }

    /** 整词剥掉指标 / 时间 / 范围 / 问句用语; 剩下为空或只剩范围 / 连接字返回 null (= 不限产品)。 */
    private static String stripQuestionWords(String k) {
        String rest = EDGE_PARTICLES.matcher(METRIC_WORDS.matcher(k).replaceAll("")).replaceAll("");
        String restCore = rest.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (restCore.isEmpty() || SCOPE_WORDS.contains(restCore) || ONLY_JOINERS.matcher(restCore).matches()) return null;
        return rest;
    }

    /**
     * 没有目录时的产品关键词 (出成率排行按批次上的产品名筛): 范围词 → null; 原词不带问句 / 指标词 → 原样;
     * 否则整词剥, 剩下为空或只剩范围词 → null。
     */
    static String productKeyword(String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.isEmpty()) return null;
        String core = k.replaceAll("[的\\s]", "").toLowerCase(Locale.ROOT);
        if (core.isEmpty() || SCOPE_WORDS.contains(core)) return null;
        if (!QUESTION_FRAGMENT.matcher(k).find() && k.length() <= 40) return k;
        String rest = stripQuestionWords(k);
        return rest == null || rest.length() > 40 ? null : rest;
    }

    static Map<String, Object> withOptionalExtraction(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(ToolDispatchService.EXTRACT_OPTIONAL_FLAG, true);
        return copy;
    }
}
