package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.service.consent.TrainingSampleRules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞轮导出文本里的数值按语境换成<b>带类型的占位符</b> (Steve 2026-11-14 更正: 只替换值, 保留句子结构与说法, 让模型学得到;
 * ⛔ 不整句删、不换成无意义符号)。纯函数。
 *
 * <ul>
 *   <li>{@code <价格>}: 数字后跟 元 / 块 / 毛 / 角; 前面是 ¥ (可隔空格); 「一斤12 / 每斤12」「12一斤 / 12/斤」; 「卖15 / 报价15」;
 *       同一小句前面出现过「价」(「价格调到12」) 且数字后面不是数量单位;</li>
 *   <li>{@code <温度>}: 数字后跟 ℃ / 度; 或紧跟在「炸到 / 烤到 / 煮到 / 加热到 / 油温 / 水温 …」后面 (「炸到180」);</li>
 *   <li>{@code <时长>}: 数字后跟 分钟 / 小时 / 秒 / 钟头 (「卤45分钟」→「卤<时长>」);</li>
 *   <li>{@code <比例>}: 数字后跟 % / ％, 或「百分之X」(「盐3%」→「<配料><比例>」);</li>
 *   <li>{@code <参数>}: 工艺参数词 ({@link TrainingSampleRules#PARAMETER_MARKERS}) 后面的其余数值;</li>
 *   <li>{@code <用量>}: 三档单位 (organizer 11-14 三审):
 *       配方专用单位 (克 / g / 毫升 / ml / 勺 / 茶匙 / 汤匙) 不看语境一律抹 (「加盐5克」「生抽一勺」);
 *       其余单位 (斤 / 公斤 / 升 / 两 / 片 / 粒 …) 看配方语境: 小句里有本厂原料名、配方类词, 或「放 / 加」在数值前 4 个字以内
 *       (「加白砂糖2斤」「这锅加水20升」);
 *       小句里有入库 / 到货 / 采购 / 领料 / 订单 / 发货 … 业务词时这一档不按配方处理 (「入库鸭腿200公斤」「订单再加20斤」原样);
 *       g 后面紧跟 网 / 信号 / 卡, 或数值前紧邻 4 个字里有 手机 / 网络 时不是单位; 「克」一律是单位;</li>
 *   <li>{@code <比例>} 另含冒号比例「3:1」(含配方类词 / 参数词 / 原料名的小句里; 「比分3:1」「时间10:30」不动);</li>
 *   <li>{@code <配料>}: 本厂原料名 (主数据确定性匹配), 只在含 {@code <用量>} / {@code <比例>} 的小句里换 (配方保密口径: 配料名 + 用量
 *       一起才是配方; 单说「八角到货了」是普通业务话, 不动);</li>
 *   <li>{@code <数量>}: 只在工厂开了「数量脱敏」时, 其余带数量单位的数 (单位保留: 「<数量>斤」)。</li>
 * </ul>
 * 同一句里同类出现不止一次就编号区分 ({@code <用量1>}、{@code <用量2>})。
 */
final class FlywheelValueMasker {

    static final String CN_DIGITS = "零〇一二三四五六七八九十百千万两";
    static final Pattern NUMBER = Pattern.compile(
            "百分之[" + CN_DIGITS + "]+(?:点[" + CN_DIGITS + "]+)?"
                    + "|(?<![A-Za-z0-9.．])[0-9０-９]+(?:[.．][0-9０-９]+)?"
                    + "|[" + CN_DIGITS + "]+(?:点[" + CN_DIGITS + "]+)?|半");
    static final String CLAUSE_BREAKS = "，,。；;！!？?、\n";

    static final List<String> PRICE_UNITS_AFTER = List.of("块钱", "元", "块", "毛", "角", "RMB", "rmb");
    static final List<String> PER_UNIT = List.of("公斤", "千克", "kg", "KG", "斤", "两", "克", "箱", "件", "袋", "包", "盒", "个", "只", "瓶", "份", "吨");
    static final List<String> SELL_WORDS = List.of("报价", "卖到", "售价", "卖", "售", "收");
    static final List<String> TEMP_UNITS_AFTER = List.of("℃", "°C", "°c", "度C", "度");
    static final List<String> TEMP_LEADS = List.of("炸到", "烤到", "煮到", "烧到", "蒸到", "加热到", "升到", "降到", "控制在", "油温", "水温", "炉温", "温度");
    static final List<String> TIME_UNITS_AFTER = List.of("个小时", "个钟头", "分钟", "小时", "钟头", "秒钟", "秒");
    static final List<String> RATIO_AFTER = List.of("%", "％");
    static final List<String> RECIPE_UNITS = List.of(
            "公斤", "千克", "kg", "KG", "毫升", "ml", "mL", "汤匙", "茶匙", "克", "g", "两", "斤", "升", "L", "勺", "片", "粒");
    /** 配方专用单位: 不看语境, 一律 {@code <用量>} (organizer 11-14 三审: 原料表叫「食盐」时「加盐5克」也得抹)。 */
    static final List<String> RECIPE_ONLY_UNITS = List.of("毫升", "ml", "mL", "ML", "汤匙", "茶匙", "克", "g", "勺");
    /** 单位 g 后面紧跟这些字时是网络制式不是克 (「2g网络」「4g信号」「4g卡」)。「克」不排除。 */
    static final List<String> NOT_UNIT_AFTER_G = List.of("网", "信号", "卡", "拉");
    /** 数值前紧邻 {@link #G_LEAD_WINDOW} 个字里有这些词时, 后面的 g 是网络制式 (「手机是4g的」); 不看整句。 */
    static final List<String> G_LEADS = List.of("手机是", "手机", "网络");
    static final int G_LEAD_WINDOW = 4;
    /** 「一」后面跟这些字是虚词不是数 (「一起」「一样」「一下」…), 不当数值 (否则「1斤一起」被当成单价)。 */
    static final String NOT_NUMBER_AFTER_YI = "起样般下些点直";
    /** 冒号比例「3:1」「3：1」: 只在含配方类词 / 参数词 / 本厂原料名的小句里抹成 {@code <比例>}。 */
    static final Pattern COLON_RATIO = Pattern.compile(
            "(?<![0-9０-９.．])[0-9０-９]+(?:[.．][0-9０-９]+)?\\s*[:：]\\s*[0-9０-９]+(?:[.．][0-9０-９]+)?(?![0-9０-９])");
    /** 小句里有这些业务词 → 不按配方处理 (配方专用单位除外): 「入库鸭腿200公斤」「订单再加20斤」原样。 */
    static final List<String> BUSINESS_WORDS = List.of(
            "入库", "到货", "采购", "领料", "出库", "下单", "订单", "发货", "收货", "进货", "退货", "库存", "盘点", "送货", "调拨");
    /** 「放 / 加」在本小句内、数值前这么多个字以内就算配方语境。 */
    static final int ADD_WINDOW = 4;
    static final List<String> RECIPE_WORDS = List.of("配方", "配料", "配比", "投料", "用量", "添加", "卤水", "腌料", "调料", "料包", "香料");
    static final List<String> QUANTITY_UNITS = List.of(
            "公斤", "千克", "kg", "KG", "Kg", "斤", "两", "克", "g", "吨", "箱", "件", "袋", "包", "盒", "个", "只",
            "桶", "瓶", "份", "托", "筐", "板", "条", "根", "罐", "车", "批", "L", "升", "毫升", "ml");
    static final int PARAM_WINDOW = 8;

    private FlywheelValueMasker() {
    }

    /** 掩哪些类。保密厂: 全部 (数量看开关); 保密关的厂: 默认都不掩 (配方是否所有厂都掩待 Steve 定)。 */
    record Scope(boolean prices, boolean recipe, boolean quantities) {
    }

    private record Span(int start, int end, String type) {
    }

    static String mask(String text, Scope scope, Collection<String> ingredients, Map<String, Integer> stats) {
        if (text == null || text.isEmpty() || !(scope.prices() || scope.recipe() || scope.quantities())) {
            return text;
        }
        List<Span> spans = new ArrayList<>();
        List<int[]> names = occurrences(text, ingredients);
        List<int[]> ratios = new ArrayList<>();
        if (scope.recipe()) {
            Matcher r = COLON_RATIO.matcher(text);
            while (r.find()) {
                if (recipeWordInClause(text, r.start()) || paramInClause(text, r.start())
                        || ingredientInClause(text, r.start(), ingredients)) {
                    spans.add(new Span(r.start(), r.end(), "比例"));
                    ratios.add(new int[]{r.start(), r.end()});
                }
            }
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            if (inside(names, m.start(), m.end()) || inside(ratios, m.start(), m.end())) {
                continue;   // 「八角」「三七」这类原料名里的数字字不是数值; 冒号比例已整段算过
            }
            if (m.group().equals("一") && m.end() < text.length()
                    && NOT_NUMBER_AFTER_YI.indexOf(text.charAt(m.end())) >= 0) {
                continue;   // 「一起 / 一样 / 一下」是虚词
            }
            Span s = classify(text, m.start(), m.end(), scope, ingredients);
            if (s != null) {
                spans.add(s);
            }
        }
        if (scope.recipe()) {
            spans.addAll(ingredientSpans(text, spans, ingredients));
        }
        spans.sort(Comparator.comparingInt(Span::start));
        // 去重叠 (先到先得)
        List<Span> kept = new ArrayList<>();
        int last = -1;
        for (Span s : spans) {
            if (s.start() >= last) {
                kept.add(s);
                last = s.end();
            }
        }
        Map<String, Integer> total = new HashMap<>();
        for (Span s : kept) {
            total.merge(s.type(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (Span s : kept) {
            int n = seen.merge(s.type(), 1, Integer::sum);
            out.append(text, at, s.start()).append('<').append(s.type());
            if (total.get(s.type()) > 1) {
                out.append(n);
            }
            out.append('>');
            at = s.end();
            stats.merge(statKey(s.type()), 1, Integer::sum);
        }
        out.append(text.substring(at));
        return out.toString();
    }

    private static Span classify(String text, int start, int end, Scope scope, Collection<String> ingredients) {
        String token = text.substring(start, end);
        String after = text.substring(end);
        String afterTrim = after.stripLeading();
        int afterGap = after.length() - afterTrim.length();
        String before = clauseBefore(text, start);
        String beforeTrim = before.stripTrailing();

        if (scope.recipe()) {
            if (token.startsWith("百分之")) {
                return new Span(start, end, "比例");
            }
            String unit = prefix(afterTrim, RATIO_AFTER);
            if (unit != null) {
                return new Span(start, end + afterGap + unit.length(), "比例");
            }
            unit = prefix(afterTrim, TEMP_UNITS_AFTER);
            if (unit != null && !(unit.equals("度") && afterTrim.startsWith("度数"))) {
                return new Span(start, end + afterGap + unit.length(), "温度");
            }
            unit = prefix(afterTrim, TIME_UNITS_AFTER);
            if (unit != null) {
                return new Span(start, end + afterGap + unit.length(), "时长");
            }
            if (endsWithAny(beforeTrim, TEMP_LEADS) && prefix(afterTrim, QUANTITY_UNITS) == null) {
                return new Span(start, end, "温度");
            }
        }
        if (scope.prices()) {
            String unit = prefix(afterTrim, PRICE_UNITS_AFTER);
            if (unit != null) {
                return new Span(start, end + afterGap + unit.length(), "价格");
            }
            if (beforeTrim.endsWith("¥") || beforeTrim.endsWith("￥")) {
                int symbol = text.lastIndexOf(beforeTrim.charAt(beforeTrim.length() - 1), start);
                return new Span(symbol, end, "价格");
            }
            boolean followedByUnit = prefix(afterTrim, QUANTITY_UNITS) != null;
            if (!followedByUnit && (endsWithPerUnit(beforeTrim) || endsWithAny(beforeTrim, SELL_WORDS))) {
                return new Span(start, end, "价格");     // 一斤12 / 每斤12 / 卖15
            }
            if (startsWithPerUnit(afterTrim) || afterTrim.startsWith("卖") || afterTrim.startsWith("出售")) {
                return new Span(start, end, "价格");     // 12一斤 / 12/斤 / 按15卖
            }
            if (!followedByUnit && before.indexOf('价') >= 0 && !token.equals("半")) {
                return new Span(start, end, "价格");     // 价格调到12
            }
        }
        if (scope.recipe()) {
            if (paramBefore(text, start)) {
                return new Span(start, end, "参数");
            }
            String unit = recipeOnlyUnit(text, start, afterTrim);
            if (unit != null) {
                return new Span(start, end + afterGap + unit.length(), "用量");
            }
            unit = prefix(afterTrim, RECIPE_UNITS);
            if (unit != null && !RECIPE_ONLY_UNITS.contains(unit) && !businessClause(text, start)) {
                // 业务单位 (斤 / 公斤 / 升) 同其余单位一样看配方语境 (原料名 / 放加窗口 / 配方词), 只靠业务词否决
                // (organizer 11-15 终审: 只认配方词会漏「加白砂糖2斤」「这锅加水20升」)
                if (recipeContext(text, start, before, ingredients)) {
                    return new Span(start, end + afterGap + unit.length(), "用量");
                }
            }
        }
        if (scope.quantities() && prefix(afterTrim, QUANTITY_UNITS) != null) {
            return new Span(start, end, "数量");
        }
        return null;
    }

    private static List<int[]> occurrences(String text, Collection<String> names) {
        List<int[]> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String n : names) {
            if (n == null || n.isEmpty()) {
                continue;
            }
            int i = text.indexOf(n);
            while (i >= 0) {
                out.add(new int[]{i, i + n.length()});
                i = text.indexOf(n, i + 1);
            }
        }
        return out;
    }

    private static boolean inside(List<int[]> ranges, int start, int end) {
        for (int[] r : ranges) {
            if (start < r[1] && end > r[0]) {
                return true;
            }
        }
        return false;
    }

    /** 含用量 / 比例的小句里的本厂原料名 → 配料。 */
    private static List<Span> ingredientSpans(String text, List<Span> values, Collection<String> ingredients) {
        List<Span> out = new ArrayList<>();
        if (ingredients == null || ingredients.isEmpty()) {
            return out;
        }
        List<String> names = new ArrayList<>(ingredients);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (Span v : values) {
            if (!v.type().equals("用量") && !v.type().equals("比例")) {
                continue;
            }
            int[] clause = clause(text, v.start());
            int i = clause[0];
            while (i < clause[1]) {
                String hit = null;
                for (String n : names) {
                    if (!n.isEmpty() && text.startsWith(n, i) && i + n.length() <= clause[1]) {
                        hit = n;
                        break;
                    }
                }
                if (hit == null) {
                    i++;
                    continue;
                }
                Span s = new Span(i, i + hit.length(), "配料");
                if (out.stream().noneMatch(o -> o.start() == s.start())) {
                    out.add(s);
                }
                i += hit.length();
            }
        }
        return out;
    }

    /** 配方专用单位 (「g / ml」后面紧跟字母的不算: 「5gb」)。 */
    private static String recipeOnlyUnit(String text, int start, String afterTrim) {
        String unit = prefix(afterTrim, RECIPE_ONLY_UNITS);
        if (unit == null) {
            return null;
        }
        if (Character.isLetter(unit.charAt(0)) && afterTrim.length() > unit.length()
                && isAsciiLetter(afterTrim.charAt(unit.length()))) {
            return null;
        }
        // 只排除 g (网络制式); 「克」一律是单位 (食品厂没有克拉: 「盐3克拉匀」必须抹)
        if (unit.equals("g")) {
            String rest = afterTrim.substring(unit.length());
            if (prefix(rest, NOT_UNIT_AFTER_G) != null) {
                return null;    // 「2g网络」「4g信号」
            }
            String lead = text.substring(Math.max(0, start - G_LEAD_WINDOW), start);
            for (String w : G_LEADS) {
                if (lead.contains(w)) {
                    return null;    // 「手机是4g的」; 只看数值前紧邻几个字, 不看整句 (「手机上记一下味精2g」照抹)
                }
            }
        }
        return unit;
    }

    private static boolean paramInClause(String text, int start) {
        int[] c = clause(text, start);
        String clause = text.substring(c[0], c[1]);
        for (String marker : TrainingSampleRules.PARAMETER_MARKERS) {
            if (clause.contains(marker)) {
                return true;
            }
        }
        return clause.contains("比例");
    }

    private static boolean ingredientInClause(String text, int start, Collection<String> ingredients) {
        if (ingredients == null) {
            return false;
        }
        int[] c = clause(text, start);
        String clause = text.substring(c[0], c[1]);
        for (String n : ingredients) {
            if (n != null && !n.isEmpty() && clause.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean businessClause(String text, int start) {
        int[] c = clause(text, start);
        String clause = text.substring(c[0], c[1]);
        for (String w : BUSINESS_WORDS) {
            if (clause.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean recipeWordInClause(String text, int start) {
        int[] c = clause(text, start);
        String clause = text.substring(c[0], c[1]);
        for (String w : RECIPE_WORDS) {
            if (clause.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** {@code before} = 本小句里数值之前的部分 (未去空格)。 */
    private static boolean recipeContext(String text, int start, String before, Collection<String> ingredients) {
        String near = before.substring(Math.max(0, before.length() - ADD_WINDOW));
        if (near.contains("放") || near.contains("加")) {
            return true;
        }
        if (recipeWordInClause(text, start)) {
            return true;
        }
        int[] c = clause(text, start);
        String clause = text.substring(c[0], c[1]);
        if (ingredients != null) {
            for (String n : ingredients) {
                if (n != null && !n.isEmpty() && clause.contains(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean paramBefore(String text, int start) {
        String window = text.substring(Math.max(0, start - PARAM_WINDOW), start);
        for (String marker : TrainingSampleRules.PARAMETER_MARKERS) {
            int at = window.lastIndexOf(marker);
            if (at >= 0 && noBreak(window.substring(at))) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithPerUnit(String s) {
        for (String u : PER_UNIT) {
            if (s.endsWith("一" + u) || s.endsWith("每" + u) || s.endsWith("1" + u)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithPerUnit(String s) {
        for (String u : PER_UNIT) {
            if (s.startsWith("一" + u) || s.startsWith("每" + u) || s.startsWith("/" + u) || s.startsWith("／" + u)) {
                return true;
            }
        }
        return false;
    }

    /** 本小句里、位置之前的部分。 */
    private static String clauseBefore(String text, int start) {
        return text.substring(clause(text, start)[0], start);
    }

    private static int[] clause(String text, int at) {
        int s = at;
        while (s > 0 && CLAUSE_BREAKS.indexOf(text.charAt(s - 1)) < 0) {
            s--;
        }
        int e = at;
        while (e < text.length() && CLAUSE_BREAKS.indexOf(text.charAt(e)) < 0) {
            e++;
        }
        return new int[]{s, e};
    }

    private static boolean noBreak(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (CLAUSE_BREAKS.indexOf(s.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static String prefix(String s, List<String> words) {
        for (String w : words) {
            if (s.startsWith(w)) {
                return w;
            }
        }
        return null;
    }

    private static boolean endsWithAny(String s, List<String> words) {
        for (String w : words) {
            if (s.endsWith(w)) {
                return true;
            }
        }
        return false;
    }

    private static String statKey(String type) {
        return switch (type) {
            case "价格" -> "price";
            case "温度" -> "temperature";
            case "时长" -> "duration";
            case "比例" -> "ratio";
            case "参数" -> "param";
            case "用量" -> "amount";
            case "配料" -> "ingredient";
            case "数量" -> "quantity";
            default -> type;
        };
    }
}
