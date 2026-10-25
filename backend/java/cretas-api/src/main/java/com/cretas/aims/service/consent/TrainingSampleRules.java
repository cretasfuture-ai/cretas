package com.cretas.aims.service.consent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图对应 (用户原话 → 意图) 进训练样本表之前的规则 —— Steve 2026-10-24 拍板。
 *
 * <ul>
 *   <li>原话里有配方 / 工艺参数类内容 ({@link #PARAMETER_MARKERS}): 任何工厂都不存, 授权也不存;</li>
 *   <li>其余原话默认存, 用来学「听懂」; 未授权工厂的原话把数字、手机号、身份证号、银行卡号换成占位符;
 *       已授权 (training_consent = private / shared_anon) 原样存。</li>
 * </ul>
 * 工序词 (腌制、滚揉 …) 在原话里允许 —— 意图模型学的是说法, 不生成建议。
 *
 * <p>与 Python {@code backend/python/smartbi/services/training_consent.py} 的
 * {@code PARAMETER_MARKERS} / {@code mask_numbers} 必须一致: 两边都跑
 * {@code training_sample_rule_cases.json}, Python 测试还会比对本文件里的词表。
 */
public final class TrainingSampleRules {

    /** 配方 / 工艺参数类词 (与 Python PARAMETER_MARKERS 逐项相同)。 */
    public static final List<String> PARAMETER_MARKERS = List.of(
            "配方", "配比", "配料比", "投料比", "用量", "添加量", "添加比例",
            "工艺参数", "工艺条件", "加工参数", "杀菌温度", "杀菌时间", "灭菌温度", "灭菌时间",
            "中心温度", "油温", "转速", "糖度", "盐度", "含水量", "浓度", "pH");

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
    static final Pattern CUSTOMER_NAMED = Pattern.compile(
            "客户(?:名称|名)?\\s*[:：]\\s*[" + HAN + "A-Za-z0-9]{2,}"
            + "|客户\\s*[「“\"『][^」”\"』]{2,}[」”\"』]"
            + "|[「“\"『][^」”\"』]{2,}[」”\"』]\\s*客户");
    static final Pattern PERSON = Pattern.compile(
            PERSON_ROLE + "\\s*[:：|]?\\s*[" + SURNAMES + "][" + HAN + "]{1,2}"
            + "|(?<![" + HAN + "])[" + SURNAMES + "][" + HAN + "]?(?:先生|女士|小姐|经理|总监|老板|师傅|厂长)"
            + "|(?<![" + HAN + "])[" + SURNAMES + "]总(?![量额数计成产体部结和监经])"
            + "|(?:联系|找|通知|叫|告诉)(?!方式|方法|方面|电话|人)[" + SURNAMES + "][" + HAN + "]{1,2}"
            + "(?=[\\s，,。；;、:：（(]|$|\\d)");

    static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    static final Pattern BANK_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{16,19}(?![0-9A-Za-z])");
    static final Pattern PHONE = Pattern.compile("(?<![0-9A-Za-z])1[3-9]\\d{9}(?![0-9A-Za-z])");
    /** 数字; F006 / V2 / B12 这类编号保留。 */
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_.<])\\d+(?:\\.\\d+)?");

    private TrainingSampleRules() {
    }

    /** 原话含配方 / 工艺参数类内容、具体客户名或人名 → 任何工厂都不进训练样本。 */
    public static boolean utteranceHasBlockedContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : PARAMETER_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return mentionsSpecificCustomer(text) || mentionsPersonName(text);
    }

    /** 「客户」加具体名称 (公司类名称、客户：XX、带引号的名字)。 */
    static boolean mentionsSpecificCustomer(String text) {
        if (text == null || !text.contains("客户")) {
            return false;
        }
        return CUSTOMER_NAMED.matcher(text).find() || ORG_NAME.matcher(text).find();
    }

    /** 岗位 / 称谓 / 联系语境里的人名 (操作员：王五、李经理、联系张三)。 */
    static boolean mentionsPersonName(String text) {
        return text != null && PERSON.matcher(text).find();
    }

    /** 未授权工厂原话的脱敏: 证件号 / 卡号 / 手机号 → 占位符, 其余数字 → {@code <数值>}。 */
    public static String maskNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ID_CARD.matcher(text).replaceAll("<证件号>");
        out = BANK_CARD.matcher(out).replaceAll("<卡号>");
        out = PHONE.matcher(out).replaceAll("<电话>");
        return NUMBER.matcher(out).replaceAll("<数值>");
    }

    /**
     * 一条原话该怎么存: {@code null} = 不存; 否则返回要存的文本 (已授权原样, 未授权脱敏)。
     */
    public static String textToStore(String text, boolean authorized) {
        if (utteranceHasBlockedContent(text)) {
            return null;
        }
        return authorized ? text : maskNumbers(text);
    }
}
