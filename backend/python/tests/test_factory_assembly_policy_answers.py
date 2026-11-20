"""建厂对话的厂规/需检/回料上限答案解析: 问了才写, 没问到不出现(服务端不覆盖现值)。"""
from factory_assembly import generation as g


def test_asked_policy_answers_are_parsed():
    d = ("缺料时能不能开工：拦住，补齐料才能开工\n"
         "报工前要不要先有领料单：必须先有\n"
         "工序报工能不能默认跳过：不跳过，每道都报\n"
         "物料平衡提醒线：差5%提醒、差15%报警\n")
    assert g._factory_policy(d) == {
        "blockStartOnOpenShortage": True,
        "requireRequisitionBeforeReport": True,
        "skipProcessReportingDefault": False,
        "materialBalanceWarnPct": 5.0,
        "materialBalanceRedPct": 15.0,
    }


def test_unasked_or_default_answers_produce_nothing():
    assert g._factory_policy("") == {}
    assert g._factory_policy("缺料时能不能开工：按系统默认\n物料平衡提醒线：没有\n") == {}
    assert g._factory_policy("物料平衡提醒线：不说就用默认") == {}


def test_balance_lines_that_are_not_two_ordered_numbers_are_not_guessed():
    for ans in ("差5%", "差15%提醒、差5%报警", "差5%、10%、20%", "差0%、10%", "差50%、150%"):
        assert g._factory_policy("物料平衡提醒线：" + ans) == {}, ans


def test_product_inspection_and_rework_limit_land_on_named_products_only():
    d = "哪些产品要先检验：卤牛肉、鸭脖\n回料比例上限：卤牛肉 10%、卤鸭脖 5%\n"
    ps = [{"name": "卤牛肉"}, {"name": "卤鸭脖"}, {"name": "卤鸡爪"}]
    skipped = g._product_policy(d, ps)
    assert ps[0] == {"name": "卤牛肉", "inspectionRequired": True, "maxReworkRatioPercent": 10.0}
    assert ps[1] == {"name": "卤鸭脖", "maxReworkRatioPercent": 5.0}
    assert ps[2] == {"name": "卤鸡爪"}          # 没提到的产品一个字段都不加
    assert skipped == ["需检:鸭脖"]              # 认不出的如实报告, 不猜


def test_none_answers_and_bad_numbers():
    ps = [{"name": "卤牛肉"}]
    assert g._product_policy("哪些产品要先检验：没有\n回料比例上限：没有", ps) == []
    assert ps == [{"name": "卤牛肉"}]
    assert g._product_policy("回料比例上限：卤牛肉 150%", ps) == ["回料上限:卤牛肉 150%"]
    assert ps == [{"name": "卤牛肉"}]


def test_balance_line_in_wrong_order_is_not_written_but_reported_by_the_wiring():
    import inspect
    src = inspect.getsource(g)
    # 解析层不猜(返回空), 接线层必须把「说了没落上」报进 notBuilt
    assert g._factory_policy("物料平衡提醒线：差15%提醒、差5%报警") == {}
    assert "物料平衡提醒线:' + _bal_ans" in src
