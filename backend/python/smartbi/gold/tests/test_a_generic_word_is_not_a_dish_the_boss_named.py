"""通名不是老板点的那道菜 —— 目录说「查不到」之后再问一句「它是不是通名」。

## 缺陷（prod MOCK_REST 实测 3/3 稳定，2026-08-20）

    问「最近30天各门店**菜品**毛利分别是多少」 -> 拒答 73 字
      「最近30天没有找到「菜品」的门店销售记录，不能给出该菜的门店拆分，
        也不会用其他菜品或榜单替代。」
    只换那一个词问「各门店**水煮牛肉**毛利」   -> 787 字，10 家门店全名齐

## 成因

`dish_slot_after_catalogue_check` 的承重条件是 `ranking_direction`，
而它的理由**是对的**：老板点名一道菜时那个名字就是题目，摘掉 = 替他改题。

▎但「各门店菜品毛利」和「香辣蟹煲卖得怎么样」在那道闸眼里长得一模一样 ——
▎两者都是 `ranking_direction is None` **且**目录查不到。

目录**永远**分不开这两件事（菜名是开集，「菜品」本来就不在菜单里）；
词表**已经**分得开（通名是闭集）。闸没问它，一句话的缺口。

## 🔴 承重断言必须两条，⛔ 不是一条

  ① 「各门店菜品毛利」**不再拒答**（通名被摘掉）
  ② 「香辣蟹煲卖得怎么样」**逐字不变**（仍定向拒答）

▎少了②，这一刀就退化成「拿全菜榜顶替他点名的菜」—— 那正是原设计要防的。
"""
import pytest

from smartbi.gold.restaurant import restaurant_ops_router as router

#: 一份**真实**菜单（MOCK_REST prod 实测的那 10 道里取 4 道）。
#: ⛔ 不用「菜品」「单品」这类词当菜名 —— 那会把被测的区分做没了。
_MENU = frozenset({"水煮牛肉", "干锅花菜", "娃娃菜", "米饭"})


@pytest.fixture
def menu_loaded():
    token = router.set_dish_catalogue(_MENU)
    try:
        yield
    finally:
        router._DISH_CATALOGUE.reset(token)


@pytest.fixture
def menu_unavailable():
    token = router.set_dish_catalogue(None)
    try:
        yield
    finally:
        router._DISH_CATALOGUE.reset(token)


class TestTheTwoLoadBearingAssertions:
    """两条一起才成立 —— ⛔ 任何一条单独绿都不算这一刀做成了。"""

    def test_a_generic_word_is_dropped_so_the_question_can_be_answered(
            self, menu_loaded):
        """🔴 承重①：通名被摘掉，本轮维度照常回答。"""
        kept, dropped = router.dish_slot_after_catalogue_check(
            "菜品", ranking_direction=None)
        assert kept is None, (
            "「菜品」还留在菜名槽位里 —— 那会让 resolver 去查一道叫「菜品」的菜，"
            "老板拿到的是「没有找到「菜品」的门店销售记录」"
        )
        assert dropped == "菜品", "摘掉了却没上报 —— ⛔ 绝不静默丢掉"

    def test_a_dish_the_boss_named_is_never_dropped(self, menu_loaded):
        """🔴 承重②：老板点名的那道菜**一个字都不许动**，哪怕菜单里没有它。

        ⛔ 这条是这一刀的**边界**。它红了说明我们开始替老板改题：
           他问「香辣蟹煲卖得怎么样」，我们拿全菜榜顶替。
        """
        kept, dropped = router.dish_slot_after_catalogue_check(
            "香辣蟹煲", ranking_direction=None)
        assert kept == "香辣蟹煲", (
            "点名的菜被摘掉了 —— 这一刀退化成了「拿全菜榜顶替他点名的菜」"
        )
        assert dropped is None, "不该有披露：我们什么都没摘"


class TestTheGateCoversWhatTheUpstreamListMisses:
    """🔴 这道闸的通名支路**不是死代码** —— 它接住的是上游那份**部分**清单漏掉的。

    形态 D 的第四份：`restaurant_intent._verbatim_entity` 里内联着**又一张**
    通名清单（8 个菜/店词），它和已登记的 `_DISH_GENERIC_TOKENS`（43 条）**只部分重叠**。
    实测（本用例即读数）：

        菜品  -> _verbatim_entity 已拒
        单品  -> _verbatim_entity **放行**    ← 只有本闸接得住
        产品  -> _verbatim_entity **放行**    ← 同上
        整体  -> _verbatim_entity **放行**    ← 同上

    ⚠️ 那份清单在 `restaurant_intent.py`，本轮**不碰那个文件**（有并行线在改）。
       ⇒ 本闸是 LLM 直填槽位那条路上**唯一**的防线，⛔ 不能因为「今天没触发」删掉。
    """

    #: ⚠️ 这三个是**读数**，⛔ 不是愿望。哪天上游那份清单补全了，这条会红 ——
    #:    那时该做的是把这里改小（并说清是靠哪一处替代的），⛔ 不是删掉本闸。
    ONLY_THIS_GATE_CATCHES = ("单品", "产品", "整体")

    @pytest.mark.parametrize("word", ONLY_THIS_GATE_CATCHES)
    def test_the_upstream_list_lets_it_through(self, word):
        """前提断言：先证明上游**真的**放行 —— 否则下面那条断言在守空气。"""
        from smartbi.gold.restaurant import restaurant_intent as ri

        query = f"最近30天{word}毛利怎么样"
        assert ri._verbatim_entity(word, query) == word, (
            f"上游 _verbatim_entity 现在也拒了「{word}」—— "
            "本闸对它不再是唯一防线，把它从 ONLY_THIS_GATE_CATCHES 里移走"
        )

    @pytest.mark.parametrize("word", ONLY_THIS_GATE_CATCHES)
    def test_this_gate_catches_it(self, menu_loaded, word):
        kept, dropped = router.dish_slot_after_catalogue_check(
            word, ranking_direction=None)
        assert (kept, dropped) == (None, word)


class TestTheOldBehaviourIsUntouched:
    """⛔ 阴性对照：除了新增那一格，其余每一格都必须逐字不变。"""

    @pytest.mark.parametrize("mention,direction,expect_kept,expect_dropped", [
        # 目录认得它 -> 原样留下（排行与否都一样）
        ("水煮牛肉", None, "水煮牛肉", None),
        ("水煮牛肉", "best", "水煮牛肉", None),
        ("娃娃菜", "worst", "娃娃菜", None),
        # 排行 + 目录查不到 -> 摘（原有行为，「最差」那个 bug 的修复）
        ("最差", "worst", None, "最差"),
        ("最好", "best", None, "最好"),
        # 具体菜名 + 目录查不到 + 非排行 -> 维持定向拒答
        ("佛跳墙", None, "佛跳墙", None),
        # 空槽位
        (None, None, None, None),
        ("", "worst", "", None),
    ])
    def test_every_other_cell_is_unchanged(
            self, menu_loaded, mention, direction, expect_kept, expect_dropped):
        kept, dropped = router.dish_slot_after_catalogue_check(
            mention, ranking_direction=direction)
        assert (kept, dropped) == (expect_kept, expect_dropped)

    def test_fail_open_when_the_menu_is_unavailable(self, menu_unavailable):
        """🔴 目录不可用 ⇒ 整道闸恒 fail-open，**含新增的通名支路**。

        ⚠️ 这一条是这一刀最容易搞砸的地方：通名支路只看词表，
           如果把它放在目录判断**之前**，目录挂掉时它会照样摘 ——
           那就把一道 fail-open 的闸变成了 fail-closed。
        """
        for mention in ("菜品", "香辣蟹煲", "最差", "水煮牛肉"):
            for direction in (None, "best", "worst"):
                assert router.dish_slot_after_catalogue_check(
                    mention, ranking_direction=direction) == (mention, None), (
                    f"目录不可用时 {mention!r}/{direction!r} 的行为变了 —— "
                    "fail-open 是既有纪律"
                )


class TestTheDisclosureStaysTrue:
    """披露必须**是真的** —— 一句不真实的披露比没有披露更糟。"""

    def test_the_generic_branch_does_not_claim_a_ranking(self):
        """通名支路给的**不是**排行，⛔ 披露里不许说「上面这张排行」。"""
        text = router.dish_catalogue_miss_disclosure("菜品")
        assert "排行" not in text, (
            "通名被摘掉时给的是本轮维度的答案(例如门店拆分)，不是排行 —— "
            "这句披露在撒谎"
        )
        assert "菜品" in text, "没说清我们摘掉了哪个词"
        assert "所有菜" in text, "没说清我们把它当成了什么"

    def test_the_ranking_branch_keeps_its_original_wording(self):
        """阴性对照：排行那一支的措辞**逐字不变**。"""
        assert router.dish_catalogue_miss_disclosure("香辣蟹煲") == (
            "\n\n（说明：菜单里没查到「香辣蟹煲」这道菜，所以这次没按单道菜算，"
            "给的是上面这张排行。如果它是刚上架的新菜，先加进菜单就能单独看它。）"
        )

class TestThePredicateHasExactlyOneHome:
    """🔴 形态 D：同一个东西两份，它一定会漂 —— 而这一刀**就是被漂咬过一次的**。

    实测（prod 探针把闸包起来计数）：`dish_slot_after_catalogue_check` 确实把
    `spec.dish_slot` 摘掉了（`returned: [null, "菜品"]`），**而老板看到的拒答一个字
    没变** —— 因为 `store_dish_split_dish` 从**原始问句**又把「菜品」捞了回来。
    ▎修一处等于没修。所以这里钉的是「只有一个家 + 两个消费点都连着它」。
    """

    def test_both_consumers_go_through_the_one_helper(self):
        """两个消费点都必须调 `_is_generic_dish_word`，⛔ 不许各写各的。

        ⚠️ 用 AST 数**调用节点**，⛔ 不用字符串计数 —— 本仓记过三次
           「正则把 docstring 里提到函数名的那行也数上」。
        """
        import ast
        import inspect

        def calls_in(fn):
            tree = ast.parse(inspect.getsource(fn).lstrip())
            return {n.func.id for n in ast.walk(tree)
                    if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}

        for fn in (router.dish_slot_after_catalogue_check,
                   router.store_dish_split_dish):
            assert "_is_generic_dish_word" in calls_in(fn), (
                f"{fn.__name__} 没有调 _is_generic_dish_word —— "
                "通名判据又长出了第二份，或者这个消费点根本没接上"
            )

    def test_the_helper_uses_the_registered_wordlist(self):
        """⛔ 零新增词表：判据用的必须是**已登记的**那张表。

        词表棘轮零余量，新增任何一张（或含中文的正则）都会当天弄红它。
        """
        import ast
        import inspect

        tree = ast.parse(inspect.getsource(router._is_generic_dish_word).lstrip())
        names = {n.id for n in ast.walk(tree) if isinstance(n, ast.Name)}
        assert "_DISH_GENERIC_TOKENS" in names, (
            "通名判据没用 _DISH_GENERIC_TOKENS —— 是不是又手写了一张表？"
        )
        literals = [n.value for n in ast.walk(tree)
                    if isinstance(n, ast.Constant) and isinstance(n.value, str)]
        inline_cn = [s for s in literals
                     if len(s) <= 6 and any("一" <= c <= "鿿" for c in s)]
        assert not inline_cn, f"helper 里出现了内联中文词: {inline_cn}"

    def test_the_regex_path_refuses_a_generic_word(self):
        """🔴 承重：正则那条路也必须拒绝通名。"""
        assert router.store_dish_split_dish(
            "最近30天各门店菜品毛利分别是多少") is None, (
            "正则从原始问句捞回了「菜品」—— 闸摘掉了它也没用"
        )

    @pytest.mark.parametrize("query,expect", [
        # 阴性对照：真菜名照常抽出来，⛔ 一个都不许被通名判据吃掉
        ("哪家店的米饭卖得最好", "米饭"),
        ("哪家店的干锅花菜卖得最好", "干锅花菜"),   # 名字里带裸单字「菜」
        ("哪家店的娃娃菜销量最高", "娃娃菜"),        # 同上
        ("各门店的水煮牛肉毛利", "水煮牛肉"),
        # 纯门店问句本来就没有菜
        ("哪家店营收最高", None),
    ])
    def test_real_dish_names_survive(self, query, expect):
        assert router.store_dish_split_dish(query) == expect
