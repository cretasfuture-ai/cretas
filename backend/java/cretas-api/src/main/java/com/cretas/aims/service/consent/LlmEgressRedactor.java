package com.cretas.aims.service.consent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询结果发给外部大模型之前的脱敏 (官网「数据安全」: AI 助手发给模型前脱敏, 脱敏不了就不发)。
 *
 * <p>默认不放行: 字段分四类, 不在放行名单里的字符串一律换代号。
 * <ul>
 *     <li><b>不发</b>: 联系方式、证件、地址、备注 / 原因 / 说明这类自由文本 (英文与中文字段名)。</li>
 *     <li><b>换代号</b>: 人和交易对象 (客户 / 供应商 / 单位 / 人员, 含复数、{@code *By}、中文字段名)
 *         → {@code [客户A]} {@code [人员B]}; 模型回答后 {@link Result#restore} 换回真名再给用户看。
 *         代号里没有数字, 不干扰数字出处闸。</li>
 *     <li><b>放行</b>: 业务对象名 (产品 / 物料 / 设备 / 规则…) 与编码、状态、单位、日期类字段;
 *         本厂工艺保密产品名仍换成 {@code [保密产品A]}。</li>
 *     <li><b>系统文字</b> (message / summary / title…): 保留, 但抹掉号码, 单位名与「角色+人名」换代号,
 *         已换过代号的真名在这里出现也换掉。</li>
 *     <li>其余字段的字符串: 像编码的 ({@code PB-20261025-0007} / {@code IN_PROGRESS}) 与通用词保留, 其余换代号。</li>
 * </ul>
 *
 * <p>发出前再扫一遍: 残留号码 (含数字型字段里的手机号)、真名或保密产品名就拒绝 ({@link Result#refused()}),
 * 调用方必须改用本机结果。入参先经 {@link #toPlainTree} 转成 Map / List / 标量; 转不了的类型也拒绝。
 */
public final class LlmEgressRedactor {

    // ------------------------------------------------------------------ 字段分类

    /** 不发: 联系方式 / 证件 / 地址 / 自由文本。 */
    private static final Pattern DROP_KEY = Pattern.compile(
            "(?i)^(tel|fax|qq|addr|email|mail|wechat|wechatid|weixin|password|token|secret|reason|content|detail"
            + "|details|opinion|complaint|memo|feedback|description|desc)$"
            + "|(?i)^.*(phone|phones|phoneno|phonenumber|mobile|mobileno|mobilenumber|telno|tel|telephone"
            + "|contacttel|contactnumber|contactinfo|contactway|email|emails|address|addresses|addr|idcard"
            + "|idcardno|idno|idnumber|identitycard|passport|bankcard|bankaccount|bankaccountno|accountno"
            + "|accountnumber|cardno|cardnumber|bankno|iban|swift"
            + "|wechat|wechatid|remark|remarks|note|notes|comment|comments|reason|reasons|description"
            + "|feedback|complaint|opinion|memo)$");

    private static final Pattern DROP_KEY_ZH = Pattern.compile(
            ".*(电话|手机|联系方式|座机|传真|邮箱|微信|地址|住址|身份证|证件|银行|卡号|账号|密码"
            + "|备注|原因|理由|说明|描述|意见|反馈|投诉|内容|详情|留言|批注).*");

    /** 业务对象名: 放行 (含复数)。 */
    private static final Pattern KEEP_NAME_KEY = Pattern.compile(
            "(?i)^(product|producttype|filterproduct|material|rawmaterial|materialtype|matchedmaterial|ingredient"
            + "|additive|sku|item|equipment|device|rule|intent|process|workprocess|procedure|checkpoint|mode|tool"
            + "|skill|feature|task|strategy|template|sheet|sample|role|newrole|ratio|protocol|page|model|leavetype"
            + "|filter|category|type|warehouse|location|unit|department|stage|step|indicator|metric|batch|status"
            + "|statusdisplay|line|workshop|handlerbean|plan|report|module|field|column|chart|kpi|brand"
            + "|spec|specification|grade|level|shift|season|region|channel|function|menu|permission|alert)names?$");

    /** 编码 / 状态 / 数量 / 日期 / 单位类字段: 放行。 */
    private static final Pattern KEEP_CODE_KEY = Pattern.compile(
            "(?i)^(id|ids|code|codes|no|number|count|total|amount|status|state|type|kind|unit|units|level|priority"
            + "|role|roles|rolename|department|departments|dept|position|title|post|shift"
            + "|category|grade|stage|step|period|month|week|day|date|year|time|currency|trend|direction|severity"
            + "|result|version|percent|percentage|rate|ratio|score|rank|unitname|uom|spec|specification|sku"
            + "|productcode|batchno|batchnumber|orderno|ordernumber)$"
            + "|(?i)^.*(id|ids|code|codes|count|total|amount|qty|quantity|price|cost|rate|ratio|percent|score"
            + "|status|state|type|level|date|time|at|days|hours|minutes|weight|volume|unit|no|number|seq|index"
            + "|version|flag|enabled|active|valid|display|label|text|result|verdict|range|period|stage|mode"
            + "|direction|importance|priority|severity|grade|trend)$");

    private static final Pattern KEEP_KEY_ZH = Pattern.compile(
            ".*(产品|物料|原料|品名|规格|单位|数量|金额|单价|价格|成本|日期|时间|状态|类型|类别|等级|批次|编号|编码"
            + "|库存|产量|合格率|比例|占比|仓库|设备|工序|指标|阈值|预警|告警级别|结果|范围|风险|不良|模式|方向|阶段"
            + "|周期|趋势|级别|优先级).*");

    /** 系统生成的说明文字: 保留但过扫描。 */
    private static final Pattern SYSTEM_TEXT_KEY = Pattern.compile(
            "(?i)^(message|messages|msg|summary|summaries|title|subtitle|label|labels|tip|tips|hint|hints"
            + "|suggestion|suggestions|advice|alert|alerts|alertmessage|warning|warnings|conclusion|conclusions"
            + "|insight|insights|headline|text|displaytext|formattedtext|statustext|statusdisplay|statusdisplayname"
            + "|risk|risks|riskdescription|action|actions|nextstep|nextsteps)$");

    /** 人 / 交易对象 (英文, 含复数与 *By)。 */
    private static final Pattern PARTY_KEY = Pattern.compile(
            "(?i)^.*(name|names|username|usernames|person|persons|people|contact|contacts|operator|operators"
            + "|owner|owners|manager|managers|supervisor|supervisors|leader|leaders|customer|customers|client"
            + "|clients|supplier|suppliers|vendor|vendors|worker|workers|employee|employees|staff|inspector"
            + "|inspectors|handler|handlers|approver|approvers|assignee|assignees|recorder|requestor|requester"
            + "|creator|signer|receiver|receivers|recipient|recipients|sender|driver|drivers|buyer|buyers"
            + "|purchaser|salesman|salesperson|consignee|shipper|carrier|merchant|merchants|partner|partners"
            + "|company|companies|store|stores|shop|shops|member|members|by|rep|reps|assignedto|account"
            + "|loginaccount|login|nickname)$");

    /** 单独的「单位」多指计量单位 (kg), 只有「所属单位 / 往来单位 / 单位名称」这类才当机构。 */
    private static final Pattern PARTY_KEY_ZH = Pattern.compile(
            ".*(客户|供应商|供应方|厂商|商家|商户|门店|公司|所属单位|往来单位|客户单位|单位名称|联系人|负责人|责任人"
            + "|经办人|操作员|操作人|操作工|检验员|质检员|审核人|审批人|签收人|收货人|发货人|制单人|创建人|处理人|确认人"
            + "|维护人|管理员|经理|员工|人员|姓名|司机|业务员|销售员|采购员|仓管|主管|班长|组长|申请人|提交人).*");

    /** 「name」裸字段: 只看最近一层容器的字段名。 */
    private static final Pattern BUSINESS_CONTAINER = Pattern.compile(
            "(?i)^(products?|producttypes?|materials?|rawmaterials?|ingredients?|additives?|skus?|equipments?"
            + "|devices?|rules?|intents?|process(es)?|workprocesses|procedures?|checkpoints?|tools?|skills?"
            + "|templates?|categor(y|ies)|types?|warehouses?|locations?|units?|stages?|steps?|indicators?"
            + "|metrics?|recipes?|boms?|lines?|workshops?|modules?|charts?|kpis?|reports?|plans?)$");

    /** 展示用后缀: 去掉后看词干是不是人 / 交易对象。 */
    private static final Pattern DISPLAY_SUFFIX = Pattern.compile("(?i)^(.*?)(display|label|text|desc|str|info)$");

    private static final Pattern PERSON_CONTAINER = Pattern.compile(
            "(?i)^.*(users?|employees?|staff|workers?|operators?|persons?|people|members?|contacts?|inspectors?"
            + "|supervisors?|managers?|leaders?|owners?|handlers?|approvers?|drivers?|salesm[ae]n|buyers?).*$");

    // ------------------------------------------------------------------ 值

    /** 像编码 / 枚举 / 日期的值: 不是名字, 放行。 */
    private static final Pattern CODE_LIKE = Pattern.compile("^[A-Za-z0-9_\\-./:#+ %,¥$]{1,64}$");

    /** 不透露任何东西的通用词: 不换代号, 也不参与跨字段替换。 */
    private static final Set<String> GENERIC = Set.of("系统", "管理员", "平台", "未知", "无", "暂无", "未分配", "默认",
            "全部", "其他", "其它", "本厂", "admin", "system", "unknown", "N/A", "null", "-", "—");

    /** 训练规则之外的号码写法: 带横杠 / 空格的手机号、+86、座机。 */
    private static final Pattern MOBILE_LOOSE = Pattern.compile(
            "(?<![0-9])(?:\\+?86[- ]?)?1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}(?![0-9])");
    private static final Pattern LANDLINE = Pattern.compile(
            "(?<![0-9])(?:\\(0\\d{2,3}\\)\\s?|0\\d{2,3}[- ]?)\\d{7,8}(?![0-9])");
    /** 编码类值整个就是座机 (contactNo = 021-58881234)。 */
    private static final Pattern LANDLINE_WHOLE = Pattern.compile("^\\(?0\\d{2,3}\\)?[- ]?\\d{7,8}$");
    /** 编码类值整个就是 16-19 位数字: 当银行卡 (以 id 结尾的字段除外, 那是雪花 id)。 */
    private static final Pattern CARD_WHOLE = Pattern.compile("^\\d{16,19}$");
    private static final Pattern ID_KEY = Pattern.compile("(?i)^(id|.*id|.*ids)$");
    private static final Pattern MOBILE_NUMBER = Pattern.compile("^1[3-9]\\d{9}$");

    // ---------------- 文本模式补充 (只用于外发: 代号可还原, 误认一个词只是多一个代号; 训练规则那几个常量与
    // Python 逐字对齐, 不能改, 所以宽一点的写法放这里)

    /** 训练规则姓氏表之外的常见姓 (含复姓首字), 只用于「姓 + 称谓」。 */
    private static final String EXTRA_SURNAMES = "牛常欧司诸上于向付万傅齐葛祁倪柳毕甘施尚屈翁庞裴伍俞阮聂单";
    /** 「姓 + 称谓」不管前面是不是汉字 (训练规则要求前面不是汉字, 「找李经理确认」认不出)。 */
    private static final Pattern TITLED_NAME = Pattern.compile(
            "[" + TrainingSampleRules.SURNAMES + EXTRA_SURNAMES + "][" + TrainingSampleRules.HAN + "]?"
            + "(?:经理|总监|老板|师傅|厂长|主任|科长|部长|组长|班长|主管|先生|女士|小姐)");
    /** 训练规则后缀表之外、食品行业常见的单位后缀。 */
    private static final Pattern ORG_NAME_EXTRA = Pattern.compile("[" + TrainingSampleRules.HAN + "A-Za-z0-9]{2,12}"
            + "(?:牧业|肉业|肉联厂|合作社|养殖场|养殖基地|农场|冷链|物流|餐饮|贸易|商店|酒楼|批发市场)");
    /**
     * 公司名正则从左边贪婪吞字: 「查一下永辉超市」「供应商是永辉超市」—— 名字从最后一个这类词之后开始。
     * 「的 / 和 / 与 / 在」这类常出现在店名里的字不算 (切错了会把半个名字留在明文里)。
     */
    private static final Pattern ORG_LEAD_IN = Pattern.compile(
            "^.*(?:一下|我们是|我们|是|给|向|从|找|查|问|跟|让|叫|请|把|往|跟进|联系|通知|客户|供应商)");
    /** 时间 / 范围 / 动词 + 行业词 (「本周物流」「查看冷链」「所有养殖场」): 说的是业务, 不是某家单位。 */
    private static final Pattern GENERIC_INDUSTRY_PHRASE = Pattern.compile(
            "(?:本周|上周|下周|本月|上月|下月|今年|去年|本年|今天|昨天|明天|本季度|上季度|近期|最近|当前|目前|"
                    + "这批|那批|这些|那些|所有|全部|整体|总体|各个|各|我们的|我们|我方|我司|公司|工厂|本厂|"
                    + "查看|查询|查一下|看看|看一下|统计|分析|对比|汇总)+"
                    + "(?:牧业|肉业|肉联厂|合作社|养殖场|养殖基地|农场|冷链|物流|餐饮|贸易|商店|酒楼|批发市场)");
    /** 不剥「找 / 跟 / 叫」: 找钢网、跟谁学这类单位名就以它们开头。 */
    private static final String SINGLE_CHAR_ORG_LEAD_INS = "给让请把往";
    /** 人名正则带着岗位 / 动词前缀 (「质检员：赵铁柱」「通知张三」): 只换名字本身。 */
    private static final Pattern PERSON_LEAD_IN = Pattern.compile(
            "^(?:" + TrainingSampleRules.PERSON_ROLE + "|联系|找|通知|叫|告诉)\\s*[:：|]?\\s*");
    /** 号码的其它写法: 分组银行卡 / 身份证、15 位旧身份证、三段座机 / 400、点分 / 全角手机号。 */
    private static final Pattern CARD_GROUPED = Pattern.compile(
            "(?<![0-9])\\d{4}(?:[ -]\\d{4}){3}(?:[ -]?\\d{1,3})?(?![0-9])");
    private static final Pattern ID_GROUPED = Pattern.compile(
            "(?<![0-9])\\d{6}[ -]\\d{8}[ -]\\d{3}[\\dXx](?![0-9A-Za-z])");
    private static final Pattern ID_15 = Pattern.compile("(?<![0-9A-Za-z])\\d{15}(?![0-9A-Za-z])");
    private static final Pattern LANDLINE_SEGMENTED = Pattern.compile(
            "(?<![0-9])(?:0\\d{2,3}|400|800)[- ]\\d{3,4}[- ]\\d{4}(?![0-9])");
    private static final Pattern MOBILE_DOTTED = Pattern.compile(
            "(?<![0-9])(?:\\+?86[ .]?)?1[3-9]\\d\\.\\d{4}\\.\\d{4}(?![0-9])");
    private static final Pattern MOBILE_FULLWIDTH = Pattern.compile("(?<![０-９])１[３-９][０-９]{9}(?![０-９])");
    /** 单位名去掉这些后缀后剩下的简称 (「老街坊餐饮有限公司」→「老街坊」) 也当同一个人 / 单位。 */
    private static final Pattern ORG_SUFFIX_STRIP = Pattern.compile(
            "(?:股份有限公司|有限责任公司|有限公司|公司|集团|超市|商贸|商行|批发部|门市部|连锁|酒店|饭店|食品厂|牧业|肉业|肉联厂"
            + "|合作社|养殖场|农场|冷链|物流|餐饮|贸易|商店|酒楼|食品|科技)$");

    private static final String HIDDEN = "[已隐藏]";
    /** 不发字段的值短于此不在正文里替换 (两个字的「合格」「正常」太容易误伤)。 */
    private static final int MIN_DROPPED_LENGTH = 3;

    private enum Kind {
        CUSTOMER("客户"), SUPPLIER("供应商"), ORG("单位"), PERSON("人员"), NAME("名称"), PRODUCT("保密产品"),
        PHONE("电话"), ID_NUMBER("证件号"), CARD("卡号"), HIDDEN_FIELD("隐藏内容");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private static final String KIND_LABELS = java.util.Arrays.stream(Kind.values())
            .map(k -> Pattern.quote(k.label)).collect(java.util.stream.Collectors.joining("|"));

    /** 去括号的代号后面若跟着这些字, 是普通说法 (「客户A类」「人员A班」「单位A栋」), 不是代号。 */
    private static final String BARE_TOKEN_CLASSIFIERS = "类级班组区栋座型款档线号层楼室段队";

    /**
     * 代号及模型常见的改写, 两种形状: 成对的括号 (方括号 / 全角括号, 括号里可以有空格) 或去掉括号
     * ({@code 单位A}, 类别名与字母紧挨着)。字母后不能紧跟字母数字 ({@code 单位A} 不会吃掉 {@code 单位AB}
     * 的前半), 去括号的还不能紧跟量词 ({@link #BARE_TOKEN_CLASSIFIERS})。只换回本次请求发出去的代号。
     * 分组: 1/2 = 带括号的类别名 / 字母, 3/4 = 去括号的; 用 {@link #canonicalToken} 取标准写法。
     */
    static final Pattern LOOSE_TOKEN = Pattern.compile(
            "[\\[【［][ \\t]*(" + KIND_LABELS + ")[ \\t]*([A-Z]+)[ \\t]*[\\]】］]"
                    + "|(" + KIND_LABELS + ")([A-Z]+)(?![A-Za-z0-9" + BARE_TOKEN_CLASSIFIERS + "])");

    static String canonicalToken(Matcher m) {
        return m.group(1) != null ? "[" + m.group(1) + m.group(2) + "]" : "[" + m.group(3) + m.group(4) + "]";
    }

    /** 流式: 结尾可能是半个代号 (括号 / 类别名的前几个字 / 类别名 + 字母), 先攒着等下一段。 */
    private static final String KIND_LABEL_PREFIXES = java.util.Arrays.stream(Kind.values())
            .flatMap(k -> java.util.stream.IntStream.rangeClosed(1, k.label.length())
                    .mapToObj(n -> Pattern.quote(k.label.substring(0, n))))
            .collect(java.util.stream.Collectors.joining("|"));
    private static final Pattern PARTIAL_TOKEN_TAIL = Pattern.compile(
            "(?:[\\[【［][ \\t]*(?:(?:" + KIND_LABEL_PREFIXES + ")(?:[ \\t]*[A-Z]+)?[ \\t]*)?"
                    + "|(?:" + KIND_LABEL_PREFIXES + ")[A-Z]*)$");

    /** 调用方已知的交易对象 / 人 (从记录里取出的客户名等), 文本模式下整串换代号。 */
    public enum Party {
        CUSTOMER(Kind.CUSTOMER), SUPPLIER(Kind.SUPPLIER), ORG(Kind.ORG), PERSON(Kind.PERSON);

        private final Kind kind;

        Party(Kind kind) {
            this.kind = kind;
        }
    }

    /** 脱敏结果。 */
    public static final class Result {
        private final String payload;
        private final List<String> texts;
        private final Map<String, String> tokenToReal;
        private final String refusal;
        private final int droppedFields;

        Result(String payload, Map<String, String> tokenToReal, String refusal, int droppedFields) {
            this(payload, List.of(), tokenToReal, refusal, droppedFields);
        }

        Result(String payload, List<String> texts, Map<String, String> tokenToReal, String refusal, int droppedFields) {
            this.payload = payload;
            this.texts = texts;
            this.tokenToReal = tokenToReal;
            this.refusal = refusal;
            this.droppedFields = droppedFields;
        }

        static Result refusedFor(String reason) {
            return new Result(null, Map.of(), reason, 0);
        }

        /** 可以发给模型的 JSON; {@link #refused()} 时为 null。 */
        public String payload() {
            return payload;
        }

        /** 文本模式: 与入参一一对应、可以发给模型的文本; {@link #refused()} 时为空。 */
        public List<String> texts() {
            return texts;
        }

        /** 文本模式第 i 段 (入参为 null 的段仍为 null)。 */
        public String text(int i) {
            return texts.get(i);
        }

        public boolean refused() {
            return refusal != null;
        }

        /** 拒绝原因 (只描述类别, 不含原始值, 可以写日志)。 */
        public String refusal() {
            return refusal;
        }

        public int tokenCount() {
            return tokenToReal.size();
        }

        public int droppedFields() {
            return droppedFields;
        }

        /**
         * 把模型回答里的代号换回真名。模型常把代号改写: 去掉方括号 ({@code 单位A}, 填表字段里最常见)、
         * 换成全角括号 ({@code 【单位A】}) 或在中间加空格 —— 这些写法只要对得上本次请求发出去的代号, 一律换回。
         */
        public String restore(String text) {
            return restoreWith(text, real -> real);
        }

        /**
         * 模型回的是 JSON 文本、而解析在别处做时: 换回的真名按 JSON 字符串转义 (代号总是出现在 JSON 字符串里),
         * 真名带引号 / 反斜杠也不会弄坏 JSON。代号的改写形式同 {@link #restore}。
         */
        public String restoreJsonText(String json) {
            return restoreWith(json, real ->
                    new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(real)));
        }

        private String restoreWith(String text, java.util.function.UnaryOperator<String> encode) {
            if (text == null || tokenToReal.isEmpty()) {
                return text;
            }
            Matcher m = LOOSE_TOKEN.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            while (m.find()) {
                String real = tokenToReal.get(canonicalToken(m));
                m.appendReplacement(out, Matcher.quoteReplacement(real != null ? encode.apply(real) : m.group()));
            }
            m.appendTail(out);
            return out.toString();
        }

        /**
         * 模型回的是 JSON 时, 先解析再逐个字符串换回真名 (真名里有引号 / 反斜杠也不会弄坏 JSON)。
         * 返回新的 Map / List; 其它值原样返回。
         */
        public Object restoreDeep(Object node) {
            if (tokenToReal.isEmpty()) {
                return node;
            }
            if (node instanceof Map<?, ?> map) {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object k = e.getKey() instanceof String ks ? restore(ks) : e.getKey();
                    out.put(k, restoreDeep(e.getValue()));
                }
                return out;
            }
            if (node instanceof List<?> items) {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(restoreDeep(item));
                }
                return out;
            }
            return node instanceof String s ? restore(s) : node;
        }

        /**
         * 流式回答: 代号可能被拆在两段 token 里 ({@code "[客"} + {@code "户A]"}), 攒到代号闭合再换回真名往下发。
         * 流结束时必须调 {@link StreamRestorer#flush()}。
         */
        public StreamRestorer streamRestorer(java.util.function.Consumer<String> downstream) {
            return new StreamRestorer(this, downstream);
        }
    }

    /** 见 {@link Result#streamRestorer}。 */
    public static final class StreamRestorer implements java.util.function.Consumer<String> {
        /** 最长的代号 ({@code [保密产品AB]}) 远短于此; 超过还没闭合的 "[" 不是代号, 直接放行。 */
        private static final int MAX_PENDING = 16;

        private final Result result;
        private final java.util.function.Consumer<String> downstream;
        private final StringBuilder pending = new StringBuilder();

        StreamRestorer(Result result, java.util.function.Consumer<String> downstream) {
            this.result = result;
            this.downstream = downstream;
        }

        @Override
        public synchronized void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            pending.append(chunk);
            int open = pending.lastIndexOf("[");
            int cut = open >= 0 && pending.indexOf("]", open) < 0 && pending.length() - open <= MAX_PENDING
                    ? open : pending.length();
            // 半个代号 (全角括号开头、去括号、被拆开的 "单位" + "A" + "B"): 只看最后 MAX_PENDING 个字,
            // 结尾是半个代号就先不发
            int from = Math.max(0, pending.length() - MAX_PENDING);
            Matcher tail = PARTIAL_TOKEN_TAIL.matcher(pending).region(from, pending.length());
            if (tail.find()) {
                cut = Math.min(cut, tail.start());
            }
            if (cut > 0) {
                String ready = pending.substring(0, cut);
                pending.delete(0, cut);
                downstream.accept(result.restore(ready));
            }
        }

        public synchronized void flush() {
            if (pending.length() > 0) {
                String rest = pending.toString();
                pending.setLength(0);
                downstream.accept(result.restore(rest));
            }
        }
    }

    private final ObjectMapper objectMapper;

    public LlmEgressRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把结果转成只含 Map / List / 标量的树: JsonNode、POJO、record、数组、Optional 都展开。
     * Map / List / 数字 / 字符串原样保留 (不经 Jackson 往返, 数字精度不变)。
     *
     * @throws IllegalArgumentException 转不了 (调用方据此拒绝发送)
     */
    public static Object toPlainTree(ObjectMapper mapper, Object node) {
        if (node == null || node instanceof CharSequence || node instanceof Number || node instanceof Boolean
                || node instanceof Enum<?> || node instanceof TemporalAccessor || node instanceof Date) {
            return node instanceof CharSequence cs ? cs.toString() : node;
        }
        if (node instanceof JsonNode json) {
            return mapper.convertValue(json, Object.class);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), toPlainTree(mapper, e.getValue()));
            }
            return out;
        }
        if (node instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) {
                out.add(toPlainTree(mapper, item));
            }
            return out;
        }
        if (node instanceof Object[] array) {
            List<Object> out = new ArrayList<>();
            for (Object item : array) {
                out.add(toPlainTree(mapper, item));
            }
            return out;
        }
        if (node instanceof Optional<?> opt) {
            return toPlainTree(mapper, opt.orElse(null));
        }
        // POJO / record / 基本类型数组: 交给 Jackson 展开成 Map / List
        return toPlainTree(mapper, mapper.convertValue(node, Object.class));
    }

    /**
     * @param data           要发给模型的结构化结果
     * @param protectedNames 本厂工艺保密产品名 (可为空)
     */
    public Result redact(Object data, Collection<String> protectedNames) {
        return redact(data, List.of(), Map.of(), protectedNames, false);
    }

    /**
     * 文本模式 (拼好的提示词片段 / 对话历史): 各段共用一套代号, 模型回答用 {@link Result#restore} 换回。
     * <ul>
     *     <li>手机号 / 座机 / 身份证号 / 银行卡号 → {@code [电话A]} {@code [证件号A]} {@code [卡号A]} (可还原,
     *         用户自己说的号码填进表单时不会丢);</li>
     *     <li>{@code knownParties} (调用方从记录里取出的客户名等) 与工艺保密产品名, 出现在任何一段都换代号;</li>
     *     <li>{@code detectNames}: 再把「公司类名称」「岗位 + 人名」「李经理」这类片段换代号 —— 历史和带记录的
     *         提示词要开; 只有用户原话、要按原话识别意图时可以关 (只抹号码)。</li>
     * </ul>
     * 发出前同样再扫一遍, 残留就拒绝。
     */
    public Result redactTexts(List<String> texts, Map<String, Party> knownParties,
                              Collection<String> protectedNames, boolean detectNames) {
        return redact(null, texts, knownParties, protectedNames, detectNames);
    }

    /**
     * 结构化结果 + 文本 (如: 候选人名单 + 用户原话) 共用一套代号: 名单里换成 {@code [人员A]} 的人,
     * 原话里提到时也是 {@code [人员A]}, 模型才能把两边对上。{@code data} 为 null 时只有文本。
     */
    public Result redact(Object data, List<String> texts, Map<String, Party> knownParties,
                         Collection<String> protectedNames, boolean detectNames) {
        return redact(data, texts, knownParties, protectedNames, detectNames, false);
    }

    /**
     * 只发文本、文本是从 {@code record} 拼出来的 (记录本身不发): 记录里认出的一切 —— 人和交易对象名、
     * 不认识字段里换了代号的值、不发字段 (地址 / 备注 / 电话) 的值 —— 在文本里出现都换掉, 发前也按这些查。
     * 记录展开不了就拒绝 (不退回只按文本猜)。
     */
    public Result redactTextsWithRecord(Object record, List<String> texts, Map<String, Party> knownParties,
                                        Collection<String> protectedNames) {
        Result r = redact(record, texts, knownParties, protectedNames, true, true);
        return r.refused() ? r : new Result(null, r.texts, r.tokenToReal, null, r.droppedFields);
    }

    private Result redact(Object data, List<String> texts, Map<String, Party> knownParties,
                          Collection<String> protectedNames, boolean detectNames, boolean recordOnly) {
        Object tree = null;
        if (data != null) {
            try {
                tree = toPlainTree(objectMapper, data);
            } catch (RuntimeException e) {
                return new Result(null, Map.of(), "结果类型无法展开", 0);
            }
        }
        Session s = new Session(protectedNames);
        s.recordOnly = recordOnly;
        // 入参里本来就写着「[客户A]」这种字样时, 生成的代号要避开它, 否则还原时会被换成别人的真名
        StringBuilder corpus = new StringBuilder();
        for (String text : texts) {
            if (text != null) {
                corpus.append(text).append('\n');
            }
        }
        if (tree != null) {
            List<String> strings = new ArrayList<>();
            s.collect(tree, strings, new ArrayList<>());
            strings.forEach(x -> corpus.append(x).append('\n'));
        }
        s.reserved = corpus.toString();
        if (knownParties != null) {
            for (Map.Entry<String, Party> e : knownParties.entrySet()) {
                String real = e.getKey() == null ? "" : e.getKey().strip();
                if (real.length() >= 2 && !GENERIC.contains(real)) {
                    s.known(e.getValue().kind, real);
                }
            }
        }
        Object cleanedTree = tree == null ? null : s.walk(tree, "", null);
        List<String> scrubbed = new ArrayList<>();
        for (String text : texts) {
            scrubbed.add(text == null ? null : s.scrubText(text, detectNames));
        }
        // 第二遍: 后面才认出的真名, 在前面的字段 / 段落里也换掉
        cleanedTree = s.replaceKnownEverywhere(cleanedTree);
        List<String> cleanedTexts = new ArrayList<>();
        List<String> textReals = s.textReals();
        for (String text : scrubbed) {
            cleanedTexts.add(text == null ? null : s.replaceKnownText(text, textReals));
        }
        // 记录不发时只查要发的文本 (外加: 不发字段的值一个都不许留下)
        List<Object> all = new ArrayList<>(cleanedTexts);
        if (!recordOnly) {
            all.add(cleanedTree);
        }
        String leak = s.findLeak(all);
        if (leak == null && recordOnly) {
            leak = s.findDroppedValue(cleanedTexts);
        }
        if (leak != null) {
            return new Result(null, Map.of(), leak, s.dropped);
        }
        try {
            String payload = data == null ? null : objectMapper.writeValueAsString(cleanedTree);
            return new Result(payload, java.util.Collections.unmodifiableList(cleanedTexts), s.tokenToReal, null,
                    s.dropped);
        } catch (JsonProcessingException e) {
            return new Result(null, Map.of(), "序列化失败", s.dropped);
        }
    }

    private static final class Session {
        final Map<String, String> realToToken = new LinkedHashMap<>();
        final Map<String, String> tokenToReal = new LinkedHashMap<>();
        final Map<String, Kind> realKind = new LinkedHashMap<>();
        final Map<Kind, Integer> counters = new LinkedHashMap<>();
        final List<String> protectedNames = new ArrayList<>();
        /** 结构化记录里不发的字段值 (长的在前); 只用于文本模式: 正文里出现就换 {@code [隐藏内容A]}。 */
        final List<String> droppedValues = new ArrayList<>();
        /** 调用方明确给出的名字 (及其简称 / 转义写法): 一律在各处替换。 */
        final Set<String> explicitReals = new java.util.HashSet<>();
        /** 入参原文 (含结构化结果里的字符串): 生成的代号不能和里面已有的字样相同。 */
        String reserved = "";
        /** 记录只用来认名字、不发: 不认识字段里的值 (名称代号) 也要在文本里换掉。 */
        boolean recordOnly;
        int dropped;
        String unsupported;

        Session(Collection<String> names) {
            if (names != null) {
                for (String n : names) {
                    // strip 而不是 trim: 名字首尾的全角空格也去掉, 否则只认「名字 + 全角空格」, 正文里的名字原样发出
                    if (n != null && n.strip().length() >= ProcessSecretSampleFilter.MIN_NAME_LENGTH) {
                        protectedNames.add(n.strip());
                    }
                }
                protectedNames.sort(Comparator.comparingInt(String::length).reversed());
            }
        }

        /**
         * 调用方给的已知名字: 不论长短 / 是否像编码都在各处替换 (纯数字除外);
         * 单位名再加上去掉后缀的简称 (≥3 字), 以及 JSON 转义后的写法, 都指向同一个代号 (还原成全称)。
         */
        void known(Kind kind, String real) {
            String t = token(kind, real);
            explicitReals.add(real);
            if (kind != Kind.PERSON) {
                // 一次去掉最后一个后缀, 每一级简称都登记: 老街坊餐饮有限公司 → 老街坊餐饮 → 老街坊
                String core = real;
                Matcher m = ORG_SUFFIX_STRIP.matcher(core);
                while (m.find()) {
                    core = core.substring(0, m.start());
                    if (core.length() < 3) {
                        break;
                    }
                    alias(core, t, kind);
                    m = ORG_SUFFIX_STRIP.matcher(core);
                }
            }
            String escaped = new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(real));
            if (!escaped.equals(real)) {
                alias(escaped, t, kind);
            }
        }

        void alias(String alias, String token, Kind kind) {
            if (!realToToken.containsKey(alias) && !GENERIC.contains(alias)) {
                realToToken.put(alias, token);
                realKind.put(alias, kind);
                explicitReals.add(alias);
            }
        }

        private String reservedScanned;
        private Set<String> reservedTokens = Set.of();

        /**
         * 入参里已经出现过的代号字样 (含去括号 / 全角括号写法, 如原话里本来就有「单位A栋」):
         * 这些不能再发, 否则还原时会把原话里的字样错换成真名。
         */
        Set<String> reservedTokens() {
            if (!reserved.equals(reservedScanned)) {
                Set<String> found = new java.util.HashSet<>();
                Matcher m = LOOSE_TOKEN.matcher(reserved);
                while (m.find()) {
                    found.add(canonicalToken(m));
                }
                reservedTokens = found;
                reservedScanned = reserved;
            }
            return reservedTokens;
        }

        String token(Kind kind, String real) {
            String existing = realToToken.get(real);
            if (existing != null) {
                return existing;
            }
            int n = counters.merge(kind, 1, Integer::sum);
            String t = "[" + kind.label + letters(n) + "]";
            while (reservedTokens().contains(t)) {
                n = counters.merge(kind, 1, Integer::sum);
                t = "[" + kind.label + letters(n) + "]";
            }
            realToToken.put(real, t);
            tokenToReal.put(t, real);
            realKind.put(real, kind);
            return t;
        }

        /**
         * @param key       当前值所在的字段名 (列表元素沿用列表的字段名)
         * @param container 最近一层容器的字段名 (用于判断裸 name 字段)
         */
        Object walk(Object node, String key, String container) {
            if (node instanceof Map<?, ?> map) {
                // {"operatorCounts": {"张三": 12}}: 人 / 交易对象名做了键, 值是数 —— 键本身就是名字
                Kind keysAre = key == null || key.isEmpty() ? Kind.NAME : kindOf(key);
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (isDropKey(k)) {
                        dropped++;
                        rememberDropped(e.getValue());
                        continue;
                    }
                    String outKey = keysAre != Kind.NAME && e.getValue() instanceof Number && !isCode(k)
                            && !GENERIC.contains(k) ? token(keysAre, k) : k;
                    out.put(outKey, walk(e.getValue(), k, key));
                }
                return out;
            }
            if (node instanceof List<?> items) {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(walk(item, key, container));
                }
                return out;
            }
            if (node instanceof String str) {
                return string(str, key, container);
            }
            if (node == null || node instanceof Number || node instanceof Boolean || node instanceof Enum<?>
                    || node instanceof TemporalAccessor || node instanceof Date) {
                return node;
            }
            unsupported = node.getClass().getSimpleName();
            return null;
        }

        /** 不发的字段 (地址 / 备注 / 电话…) 的值: 同一份内容出现在提示词正文里时也要换掉。 */
        void rememberDropped(Object value) {
            if (value instanceof String s) {
                String v = s.trim();
                if (v.length() >= MIN_DROPPED_LENGTH && !GENERIC.contains(v) && !droppedValues.contains(v)) {
                    droppedValues.add(v);
                    droppedValues.sort(Comparator.comparingInt(String::length).reversed());
                }
            } else if (value instanceof Map<?, ?> map) {
                map.values().forEach(this::rememberDropped);
            } else if (value instanceof List<?> items) {
                items.forEach(this::rememberDropped);
            }
        }

        String string(String value, String key, String container) {
            String v = value.trim();
            if (v.isEmpty() || GENERIC.contains(v)) {
                return value;
            }
            Kind party = partyKind(key, container);
            if (party != null) {
                return token(party, v);
            }
            if (isKeepKey(key) || isSystemTextKey(key) || isBusinessObjectName(key, container)) {
                // 编码类值 (批次号 F006-20261025-0007) 不按「含座机 / 含卡号」误抹, 只看整个值是不是号码
                return isCode(v) ? scrubCode(value, key) : scrubFreeText(value);
            }
            if (isCode(v)) {
                return wholeNumberLooksPrivate(v, key) || MOBILE_LOOSE.matcher(v).find()
                        || MOBILE_DOTTED.matcher(v).find() ? HIDDEN : value;
            }
            // 不认识的字段里的非编码字符串: 默认换代号
            return token(Kind.NAME, v);
        }

        boolean isDropKey(String key) {
            return DROP_KEY.matcher(key).matches() || DROP_KEY_ZH.matcher(key).matches();
        }

        boolean isKeepKey(String key) {
            return key != null && (KEEP_NAME_KEY.matcher(key).matches() || KEEP_CODE_KEY.matcher(key).matches()
                    || (KEEP_KEY_ZH.matcher(key).matches() && !PARTY_KEY_ZH.matcher(key).matches()));
        }

        boolean isSystemTextKey(String key) {
            return key != null && SYSTEM_TEXT_KEY.matcher(key).matches();
        }

        /** 裸 name / names 字段, 最近一层容器是产品 / 物料这类业务对象。 */
        boolean isBusinessObjectName(String key, String container) {
            return key != null && (key.equalsIgnoreCase("name") || key.equalsIgnoreCase("names"))
                    && container != null && BUSINESS_CONTAINER.matcher(container).matches();
        }

        static boolean isCode(String v) {
            return CODE_LIKE.matcher(v).matches();
        }

        String scrubCode(String value, String key) {
            if (wholeNumberLooksPrivate(value.trim(), key)) {
                return HIDDEN;
            }
            String out = TrainingSampleRules.ID_CARD.matcher(value).replaceAll(HIDDEN);
            out = MOBILE_DOTTED.matcher(out).replaceAll(HIDDEN);
            out = MOBILE_LOOSE.matcher(out).replaceAll(HIDDEN);
            return TrainingSampleRules.PHONE.matcher(out).replaceAll(HIDDEN);
        }

        /** 整个值就是座机号, 或 16-19 位纯数字 (卡号; 以 id 结尾的字段是雪花 id, 除外)。 */
        static boolean wholeNumberLooksPrivate(String v, String key) {
            if (LANDLINE_WHOLE.matcher(v).matches()) {
                return true;
            }
            return CARD_WHOLE.matcher(v).matches() && (key == null || !ID_KEY.matcher(key).matches());
        }

        /** null = 不是指向人 / 交易对象的字段。 */
        Kind partyKind(String key, String container) {
            if (key == null || key.isEmpty() || KEEP_NAME_KEY.matcher(key).matches()) {
                return null;
            }
            if (PARTY_KEY_ZH.matcher(key).matches()) {
                return kindOf(key);
            }
            String k = key.toLowerCase();
            if ("name".equals(k) || "names".equals(k)) {
                String c = container == null ? "" : container;
                if (BUSINESS_CONTAINER.matcher(c).matches()) {
                    return null;
                }
                Kind byContainer = kindOf(c);
                if (byContainer != Kind.NAME) {
                    return byContainer;
                }
                return PERSON_CONTAINER.matcher(c).matches() ? Kind.PERSON : Kind.NAME;
            }
            // assigneeDisplay / customerLabel / operatorText: 去掉展示后缀后是人 / 交易对象, 值就是名字
            Matcher shown = DISPLAY_SUFFIX.matcher(key);
            if (shown.matches() && !shown.group(1).isEmpty()) {
                String stem = shown.group(1);
                if (!KEEP_NAME_KEY.matcher(stem).matches() && !KEEP_CODE_KEY.matcher(stem).matches()
                        && PARTY_KEY.matcher(stem).matches()) {
                    Kind kind = kindOf(stem);
                    return kind == Kind.NAME ? Kind.PERSON : kind;
                }
            }
            if (isSystemTextKey(key) || KEEP_CODE_KEY.matcher(key).matches() || !PARTY_KEY.matcher(key).matches()) {
                return null;
            }
            Kind kind = kindOf(key);
            if (kind != Kind.NAME) {
                return kind;
            }
            // 只有「xxxName」且看不出是人: 名称代号; 其余 (contact / owner / *By …) 当人
            return k.matches(".*names?") ? Kind.NAME : Kind.PERSON;
        }

        /** 按字段名里的词判断代号类别; 判断不了返回 NAME。 */
        static Kind kindOf(String key) {
            String k = key.toLowerCase();
            if (k.matches(".*(customer|client|consignee|store|shop).*") || key.matches(".*(客户|门店).*")) {
                return Kind.CUSTOMER;
            }
            if (k.matches(".*(supplier|vendor).*") || key.matches(".*(供应商|厂商).*")) {
                return Kind.SUPPLIER;
            }
            if (k.matches(".*(company|companies|merchant|partner|carrier|shipper).*")
                    || key.matches(".*(公司|单位|商家|商户).*")) {
                return Kind.ORG;
            }
            if (k.matches(".*(user|full|real|nick|person|people|contact|operator|owner|manager|supervisor|leader"
                    + "|worker|employee|staff|inspector|handler|approver|assignee|recorder|requestor|requester"
                    + "|creator|signer|receiver|recipient|sender|driver|buyer|purchaser|salesman|salesperson"
                    + "|member|responsible).*|.*by") || key.matches(".*(人|员|姓名|司机|仓管|主管|班长|组长).*")) {
                return Kind.PERSON;
            }
            return Kind.NAME;
        }

        String scrubFreeText(String value) {
            String out = ID_GROUPED.matcher(value).replaceAll(HIDDEN);
            out = TrainingSampleRules.ID_CARD.matcher(out).replaceAll(HIDDEN);
            out = CARD_GROUPED.matcher(out).replaceAll(HIDDEN);
            out = TrainingSampleRules.BANK_CARD.matcher(out).replaceAll(HIDDEN);
            out = ID_15.matcher(out).replaceAll(HIDDEN);
            out = MOBILE_FULLWIDTH.matcher(out).replaceAll(HIDDEN);
            out = MOBILE_DOTTED.matcher(out).replaceAll(HIDDEN);
            out = MOBILE_LOOSE.matcher(out).replaceAll(HIDDEN);
            out = LANDLINE_SEGMENTED.matcher(out).replaceAll(HIDDEN);
            out = LANDLINE.matcher(out).replaceAll(HIDDEN);
            out = TrainingSampleRules.PHONE.matcher(out).replaceAll(HIDDEN);
            out = replaceSpans(out, TrainingSampleRules.ORG_NAME, Kind.ORG);
            out = replaceSpans(out, TrainingSampleRules.PERSON, Kind.PERSON);
            return out;
        }

        /** 文本模式: 已知真名 / 保密产品名先换, 再把号码换成可还原的代号, 按需再认公司名与人名。 */
        String scrubText(String value, boolean detectNames) {
            // 不发字段的值先换 (地址里常含客户名: 先换名字, 整串地址就对不上了); 文本是 JSON 时值是转义后的写法
            String out = value;
            for (String hidden : droppedValues) {
                for (String form : forms(hidden)) {
                    if (out.contains(form)) {
                        out = out.replace(form, token(Kind.HIDDEN_FIELD, hidden));
                    }
                }
            }
            out = replaceKnownText(out, textReals());
            out = replaceSpans(out, ID_GROUPED, Kind.ID_NUMBER);
            out = replaceSpans(out, TrainingSampleRules.ID_CARD, Kind.ID_NUMBER);
            out = replaceSpans(out, CARD_GROUPED, Kind.CARD);
            out = replaceSpans(out, TrainingSampleRules.BANK_CARD, Kind.CARD);
            out = replaceSpans(out, ID_15, Kind.ID_NUMBER);
            out = replaceSpans(out, MOBILE_FULLWIDTH, Kind.PHONE);
            out = replaceSpans(out, MOBILE_DOTTED, Kind.PHONE);
            out = replaceSpans(out, MOBILE_LOOSE, Kind.PHONE);
            out = replaceSpans(out, LANDLINE_SEGMENTED, Kind.PHONE);
            out = replaceSpans(out, LANDLINE, Kind.PHONE);
            out = replaceSpans(out, TrainingSampleRules.PHONE, Kind.PHONE);
            if (detectNames) {
                // 公司名 / 人名的匹配会带上前面的动词或岗位, 只把名字本身换成代号, 前缀原样留着
                out = replaceSpans(out, TrainingSampleRules.ORG_NAME, Kind.ORG, Session::orgCore);
                out = replaceSpans(out, ORG_NAME_EXTRA, Kind.ORG, Session::orgCore);
                out = replaceSpans(out, TrainingSampleRules.PERSON, Kind.PERSON, Session::personCore);
                out = replaceSpans(out, TITLED_NAME, Kind.PERSON);
            }
            return out;
        }

        String replaceSpans(String text, Pattern pattern, Kind kind) {
            return replaceSpans(text, pattern, kind, java.util.function.UnaryOperator.identity());
        }

        /**
         * @param core 从整段匹配里取出名字本身 (匹配的后缀部分); 前面多出来的字原样保留。
         *             返回 null 表示这段不是名字 (如「本周物流」), 原样保留。
         */
        String replaceSpans(String text, Pattern pattern, Kind kind, java.util.function.UnaryOperator<String> core) {
            Matcher m = pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            boolean numeric = kind == Kind.PHONE || kind == Kind.CARD || kind == Kind.ID_NUMBER;
            while (m.find()) {
                if (numeric && afterDecimalPoint(text, m.start())) {
                    continue; // 0.0523809524 / 0.30000000000000004 的小数部分不是号码
                }
                String span = m.group();
                String name = core.apply(span);
                if (name == null) {
                    continue;
                }
                String lead = span.substring(0, span.length() - name.length());
                m.appendReplacement(sb, Matcher.quoteReplacement(lead + token(kind, name)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        static boolean afterDecimalPoint(String text, int start) {
            return start >= 2 && text.charAt(start - 1) == '.' && Character.isDigit(text.charAt(start - 2));
        }

        /** 与 {@code find()} 相同, 但跳过紧跟在小数点后面的数字串。 */
        static boolean findNumber(Pattern pattern, String text) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                if (!afterDecimalPoint(text, m.start())) {
                    return true;
                }
            }
            return false;
        }

        /** 「查一下永辉超市」→「永辉超市」; 去掉之后不再像单位名 (「美的集团」) 就保留整段。 */
        static String orgCore(String span) {
            Matcher lead = ORG_LEAD_IN.matcher(span);
            // 单字前缀一般不剥 (「向阳食品」「从化…」的首字就是名字), 但这几个动词不会是单位名的首字:
            // 「给华东冷链…下单」不剥的话, 填表字段会还原成「给华东冷链…」
            String name = span;
            if (lead.find() && (lead.end() >= 2 || SINGLE_CHAR_ORG_LEAD_INS.indexOf(span.charAt(0)) >= 0)
                    && lead.end() < span.length()) {
                String rest = span.substring(lead.end());
                if (TrainingSampleRules.ORG_NAME.matcher(rest).matches() || ORG_NAME_EXTRA.matcher(rest).matches()) {
                    name = rest;
                }
            }
            // 「本周物流成本」「查看冷链运输记录」: 时间 / 动词 + 行业词是普通说法, 不是单位名 (换掉会丢了意图里的关键词)
            return GENERIC_INDUSTRY_PHRASE.matcher(name).matches() ? null : name;
        }

        /** 「质检员：赵铁柱」「通知张三」→ 只取名字。 */
        static String personCore(String span) {
            Matcher lead = PERSON_LEAD_IN.matcher(span);
            if (lead.find() && lead.end() < span.length() && span.length() - lead.end() >= 2) {
                return span.substring(lead.end());
            }
            return span;
        }

        /** 第二遍: 已换过代号的真名、保密产品名, 在任何字符串 (含业务对象名、字段名) 里出现都换掉。 */
        Object replaceKnownEverywhere(Object node) {
            return replaceKnown(node, spreadableReals());
        }

        List<String> spreadableReals() {
            List<String> reals = new ArrayList<>();
            for (String real : realToToken.keySet()) {
                if (replaceableEverywhere(real)) {
                    reals.add(real);
                }
            }
            reals.sort(Comparator.comparingInt(String::length).reversed());
            return reals;
        }

        /**
         * 文本 (原话 / 提示词) 里要换掉的: 上面那些, 再加结构化结果里不认识字段的值 (名称代号) ——
         * 用户提到排名里的「周晓明」时, 数据里是 [名称A], 原话里也得是 [名称A]。只用于文本, 不扩散进结构化结果。
         */
        List<String> textReals() {
            List<String> reals = new ArrayList<>(spreadableReals());
            for (Map.Entry<String, Kind> e : realKind.entrySet()) {
                String real = e.getKey();
                if (e.getValue() == Kind.NAME && !reals.contains(real) && real.length() >= 2
                        && !real.matches("\\d+") && !isCode(real)) {
                    reals.add(real);
                }
            }
            reals.sort(Comparator.comparingInt(String::length).reversed());
            return reals;
        }

        /**
         * 哪些真名要在其它字段里也换掉 (以及最后一遍要查):
         * 人 / 客户 / 供应商 / 单位 / 保密产品 —— 包括 wangjg 这类拼音登录名 (UserCreateTool 会在 message 里重复它);
         * 按字段推断出的「名称」代号不扩散 (多为状态 / 说明短语, 扩散会把「合格」换进「合格率」)。
         * 纯数字、过短的不扩散。
         */
        boolean replaceableEverywhere(String real) {
            Kind kind = realKind.get(real);
            if (explicitReals.contains(real)) {
                return real.length() >= 2 && !real.matches("\\d+");
            }
            // (名称代号不扩散进结构化结果; 文本里的替换见 textReals)
            // 隐藏内容只在文本模式里替换 (scrubText), 不扩散进结构化结果
            if (kind == null || kind == Kind.NAME || kind == Kind.HIDDEN_FIELD || real.length() < 2
                    || real.matches("\\d+")) {
                return false;
            }
            return !CODE_LIKE.matcher(real).matches() || (real.length() >= 3 && real.matches(".*[A-Za-z].*"));
        }

        Object replaceKnown(Object node, List<String> reals) {
            if (node instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.put(replaceKnownText(String.valueOf(e.getKey()), reals), replaceKnown(e.getValue(), reals));
                }
                return out;
            }
            if (node instanceof List<?> items) {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(replaceKnown(item, reals));
                }
                return out;
            }
            if (node instanceof String str) {
                return replaceKnownText(str, reals);
            }
            return node;
        }

        String replaceKnownText(String text, List<String> reals) {
            if (isToken(text)) {
                return text;
            }
            String out = text;
            for (String name : protectedNames) {
                if (out.contains(name)) {
                    out = out.replace(name, token(Kind.PRODUCT, name));
                }
            }
            for (String real : reals) {
                for (String form : forms(real)) {
                    if (!out.contains(form)) {
                        continue;
                    }
                    out = isAscii(form)
                            ? asciiWord(form).matcher(out).replaceAll(Matcher.quoteReplacement(realToToken.get(real)))
                            : out.replace(form, realToToken.get(real));
                }
            }
            return out;
        }

        /** 原样, 以及放进 JSON 字符串后的写法 (换行 / 引号 / 反斜杠被转义); 中文不变。 */
        static List<String> forms(String real) {
            String escaped = new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(real));
            return escaped.equals(real) ? List.of(real) : List.of(real, escaped);
        }

        /** 拼音 / 英文名按整词匹配 (wangjg 不该匹配 wangjgx), 中文按子串。 */
        static boolean isAscii(String s) {
            return s.chars().allMatch(c -> c < 128);
        }

        static Pattern asciiWord(String real) {
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(real) + "(?![A-Za-z0-9_])");
        }

        boolean stillContains(String s, String real) {
            for (String form : forms(real)) {
                if (isAscii(form) ? asciiWord(form).matcher(s).find() : s.contains(form)) {
                    return true;
                }
            }
            return false;
        }

        boolean isToken(String text) {
            return tokenToReal.containsKey(text);
        }

        /** 发出前最后一遍: 残留号码 / 真名 / 保密产品名 / 不支持的类型 → 返回原因 (不含原始值); 干净返回 null。 */
        String findLeak(Object node) {
            if (unsupported != null) {
                return "结果里有无法处理的类型";
            }
            List<String> strings = new ArrayList<>();
            List<Number> numbers = new ArrayList<>();
            collect(node, strings, numbers);
            for (Number n : numbers) {
                if (MOBILE_NUMBER.matcher(n.toString()).matches()) return "残留手机号";
            }
            for (String s : strings) {
                if (findNumber(TrainingSampleRules.PHONE, s) || findNumber(MOBILE_LOOSE, s)
                        || findNumber(MOBILE_DOTTED, s) || MOBILE_FULLWIDTH.matcher(s).find()) return "残留手机号";
                if (findNumber(TrainingSampleRules.ID_CARD, s)) return "残留证件号";
                // 编码类字符串 (批次号 / 单号) 不按「含座机 / 含卡号」判, 只看整个值 (卡号已在字段级按字段名处理)
                if (Session.isCode(s)) {
                    if (LANDLINE_WHOLE.matcher(s.trim()).matches()) return "残留电话号码";
                } else {
                    if (findNumber(LANDLINE, s) || findNumber(LANDLINE_SEGMENTED, s)) return "残留电话号码";
                    if (findNumber(TrainingSampleRules.BANK_CARD, s) || findNumber(CARD_GROUPED, s)) {
                        return "残留银行卡号";
                    }
                    if (findNumber(ID_GROUPED, s) || findNumber(ID_15, s)) return "残留证件号";
                }
                for (String name : protectedNames) {
                    if (s.contains(name)) return "残留工艺保密产品名";
                }
                for (String real : realToToken.keySet()) {
                    if (replaceableEverywhere(real) && stillContains(s, real)) return "残留已脱敏的名称";
                }
            }
            return null;
        }

        String findDroppedValue(List<String> texts) {
            for (String text : texts) {
                if (text == null) {
                    continue;
                }
                for (String hidden : droppedValues) {
                    for (String form : forms(hidden)) {
                        if (text.contains(form)) return "残留不发送的字段内容";
                    }
                }
            }
            return null;
        }

        void collect(Object node, List<String> strings, List<Number> numbers) {
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    strings.add(String.valueOf(e.getKey()));
                    collect(e.getValue(), strings, numbers);
                }
            } else if (node instanceof List<?> items) {
                for (Object item : items) {
                    collect(item, strings, numbers);
                }
            } else if (node instanceof String str) {
                if (!isToken(str)) {
                    strings.add(str);
                }
            } else if (node instanceof Number num) {
                numbers.add(num);
            }
        }
    }

    /** 1→A, 26→Z, 27→AA。 */
    static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        int x = n;
        while (x > 0) {
            x--;
            sb.insert(0, (char) ('A' + x % 26));
            x /= 26;
        }
        return sb.toString();
    }
}
