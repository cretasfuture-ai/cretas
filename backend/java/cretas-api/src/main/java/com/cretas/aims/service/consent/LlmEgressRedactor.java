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
            + "|statusdisplay|line|workshop|handlerbean|plan|report|module|field|column|chart|kpi|brand|display"
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

    /**
     * 文本模式补充: 「姓 + 称谓」不管前面是不是汉字 (训练规则要求前面不是汉字, 「找李经理确认」认不出)。
     * 只用于外发: 代号可还原, 误认一个词只是多一个代号。
     */
    private static final Pattern TITLED_NAME = Pattern.compile(
            "[" + TrainingSampleRules.SURNAMES + "][" + TrainingSampleRules.HAN + "]?"
            + "(?:经理|总监|老板|师傅|厂长|主任|科长|部长|组长|班长|主管|先生|女士|小姐)");

    private static final String HIDDEN = "[已隐藏]";

    private enum Kind {
        CUSTOMER("客户"), SUPPLIER("供应商"), ORG("单位"), PERSON("人员"), NAME("名称"), PRODUCT("保密产品"),
        PHONE("电话"), ID_NUMBER("证件号"), CARD("卡号");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

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

        /** 把模型回答里的代号换回真名。 */
        public String restore(String text) {
            if (text == null || tokenToReal.isEmpty()) {
                return text;
            }
            String out = text;
            for (Map.Entry<String, String> e : tokenToReal.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
            }
            return out;
        }

        /**
         * 模型回的是 JSON 文本、而解析在别处做时: 换回的真名按 JSON 字符串转义 (代号总是出现在 JSON 字符串里),
         * 真名带引号 / 反斜杠也不会弄坏 JSON。
         */
        public String restoreJsonText(String json) {
            if (json == null || tokenToReal.isEmpty()) {
                return json;
            }
            String out = json;
            for (Map.Entry<String, String> e : tokenToReal.entrySet()) {
                out = out.replace(e.getKey(),
                        new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(e.getValue())));
            }
            return out;
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
        Object tree = null;
        if (data != null) {
            try {
                tree = toPlainTree(objectMapper, data);
            } catch (RuntimeException e) {
                return new Result(null, Map.of(), "结果类型无法展开", 0);
            }
        }
        Session s = new Session(protectedNames);
        if (knownParties != null) {
            for (Map.Entry<String, Party> e : knownParties.entrySet()) {
                String real = e.getKey() == null ? "" : e.getKey().trim();
                if (real.length() >= 2 && !GENERIC.contains(real)) {
                    s.token(e.getValue().kind, real);
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
        for (String text : scrubbed) {
            cleanedTexts.add(text == null ? null : (String) s.replaceKnownEverywhere(text));
        }
        List<Object> all = new ArrayList<>(cleanedTexts);
        all.add(cleanedTree);
        String leak = s.findLeak(all);
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
        int dropped;
        String unsupported;

        Session(Collection<String> names) {
            if (names != null) {
                for (String n : names) {
                    if (n != null && n.trim().length() >= ProcessSecretSampleFilter.MIN_NAME_LENGTH) {
                        protectedNames.add(n.trim());
                    }
                }
                protectedNames.sort(Comparator.comparingInt(String::length).reversed());
            }
        }

        String token(Kind kind, String real) {
            String existing = realToToken.get(real);
            if (existing != null) {
                return existing;
            }
            int n = counters.merge(kind, 1, Integer::sum);
            String t = "[" + kind.label + letters(n) + "]";
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
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (isDropKey(k)) {
                        dropped++;
                        continue;
                    }
                    out.put(k, walk(e.getValue(), k, key));
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
                return wholeNumberLooksPrivate(v, key) || MOBILE_LOOSE.matcher(v).find() ? HIDDEN : value;
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
            String out = TrainingSampleRules.ID_CARD.matcher(value).replaceAll(HIDDEN);
            out = TrainingSampleRules.BANK_CARD.matcher(out).replaceAll(HIDDEN);
            out = MOBILE_LOOSE.matcher(out).replaceAll(HIDDEN);
            out = LANDLINE.matcher(out).replaceAll(HIDDEN);
            out = TrainingSampleRules.PHONE.matcher(out).replaceAll(HIDDEN);
            out = replaceSpans(out, TrainingSampleRules.ORG_NAME, Kind.ORG);
            out = replaceSpans(out, TrainingSampleRules.PERSON, Kind.PERSON);
            return out;
        }

        /** 文本模式: 已知真名 / 保密产品名先换, 再把号码换成可还原的代号, 按需再认公司名与人名。 */
        String scrubText(String value, boolean detectNames) {
            String out = replaceKnownText(value, spreadableReals());
            out = replaceSpans(out, TrainingSampleRules.ID_CARD, Kind.ID_NUMBER);
            out = replaceSpans(out, TrainingSampleRules.BANK_CARD, Kind.CARD);
            out = replaceSpans(out, MOBILE_LOOSE, Kind.PHONE);
            out = replaceSpans(out, LANDLINE, Kind.PHONE);
            out = replaceSpans(out, TrainingSampleRules.PHONE, Kind.PHONE);
            if (detectNames) {
                out = replaceSpans(out, TrainingSampleRules.ORG_NAME, Kind.ORG);
                out = replaceSpans(out, TrainingSampleRules.PERSON, Kind.PERSON);
                out = replaceSpans(out, TITLED_NAME, Kind.PERSON);
            }
            return out;
        }

        String replaceSpans(String text, Pattern pattern, Kind kind) {
            Matcher m = pattern.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token(kind, m.group())));
            }
            m.appendTail(sb);
            return sb.toString();
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
         * 哪些真名要在其它字段里也换掉 (以及最后一遍要查):
         * 人 / 客户 / 供应商 / 单位 / 保密产品 —— 包括 wangjg 这类拼音登录名 (UserCreateTool 会在 message 里重复它);
         * 按字段推断出的「名称」代号不扩散 (多为状态 / 说明短语, 扩散会把「合格」换进「合格率」)。
         * 纯数字、过短的不扩散。
         */
        boolean replaceableEverywhere(String real) {
            Kind kind = realKind.get(real);
            if (kind == null || kind == Kind.NAME || real.length() < 2 || real.matches("\\d+")) {
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
                if (!out.contains(real)) {
                    continue;
                }
                out = isAscii(real)
                        ? asciiWord(real).matcher(out).replaceAll(Matcher.quoteReplacement(realToToken.get(real)))
                        : out.replace(real, realToToken.get(real));
            }
            return out;
        }

        /** 拼音 / 英文名按整词匹配 (wangjg 不该匹配 wangjgx), 中文按子串。 */
        static boolean isAscii(String s) {
            return s.chars().allMatch(c -> c < 128);
        }

        static Pattern asciiWord(String real) {
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(real) + "(?![A-Za-z0-9_])");
        }

        boolean stillContains(String s, String real) {
            return isAscii(real) ? asciiWord(real).matcher(s).find() : s.contains(real);
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
                if (TrainingSampleRules.PHONE.matcher(s).find() || MOBILE_LOOSE.matcher(s).find()) return "残留手机号";
                if (TrainingSampleRules.ID_CARD.matcher(s).find()) return "残留证件号";
                // 编码类字符串 (批次号 / 单号) 不按「含座机 / 含卡号」判, 只看整个值 (卡号已在字段级按字段名处理)
                if (Session.isCode(s)) {
                    if (LANDLINE_WHOLE.matcher(s.trim()).matches()) return "残留电话号码";
                } else {
                    if (LANDLINE.matcher(s).find()) return "残留电话号码";
                    if (TrainingSampleRules.BANK_CARD.matcher(s).find()) return "残留银行卡号";
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
