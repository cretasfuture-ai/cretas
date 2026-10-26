package com.cretas.aims.service.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LlmEgressRedactorTest {

    private final LlmEgressRedactor redactor = new LlmEgressRedactor(new ObjectMapper());

    /** 形状照 DAILY_CUSTOMER_FOLLOWUP / 采购周计划这类工作台编排结果。 */
    private static Map<String, Object> workdeskResult() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerName", "张记食品有限公司");
        customer.put("contactPerson", "王建国");
        customer.put("phone", "13812345678");
        customer.put("shippingAddress", "上海市松江区某路 88 号");
        customer.put("receivableAmount", 12800);
        customer.put("notes", "王总说下周付款");

        Map<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("supplierName", "华东冷链商贸");
        supplier.put("materialName", "猪前腿肉");
        supplier.put("quantity", 300);

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchNumber", "PB-20261025-0007");
        batch.put("productName", "五香虎皮猪肘");
        batch.put("operatorName", "李四");
        batch.put("status", "IN_PROGRESS");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customers", List.of(customer));
        result.put("suppliers", List.of(supplier));
        result.put("batches", List.of(batch));
        result.put("users", List.of(Map.of("name", "赵六", "role", "仓管")));
        result.put("products", List.of(Map.of("name", "卤猪蹄", "stock", 42)));
        result.put("summary", "张记食品有限公司本月下单 3 次, 联系人王建国, 电话 13812345678");
        return result;
    }

    @Test
    void namesBecomeTokensAndContactFieldsAreNotSent() {
        LlmEgressRedactor.Result r = redactor.redact(workdeskResult(), Set.of());

        assertThat(r.refused()).isFalse();
        String sent = r.payload();
        for (String real : List.of("张记食品有限公司", "王建国", "13812345678", "华东冷链商贸", "李四", "赵六",
                "上海市松江区", "王总说下周付款")) {
            assertThat(sent).as("不应发出: " + real).doesNotContain(real);
        }
        assertThat(sent).contains("[客户A]", "[供应商A]", "[人员");
        // 联系方式 / 地址 / 备注字段整个不发
        assertThat(sent).doesNotContain("\"phone\"", "\"shippingAddress\"", "\"notes\"");
        assertThat(r.droppedFields()).isEqualTo(3);
    }

    @Test
    void businessObjectNamesAndNumbersAreKept() {
        String sent = redactor.redact(workdeskResult(), Set.of()).payload();

        assertThat(sent).contains("猪前腿肉", "五香虎皮猪肘", "卤猪蹄", "PB-20261025-0007", "IN_PROGRESS");
        assertThat(sent).contains("12800", "300", "42");
        // 代号里没有数字: 不干扰数字出处闸
        Matcher m = Pattern.compile("\\[(客户|供应商|单位|人员|名称|保密产品)[^\\]\"]*]").matcher(sent);
        int tokens = 0;
        while (m.find()) {
            tokens++;
            assertThat(m.group()).doesNotContainPattern("\\d");
        }
        assertThat(tokens).isGreaterThanOrEqualTo(5);
    }

    @Test
    void theSameRealNameAnywhereGetsTheSameToken() {
        LlmEgressRedactor.Result r = redactor.redact(workdeskResult(), Set.of());

        // summary 里的公司名和人名, 与 customerName / contactPerson 字段用同一个代号
        String sent = r.payload();
        assertThat(sent).contains("\"customerName\":\"[客户A]\"");
        assertThat(sent).contains("[客户A]本月下单 3 次");
        assertThat(sent).doesNotContain("张记食品", "电话 1");
    }

    @Test
    void restorePutsRealNamesBackIntoTheAnswer() {
        LlmEgressRedactor.Result r = redactor.redact(workdeskResult(), Set.of());

        String answer = "[客户A] 应收 12800 元, 联系人 [人员A]; 供应商 [供应商A] 待到货 300。";
        assertThat(r.restore(answer)).isEqualTo("张记食品有限公司 应收 12800 元, 联系人 王建国; 供应商 华东冷链商贸 待到货 300。");
    }

    @Test
    void processSecretProductNamesAreTokenisedEvenUnderProductName() {
        LlmEgressRedactor.Result r = redactor.redact(workdeskResult(), Set.of("五香虎皮猪肘"));

        assertThat(r.payload()).doesNotContain("五香虎皮猪肘").contains("[保密产品A]");
        assertThat(r.payload()).contains("卤猪蹄");
        assertThat(r.restore("[保密产品A] 在制")).isEqualTo("五香虎皮猪肘 在制");
    }

    @Test
    void refusesWhenSomethingCannotBeRedacted() {
        // 手机号做了字段名: 字段名不改写, 最后一遍扫到 → 拒绝, 调用方不得发出
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("13812345678", Map.of("orders", 2));

        LlmEgressRedactor.Result r = redactor.redact(data, Set.of());

        assertThat(r.refused()).isTrue();
        assertThat(r.payload()).isNull();
        assertThat(r.refusal()).isEqualTo("残留手机号").doesNotContain("138");
    }

    @Test
    void bareNameWithoutBusinessContextIsTokenised() {
        Map<String, Object> data = Map.of("rows", List.of(Map.of("name", "钱七", "count", 2)));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("钱七").contains("[名称A]");
    }

    // ---------------------------------------------------------------- 独立审查找出的形状

    /** SupplierPriceAlertTool / QualityCheckCreateTool 用中文字段名。 */
    @Test
    void chineseKeysArePartiesOrDropped() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("供应商", "和盛牛业");
        row.put("检验员", "孙八");
        row.put("备注", "孙八说明天到");
        row.put("请假原因", "家里有事");
        row.put("物料", "牛腩");
        row.put("涨幅", "12.5%");

        String sent = redactor.redact(Map.of("alerts", List.of(row)), Set.of()).payload();

        assertThat(sent).doesNotContain("和盛牛业", "孙八", "明天到", "家里有事", "备注", "请假原因");
        assertThat(sent).contains("[供应商A]", "[人员A]", "牛腩", "12.5%");
    }

    /** CustomerNotifyBatchTool 的 sampleCustomerNames 是字符串列表。 */
    @Test
    void pluralAndListKeysAreTokenised() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sampleCustomerNames", List.of("老王烧烤", "张记"));
        data.put("operators", List.of("钱七"));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("老王烧烤", "张记", "钱七").contains("[客户A]", "[客户B]", "[人员A]");
    }

    /** AlertAcknowledgeTool / AlertResolveTool 把登录名放在 acknowledgedBy / resolvedBy。 */
    @Test
    void byKeysAreTokenisedEvenWhenTheValueLooksLikeACode() {
        Map<String, Object> data = Map.of("acknowledgedBy", "zhangsan", "resolvedBy", "factory_demo");

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("zhangsan", "factory_demo").contains("[人员");
    }

    @Test
    void bareNameUsesTheNearestContainer() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("data", Map.of("items", List.of(Map.of("name", "王建国"))));
        data.put("batch", Map.of("supervisor", Map.of("name", "张三")));
        data.put("products", List.of(Map.of("name", "卤猪蹄")));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("王建国", "张三").contains("卤猪蹄");
    }

    @Test
    void moreContactAndFreeTextKeysAreDropped() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : List.of("phoneNumber", "contactTel", "mobileNo", "idCardNo", "bankAccountNo", "wechatId",
                "issueDescription", "customerFeedback", "reason", "content", "addr")) {
            data.put(key, "王建国 x");
        }
        data.put("orderCount", 3);

        LlmEgressRedactor.Result r = redactor.redact(data, Set.of());

        assertThat(r.payload()).isEqualTo("{\"orderCount\":3}");
        assertThat(r.droppedFields()).isEqualTo(11);
    }

    @Test
    void otherPhoneFormatsAreBlankedInTextAndANumericMobileRefuses() {
        String sent = redactor.redact(Map.of("message", "电话 138-1234-5678 / 021-58881234 / +8613812345678"),
                Set.of()).payload();
        assertThat(sent).doesNotContain("1234-5678", "58881234", "13812345678");

        LlmEgressRedactor.Result r = redactor.redact(Map.of("value", 13812345678L), Set.of());
        assertThat(r.refused()).isTrue();
        assertThat(r.refusal()).isEqualTo("残留手机号");
    }

    /** SkillExecutorImpl 把工具结果存成 JsonNode: 不能被拆成空列表, 也不能绕过脱敏。 */
    @Test
    void jsonNodeResultsAreRedactedNotEmptied() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = Map.of("customer_followup",
                mapper.readTree("{\"customers\":[{\"customerName\":\"张记食品有限公司\",\"receivableAmount\":12800}]}"));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).contains("[客户A]", "12800").doesNotContain("张记食品有限公司");
    }

    record EmployeeView(String fullName, String phone, String department, int monthlyOrders) {
    }

    @Test
    void pojoAndRecordFieldsAreRedactedNotSerialisedWhole() {
        Map<String, Object> data = Map.of("profile", new EmployeeView("王建国", "13812345678", "仓储部", 7));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("王建国", "13812345678").contains("[人员A]", "仓储部", "7");
    }

    @Test
    void genericWordsAndCodesAreNotSpreadAcrossFields() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resolvedBy", "系统");
        data.put("factory", "F006");
        data.put("batchNumber", "F006-20261025-0007");
        data.put("message", "系统已自动关闭告警");

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).contains("\"resolvedBy\":\"系统\"", "\"batchNumber\":\"F006-20261025-0007\"",
                "系统已自动关闭告警");
    }

    @Test
    void unknownKeysWithFreeTextAreTokenisedByDefault() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("progress", "老王那边进行中");
        data.put("progressCode", "IN_PROGRESS");

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("老王").contains("[名称A]", "IN_PROGRESS");
    }

    // ---------------------------------------------------------------- 第二轮审查

    /** UserCreateTool / ApprovalPendingQueryTool 在 message 里重复登录名。 */
    @Test
    void asciiPartyNamesAreReplacedInMessagesToo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", "wangjg");
        data.put("salesRep", "Zhang Wei");
        data.put("message", "用户创建成功，用户名: wangjg；对接销售 Zhang Wei；wangjgx 是另一个账号");

        LlmEgressRedactor.Result r = redactor.redact(data, Set.of());

        assertThat(r.refused()).isFalse();
        assertThat(r.payload()).doesNotContain("用户名: wangjg；", "Zhang Wei").contains("wangjgx");
        assertThat(r.restore(r.payload())).contains("用户名: wangjg；", "Zhang Wei");
    }

    /** SupplierBankAccountDTO.accountNumber; 卡号 / 座机做了整个值。 */
    @Test
    void bankCardsAndLandlinesAsWholeValuesAreHidden() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountNumber", "6222021234567890123");
        data.put("settlementNumber", "6222021234567890");
        data.put("contactNo", "021-58881234");
        data.put("orderId", "1792901944123456789");
        data.put("message", "座机 (021)58881234 或 02158881234");

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("6222021234567890", "58881234").contains("1792901944123456789");
    }

    /** 工作台的状态 / 阶段文字要让模型看得懂; 「单位」= 计量单位。 */
    @Test
    void statusDisplayTextAndMeasurementUnitAreKept() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("currentStatusDisplay", "待放行");
        row.put("stageDisplay", "报价中");
        row.put("质检结果", "不合格");
        row.put("风险等级", "高");
        row.put("单位", "kg");
        row.put("所属单位", "华东分公司");
        row.put("合格率", "92%");

        String sent = redactor.redact(Map.of("rows", List.of(row)), Set.of()).payload();

        assertThat(sent).contains("待放行", "报价中", "不合格", "\"kg\"", "合格率", "92%");
        assertThat(sent).doesNotContain("华东分公司");
    }

    // ------------------------------------------------------------------ 文本模式

    @Test
    void textModeTokenisesNumbersNamesAndKnownPartiesAcrossSegmentsAndRestores() {
        List<String> texts = new java.util.ArrayList<>();
        texts.add("上一轮: 已给老街坊下单, 由司机：钱大海送货");
        texts.add("老街坊的联系人：赵铁柱 手机 139-1234-5678, 座机 (021)58881234, 卡号 6222021234567890123");
        texts.add(null);
        texts.add("华东冷链商贸有限公司 的 秘制酱牛肉 身份证 31010119900101123X");

        LlmEgressRedactor.Result r = redactor.redactTexts(texts,
                Map.of("老街坊", LlmEgressRedactor.Party.CUSTOMER), Set.of("秘制酱牛肉"), true);

        assertThat(r.refused()).isFalse();
        String sent = String.join("\n", r.texts().subList(0, 2)) + "\n" + r.text(3);
        assertThat(sent).doesNotContain("老街坊", "钱大海", "赵铁柱", "139-1234-5678", "58881234",
                "6222021234567890123", "华东冷链商贸", "秘制酱牛肉", "31010119900101123X");
        assertThat(r.text(0)).contains("[客户A]");
        assertThat(r.text(2)).isNull();
        // 模型照抄代号, 用户看到的是原文
        assertThat(r.restore(r.text(1))).isEqualTo(texts.get(1));
        assertThat(r.restore(r.text(3))).isEqualTo(texts.get(3));
    }

    @Test
    void utteranceModeOnlyTokenisesNumbersAndKeepsWording() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("把客户张记食品有限公司的电话改成13812345678"),
                Map.of(), Set.of(), false);

        assertThat(r.text(0)).isEqualTo("把客户张记食品有限公司的电话改成[电话A]");
        assertThat(r.restore("{\"phone\":\"[电话A]\"}")).isEqualTo("{\"phone\":\"13812345678\"}");
    }

    @Test
    void streamRestorerWaitsForSplitTokensAndFlushesTheTail() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("联系人：王建国 13812345678"),
                Map.of(), Set.of(), true);
        List<String> out = new java.util.ArrayList<>();
        LlmEgressRedactor.StreamRestorer restorer = r.streamRestorer(out::add);

        for (String chunk : List.of("请找 [人", "员A]", " 电话 [电话A] [", "备注")) {
            restorer.accept(chunk);
        }
        restorer.flush();

        // 代号只盖住名字本身 (「联系人：」留在明文里), 还原回来就是名字
        assertThat(String.join("", out)).isEqualTo("请找 王建国 电话 13812345678 [备注");
        assertThat(out).noneMatch(s -> s.contains("[人") || s.contains("员A]"));
    }

    @Test
    void restoreJsonTextEscapesRealNamesWithQuotes() throws Exception {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("x"),
                Map.of("\"老王\"牛肉", LlmEgressRedactor.Party.CUSTOMER), Set.of(), false);

        String json = r.restoreJsonText("{\"customer\":\"[客户A]\"}");

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("customer").asText())
                .isEqualTo("\"老王\"牛肉");
    }

    @Test
    void structuredAndTextShareTokens() {
        Map<String, Object> directory = Map.of("users", List.of(Map.of("id", "42", "name", "赵铁柱")));

        LlmEgressRedactor.Result r = redactor.redact(directory, List.of("让赵铁柱审批"), Map.of(), Set.of(), true);

        assertThat(r.payload()).contains("[人员A]", "\"42\"").doesNotContain("赵铁柱");
        assertThat(r.text(0)).isEqualTo("让[人员A]审批");
    }

    @Test
    void titledNamesAfterChineseTextAreTokenisedToo() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("加一道卤制, 找李经理确认, 再问王师傅"),
                Map.of(), Set.of(), true);

        assertThat(r.text(0)).doesNotContain("李经理", "王师傅").contains("加一道卤制");
        assertThat(r.restore(r.text(0))).isEqualTo("加一道卤制, 找李经理确认, 再问王师傅");
    }

    // ------------------------------------------------------------------ 独立审查发现的漏洞 (2026-10-26)

    @Test
    void orgAndPersonTokensDoNotSwallowLeadingVerbsOrRoles() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of(
                "查一下永辉超市的订单", "供应商是华东冷链商贸有限公司", "质检员：赵铁柱 复检, 通知钱大海,"),
                Map.of(), Set.of(), true);

        assertThat(r.text(0)).startsWith("查一下[单位").endsWith("]的订单").doesNotContain("永辉");
        assertThat(r.text(1)).startsWith("供应商是[单位").doesNotContain("华东冷链");
        assertThat(r.text(2)).startsWith("质检员：[人员").contains("通知[人员").doesNotContain("赵铁柱", "钱大海");
        // 模型把代号填进字段时, 还原出来的是名字本身, 不带「供应商是」「质检员：」
        assertThat(r.restore("{\"supplier\":\"" + r.text(1).substring("供应商是".length()) + "\"}"))
                .isEqualTo("{\"supplier\":\"华东冷链商贸有限公司\"}");
        assertThat(r.restore(r.text(2).substring("质检员：".length(), "质检员：".length() + 5))).isEqualTo("赵铁柱");
    }

    @Test
    void namesInsideShopNamesAreNotCutInHalf() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("美的集团和和盛牛业有限公司"), Map.of(), Set.of(), true);

        assertThat(r.text(0)).doesNotContain("美的", "和盛");
    }

    @Test
    void otherNumberFormatsAreTokenisedAndCheckedBeforeSending() {
        String text = "卡 6222 0212 3456 7890 123, 证 310101 19900101 123X, 旧证 310101900101123, "
                + "座机 021-5888-1234 / 400-820-8820, 手机 138.0013.8000 / １３８００１３８０００";

        LlmEgressRedactor.Result r = redactor.redactTexts(List.of(text), Map.of(), Set.of(), false);

        assertThat(r.refused()).isFalse();
        assertThat(r.text(0)).doesNotContainPattern("[0-9０-９]{3}");
        assertThat(r.restore(r.text(0))).isEqualTo(text);
    }

    @Test
    void titledNamesWithSurnamesOutsideTheTrainingListAreTokenised() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("找牛经理和欧阳经理, 再问常师傅"), Map.of(), Set.of(), true);

        assertThat(r.text(0)).doesNotContain("牛经理", "欧阳经理", "常师傅");
    }

    @Test
    void shortFormsAndJsonEscapedFormsOfKnownNamesAreTokenisedToo() {
        LlmEgressRedactor.Result r = redactor.redactTexts(
                List.of("老街坊要求少盐", "{\"name\":\"\\\"老王\\\"牛肉\"}", "审批人 lw"),
                Map.of("老街坊餐饮有限公司", LlmEgressRedactor.Party.CUSTOMER,
                        "\"老王\"牛肉", LlmEgressRedactor.Party.CUSTOMER,
                        "lw", LlmEgressRedactor.Party.PERSON),
                Set.of(), true);

        assertThat(r.text(0)).isEqualTo("[客户A]要求少盐");
        assertThat(r.text(1)).doesNotContain("老王");
        assertThat(r.text(2)).doesNotContain("lw");
    }

    @Test
    void literalTokensAlreadyInTheInputAreNotReused() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("用户自己写了 [客户A], 客户是 张记食品有限公司"),
                Map.of("张记食品有限公司", LlmEgressRedactor.Party.CUSTOMER), Set.of(), true);

        assertThat(r.text(0)).contains("[客户A]").contains("[客户B]").doesNotContain("张记");
        assertThat(r.restore("[客户A]")).isEqualTo("[客户A]");
    }

    @Test
    void droppedFieldValuesAreHiddenWhenTheyAlsoAppearInThePromptText() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("customerName", "张记食品有限公司");
        record.put("shippingAddress", "上海市松江区某路 88 号");
        record.put("remark", "王总说下周付款");
        String prompt = "提交值: 张记食品有限公司 / 上海市松江区某路 88 号 / 王总说下周付款";

        LlmEgressRedactor.Result r = redactor.redact(record, List.of(prompt), Map.of(), Set.of(), true);

        assertThat(r.text(0)).doesNotContain("张记", "松江", "下周付款");
        assertThat(r.restore(r.text(0))).isEqualTo(prompt);
    }

    @Test
    void personDisplayFieldsAndPersonNamedMapKeysAreTokenised() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("displayName", "张三");
        row.put("assigneeDisplay", "李四");
        row.put("customerLabel", "老街坊");
        row.put("statusDisplay", "进行中");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", List.of(row));
        data.put("operatorCounts", Map.of("王五", 12));

        String sent = redactor.redact(data, Set.of()).payload();

        assertThat(sent).doesNotContain("张三", "李四", "老街坊", "王五").contains("进行中", "12");
    }

    @Test
    void textModeTokensCarryNoDigits() {
        LlmEgressRedactor.Result r = redactor.redactTexts(List.of("13812345678 13912345678 6222021234567890123"),
                Map.of(), Set.of(), true);

        assertThat(r.text(0)).doesNotContainPattern("[0-9]");
    }

    @Test
    void lettersSequence() {
        assertThat(LlmEgressRedactor.letters(1)).isEqualTo("A");
        assertThat(LlmEgressRedactor.letters(26)).isEqualTo("Z");
        assertThat(LlmEgressRedactor.letters(27)).isEqualTo("AA");
    }
}
