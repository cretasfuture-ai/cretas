package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把一句话里说的原料批次状态 (「已用完」「质检中」「USED_UP」…) 认成 {@link MaterialBatchStatus}。
 *
 * <p>ERP-S6-10: 对话入口 (/ai-intents/execute) 对「只有可选参数」的读工具不做参数抽取,
 * 「查询已用完状态的原料批次」到工具时 status 是空的, 于是返回全部批次。这里在工具内确定性地认,
 * 规则:
 * <ul>
 *   <li>认出恰好一个状态 → 按它过滤;</li>
 *   <li>说了状态但认不出 (「冻结状态」「即将过期」), 或一句里说了两个不同状态 →
 *       400 并列出能查的状态, ⛔ 不静默当成「不限状态」(那就是原缺陷的样子);</li>
 *   <li>根本没提状态 → 不限状态。</li>
 * </ul>
 */
final class MaterialBatchStatusPhrase {

    /** 词 → 状态。按词长从长到短匹配, 「生产预留」先于「预留」。 */
    private static final Map<String, MaterialBatchStatus> WORDS = words();

    /**
     * 和某个状态词重叠、但意思不是那个状态的说法: 命中就请用户换个问法, 不按子串误判。
     * (「即将过期」不是已过期; 「不可用」不是某一个状态。)
     */
    private static final List<String> NOT_A_SINGLE_STATUS = List.of(
            "即将过期", "快过期", "快要过期", "临期", "快到期", "将要过期", "未过期", "没过期",
            "不可用", "未用完", "没用完");

    /** 说的是日期字段不是状态: 「按过期时间排序」不是「已过期」。 */
    private static final List<String> DATE_FIELDS = List.of("过期时间", "过期日期", "过期日");

    private static final Pattern CODE =Pattern.compile("(?<![A-Za-z_])("
            + Arrays.stream(MaterialBatchStatus.values()).map(Enum::name)
                    .sorted((a, b) -> b.length() - a.length()).collect(Collectors.joining("|"))
            + ")(?![A-Za-z_])", Pattern.CASE_INSENSITIVE);

    /** 「状态为 X」「状态是X」「状态: X」 */
    private static final Pattern STATUS_IS = Pattern.compile("状态\\s*(?:为|是|=|:|：)\\s*([\\p{IsHan}A-Za-z_]{1,8})");
    /** 「X状态」里紧挨着「状态」的那串汉字 */
    private static final Pattern BEFORE_STATUS = Pattern.compile("([\\p{IsHan}]{1,10})状态");
    private static final Pattern LEADING_VERB = Pattern.compile("^(?:帮我|请|麻烦)?(?:查询|查看|查一下|查查|查|看看|看一下|看|找出|找|列出|显示|统计|所有|全部)+");
    /** 「X状态」里 X 是这些词时说的是「状态这个维度」, 不是某个具体状态 */
    private static final List<String> GENERIC = List.of(
            "批次", "原料", "原材料", "材料", "物料", "库存", "当前", "目前", "现在", "各", "每个", "所有", "全部", "什么", "哪些", "哪种");

    private MaterialBatchStatusPhrase() {
    }

    /** 显式传入的 status 参数: 中文名 / 同义词 / 代码 (不分大小写) 都归一成枚举名; 认不出原样返回, 交给服务层 400。 */
    static String normalizeParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        MaterialBatchStatus word = WORDS.get(value);
        if (word != null) {
            return word.name();
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (MaterialBatchStatus s : MaterialBatchStatus.values()) {
            if (s.name().equals(upper)) {
                return s.name();
            }
        }
        return value;
    }

    /**
     * 从用户原话里认状态。
     *
     * @return 认出的唯一状态; 没提状态时为空
     * @throws BusinessException 400 —— 提了状态却认不出, 或一句里说了不止一个状态
     */
    static Optional<MaterialBatchStatus> fromSentence(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return Optional.empty();
        }
        String text = sentence.trim();
        for (String phrase : NOT_A_SINGLE_STATUS) {
            if (text.contains(phrase)) {
                throw unrecognized(phrase);
            }
        }

        Set<MaterialBatchStatus> found = new LinkedHashSet<>();
        StringBuilder masked = new StringBuilder(text);
        for (String field : DATE_FIELDS) {
            int at = masked.indexOf(field);
            while (at >= 0) {
                mask(masked, at, at + field.length());
                at = masked.indexOf(field, at + field.length());
            }
        }
        Matcher code = CODE.matcher(text);
        while (code.find()) {
            found.add(MaterialBatchStatus.valueOf(code.group(1).toUpperCase(Locale.ROOT)));
            mask(masked, code.start(), code.end());
        }
        for (Map.Entry<String, MaterialBatchStatus> word : WORDS.entrySet()) {
            int at = masked.indexOf(word.getKey());
            while (at >= 0) {
                found.add(word.getValue());
                mask(masked, at, at + word.getKey().length());
                at = masked.indexOf(word.getKey(), at + word.getKey().length());
            }
        }

        if (found.size() > 1) {
            throw new BusinessException(400, "一次只能按一个批次状态查询，这句话里有："
                    + found.stream().map(MaterialBatchStatus::getDisplayName).collect(Collectors.joining("、")))
                    .withHint("请分开问，例如「查询已用完状态的原料批次」");
        }
        if (found.size() == 1) {
            return Optional.of(found.iterator().next());
        }

        // 没认出任何状态: 若用户确实说了某个具体状态, 不能当成「不限状态」
        String named = namedButUnknown(masked.toString());
        if (named != null) {
            throw unrecognized(named);
        }
        return Optional.empty();
    }

    private static String namedButUnknown(String text) {
        Matcher is = STATUS_IS.matcher(text);
        if (is.find()) {
            return is.group(1);
        }
        Matcher before = BEFORE_STATUS.matcher(text);
        while (before.find()) {
            String token = LEADING_VERB.matcher(before.group(1)).replaceFirst("");
            if (token.isEmpty() || token.endsWith("的") || token.contains("\u0000")) {
                continue;
            }
            boolean generic = GENERIC.stream().anyMatch(token::endsWith);
            if (!generic) {
                return token;
            }
        }
        return null;
    }

    private static BusinessException unrecognized(String said) {
        return new BusinessException(400, "没认出你说的批次状态「" + said + "」，没有按状态查询")
                .withHint("能查的状态：" + Arrays.stream(MaterialBatchStatus.values())
                        .map(s -> s.getDisplayName() + "(" + s.name() + ")")
                        .collect(Collectors.joining("、"))
                        + "。请换成其中一个再问，例如「查询已过期状态的原料批次」");
    }

    private static void mask(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            text.setCharAt(i, '\u0000');
        }
    }

    private static Map<String, MaterialBatchStatus> words() {
        Map<String, MaterialBatchStatus> raw = new LinkedHashMap<>();
        for (MaterialBatchStatus s : MaterialBatchStatus.values()) {
            raw.put(s.getDisplayName(), s);
        }
        // 「库存中」在日常说法里是「在库存里」(「库存中有哪些已过期的」), 不当成 IN_STOCK; 要这个旧状态请说代码。
        raw.remove(MaterialBatchStatus.IN_STOCK.getDisplayName());
        raw.put("预留", MaterialBatchStatus.RESERVED);
        raw.put("用完", MaterialBatchStatus.USED_UP);
        raw.put("用光", MaterialBatchStatus.USED_UP);
        raw.put("耗尽", MaterialBatchStatus.DEPLETED);
        raw.put("过期", MaterialBatchStatus.EXPIRED);
        raw.put("检验中", MaterialBatchStatus.INSPECTING);
        raw.put("待检验", MaterialBatchStatus.INSPECTING);
        raw.put("待质检", MaterialBatchStatus.INSPECTING);
        raw.put("待检", MaterialBatchStatus.INSPECTING);
        raw.put("报废", MaterialBatchStatus.SCRAPPED);
        List<String> keys = new ArrayList<>(raw.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        Map<String, MaterialBatchStatus> ordered = new LinkedHashMap<>();
        keys.forEach(k -> ordered.put(k, raw.get(k)));
        return ordered;
    }
}
