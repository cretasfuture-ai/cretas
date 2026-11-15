package com.cretas.aims.ai.tool;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #1767 第 3 轮口径表 A (1767-round3-spec.md): 个人工资拒答守卫。
 * 原则: 只拒「指向具体个人或逐人数额」的工资问题; 汇总 / 规则 / 口径一律放行。
 * 「各 / 每个」单独出现不算逐人, 必须紧跟人称名词 (每个工人 / 每位员工 / 各班组长 / 各人 / 每人)。
 * 表是完成标准: ⛔ 不为了过表改表, 一行一个断言。
 */
class PersonalPayRefusalTableContractTest {

    @ParameterizedTest(name = "拒: {0}")
    @ValueSource(strings = {
            "每个工人的工资多少",
            "每个工人的工资怎么算出来是多少",
            "每个员工的提成怎么算的，各拿了多少",
            "员工的提成怎么算的，各人拿了多少",
            "张三的工资多少",
            "张三工资怎么算",
            "员工A和员工B的工资",
            "各班组长工资明细",
            "列出所有人的工资",
            "每位员工的工资汇总",
            "每个员工的工资总额是多少"})
    void mustRefuse(String text) {
        assertTrue(PersonalPayRefusalGuard.refuses(text), "应拒答: " + text);
    }

    @ParameterizedTest(name = "放行: {0}")
    @ValueSource(strings = {
            "工资怎么算",
            "工人工资怎么算",
            "工资/奖金怎么算",
            "本月人工成本多少",
            "员工工资总额是多少",
            "每个月员工工资总额是多少",
            "各车间员工工资总额",
            "员工工资和奖金总额",
            "本月发了多少工资（总额）",
            "工资策略",
            "每个产品的毛利",
            "计件工资多少钱一件"})
    void mustPass(String text) {
        assertFalse(PersonalPayRefusalGuard.refuses(text), "应放行: " + text);
    }
}
