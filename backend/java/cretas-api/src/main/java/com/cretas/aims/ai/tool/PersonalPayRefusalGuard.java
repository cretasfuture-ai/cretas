package com.cretas.aims.ai.tool;

import java.util.regex.Pattern;

/**
 * 「每个工人的工资是多少」这类问法要的是个人工资明细: 对话 AI 不提供按人的工资 (人员隐私), 整轮拒答并指向汇总口径。
 * 放在识别之前 (IntentExecutionOrchestrator.executeInScope), 所以不管这句话落到哪个意图 / 哪个角色都一样
 * (2026-11-15 复跑 Q19: 财务号落进「资产负债表 / 会计科目」选项, 管理员号落进工资策略查询并回英文内部串)。
 * 只拒「人员名词 + 工资词」; 「工资策略 / 工资规则 / 总人工成本」这类汇总或规则问法不拒。
 */
public final class PersonalPayRefusalGuard {

    public static final String STATUS = "PERSONAL_PAY_REFUSED";

    public static final String MESSAGE = "按个人的工资明细涉及员工隐私，这里不提供。"
            + "可以问：本月总人工成本、人工成本占总成本多少，或工厂的工资计算方式（按件 / 按时 / 混合）。";

    private static final Pattern STAFF_PAY = Pattern.compile(
            "(工人|员工|职工|工友|师傅|操作员|操作工|人员|大家|每个人|每人|每位|每名|各人|所有人)(们)?(的|每月|每个月|月)?"
                    + "(工资|薪资|薪水|薪酬|月薪|底薪|奖金|提成|计件收入|收入)");
    private static final Pattern POLICY_OR_AGGREGATE = Pattern.compile(
            "(工资|薪资|薪酬)(策略|规则|模式|政策|方案|制度|结构|计算|成本|总额|合计|汇总|占比)|人工成本|工资总额|总工资");

    private PersonalPayRefusalGuard() {}

    public static boolean refuses(String userInput) {
        if (userInput == null) return false;
        return STAFF_PAY.matcher(userInput).find() && !POLICY_OR_AGGREGATE.matcher(userInput).find();
    }
}
