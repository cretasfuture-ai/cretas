package com.cretas.aims.service.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 外部软件（黑湖）报工快照的「数据需修正」检测。纯函数: 只读已经按工厂 / 授权校验过的快照行, 不查库、不写任何一边。
 * 快照行 = 报工日 × 产品 × 工序 × 单位 汇总后的一行 (goodQty 是良品数量, unit 是黑湖原文单位)。
 *
 * <p>四类问题 (一行可同时命中多类, 每类各出一条):
 * <ul>
 *   <li>{@link Type#COMPOUND_UNIT} 单位里混了两种 (含 / 等), 如「kg/盒」;</li>
 *   <li>{@link Type#MIXED_UNIT} 同产品同工序在窗口内单位不一致: 取天数最多的单位为主单位, 少数的列出;
 *       并列判不出主单位就如实写判不出, ⛔ 不硬选;</li>
 *   <li>{@link Type#ZERO_QTY} 数量 ≤ 0;</li>
 *   <li>{@link Type#MISSING_QTY} 数量没填 (「不知道」, ⛔ 不当 0 也不当正常值)。</li>
 * </ul>
 * 「出料」等工序与同产品其余天单位不同, 就是同产品同工序单位不一致, 并入 MIXED_UNIT。
 */
public final class ExternalDataIssueDetector {

    /** 找谁: 沿用对照表「找谁核」口径 = 车间 (黑湖报工填报人)。 */
    public static final String OWNER = "车间（黑湖报工填报人）";

    public enum Type {
        COMPOUND_UNIT("单位写成了复合单位"),
        MIXED_UNIT("同一工序单位不一致"),
        ZERO_QTY("数量为 0 或负数"),
        MISSING_QTY("数量没填");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Issue(LocalDate reportDate, String productName, String processName, BigDecimal qty, String unit,
                        String type, String typeLabel, String suggestion, String owner) {
    }

    /** 已经解析好的一行。 */
    public record Row(LocalDate date, String product, String process, BigDecimal qty, String unit) {
    }

    private ExternalDataIssueDetector() {
    }

    public static boolean isCompound(String unit) {
        if (unit == null) return false;
        return unit.indexOf('/') >= 0 || unit.indexOf('／') >= 0 || unit.indexOf('\\') >= 0;
    }

    /** 单位写法归一 (只用于「是不是同一个单位」的比较; 展示永远用原文)。 */
    static String norm(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "公斤", "千克", "kgs" -> "kg";
            default -> u;
        };
    }

    public static List<Issue> detect(List<Row> rows) {
        // 同产品同工序: 单位 -> 出现的天数 (只数非空、非复合的单位; 复合单位本身已是问题, 不参与投票)
        Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
        for (Row r : rows) {
            String u = r.unit() == null ? "" : r.unit().trim();
            if (u.isEmpty() || isCompound(u)) continue;
            votes.computeIfAbsent(groupKey(r), k -> new LinkedHashMap<>()).merge(norm(u), 1, Integer::sum);
        }
        Map<String, String> mainUnit = new LinkedHashMap<>();   // groupKey -> 主单位 (判得出才有)
        Map<String, String> tieText = new LinkedHashMap<>();    // groupKey -> 并列的单位们 (判不出)
        for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
            int max = e.getValue().values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> top = e.getValue().entrySet().stream().filter(x -> x.getValue() == max)
                    .map(Map.Entry::getKey).toList();
            if (top.size() == 1) mainUnit.put(e.getKey(), top.get(0));
            else tieText.put(e.getKey(), String.join("、", top.stream().map(u -> "「" + u + "」").toList()));
        }

        List<Issue> out = new ArrayList<>();
        for (Row r : rows) {
            String key = groupKey(r);
            String u = r.unit() == null ? "" : r.unit().trim();
            String shown = r.qty() == null ? "没填" : r.qty().stripTrailingZeros().toPlainString() + (u.isEmpty() ? "" : " " + u);

            if (isCompound(u)) {
                String main = mainUnit.get(key);
                String fix = main != null
                        ? "改成这个产品这道工序平时用的单位「" + main + "」，并核对数量是按该单位填的"
                        : "改成单一单位（如 kg 或 盒），并核对数量是按该单位填的";
                out.add(issue(r, Type.COMPOUND_UNIT, "单位「" + u + "」里混了两种单位；" + fix + "。"));
            }

            Map<String, Integer> v = votes.get(key);
            if (!u.isEmpty() && !isCompound(u) && v != null && v.size() > 1) {
                String main = mainUnit.get(key);
                if (main != null && !main.equals(norm(u))) {
                    out.add(issue(r, Type.MIXED_UNIT, "这道工序多数天用「" + main + "」，这一天写的是「" + u
                            + "」；请核对是不是填错了单位，填错就改成「" + main + "」。"));
                } else if (main == null) {
                    out.add(issue(r, Type.MIXED_UNIT, "这道工序在" + tieText.get(key)
                            + "之间天数一样多，判不出主单位；请车间确认这道工序应该用哪个单位，再把填错的改过来。"));
                }
            }

            if (r.qty() == null) {
                out.add(issue(r, Type.MISSING_QTY, "这一行没有填数量（现在是「" + shown + "」）；请核对是漏填，补上实际数量。"));
            } else if (r.qty().signum() <= 0) {
                out.add(issue(r, Type.ZERO_QTY, "数量是 " + shown + "；请核对是漏填还是这一天确实没生产，漏填就补上实际数量。"));
            }
        }
        out.sort(Comparator.comparing(Issue::reportDate, Comparator.reverseOrder())
                .thenComparing(Issue::productName).thenComparing(Issue::processName).thenComparing(Issue::type));
        return out;
    }

    private static String groupKey(Row r) {
        return r.product() + "\u0001" + r.process();
    }

    private static Issue issue(Row r, Type type, String suggestion) {
        return new Issue(r.date(), r.product(), r.process(), r.qty(), r.unit(), type.name(), type.label(), suggestion, OWNER);
    }
}
