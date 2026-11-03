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

    static final int MIN_TERM_LENGTH = 2;

    /** 品类词 (与 Python CATEGORY_WORDS 逐项相同; 标出来的代理判据, 可替换)。 */
    public static final List<String> CATEGORY_WORDS = List.of(
            "牛肉", "猪肉", "羊肉", "鸡肉", "鸭肉", "鹅肉", "鱼肉", "禽肉", "肉类", "肉制品",
            "熟食", "卤味", "卤制品", "酱卤", "酱卤制品", "猪肘", "原切", "调理品", "预制菜",
            "速冻", "冷冻", "冷鲜", "成品", "半成品", "原料", "辅料", "包材", "礼盒");

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

    private static Set<String> genericWords(Collection<Product> products) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : CATEGORY_WORDS) {
            words.add(fold(w));
        }
        for (Product p : products) {
            for (String v : new String[]{p.category(), p.productCategory()}) {
                if (v != null && !v.isBlank()) {
                    words.add(fold(v.strip()));
                }
            }
        }
        return words;
    }

    /**
     * 一家工厂的保密词: 每个保密产品的全名 / 编码 / ID, 加上登记过的专属别名
     * (别名目前没有登记载体, 生产上恒为空 —— 见 PR 待决问题)。
     */
    public static Set<String> secretTerms(Collection<Product> products, Collection<String> aliases) {
        Set<String> generic = genericWords(products);
        Set<String> out = new LinkedHashSet<>();
        for (Product p : products) {
            for (String v : new String[]{p.name(), p.code(), p.id()}) {
                String term = usableTerm(v, generic);
                if (term != null) {
                    out.add(term);
                }
            }
        }
        if (aliases != null) {
            for (String a : aliases) {
                String term = usableTerm(a, generic);
                if (term != null) {
                    out.add(term);
                }
            }
        }
        return Set.copyOf(out);
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
