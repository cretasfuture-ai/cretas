package com.cretas.aims.service.execution;

import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缺参续轮 (2026-10-06): 上一轮刚问了「请提供工序任务ID」, 这一句算不算「在回答那个问题」。
 *
 * <p>prod 实测 (F006 operator, 11:56): 问完任务号, 用户答「1827」, 流式入口把它当一句新话重新识别,
 * 出了澄清按钮「开始工序 / 查询温度记录」—— 参数收集会话建好了却从没被读过。
 *
 * <h3>规则 (纯函数, 无副作用; 在识别之前判定, 因为识别层自己会开/关多轮会话)</h3>
 * <ul>
 *   <li>待填槽位全是数字型: 这句话去掉数字和少量「包装词」(是 / 号 / 任务 / 产量 / 公斤 …) 之后
 *       什么都不剩, 且数字个数不超过待填槽位数。「1827」「1827号」「就是1827」算;
 *       「查一下1830号任务」「做完了，产量0」不算 (剩下「查一下」「做完了」)。</li>
 *   <li>有非数字型槽位 (例如说产品名): 短句 (≤ {@link #MAX_ANSWER_CHARS} 字) 且不是已知命令短语
 *       (调用方给判定, 例如知识库短语表)。</li>
 * </ul>
 * 不算回答时调用方必须照常识别 —— 宁可多问一句, 也不让一句新话被旧会话截走。
 */
public final class SlotAnswerRule {

    /** 超过这个长度的话一律当新话: 回答一个缺参问题用不了这么多字。 */
    public static final int MAX_ANSWER_CHARS = 16;

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "INTEGER", "INT", "LONG", "NUMBER", "DOUBLE", "DECIMAL", "FLOAT", "QUANTITY");
    /** 数字型回答里允许出现的包装词; 长的写在前面, 免得「就是」被「是」先吃掉一半。 */
    private static final Pattern NUMERIC_FILLER = Pattern.compile(
            "就是|那个|这个|编号|单号|任务|工序|产量|数量|实际|一共|总共|做了|公斤|千克"
                    + "|是|第|号|共|个|的|吧|啊|呢|呀|哦|嗯|#|kg|KG|Kg|ID|Id|id|斤|克|吨|箱|袋|件|包|桶|瓶|盒|份");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\s\\u3000，,。.！!？?、~～:：]+");

    private SlotAnswerRule() {
    }

    public static boolean isNumericType(String type) {
        return type != null && NUMERIC_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    /** 文本型槽位里「一个编号」的写法: 字母 / 数字开头, 中间可有 - _ . # /, 不含空白。 */
    private static final Pattern CODE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._#/-]{2,39}");
    private static final Set<String> FREE_TEXT_TYPES = Set.of("TEXT", "STRING", "BATCH_ID");

    /** 文本型 (可直接把原话当值的) 槽位: 类型没写 / TEXT / STRING / BATCH_ID。日期、布尔、选择不算。 */
    public static boolean isFreeTextType(String type) {
        return type == null || type.isBlank() || FREE_TEXT_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 这句话是否<b>就是一个编号</b> (批次号 {@code MT-20260829-9687}、单号 …): 去掉首尾空白后整句符合
     * {@link #CODE_TOKEN} 且至少含一个数字。是的话原样返回 —— 值就在原话里, 不需要模型再「理解」一遍。
     * 英文单词 (yes / ok) 不含数字, 不算; 中文一律不算 (名称走模型 / 工具自己的解析)。
     */
    public static Optional<String> codeToken(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        String text = reply.strip();
        if (!CODE_TOKEN.matcher(text).matches() || text.chars().noneMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /**
     * 这句话是否只是在报数: 是的话返回其中的数字 (按出现顺序), 否则 empty。
     * 小数点在去标点之前先取数字, 所以「95.5」不会被拆成两个数。
     */
    public static Optional<List<String>> numbersOnly(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        String text = reply.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        List<String> numbers = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            numbers.add(m.group());
        }
        if (numbers.isEmpty()) {
            return Optional.empty();
        }
        String rest = NUMBER.matcher(text).replaceAll(" ");
        rest = NUMERIC_FILLER.matcher(rest).replaceAll("");
        rest = PUNCTUATION.matcher(rest).replaceAll("");
        return rest.isEmpty() ? Optional.of(numbers) : Optional.empty();
    }

    /**
     * @param pending        还没收集到的槽位 (空 = 没有在等回答)
     * @param isKnownCommand 「这句话本身是不是一条已知命令」(非数字槽位时用来挡「上班打卡」这种新话)
     */
    public static boolean looksLikeAnswer(String reply, List<RequiredParameter> pending,
                                          Predicate<String> isKnownCommand) {
        if (reply == null || pending == null || pending.isEmpty()) {
            return false;
        }
        boolean allNumeric = pending.stream().allMatch(p -> isNumericType(p.getType()));
        // 一个完整的编号 (批次号常常超过 16 个字符) 只要有文本型槽位在等就算回答, 不受长度限制
        if (!allNumeric && pending.stream().anyMatch(p -> isFreeTextType(p.getType())) && codeToken(reply).isPresent()) {
            return true;
        }
        String compact = PUNCTUATION.matcher(reply).replaceAll("");
        if (compact.isEmpty() || compact.length() > MAX_ANSWER_CHARS) {
            return false;
        }
        if (allNumeric) {
            Optional<List<String>> numbers = numbersOnly(reply);
            return numbers.isPresent() && numbers.get().size() <= pending.size();
        }
        return isKnownCommand == null || !isKnownCommand.test(reply.strip());
    }
}
