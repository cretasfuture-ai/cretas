"""繁体输入必须走进和简体**同一批**确定性词表。

## 缺陷（2026-08-20，`restaurant_ops_router` 实测）

`with_traditional()` 这个 helper 就在同一个文件里，姊妹表都过了它 ——
唯独判「这是不是通名」的那批**没有**。成因不是有人忘了，是**它定义得太晚**：
helper 原来写在 `:1975`，而 `_SALES_VALUE_TOKENS`(:322)、
`_DISH_GENERIC_TOKENS`(:1058) 等七张表排在它前面，**从物理上调不到**。
⇒ 形态 B「机制在、没接上」，只不过接不上的原因是定义顺序。

实测老板会看到什么（本文件的确定性层，⛔ 不过 LLM）：

    「最近30天整體的毛利率是多少」 -> dish_candidate = '整體'   ← 通名被当菜名
    「領料成本多少」               -> dish_candidate = '領料'   ← 领料被当菜名
    「损耗價值最高的食材」          -> wastage_axis  = 'qty'    ← 该按金额排，排成了数量

## 🔴 结构性理由（⛔ 不只是「好看」）

菜品目录来自 `dim_product`。目录为空时 `_catalogue_says_not_a_dish` 整个
fail-open —— **那时词表是唯一防线，而它只有简体**。
2026-08-20 prod 上就有工厂处在这个状态。

## 判据形态

⛔ 不断言「表里有几个词」（那是字面，且会和词表棘轮打架）。
✅ 断言**行为**：同一句话的简繁两版，在这几个确定性读数上给出同一个答案。

⚠️ 阴性对照有两条，都不能少：
  ① **简体侧逐字不变** —— 补繁体不许改简体的任何行为
  ② **真实体的原文不许被抹平** —— 门店/菜品名要按原文去库里比对，
     「米飯」必须原样留着（那是实体抽取，不是词表判读）
"""
import pytest

from smartbi.gold.restaurant import restaurant_ops_router as R


#: (简体, 繁体, 读数名, 期望值) —— 期望值是**简繁共同**应该给出的那个。
BEHAVIOUR_PAIRS = [
    ("最近30天整体的毛利率是多少", "最近30天整體的毛利率是多少",
     "dish_candidate", None),
    ("领料成本多少", "領料成本多少", "dish_candidate", None),
    ("损耗价值最高的食材", "损耗價值最高的食材", "wastage_axis", "cost"),
]


def _read(name, query):
    if name == "dish_candidate":
        return R.extract_dish_candidate(query)
    if name == "wastage_axis":
        return R._wastage_rank_axis(query)
    raise AssertionError("未知读数 %r —— 这道闸量不到东西" % name)


@pytest.mark.parametrize("simplified,traditional,reading,expected", BEHAVIOUR_PAIRS)
def test_traditional_gets_the_same_reading_as_simplified(
        simplified, traditional, reading, expected):
    """🔴 承重：同一句话的简繁两版，确定性读数必须一致。"""
    got_simplified = _read(reading, simplified)
    got_traditional = _read(reading, traditional)
    # 阳性对照：先证明简体那侧本来就是对的 —— 否则「两侧相同」可能是**一起错**。
    assert got_simplified == expected, (
        f"简体侧 {reading}({simplified!r}) = {got_simplified!r}，"
        f"期望 {expected!r} —— 基准就不对，下面那条断言没有意义"
    )
    assert got_traditional == expected, (
        f"繁体侧 {reading}({traditional!r}) = {got_traditional!r}，"
        f"而简体侧是 {got_simplified!r}。\n"
        "同一句话只因简繁不同就判读不同 —— 去看这张词表过没过 `with_traditional()`，"
        "以及 `with_traditional` 的定义**排不排在它前面**。"
    )


def test_the_helper_is_defined_before_every_table_that_needs_it():
    """⛔ 定义顺序本身就是那个缺陷 —— 钉住它，别让下一张表又排到前面去。

    ⚠️ 这条用 AST 数**结构**，⛔ 不用字符串计数（本仓记过三次「正则数进了
       docstring / 注释」）。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(R))
    helper_line = next(
        (n.lineno for n in tree.body
         if isinstance(n, ast.FunctionDef) and n.name == "with_traditional"),
        None,
    )
    assert helper_line, "with_traditional 不见了或改名了 —— 这道闸已经量不到东西"

    late = []
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for call in ast.walk(node.value):
            if (isinstance(call, ast.Call)
                    and isinstance(call.func, ast.Name)
                    and call.func.id == "with_traditional"
                    and node.lineno < helper_line):
                late.append((node.targets[0].id, node.lineno))
    assert not late, (
        f"这些表调了 with_traditional 但排在它的定义(:{helper_line})之前，"
        f"import 时就会 NameError: {late}"
    )


class TestTheNegativeControls:
    """⛔ 少了这两条，「补繁体」会变成「把所有输入折叠成简体」——那会误伤实体抽取。"""

    def test_simplified_behaviour_is_untouched(self):
        """阴性对照①：补繁体不许改动简体侧的任何读数。"""
        for simplified, _t, reading, expected in BEHAVIOUR_PAIRS:
            assert _read(reading, simplified) == expected

    def test_a_real_dish_name_keeps_its_original_characters(self):
        """阴性对照②：真菜名按**原文**抽取 —— ⛔ 不许被折叠成简体。

        门店/菜品名要拿原文去库里比对；把用户输入折叠成简体后，
        以繁体命名的真实门店/菜品反而匹配不上。
        """
        assert R.store_dish_split_dish("哪家店的米飯卖得最好") == "米飯"
        assert R.store_dish_split_dish("哪家店的米饭卖得最好") == "米饭"

    def test_a_named_dish_is_still_not_a_generic_token(self):
        """阴性对照③：加了 44 个繁体变体之后，真菜名一个都没被收进通名表。"""
        for real in ("水煮牛肉", "佛跳墙", "罗氏虾", "干锅花菜",
                     "总汇三明治", "香辣蟹煲"):
            assert real not in R._DISH_GENERIC_TOKENS, real
