package com.cretas.aims.ai.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「每个工人的工资是多少」这类问法要的是个人工资明细: 对话 AI 不提供按人的工资 (人员隐私), 整轮拒答并指向汇总口径。
 * 放在识别之前 (普通 / 流式 / 多意图 / 显式意图四个入口), 所以不管这句话落到哪个意图 / 哪个角色都一样
 * (2026-11-15 复跑 Q19: 财务号落进「资产负债表 / 会计科目」选项, 管理员号落进工资策略查询并回英文内部串)。
 *
 * <p>口径 (#1767 第 3 轮口径表 A, 见 PersonalPayRefusalTableContractTest): 只拒「指向具体个人或逐人数额」的工资问题,
 * 汇总 / 规则 / 口径一律放行。逐个子句 (只按标点和「以及 / 并且 / 另外 / 然后 / 还有」切, ⛔ 不按「和 / 及 / 顿号 / 斜杠」切:
 * 「员工工资和奖金总额」是一个汇总问法) 判断, 子句里出现下面任一项且整句问的是工资就拒:
 * <ol>
 *   <li>逐人: 「每个 / 每位 / 每名 / 各 / 各位 …」<b>紧跟人称名词</b> (每个工人 / 每位员工 / 各班组长 / 各人 / 每人),
 *       或「分别 / 逐个 / 各自」; ⛔ 不用单字「各 / 每个」(「每个月 / 各车间」是汇总维度, 不是人);</li>
 *   <li>点名到人: 常见姓 + 1~2 字 (张三 / 王师傅 / 老李) 紧接工资词, 或「员工A / 工人甲」; 岗位 / 部门 / 时间词不算人名;</li>
 *   <li>要清单: 列出 / 明细 / 清单 / 名单 / 排行 / 谁;</li>
 *   <li>「人员名词 + 工资词」且该子句不是汇总 (总额 / 合计 / 人工成本) 也不是规则 (怎么算 / 策略) 问法 (「工人工资是多少」)。</li>
 * </ol>
 * 逐人 / 点名 / 清单不被汇总词或规则词豁免 (「每位员工的工资汇总」「每个工人的工资怎么算出来是多少」仍拒);
 * 组合句「员工的提成怎么算的，各人拿了多少」后半句没有工资词, 但它是「各人 + 拿了多少」且整句在问工资, 也拒。
 */
public final class PersonalPayRefusalGuard {

    public static final String STATUS = "PERSONAL_PAY_REFUSED";

    public static final String MESSAGE = "按个人的工资明细涉及员工隐私，这里不提供。"
            + "可以问：本月总人工成本、人工成本占总成本多少，或工厂的工资计算方式（按件 / 按时 / 混合）。";

    private static final String PAY = "工资|薪资|薪水|薪酬|月薪|底薪|奖金|提成|计件收入";
    private static final Pattern PAY_WORD = Pattern.compile(PAY);

    private static final String PERSON_NOUN = "工人|员工|职工|工友|师傅|操作员|操作工|人员|班组长|组长|班长|车间主任|主管|经理|同事|个人|人";
    /** 逐人: 量词后必须紧跟人称名词; 「每人 / 各人」「分别 / 逐个 / 各自」本身就是逐人。 */
    private static final Pattern PER_PERSON = Pattern.compile(
            "(每一个|每一位|每个|每位|每名|各个|各位|各)(" + PERSON_NOUN + ")|每人|分别|逐个|逐人|挨个|各自");

    /** 要清单 / 排名到人。 */
    private static final Pattern LIST_REQUEST = Pattern.compile("列出|列一下|列个|明细|清单|名单|一览|排行|排名|谁");

    /** 问在哪操作 / 谁经手, 不是问数额。 */
    private static final Pattern OPERATIONAL = Pattern.compile(
            "在哪|哪里|哪儿|怎么导出|导出|下载|打印|入口|谁负责|谁来|谁管|谁发|谁算|找谁");

    /** 人员名词 + 工资词 (中间只允许「们 / 的 / 每月」): 「工人工资是多少」。 */
    private static final Pattern STAFF_PAY = Pattern.compile(
            "(工人|员工|职工|工友|师傅|操作员|操作工|人员|班组长|组长|班长|大家|所有人)(们)?(的|每月|每个月|月)?(" + PAY + ")");
    private static final Pattern RULE_WORDS = Pattern.compile(
            "(" + PAY + ")(的)?(策略|规则|模式|政策|方案|制度|结构|计算|怎么算|如何算|怎么计算)");
    private static final Pattern AGGREGATE_WORDS = Pattern.compile(
            "(" + PAY + ")(的)?(成本|总额|合计|汇总|占比)|人工成本|总工资|总薪资");

    /** 常见单姓 (前百名去掉易与业务词撞的 车 应 计 全 年 平 何 付 金 万 等)。 */
    private static final String SURNAMES = "王李张刘陈杨黄赵吴周徐孙马朱胡郭高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁"
            + "沈姚卢姜崔钟谭陆汪范石廖贾韦方邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵钱严覃武戴莫孔汤";
    /** 姓 + 1~2 字 (可带 师傅 / 老师 / 老 / 小) 紧接 (的 / 本月 …) 工资词: 张三的工资 / 张三工资怎么算 / 王师傅的工资 / 老李工资。 */
    private static final Pattern NAMED_BEFORE_PAY = Pattern.compile(
            "((?:老|小)[" + SURNAMES + "]|[" + SURNAMES + "][\\u4e00-\\u9fa5&&[^的]]{1,2}?)"
                    + "(?:的|这个月的?|本月的?|上个月的?|上月的?)?(?:" + PAY + ")");
    /** 员工A / 工人甲 / 员工1 号: 用编号指到人。 */
    private static final Pattern CODED_PERSON = Pattern.compile("(" + PERSON_NOUN + ")[A-Za-z0-9甲乙丙丁]+号?");
    /** 名字里不会出现的字 (岗位 / 部门 / 时间 / 计算 / 范围): 命中说明抓到的是业务词, 不是人名。 */
    private static final Pattern NON_NAME_CHARS = Pattern.compile(
            "[工人员班组厂间部门季月年天周日算计发总均全所各每本上下这那哪多少加绩效奖件时职岗位财务仓管质检销售采购司机长元块钱额和与及跟同或温围末经]");

    /** 「各人拿了多少」「每人多少」: 本句没工资词, 但整句在问工资时就是逐人数额。 */
    private static final Pattern RECEIVE_OR_AMOUNT = Pattern.compile("拿了|拿到|领了|发了|得了|多少|几");
    /** 逐人子句里问的是别的指标 (产量 / 工时 …), 不是工资。 */
    private static final Pattern OTHER_METRIC = Pattern.compile("产量|产能|效率|工时|出勤|任务|批次|件数|数量|绩效分|考勤");

    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "[，,。；;：:？?！!\\n]+|以及|并且|还有|另外|然后");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

    private PersonalPayRefusalGuard() {}

    public static boolean refuses(String userInput) {
        if (userInput == null) return false;
        String text = SPACES.matcher(userInput).replaceAll("");
        if (!PAY_WORD.matcher(text).find()) return false;
        for (String clause : CLAUSE_SPLIT.split(text)) {
            if (clause.isEmpty()) continue;
            boolean pay = PAY_WORD.matcher(clause).find();
            boolean perPerson = PER_PERSON.matcher(clause).find();
            if (pay) {
                if (perPerson || namesAPerson(clause)) return true;
                // 问操作 / 流程 (工资明细表在哪导出、谁负责发工资) 不是在要数额: 没有逐人 / 点名时放行
                if (OPERATIONAL.matcher(clause).find()) continue;
                if (LIST_REQUEST.matcher(clause).find()) return true;
                if (STAFF_PAY.matcher(clause).find()
                        && !RULE_WORDS.matcher(clause).find() && !AGGREGATE_WORDS.matcher(clause).find()) return true;
            } else if (perPerson && RECEIVE_OR_AMOUNT.matcher(clause).find() && !OTHER_METRIC.matcher(clause).find()) {
                // 组合句后半: 「员工的提成怎么算的，各人拿了多少」
                return true;
            }
        }
        return false;
    }

    private static boolean namesAPerson(String clause) {
        if (CODED_PERSON.matcher(clause).find()) return true;
        Matcher m = NAMED_BEFORE_PAY.matcher(clause);
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("老") || name.startsWith("小")) return true;   // 老王 / 小李
            // 姓后面的字 (去掉 师傅 / 老师 称呼) 不能是岗位 / 部门 / 时间 / 计算用字: 「高工资」「白班工资」不是人名
            String given = name.substring(1).replaceAll("(师傅|老师)$", "");
            if (!NON_NAME_CHARS.matcher(given).find()) return true;
        }
        return false;
    }
}
