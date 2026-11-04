package com.cretas.aims.service.consent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图对应 (用户原话 → 意图) 进训练样本表之前的内容规则 —— Steve 2026-10-24 拍板, 2026-10-29 选 A 改。
 *
 * <ul>
 *   <li>配方 / 工艺参数类词 ({@link #PARAMETER_MARKERS}) <b>和具体数值在同一句 / 同一条记录里</b>:
 *       任何工厂都不存, 授权也不存 ({@link TrainingValueRules#hasWordWithValue}, 12 字窗口)。词单独出现 (「杀菌温度一般设多少」)
 *       不拦 —— 保密的是数值;</li>
 *   <li>工资 / 考勤词 ({@link #PERSONNEL_MARKERS}) <b>和具体数值在一起</b>: 隐私 (Steve 2026-11-04), 与人名、客户名一样
 *       任何工厂都不存, <b>任何开关都不管</b> ({@link #utteranceHasPayValue})。词单独出现 (「本月工资总额」) 不拦。
 *       工艺词那条才归「训练数据过滤」(d) 管, 见 {@link #utteranceBlocked};</li>
 *   <li>其余原话默认存, 用来学「听懂」; 未授权工厂的原话把数字、手机号、身份证号、银行卡号换成占位符;
 *       已授权 (training_consent = private / shared_anon) 原样存。</li>
 * </ul>
 * 工序词 (腌制、滚揉 …) 在原话里允许 —— 意图模型学的是说法, 不生成建议。人名 / 客户名不在这里猜:
 * 按本厂人员表 / 客户表精确匹配, 由 {@link ProcessSecretSampleFilter#checkTrainingSample} 判
 * (它手里有本厂名单)。
 *
 * <p>与 Python {@code backend/python/smartbi/services/training_consent.py} 的
 * {@code PARAMETER_MARKERS} / {@code process_word_with_value} / {@code mask_numbers} 必须一致: 两边都跑
 * {@code training_sample_rule_cases.json} 和 {@code process_secret_filter_rule_cases.json},
 * Python 测试还会比对本文件里的词表。
 */
public final class TrainingSampleRules {

    /** 配方 / 工艺参数类词 (与 Python PARAMETER_MARKERS 逐项相同)。 */
    public static final List<String> PARAMETER_MARKERS = List.of(
            "配方", "配比", "配料比", "投料比", "用量", "添加量", "添加比例",
            "工艺参数", "工艺条件", "加工参数", "杀菌温度", "杀菌时间", "灭菌温度", "灭菌时间",
            "中心温度", "油温", "转速", "糖度", "盐度", "含水量", "浓度", "pH");

    /** 工资 / 考勤词 (与 Python PERSONNEL_MARKERS 逐项相同)。 */
    public static final List<String> PERSONNEL_MARKERS = List.of("工资", "薪资", "薪酬", "考勤");

    // 具体客户名 / 人名 (Steve 2026-10-24): 原话里出现就整条不存。三个常量与 Python
    // _ORG_SUFFIX / _SURNAMES / _PERSON_ROLE 逐字相同 (Python 测试比对)。
    static final String HAN = "一-龥";
    static final String ORG_SUFFIX = "(?:股份有限公司|有限公司|公司|集团|超市|商贸|商行|批发部|门市部|连锁|酒店|饭店|食品厂)";
    /** 于 / 向 / 付 / 万 不在内: 多为虚词 / 量词 (线上实测误报「负责人于今日」)。 */
    static final String SURNAMES =
            "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏"
            + "魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵"
            + "钱严覃武戴莫孔汤";
    static final String PERSON_ROLE = "(?:联系人|负责人|经办人|业务员|销售员|司机|操作员|质检员|审核人|审批人|签收人|"
            + "收货人|制单人|姓名|员工|工人|班长|组长|主管|仓管员?)";
    static final Pattern ORG_NAME = Pattern.compile("[" + HAN + "A-Za-z0-9]{2,12}" + ORG_SUFFIX);
    /** 空白: ASCII 空白再加全角空格 (中文输入里「联系人　王五」很常见, 只认 ASCII 会整段漏掉)。 */
    static final String GAP = "[\\s\\u3000]";
    static final Pattern PERSON = Pattern.compile(
            PERSON_ROLE + GAP + "*[:：|]?" + GAP + "*[" + SURNAMES + "][" + HAN + "]{1,2}"
            + "|(?<![" + HAN + "])[" + SURNAMES + "][" + HAN + "]?(?:先生|女士|小姐|经理|总监|老板|师傅|厂长)"
            + "|(?<![" + HAN + "])[" + SURNAMES + "]总(?![量额数计成产体部结和监经])"
            + "|(?:联系|找|通知|叫|告诉)(?!方式|方法|方面|电话|人)[" + SURNAMES + "][" + HAN + "]{1,2}"
            + "(?=[\\s\\u3000，,。；;、:：（(]|$|\\d)");

    static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    static final Pattern BANK_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{16,19}(?![0-9A-Za-z])");
    static final Pattern PHONE = Pattern.compile("(?<![0-9A-Za-z])1[3-9]\\d{9}(?![0-9A-Za-z])");
    private TrainingSampleRules() {
    }

    /**
     * 原话的内容规则 (全量, d 开时用): 配方 / 工艺参数词或工资 / 考勤词带具体数值 → 不进训练样本。
     * (人名 / 客户名 / 保密产品名由 {@link ProcessSecretSampleFilter#checkTrainingSample} 按本厂名单判。)
     */
    public static boolean utteranceHasBlockedContent(String text) {
        return TrainingValueRules.hasWordWithValue(text, PARAMETER_MARKERS, TrainingValueRules.CONDITIONAL_MARKERS)
                || utteranceHasPayValue(text);
    }

    /** 工资 / 考勤词带具体数值 (隐私, 不归任何开关管)。 */
    public static boolean utteranceHasPayValue(String text) {
        return TrainingValueRules.hasWordWithValue(text, PERSONNEL_MARKERS, List.of());
    }

    /**
     * 收样本 / 学习入口用这一个: 该厂 d 关时不按工艺词拦, 工资 / 考勤词 + 数值始终拦。
     *
     * @param trainingFilterOff {@link ProcessSecretSampleFilter#trainingFilterOff} 的结果
     */
    public static boolean utteranceBlocked(String text, boolean trainingFilterOff) {
        return trainingFilterOff ? utteranceHasPayValue(text) : utteranceHasBlockedContent(text);
    }

    /** 未授权工厂原话的脱敏: 证件号 / 卡号 / 手机号 → 占位符, 其余数值 (含中文数字、pH4 这类贴着单位的) → {@code <数值>}。 */
    public static String maskNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ID_CARD.matcher(text).replaceAll("<证件号>");
        out = BANK_CARD.matcher(out).replaceAll("<卡号>");
        out = PHONE.matcher(out).replaceAll("<电话>");
        return TrainingValueRules.maskValues(out);
    }

    /**
     * 一条原话该怎么存: {@code null} = 不存; 否则返回要存的文本 (已授权原样, 未授权脱敏)。
     */
    public static String textToStore(String text, boolean authorized) {
        return textToStore(text, authorized, false);
    }

    /** 同上, 带该厂 d 的状态 (见 {@link #utteranceBlocked})。 */
    public static String textToStore(String text, boolean authorized, boolean trainingFilterOff) {
        if (utteranceBlocked(text, trainingFilterOff)) {
            return null;
        }
        return authorized ? text : maskNumbers(text);
    }
}
