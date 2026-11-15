package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.SameNameMatches;
import com.cretas.aims.ai.tool.impl.crm.CustomerCrmStatusQueryTool;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 口径规则 8: 同名列表不能被各意图的格式化模板吞掉。
 *
 * <p>考勤历史走入口测试时, 模板返回 null 后会退回到工具 message, 所以那条路证明不了格式化里的同名分支
 * (变异 M3 去掉分支后入口测试仍绿)。这里钉的是模板<b>会</b>自己出字的意图 (HR 今日考勤、CRM 客户),
 * 去掉分支时回复会变成模板文字, 本类变红。
 */
@DisplayName("口径 8: 同名列表优先于意图模板")
class SameNameReplyFormatterContractTest {

    private final ResultFormatterServiceImpl formatter = new ResultFormatterServiceImpl();

    @Test
    @DisplayName("HR 今日考勤: 两个张三都列出、标车间, 不被考勤模板改写")
    void attendanceTemplateDoesNotSwallowTheSameNameList() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employeeName", "张三");
        data.put("matchCount", 2);
        data.put("employees", List.of(Map.of("userId", 11L, "result", Map.of()), Map.of("userId", 12L, "result", Map.of())));
        result.put("data", data);
        SameNameMatches.annotate(result, "员工", "张三", 2, List.of(
                new SameNameMatches.Match(11L, "张三", "车间/部门：加工一车间，工号 011", "今天上班打卡 08:01，还没有下班打卡"),
                new SameNameMatches.Match(12L, "张三", "车间/部门：包装车间，工号 027", "今天还没有打卡记录")));
        result.put("message", "查询成功");

        String text = formatter.format(IntentExecuteResponse.builder().intentCode("ATTENDANCE_TODAY")
                .intentCategory("HR").status("SUCCESS").message("查询成功").resultData(result).build());

        assertThat(text).contains("本厂有 2 位叫「张三」的员工")
                .contains("1. 张三（车间/部门：加工一车间，工号 011）：今天上班打卡 08:01")
                .contains("2. 张三（车间/部门：包装车间，工号 027）：今天还没有打卡记录");
    }

    @Test
    @DisplayName("CRM 客户状态: 两个同名的华联超市 → 都列出 (区分字段来自实体解析: 客户编码 / 地址), 华联批发不算同名")
    void crmStatusListsEveryMatchingCustomer() {
        CustomerService customers = mock(CustomerService.class);
        when(customers.getCustomerById(eq("F1"), eq("c1")))
                .thenReturn(CustomerDTO.builder().id("c1").name("华联超市").customerCode("CU001").build());
        when(customers.getCustomerById(eq("F1"), eq("c2")))
                .thenReturn(CustomerDTO.builder().id("c2").name("华联超市").customerCode("CU002").build());
        CustomerCrmStatusQueryTool tool = crmTool(customers);

        Map<String, Object> result = run(tool, "华联超市");
        String text = formatter.format(IntentExecuteResponse.builder().intentCode("CUSTOMER_QUERY")
                .intentCategory("CRM").status("SUCCESS").message(String.valueOf(result.get("message")))
                .resultData(result).build());

        assertThat(text).contains("本厂有 2 个名称匹配「华联超市」的客户")
                .contains("华联超市（客户编码 CU001，地址：中山路 8 号）")
                .contains("华联超市（客户编码 CU002，地址：解放路 3 号）")
                .doesNotContain("华联批发").doesNotContain("请提供 customerId");
        verify(customers, never()).searchCustomersByName(anyString(), anyString());
    }

    @Test
    @DisplayName("CRM 客户状态: 「华联」全名零命中 → 三个包含候选都列出让人选, 不自动选 (不查任何一家的 CRM)")
    void crmStatusDoesNotContainsMatch() {
        CustomerService customers = mock(CustomerService.class);
        Map<String, Object> result = run(crmTool(customers), "华联");
        String text = format(result);
        System.out.println("[same-name] 华联 -> " + text.replace("\n", " | "));

        // 性质: 没解析出任何一家 (matches 为 0), 候选全部列出带区分字段, 明说没替用户选
        assertThat(result).containsKey(SameNameMatches.KEY);
        assertThat(((Map<?, ?>) result.get(SameNameMatches.KEY)).get("total")).isEqualTo(0);
        assertThat(text).contains("你是不是要找")
                .contains("1. 华联超市（客户编码 CU001）")
                .contains("2. 华联超市（客户编码 CU002）")
                .contains("3. 华联批发（客户编码 CU003）")
                .contains("没有替你选")
                // 候选只给名称 + 编码, 不带地址 / 电话 (同名多个命中时才带, 见 crmStatusListsEveryMatchingCustomer)
                .doesNotContain("中山路").doesNotContain("解放路").doesNotContain("地址");
        verify(customers, never()).getCustomerById(anyString(), anyString());
        verify(customers, never()).searchCustomersByName(anyString(), anyString());
    }

    @Test
    @DisplayName("阴性对照: 毫不相干的名字 → 未找到 (不给候选); 只有一家的简称「老张家」→ 列这一家当候选, 照样不自动选")
    void crmSuggestionNegativeControls() {
        CustomerService customers = mock(CustomerService.class);
        CustomerCrmStatusQueryTool tool = crmTool(customers);

        Map<String, Object> unrelated = run(tool, "远方贸易");
        assertThat(String.valueOf(unrelated.get("message"))).contains("未找到客户");
        assertThat(unrelated).doesNotContainKey(SameNameMatches.KEY);

        Map<String, Object> single = run(tool, "老张家");
        String text = format(single);
        assertThat(text).contains("你是不是要找").contains("1. 老张家卤味店（客户编码 CU004）")
                .doesNotContain("2. ").doesNotContain("华联");
        verify(customers, never()).getCustomerById(anyString(), anyString());
    }

    private String format(Map<String, Object> result) {
        return formatter.format(IntentExecuteResponse.builder().intentCode("CUSTOMER_QUERY")
                .intentCategory("CRM").status("SUCCESS").message(String.valueOf(result.get("message")))
                .resultData(result).build());
    }

    private static CustomerCrmStatusQueryTool crmTool(CustomerService customers) {
        FactoryEntityDirectoryRepository directory = mock(FactoryEntityDirectoryRepository.class);
        when(directory.findCustomerRows("F1")).thenReturn(List.of(
                new Object[]{"c1", "华联超市", "CU001", "中山路 8 号", null, null, true},
                new Object[]{"c2", "华联超市", "CU002", "解放路 3 号", null, null, true},
                new Object[]{"c3", "华联批发", "CU003", null, null, null, true},
                new Object[]{"c4", "老张家卤味店", "CU004", null, null, null, true}));
        CustomerCrmStatusQueryTool tool = new CustomerCrmStatusQueryTool();
        ReflectionTestUtils.setField(tool, "customerService", customers);
        ReflectionTestUtils.setField(tool, "entityResolver", new FactoryEntityResolver(directory, null));
        return tool;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(CustomerCrmStatusQueryTool tool, String name) {
        Map<String, Object> params = new HashMap<>();
        params.put("customerName", name);
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                tool, "doExecute", "F1", params, Map.of("userId", 1L));
    }
}
