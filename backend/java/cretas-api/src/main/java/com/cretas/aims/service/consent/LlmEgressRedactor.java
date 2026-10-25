package com.cretas.aims.service.consent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询结果发给外部大模型之前的脱敏 (官网「数据安全」: AI 助手发给模型前脱敏, 脱敏不了就不发)。
 *
 * <p>规则 (与训练样本规则同一套识别式, 见 {@link TrainingSampleRules}):
 * <ul>
 *     <li>人名、客户 / 供应商 / 单位名 → 换成代号 {@code [客户A]} {@code [人员B]}; 模型回答后
 *         {@link Result#restore} 换回真名再给用户看。代号里没有数字, 不干扰数字出处闸。</li>
 *     <li>产品、物料、设备、规则这类业务对象名保留 (否则摘要没法用), 但工艺保密产品名也换代号。</li>
 *     <li>电话、邮箱、地址、证件、银行卡、备注 / 说明这类自由文本字段整个不发。</li>
 *     <li>其余字符串里的手机号 / 身份证 / 银行卡号抹掉, 单位名与「角色+人名」换代号。</li>
 * </ul>
 *
 * <p>发出前对整段结果再扫一遍: 还残留号码、真名或保密产品名就拒绝 ({@link Result#refused()}),
 * 调用方必须改用本机结果, 不得把原始数据发出去。
 */
public final class LlmEgressRedactor {

    /** 整个不发的字段: 联系方式、证件、地址、自由文本。 */
    private static final Pattern DROP_KEY = Pattern.compile(
            "(?i)^(phone|mobile|tel|telephone|fax|email|mail|wechat|weixin|qq|idcard|idno|idnumber|identitycard"
            + "|bankaccount|bankcard|accountno|address|password|token|secret|description|memo|feedback)$"
            + "|(?i)^.*(phone|mobile|email|address|idcard|bankcard|bankaccount|remarks?|notes?|comments?)$");

    /** 业务对象名: 保留。 */
    private static final Pattern KEEP_NAME_KEY = Pattern.compile(
            "(?i)^(product|producttype|filterproduct|material|rawmaterial|materialtype|matchedmaterial|ingredient"
            + "|additive|sku|item|equipment|device|rule|intent|process|workprocess|procedure|checkpoint|mode|tool"
            + "|skill|feature|task|strategy|template|sheet|sample|role|newrole|ratio|protocol|page|model|leavetype"
            + "|filter|category|type|warehouse|location|unit|department|stage|step|indicator|metric|batch|status"
            + "|statusdisplay|line|workshop|handlerbean|plan|report|module|field|column|chart|kpi)name$");

    /** 指向人 / 交易对象的字段 (值为字符串时换代号)。 */
    private static final Pattern PARTY_KEY = Pattern.compile(
            "(?i)^.*(name|username|person|contact|operator|owner|manager|supervisor|leader|customer|client|supplier"
            + "|vendor|worker|employee|staff|inspector|handler|approver|assignee|recorder|requestor|requester"
            + "|creator|createdby|updatedby|initiatedby|signer|receiver|recipient|sender|driver|buyer|purchaser"
            + "|salesman|salesperson|consignee|shipper|carrier|merchant|partner|company|factory|store|shop)$");

    /** 「name」这个裸字段: 祖先字段名里出现这些词就当业务对象名。 */
    private static final Pattern BUSINESS_CONTEXT = Pattern.compile(
            "(?i)(product|material|ingredient|additive|sku|item|equipment|device|rule|intent|process|procedure"
            + "|checkpoint|tool|skill|template|category|type|warehouse|location|unit|stage|step|indicator|metric"
            + "|batch|line|workshop|recipe|bom|plan|report|module|chart|kpi)");

    private static final String HIDDEN = "[已隐藏]";

    private enum Kind {
        CUSTOMER("客户"), SUPPLIER("供应商"), ORG("单位"), PERSON("人员"), NAME("名称"), PRODUCT("保密产品");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 脱敏结果。 */
    public static final class Result {
        private final String payload;
        private final Map<String, String> tokenToReal;
        private final String refusal;
        private final int droppedFields;

        Result(String payload, Map<String, String> tokenToReal, String refusal, int droppedFields) {
            this.payload = payload;
            this.tokenToReal = tokenToReal;
            this.refusal = refusal;
            this.droppedFields = droppedFields;
        }

        /** 可以发给模型的 JSON; {@link #refused()} 时为 null。 */
        public String payload() {
            return payload;
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
    }

    private final ObjectMapper objectMapper;

    public LlmEgressRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param data           要发给模型的结构化结果 (Map / List / 标量)
     * @param protectedNames 本厂工艺保密产品名 (可为空)
     */
    public Result redact(Object data, Collection<String> protectedNames) {
        Session s = new Session(protectedNames);
        Object cleaned = s.walk(data, "", new ArrayList<>());
        cleaned = s.replaceKnownEverywhere(cleaned);
        String leak = s.findLeak(cleaned);
        if (leak != null) {
            return new Result(null, Map.of(), leak, s.dropped);
        }
        try {
            return new Result(objectMapper.writeValueAsString(cleaned), s.tokenToReal, null, s.dropped);
        } catch (JsonProcessingException e) {
            return new Result(null, Map.of(), "序列化失败", s.dropped);
        }
    }

    private static final class Session {
        final Map<String, String> realToToken = new LinkedHashMap<>();
        final Map<String, String> tokenToReal = new LinkedHashMap<>();
        final Map<Kind, Integer> counters = new LinkedHashMap<>();
        final List<String> protectedNames = new ArrayList<>();
        int dropped;

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
            return t;
        }

        @SuppressWarnings("unchecked")
        Object walk(Object node, String key, List<String> ancestors) {
            if (node instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (DROP_KEY.matcher(k).matches()) {
                        dropped++;
                        continue;
                    }
                    List<String> path = new ArrayList<>(ancestors);
                    path.add(key);
                    out.put(k, walk(e.getValue(), k, path));
                }
                return out;
            }
            if (node instanceof Iterable<?> items) {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(walk(item, key, ancestors));
                }
                return out;
            }
            if (node instanceof CharSequence cs) {
                return string(cs.toString(), key, ancestors);
            }
            return node;
        }

        String string(String value, String key, List<String> ancestors) {
            String v = value.trim();
            if (v.isEmpty()) {
                return value;
            }
            Kind party = partyKind(key, ancestors);
            if (party != null) {
                return token(party, v);
            }
            return scrubFreeText(value);
        }

        /** null = 不是指向人 / 交易对象的字段。 */
        Kind partyKind(String key, List<String> ancestors) {
            if (key == null || key.isEmpty() || KEEP_NAME_KEY.matcher(key).matches()) {
                return null;
            }
            String k = key.toLowerCase();
            if ("name".equals(k)) {
                String context = String.join("/", ancestors).toLowerCase();
                if (context.matches(".*(customer|client|consignee|store|shop).*")) return Kind.CUSTOMER;
                if (context.matches(".*(supplier|vendor).*")) return Kind.SUPPLIER;
                if (context.matches(".*(company|factory|merchant|partner|carrier|org).*")) return Kind.ORG;
                if (context.matches(".*(user|employee|staff|worker|operator|person|member|contact|inspector).*")) {
                    return Kind.PERSON;
                }
                // 祖先是产品 / 物料这类业务对象: 保留; 判断不了的一律当名称换代号 (宁可多换)
                return BUSINESS_CONTEXT.matcher(context).find() ? null : Kind.NAME;
            }
            if (!PARTY_KEY.matcher(key).matches()) {
                return null;
            }
            if (k.matches(".*(customer|client|consignee|store|shop).*")) return Kind.CUSTOMER;
            if (k.matches(".*(supplier|vendor).*")) return Kind.SUPPLIER;
            if (k.matches(".*(company|factory|merchant|partner|carrier).*")) return Kind.ORG;
            if (k.endsWith("name") && !k.matches(".*(user|full|real|nick|person|contact|operator|owner|manager"
                    + "|supervisor|leader|worker|employee|staff|inspector|handler|approver|assignee|recorder"
                    + "|requestor|requester|creator|signer|receiver|recipient|sender|driver|buyer|purchaser"
                    + "|salesman|salesperson|shipper|responsible).*")) {
                return Kind.NAME;
            }
            return Kind.PERSON;
        }

        String scrubFreeText(String value) {
            String out = TrainingSampleRules.ID_CARD.matcher(value).replaceAll(HIDDEN);
            out = TrainingSampleRules.BANK_CARD.matcher(out).replaceAll(HIDDEN);
            out = TrainingSampleRules.PHONE.matcher(out).replaceAll(HIDDEN);
            out = replaceSpans(out, TrainingSampleRules.ORG_NAME, Kind.ORG);
            out = replaceSpans(out, TrainingSampleRules.PERSON, Kind.PERSON);
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
            if (node instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.put(replaceKnown(String.valueOf(e.getKey())), replaceKnownEverywhere(e.getValue()));
                }
                return out;
            }
            if (node instanceof List<?> items) {
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(replaceKnownEverywhere(item));
                }
                return out;
            }
            if (node instanceof String str) {
                return replaceKnown(str);
            }
            return node;
        }

        String replaceKnown(String text) {
            String out = text;
            for (String name : protectedNames) {
                if (out.contains(name)) {
                    out = out.replace(name, token(Kind.PRODUCT, name));
                }
            }
            List<String> reals = new ArrayList<>(realToToken.keySet());
            reals.sort(Comparator.comparingInt(String::length).reversed());
            for (String real : reals) {
                if (real.length() >= 2 && out.contains(real)) {
                    out = out.replace(real, realToToken.get(real));
                }
            }
            return out;
        }

        /** 发出前最后一遍: 残留号码 / 真名 / 保密产品名 → 返回原因 (不含原始值); 干净返回 null。 */
        String findLeak(Object node) {
            List<String> strings = new ArrayList<>();
            collectStrings(node, strings);
            for (String s : strings) {
                if (TrainingSampleRules.PHONE.matcher(s).find()) return "残留手机号";
                if (TrainingSampleRules.ID_CARD.matcher(s).find()) return "残留证件号";
                if (TrainingSampleRules.BANK_CARD.matcher(s).find()) return "残留银行卡号";
                for (String name : protectedNames) {
                    if (s.contains(name)) return "残留工艺保密产品名";
                }
                for (String real : realToToken.keySet()) {
                    if (real.length() >= 2 && s.contains(real)) return "残留已脱敏的名称";
                }
            }
            return null;
        }

        void collectStrings(Object node, List<String> out) {
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.add(String.valueOf(e.getKey()));
                    collectStrings(e.getValue(), out);
                }
            } else if (node instanceof List<?> items) {
                for (Object item : items) {
                    collectStrings(item, out);
                }
            } else if (node instanceof String str) {
                out.add(str);
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
