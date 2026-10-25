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

    @Test
    void lettersSequence() {
        assertThat(LlmEgressRedactor.letters(1)).isEqualTo("A");
        assertThat(LlmEgressRedactor.letters(26)).isEqualTo("Z");
        assertThat(LlmEgressRedactor.letters(27)).isEqualTo("AA");
    }
}
