"""产出被驳回时，⛔ 不许对老板说「这次没算出」——那是假话。

## 实测原形（2026-08-17，prod）

老板追问「它的问题出在哪」，拿到 62 字：

    这次**没算出**本轮要求的原因分析或优化动作，所以我没有把可能答非所问的
    数据端给你，我不会拿别的数据凑。说清楚具体范围我再试一次。

而直接调 `tiered_answer` 时 stderr 打出真相：

    synthesis rejected by narrative grounding gate: violations=[
      '无保留因果断言：它靠的是高客单价（每单花得多）撑起营业额',
      '未标注为假设的预算或目标：…VIP评价平均分能否从4.504星提升到4.7星以上',
      '未经验证或确认的高影响动作：可验收结果：** 下架这5个菜后', …]

▎产品**把归因和行动方案完整算出来了**（具体门店名 / 具体数字 / 具体动作 /
▎验收方式），然后因为**措辞纪律**被整份驳回。五条里没有一条是「数字错了」。
▎而给老板的那句「没算出」**是假的**。

⇒ 违反反目标里最重的一条：**敢说不准的全部本钱，就是每一句都站得住。**

## ⛔ 这里修的是措辞，不是放行

被驳回的那份文本**继续不发** —— 无保留因果断言正是不许发给店长的东西，
闸抓住它是**做对了**（设计卡 `2026-08-17-追问被叙事接地闸毙掉-设计卡.md`
裁定：A 治本 + C 止血，⛔ 不做 D「把闸降级成只报不拦」）。

## 另一半：假建议

原文案的「说清楚具体范围我再试一次」也是假的 —— 老板的范围没问题，
重问一次不会变好。**一个会误报的提示比没有提示更糟**，假建议同理。
"""
import ast
import inspect
from datetime import date

from smartbi.gold.restaurant import restaurant_intent_service as svc

# 产出被驳回时**不许**出现的字样
FALSE_WHEN_PRODUCED = "这次没算出"
# 原来那句假建议
FALSE_ADVICE = "说清楚具体范围我再试一次"


def _refusal_branch_src() -> str:
    """拒答文案那一段的源码。"""
    return inspect.getsource(svc.tiered_answer)


def test_the_refusal_has_two_branches_not_one():
    """🔴 承重：文案必须按「产出过没有」分两支。

    只有一支 = 要么对所有人撒谎（说没算出），要么对所有人说算出来了
    （而真的没算出来时那也是假话）。两种都不行。
    """
    src = _refusal_branch_src()
    tree = ast.parse(src)
    # `produced_but_rejected` 必须被赋值一次、并被用在一个条件表达式里
    assigns = [n for n in ast.walk(tree)
               if isinstance(n, ast.Assign)
               for t in n.targets
               if isinstance(t, ast.Name) and t.id == "produced_but_rejected"]
    assert len(assigns) == 1, (
        f"`produced_but_rejected` 赋值 {len(assigns)} 次，期望 1。"
        "0 = 分支没了（又变回一句话对所有人说）")

    # 🔴 只钉「有这个赋值」不够 —— 变异实测：把右边换成常量 `False`，
    #    结构一模一样，断言纹丝不动，而分支已经死了（永远走旧文案）。
    #    ⇒ 必须钉它**是从 `answer_text` 推出来的**。
    rhs = ast.unparse(assigns[0].value)
    assert "answer_text" in rhs, (
        f"`produced_but_rejected` 不是从 `answer_text` 推出来的（右边是 {rhs!r}）"
        " —— 常量化 = 分支是死的，永远只走一支")

    ifexps = [n for n in ast.walk(tree)
              if isinstance(n, ast.IfExp)
              and any(getattr(x, "id", None) == "produced_but_rejected"
                      for x in ast.walk(n.test))]
    assert ifexps, "`produced_but_rejected` 没有被用在条件表达式里 —— 分支是死的"


def test_it_does_not_claim_nothing_was_computed_when_something_was():
    """🔴 承重：产出被驳回的那一支，⛔ 不许出现「这次没算出」。"""
    src = _refusal_branch_src()
    tree = ast.parse(src)
    ifexp = next(n for n in ast.walk(tree)
                 if isinstance(n, ast.IfExp)
                 and any(getattr(x, "id", None) == "produced_but_rejected"
                         for x in ast.walk(n.test)))
    # IfExp.body = 条件为真(产出被驳回)时那一支
    produced_branch = ast.unparse(ifexp.body)
    assert FALSE_WHEN_PRODUCED not in produced_branch, (
        f"产出被驳回的分支里仍然写着「{FALSE_WHEN_PRODUCED}」—— 那是假话，"
        f"分支：{produced_branch[:200]}")
    assert FALSE_ADVICE not in produced_branch, (
        f"产出被驳回的分支里仍然写着「{FALSE_ADVICE}」—— 老板的范围没问题，"
        "重问一次不会变好，那是假建议")


def test_the_produced_branch_tells_him_what_to_do_instead():
    """答不了时要说清**他能做什么**（定义五），⛔ 不是干拒。

    ## 🔴 这条原来钉的是**字面**，2026-08-26 抬成**性质**

    原版找 `"你可以" in produced_branch`。而那句指引后来**正确地**从
    硬编码搬进了 `_narrowing_suggestion(spec)` —— 因为硬编码那版是
    「假建议」：它会去建议老板做他**已经做过**的事
    （prod 实测 2/2 撞上兜底的问句都已经限定了门店/时间段）。

    ▎搬走之后分支里只剩 `+ _narrowing`，字面查不到 —— 但需求**满足得更好**。
    ⇒ 断言改钉两件事，⛔ 不再钉字面：
      ① 分支里确实拼进了那份**算出来的**指引（而不是干拒）；
      ② 阳性对照：那个生成器在「还有没限定的槽位」时**真的**给得出
         一句「你可以…」——否则 ① 可能钉着一个恒返回空串的死机制
         （形态 B：机制在、没接上）。

    ⚠️ 三个槽位都限定了时它**故意**返回空串（「少一句废话，比多一句他做不到
    的指引好」），所以 ⛔ 不能断言「任何情况下都有这句话」。
    """
    src = _refusal_branch_src()
    tree = ast.parse(src)
    ifexp = next(n for n in ast.walk(tree)
                 if isinstance(n, ast.IfExp)
                 and any(getattr(x, "id", None) == "produced_but_rejected"
                         for x in ast.walk(n.test)))
    produced_branch = ast.unparse(ifexp.body)

    # ① 分支必须拼进**算出来的**下一步指引
    narrowing_assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == "_narrowing"
    ]
    assert len(narrowing_assigns) == 1, (
        f"`_narrowing` 赋值 {len(narrowing_assigns)} 次，期望 1 次")
    assert "_narrowing_suggestion(" in ast.unparse(narrowing_assigns[0].value), (
        "`_narrowing` 不是由 `_narrowing_suggestion` 算出来的 —— "
        "写死的指引就是假建议，这个毛病已经回来过两次")
    assert "_narrowing" in produced_branch, (
        "没告诉他下一步怎么问 —— 定义五要求说清「他自己要干什么」。"
        f"分支：{produced_branch[:200]}")

    # ② 阳性对照：生成器在「还有没限定的槽位」时真的说得出话来。
    #    ⛔ 少了这条，① 可能钉着一个恒返回空串的死机制。
    class _OpenSpec:
        store_slots = ()
        dish_slot = None
        date_range = (None, None)
        window_label = "全部历史"
        time_range_defaulted = False

    advice = svc._narrowing_suggestion(_OpenSpec())
    assert "你可以" in advice, (
        f"三个槽位全没限定时也给不出指引 —— 那份「算出来的建议」是死的：{advice!r}")

    # 阴性对照：三样都限定了就**不给**这句（少一句废话 > 多一句他做不到的）
    class _ClosedSpec:
        store_slots = ("徐汇美罗城店",)
        dish_slot = "米饭"
        date_range = (date(2026, 7, 1), date(2026, 7, 31))
        window_label = "最近30天"
        time_range_defaulted = False

    assert svc._narrowing_suggestion(_ClosedSpec()) == "", (
        "他三样都限定了还在建议他去限定 —— 那正是「假建议」本身")


def test_the_rejected_text_is_still_not_forwarded():
    """🔴 阴性对照：⛔ 不许把被驳回的那份文本发出去。

    修的是**措辞**，不是放行。无保留因果断言正是不许发给店长的东西 ——
    闸抓住它是做对了（设计卡裁定 ⛔ 不做 D）。
    少了这条，「说实话」很容易滑成「那就发给他吧」。
    """
    src = _refusal_branch_src()
    tree = ast.parse(src)
    ifexp = next(n for n in ast.walk(tree)
                 if isinstance(n, ast.IfExp)
                 and any(getattr(x, "id", None) == "produced_but_rejected"
                         for x in ast.walk(n.test)))
    produced_branch = ast.unparse(ifexp.body)
    assert "answer_text" not in produced_branch, (
        "产出被驳回的分支里引用了 `answer_text` —— 那是被闸驳回的文本，"
        "⛔ 不许原样发给老板")


def test_the_advice_does_not_assume_the_question_was_a_why(empty_ok=None):
    """🔴 当天回归：⛔ 不许假设老板问的是「为什么」。

    实测原形（2026-08-17，同日）：第一版这里硬编码了
    「例如把**为什么**换成「哪家店/哪道菜拉低了毛利」」，
    而这条文案会发给**任何**产出被驳回的问句。
    「有没有菜是卖一份亏一份的」拿到了它 —— 老板**根本没问「为什么」**。

    ▎那正是这个文件在修的那个毛病本身：**假建议**。
    ▎一句听起来很具体、其实与他的问题无关的指路，
    ▎烧的正是「敢说不准」那点本钱。
    """
    src = _refusal_branch_src()
    tree = ast.parse(src)
    ifexp = next(n for n in ast.walk(tree)
                 if isinstance(n, ast.IfExp)
                 and any(getattr(x, "id", None) == "produced_but_rejected"
                         for x in ast.walk(n.test)))
    produced_branch = ast.unparse(ifexp.body)
    assert "为什么" not in produced_branch, (
        "拒答文案里硬编码了「为什么」—— 它会发给任何被驳回的问句，"
        f"包括根本没问为什么的那些。分支：{produced_branch[:200]}")
