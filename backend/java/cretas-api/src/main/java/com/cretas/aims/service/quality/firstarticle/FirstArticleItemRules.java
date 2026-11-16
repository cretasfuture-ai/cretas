package com.cretas.aims.service.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Verdict;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首检检验项的形状、校验与逐项判定 —— <b>唯一</b>一份 (标准保存、判定预览、判定写入都走这里, 形态 D)。
 *
 * <p>两类检验项 (对齐原型 E 线 BL18「逐项上下限 / 合格选项自动判定」):
 * <ul>
 *   <li>{@code NUMERIC}: 上限 / 下限至少一个, 下限 ≤ 上限; 标准值 (可选) 在上下限之间。实测值落在 [下限, 上限] 即合格 (含端点)。</li>
 *   <li>{@code OPTION}: 至少 2 个选项; 合格选项是选项的非空<b>真</b>子集 (全合格的判定没有意义)。</li>
 * </ul>
 * 总判定: 所有必检项都有实测值且合格、选检项有值的也合格 → PASSED, 否则 FAILED; 必检项缺值 → 400 (不判)。
 */
public final class FirstArticleItemRules {

    public static final int MAX_ITEMS = 30;

    private FirstArticleItemRules() {
    }

    public enum ItemType { NUMERIC, OPTION }

    /** 标准里的一个检验项。 */
    public record Item(String key, String name, ItemType type, Boolean required, String unit,
                       BigDecimal lower, BigDecimal upper, BigDecimal target,
                       List<String> options, List<String> passOptions) {
        public boolean mustCheck() {
            return required == null || required;
        }
    }

    /** 判定时提交的一项实测值: NUMERIC 用 {@code numericValue}, OPTION 用 {@code optionValue}。 */
    public record ResultInput(String key, BigDecimal numericValue, String optionValue) {
    }

    /** 服务端算出的一项结论。{@code pass == null} 只出现在「选检项没填」。 */
    public record ItemResult(String key, String name, ItemType type, boolean required, String unit,
                             BigDecimal numericValue, String optionValue, Boolean pass, String rule) {
    }

    public record Evaluation(List<ItemResult> items, Verdict autoVerdict, List<String> failedItemNames) {
    }

    /** 校验并规整 (去空白、补 key), 不合法 → 400 写明哪一项错在哪。 */
    public static List<Item> validate(List<Item> raw) {
        if (raw == null || raw.isEmpty()) {
            throw bad("首检标准至少要有一个检验项");
        }
        if (raw.size() > MAX_ITEMS) {
            throw bad("检验项最多 " + MAX_ITEMS + " 个");
        }
        List<Item> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Item it = raw.get(i);
            String label = "第 " + (i + 1) + " 项";
            if (it == null) throw bad(label + "为空");
            String name = trim(it.name());
            if (name == null) throw bad(label + "没有名称");
            if (name.length() > 50) throw bad("检验项「" + name + "」名称超过 50 字");
            if (!names.add(name)) throw bad("检验项「" + name + "」重复了");
            String key = trim(it.key());
            if (key == null) key = "i" + (i + 1);
            if (key.length() > 40 || !keys.add(key)) throw bad("检验项「" + name + "」的编号重复或过长");
            if (it.type() == null) throw bad("检验项「" + name + "」没有选类型 (数值 / 选项)");
            String unit = trim(it.unit());
            if (it.type() == ItemType.NUMERIC) {
                BigDecimal lo = it.lower();
                BigDecimal hi = it.upper();
                if (lo == null && hi == null) throw bad("数值项「" + name + "」至少要填上限或下限");
                if (lo != null && hi != null && lo.compareTo(hi) > 0) throw bad("数值项「" + name + "」下限大于上限");
                BigDecimal t = it.target();
                if (t != null && ((lo != null && t.compareTo(lo) < 0) || (hi != null && t.compareTo(hi) > 0))) {
                    throw bad("数值项「" + name + "」的标准值必须在上下限之间");
                }
                out.add(new Item(key, name, ItemType.NUMERIC, it.mustCheck(), unit, lo, hi, t, null, null));
            } else {
                List<String> opts = cleanList(it.options());
                List<String> pass = cleanList(it.passOptions());
                if (opts.size() < 2) throw bad("选项项「" + name + "」至少要有 2 个选项");
                if (pass.isEmpty()) throw bad("选项项「" + name + "」至少要选一个合格选项");
                if (!opts.containsAll(pass)) throw bad("选项项「" + name + "」的合格选项不在选项里");
                if (pass.size() == opts.size()) throw bad("选项项「" + name + "」所有选项都合格, 判定没有意义");
                out.add(new Item(key, name, ItemType.OPTION, it.mustCheck(), null, null, null, null, opts, pass));
            }
        }
        return out;
    }

    /** 按标准快照逐项判定。必检项缺值 → 400; 提交了标准里没有的项 → 400 (不静默丢弃)。 */
    public static Evaluation evaluate(List<Item> items, List<ResultInput> inputs) {
        Map<String, ResultInput> byKey = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (Item it : items) known.add(it.key());
        if (inputs != null) {
            for (ResultInput r : inputs) {
                if (r == null || r.key() == null) continue;
                if (!known.contains(r.key())) throw bad("提交了标准里没有的检验项: " + r.key());
                if (byKey.put(r.key(), r) != null) throw bad("检验项 " + r.key() + " 提交了两次");
            }
        }
        List<ItemResult> results = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Item it : items) {
            ResultInput r = byKey.get(it.key());
            Boolean pass;
            BigDecimal num = null;
            String opt = null;
            if (it.type() == ItemType.NUMERIC) {
                num = r == null ? null : r.numericValue();
                pass = num == null ? null
                        : (it.lower() == null || num.compareTo(it.lower()) >= 0)
                        && (it.upper() == null || num.compareTo(it.upper()) <= 0);
            } else {
                opt = r == null ? null : trim(r.optionValue());
                if (opt != null && !it.options().contains(opt)) {
                    throw bad("检验项「" + it.name() + "」的值「" + opt + "」不是可选项");
                }
                pass = opt == null ? null : it.passOptions().contains(opt);
            }
            if (pass == null && it.mustCheck()) missing.add(it.name());
            if (Boolean.FALSE.equals(pass)) failed.add(it.name());
            results.add(new ItemResult(it.key(), it.name(), it.type(), it.mustCheck(), it.unit(),
                    num, opt, pass, ruleText(it)));
        }
        if (!missing.isEmpty()) {
            throw bad("必检项还没填实测值: " + String.join("、", missing));
        }
        return new Evaluation(results, failed.isEmpty() ? Verdict.PASSED : Verdict.FAILED, failed);
    }

    public static String ruleText(Item it) {
        if (it.type() == ItemType.OPTION) {
            return "合格: " + String.join(" / ", it.passOptions());
        }
        String u = it.unit() == null ? "" : " " + it.unit();
        if (it.lower() != null && it.upper() != null) return plain(it.lower()) + " ~ " + plain(it.upper()) + u;
        if (it.lower() != null) return "≥ " + plain(it.lower()) + u;
        return "≤ " + plain(it.upper()) + u;
    }

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首检数据序列化失败", e);
        }
    }

    public static List<Item> readItems(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<Item>>() { });
        } catch (JsonProcessingException e) {
            // 库里的快照读不出 = 数据坏了, 明确报错, ⛔ 不当成「没有检验项」
            throw new IllegalStateException("首检标准检验项读不出: " + e.getOriginalMessage(), e);
        }
    }

    public static List<ItemResult> readResults(ObjectMapper mapper, String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, new TypeReference<List<ItemResult>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首检结果读不出: " + e.getOriginalMessage(), e);
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static List<String> cleanList(List<String> in) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (in != null) for (String x : in) { String t = trim(x); if (t != null) s.add(t); }
        return new ArrayList<>(s);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BusinessException bad(String msg) {
        return new BusinessException(400, msg).withCode("FIRST_ARTICLE_INVALID");
    }
}
