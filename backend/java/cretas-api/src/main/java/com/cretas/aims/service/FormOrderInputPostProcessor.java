package com.cretas.aims.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 销售/采购订单 AI 录入：模型解析结果的确定性校正。
 *
 * <p>2026-11-18 演示前实测，模型对这两类订单有四种系统性偏差：
 * <ol>
 *   <li>相对日期算错（下周五→周四）——用 {@link FormRelativeDateResolver} 覆盖；</li>
 *   <li>产品/物料名被截断，丢「演示·」前缀和「250g」规格——按用户原文把名字补全到原文里连着的完整写法；</li>
 *   <li>把物料名的「演示·」前缀当供应商——用户没说供应商时清空并要求补充；</li>
 *   <li>用户没说单价却写 0——删掉，禁止假数据。</li>
 * </ol>
 * 只在能从用户原文确证时才改，认不准就保持模型输出不动。
 */
public final class FormOrderInputPostProcessor {

    private FormOrderInputPostProcessor() {}

    private static final Pattern SPEC_SUFFIX =
            Pattern.compile("^\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:kg|KG|Kg|g|G|ml|mL|ML|L|l|克|千克|公斤|毫升|升|斤)(?:\\s*[/*xX×]\\s*[0-9]+\\s*(?:袋|盒|包|瓶|罐|支|个))?");
    private static final Pattern DOT_PREFIX =
            Pattern.compile("([\\u4e00-\\u9fa5]{1,6})[·・•]$");
    private static final Pattern LEADING_VERB = Pattern.compile("^(?:采购|购买|订购|创建|新建|下单|给|买|订|进|要|向|从)+");
    private static final String UNIT_CHARS = "盒袋箱个只件包瓶桶罐份条块";
    private static final Pattern PRICE_WORDS = Pattern.compile("单价|元|块钱|价格|免费|赠送|￥|¥");

    /** 返回 true 表示改动了 values / missing。 */
    public static boolean apply(String userInput, String entityType, Map<String, Object> values,
                                List<String> missingRequired, LocalDate today) {
        if (userInput == null || values == null || entityType == null) return false;
        boolean purchase = "PURCHASE_ORDER".equals(entityType);
        boolean sales = "SALES_ORDER".equals(entityType);
        if (!purchase && !sales) return false;
        boolean changed = false;

        // 1) 交货日期
        String dateKey = purchase ? "expectedDeliveryDate" : "requiredDeliveryDate";
        var resolved = FormRelativeDateResolver.resolveDelivery(userInput, today);
        if (resolved.isPresent() && !resolved.get().toString().equals(String.valueOf(values.get(dateKey)))) {
            values.put(dateKey, resolved.get().toString());
            changed = true;
        }

        // 2) 采购：供应商其实是物料名前缀
        if (purchase && values.get("supplierName") instanceof String s && !s.isBlank()
                && isOnlyUsedAsNamePrefix(userInput, s.trim())) {
            values.remove("supplierName");
            if (missingRequired != null && !missingRequired.contains("supplierName")) {
                missingRequired.add("supplierName");
            }
            changed = true;
        }

        // 3) 明细：名字补全 + 单价 0 清掉
        String nameKey = purchase ? "materialName" : "productName";
        if (values.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) raw;
                if (item.get(nameKey) instanceof String name && !name.isBlank()) {
                    String full = restoreFullName(userInput, name.trim());
                    if (!full.equals(name)) {
                        item.put(nameKey, full);
                        changed = true;
                    }
                }
                Object price = item.get("unitPrice");
                if (price instanceof Number n && n.doubleValue() == 0.0 && !PRICE_WORDS.matcher(userInput).find()) {
                    item.remove("unitPrice");
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** supplier 在原文里每一次出现都紧跟「·」= 它是名字前缀而不是供应商。 */
    static boolean isOnlyUsedAsNamePrefix(String input, String supplier) {
        if (supplier.isEmpty()) return false;
        int idx = input.indexOf(supplier);
        if (idx < 0) return false;
        boolean any = false;
        while (idx >= 0) {
            int end = idx + supplier.length();
            if (end >= input.length() || "·・•".indexOf(input.charAt(end)) < 0) return false;
            any = true;
            idx = input.indexOf(supplier, end);
        }
        return any;
    }

    /**
     * 在原文里找到 name，向右吞并紧跟的规格（250g），向左吞并紧贴的「XX·」前缀。
     * name 在原文里找不到、或出现多次含义不一致时原样返回。
     */
    static String restoreFullName(String input, String name) {
        int idx = input.indexOf(name);
        if (idx < 0) return name;
        int start = idx;
        int end = idx + name.length();

        Matcher spec = SPEC_SUFFIX.matcher(input.substring(end));
        if (spec.find() && spec.start() == 0) {
            end += spec.end();
        }
        Matcher dot = DOT_PREFIX.matcher(input.substring(0, start));
        if (dot.find()) {
            String prefix = dot.group(1);
            int prefixStart = dot.start(1);
            // 「100盒演示·」：数字+单位字不属于名字
            if (prefixStart > 0 && Character.isDigit(input.charAt(prefixStart - 1))
                    && !prefix.isEmpty() && UNIT_CHARS.indexOf(prefix.charAt(0)) >= 0) {
                prefix = prefix.substring(1);
                prefixStart += 1;
            }
            // 动词紧贴前缀（「采购演示·」「创建演示·」）：动词不属于名字
            Matcher verb = LEADING_VERB.matcher(prefix);
            if (verb.find()) {
                prefixStart += verb.end();
                prefix = prefix.substring(verb.end());
            }
            if (!prefix.isEmpty()) {
                start = prefixStart;
            }
        }
        return input.substring(start, end);
    }

    /** 测试辅助：列出原文里的相对日期短语。 */
    static List<String> phrases(String input, LocalDate today) {
        List<String> out = new ArrayList<>();
        FormRelativeDateResolver.findAll(input, today).forEach(h -> out.add(h.phrase()));
        return out;
    }
}
