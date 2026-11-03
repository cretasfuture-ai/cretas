package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 训练样本的「本厂名单」匹配规则 (Steve 2026-10-29 选 A) —— Java 侧唯一的一份。
 *
 * <p>与 Python {@code backend/python/common/process_secret_terms.py} 同一规则; 两边都跑
 * {@code backend/python/smartbi/services/process_secret_filter_rule_cases.json} (同一份用例文件),
 * Python 测试还比对两边的 {@link #CATEGORY_WORDS}。改这里同时改 Python。
 *
 * <ul>
 *   <li>工艺保密产品的保密词: 只认<b>全名 / 编码 / 产品 ID / 登记过的专属别名</b>。纯数字的名字
 *       (F006 有个产品叫「398」, 批次号里到处是 398) 与品类词 ({@link #CATEGORY_WORDS}, 以及这些保密产品
 *       自己的品类字段值) 不收;</li>
 *   <li>本厂人员姓名 / 客户名称: 原样出现才算 (不再按「岗位词 + 姓氏」猜)。</li>
 * </ul>
 * 匹配: 只折叠 ASCII 大小写 (两边结果才一致), 汉字照原样比; 词的首 / 尾若是 ASCII 字母数字,
 * 前 / 后紧挨着的字符就不能也是 ASCII 字母数字或下划线 (编码 {@code PTF0060159} 不会在
 * {@code PTF00601590} 里命中)。少于 2 个字的不收。
 *
 * <p>只给训练样本 / 学习用; 外发脱敏仍用 {@link ProcessSecretSampleFilter#protectedProductNames}。
 */
public final class ProcessSecretTerms {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProcessSecretTerms.class);

    static final int MIN_TERM_LENGTH = 2;

    /**
     * 品类词 (含单一部位与菜名: 牛腱、鸭脖、米饭 …, Steve 2026-11-03 Q4 选 A)。只有一份:
     * {@code backend/python/common/training_rule_words.json}, 构建时拷进 jar, 与 Python 读同一个文件。
     */
    public static final List<String> CATEGORY_WORDS = TrainingValueRules.strings("category_words", false);

    /** 品类词 (已折叠)。只认这份词表 —— 复审第二轮: 不从保密产品的品类字段派生, 否则名字收不收取决于数据。 */
    static final Set<String> GENERIC_WORDS = genericWords();

    /**
     * 保密产品的「全名」本身就是品类词 (产品就叫「牛腱」) 时还收不收: Steve 2026-11-03 选 B = 不收 (只靠编码 / ID / 工艺数值规则); 值在
     * {@code training_rule_words.json} 的 {@code full_name_category_word_matches} (与 Python 同一行)。
     */
    static final Boolean FULL_NAME_CATEGORY_WORD_MATCHES = strictBoolean(TrainingValueRules.WORDS,
            "full_name_category_word_matches");


    /**
     * 词表开关只认 JSON 布尔 (true / false); 字符串 "false"、0、null、缺失一律返回 null 并记 ERROR
     * (第三次复审第 2 条: Python 的 bool("false") 是 True, 两边会悄悄不一致)。不在类初始化时抛 (那会变成 Error)。
     */
    static Boolean strictBoolean(com.fasterxml.jackson.databind.JsonNode words, String key) {
        if (words == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode n = words.get(key);
        if (n == null || !n.isBoolean()) {
            LOG.error("training_rule_words.json: {} must be a JSON boolean (true / false), got {}", key, n);
            return null;
        }
        return n.booleanValue();
    }

    /** 开关值; 词表里不是 JSON 布尔时抛 {@link IllegalStateException} (过滤器按名单查询失败处理: 挂起 / 不存)。 */
    static boolean fullNameCategoryWordMatches() {
        if (FULL_NAME_CATEGORY_WORD_MATCHES == null) {
            throw new IllegalStateException(
                    "training_rule_words.json: full_name_category_word_matches must be a JSON boolean (true / false)");
        }
        return FULL_NAME_CATEGORY_WORD_MATCHES;
    }

    /** 纯数字 (允许中间夹空白 / 点 / 横线 / 下划线 / 斜杠): 398、2024-01、１２３。 */
    private static final Pattern PURE_NUMERIC = Pattern.compile("[0-9０-９ \\t.\\-_/\\u3000]+");

    private ProcessSecretTerms() {
    }

    /** 只把 ASCII 大写字母转小写 (与 Python {@code fold} 一致)。 */
    static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** 名单里能用的那个词 (已去首尾空白、已折叠); 太短 / 纯数字 / 品类词返回 null。 */
    static String usableTerm(String raw, Set<String> generic) {
        if (raw == null) {
            return null;
        }
        String term = fold(raw.strip());
        if (term.length() < MIN_TERM_LENGTH || PURE_NUMERIC.matcher(term).matches() || generic.contains(term)) {
            return null;
        }
        return term;
    }

    /** 一个保密产品的原始字段 (测试与 Python 用例文件共用这一个形状)。 */
    public record Product(String id, String code, String name, String category, String productCategory) {
        public static Product of(ProductType p) {
            return new Product(p.getId(), p.getCode(), p.getName(), p.getCategory(), p.getProductCategory());
        }
    }

    private static Set<String> genericWords() {
        Set<String> words = new LinkedHashSet<>();
        for (String w : CATEGORY_WORDS) {
            String f = fold(w);
            if (!f.isEmpty()) {
                words.add(f);
            }
        }
        return Set.copyOf(words);
    }

    /**
     * 登记别名时不收的原因; 能用返回 null。少于 2 个字 / 纯数字 / 品类词的别名匹配器会悄悄丢掉,
     * 所以登记入口直接拒绝 (复审第二轮: 不能 PUT 回 200 却没生效)。
     */
    public static String unusableAliasReason(String alias) {
        TrainingValueRules.requireWords();
        String t = alias == null ? "" : fold(alias.strip());
        if (t.length() < MIN_TERM_LENGTH) {
            return "少于 " + MIN_TERM_LENGTH + " 个字";
        }
        if (PURE_NUMERIC.matcher(t).matches()) {
            return "是纯数字 (批次号、数量里到处都是)";
        }
        if (GENERIC_WORDS.contains(t)) {
            return "是品类词 (说的是一类东西, 不是这个产品)";
        }
        return null;
    }

    /**
     * 产品全名是品类词, 且按 Steve 2026-11-03 选 B 训练过滤不按这个名字拦 (只靠编码 / ID / 工艺数值规则) ——
     * 保密负责人保存策略时要看到提示。
     */
    public static boolean fullNameNotMatchedAsCategoryWord(String name) {
        return !fullNameCategoryWordMatches() && isCategoryWord(name);
    }

    /** {@code raw} (去首尾空白、折叠后) 是词表里的品类词。 */
    public static boolean isCategoryWord(String raw) {
        TrainingValueRules.requireWords();
        return raw != null && GENERIC_WORDS.contains(fold(raw.strip()));
    }

    /**
     * 一家工厂的保密词: 每个保密产品的全名 / 编码 / ID, 加上登记过的专属别名
     * (别名目前没有登记载体, 生产上恒为空 —— 见 PR 待决问题)。
     */
    public static Set<String> secretTerms(Collection<Product> products, Collection<String> aliases) {
        TrainingValueRules.requireWords();       // 词表读不到: 抛运行时异常, 过滤器按名单查询失败处理 (挂起 / 不存)
        boolean fullNameMatches = fullNameCategoryWordMatches();
        Set<String> out = new LinkedHashSet<>();
        for (Product p : products) {
            String[] values = {p.name(), p.code(), p.id()};
            for (int k = 0; k < values.length; k++) {
                boolean exempt = k == 0 && fullNameMatches;
                String term = usableTerm(values[k], exempt ? Set.of() : GENERIC_WORDS);
                if (term != null) {
                    out.add(term);
                }
            }
        }
        if (aliases != null) {
            for (String a : aliases) {
                String term = usableTerm(a, GENERIC_WORDS);
                if (term != null) {
                    out.add(term);
                }
            }
        }
        return Set.copyOf(out);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * {@code process_secret_policies.aliases} (JSON 数组文本, Flyway V20261104_12) → 列表; NULL / 空 → 空。
     * 格式不对就抛 (调用方的名单查询失败 → fail-closed), 与 Python {@code policy_aliases} 一致。
     */
    public static List<String> policyAliases(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(raw);
            if (!node.isArray()) {
                throw new IllegalStateException("process_secret_policies.aliases must be a JSON array");
            }
            List<String> out = new ArrayList<>();
            node.forEach(n -> {
                if (!n.isNull()) {
                    out.add(n.asText());
                }
            });
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("process_secret_policies.aliases is not valid JSON", e);
        }
    }

    /** 人员姓名 / 客户名称: 去首尾空白、太短 / 纯数字的不收 (不套品类词)。 */
    public static Set<String> directoryNames(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                String term = usableTerm(n, Set.of());
                if (term != null) {
                    out.add(term);
                }
            }
        }
        return Set.copyOf(out);
    }

    private static boolean occurs(String term, String folded) {
        boolean firstIsWord = isWordChar(term.charAt(0));
        boolean lastIsWord = isWordChar(term.charAt(term.length() - 1));
        int start = 0;
        while (true) {
            int i = folded.indexOf(term, start);
            if (i < 0) {
                return false;
            }
            int j = i + term.length();
            boolean beforeOk = !(firstIsWord && i > 0 && isWordChar(folded.charAt(i - 1)));
            boolean afterOk = !(lastIsWord && j < folded.length() && isWordChar(folded.charAt(j)));
            if (beforeOk && afterOk) {
                return true;
            }
            start = i + 1;
        }
    }

    /** 名单里任一词原样出现在任一文本里 ({@code terms} 用 {@link #secretTerms} / {@link #directoryNames} 造)。 */
    public static boolean mentionsAny(Collection<String> terms, String... texts) {
        if (terms == null || terms.isEmpty() || texts == null) {
            return false;
        }
        List<String> folded = new ArrayList<>(terms.size());
        for (String t : terms) {
            if (t != null && !t.isEmpty()) {
                folded.add(fold(t));
            }
        }
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            String f = fold(text);
            for (String term : folded) {
                if (occurs(term, f)) {
                    return true;
                }
            }
        }
        return false;
    }
}
