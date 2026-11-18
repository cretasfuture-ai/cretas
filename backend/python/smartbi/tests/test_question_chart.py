"""问题 51 回归：上传数据问答按问题出图 (原句来自 BP 证据 E21)。"""
import pandas as pd
import pytest

from smartbi.services.question_chart import (
    adapt_category_distribution,
    asked_dimension,
    asked_month,
    build_question_chart,
    normalize_cached_charts,
)


def _sales_df():
    """形状同 sales_DEMO-20261105_net.xlsx：3 产品 × 3 客户，8–10 月。"""
    rows = []
    plan = [
        # (日期, 产品, 编码, 客户, 区域, 省份, 单价, 数量)
        ("2026-08-08", "演示·藤椒掌中宝", "DEMO-C", "华北演示客户", "华北", "北京", 333.56, 1),
        ("2026-09-03", "演示·香辣鸡丁", "DEMO-B", "华南演示客户", "华南", "广州", 120.0, 3),
        ("2026-10-02", "演示·藤椒掌中宝", "DEMO-C", "华东演示客户", "华东", "上海", 300.0, 2),
        ("2026-10-05", "演示·酱卤牛肉", "DEMO-A", "华北演示客户", "华北", "北京", 150.0, 4),
        ("2026-10-09", "演示·香辣鸡丁", "DEMO-B", "华南演示客户", "华南", "广州", 100.0, 1),
        ("2026-10-20", "演示·酱卤牛肉", "DEMO-A", "华北演示客户", "华北", "北京", 150.0, 2),
    ]
    for d, p, code, c, r, prov, price, qty in plan:
        rows.append({"订单日期": d, "产品名称": p, "产品编码": code, "客户名称": c, "区域": r,
                     "省份": prov, "销售单价": price, "销售数量": qty, "销售金额": price * qty,
                     "金额单位": "元"})
    return pd.DataFrame(rows)


def _pie_data(chart):
    return {d["name"]: d["value"] for d in chart["option"]["series"][0]["data"]}


@pytest.mark.parametrize("query", ["各产品的销售额占比是多少？用图表展示", "各产品的销售额占比是多少？"])
def test_product_share_question_draws_product_pie_over_all_rows(query):
    chart = build_question_chart(query, _sales_df())
    assert chart is not None
    assert chart["type"] == "pie"
    assert chart["question_chart"]["dimension"] == "产品名称"  # 不是省份、不是产品编码
    assert chart["question_chart"]["measure"] == "销售金额"  # 不是销售单价
    assert chart["title"] == "各产品销售金额占比"
    assert _pie_data(chart) == {
        "演示·藤椒掌中宝": pytest.approx(333.56 + 600.0),
        "演示·酱卤牛肉": pytest.approx(600.0 + 300.0),
        "演示·香辣鸡丁": pytest.approx(360.0 + 100.0),
    }


def test_october_top_customer_question_filters_month_and_ranks_customers():
    chart = build_question_chart("10月份哪个客户买得最多，帮我分析一下", _sales_df())
    assert chart is not None
    assert chart["type"] == "bar"
    assert chart["question_chart"]["month"] == 10
    assert chart["question_chart"]["rows"] == 4  # 只算 10 月的 4 行
    assert chart["title"] == "2026年10月 各客户销售金额排名"
    opt = chart["option"]
    assert opt["xAxis"]["data"] == ["华北演示客户", "华东演示客户", "华南演示客户"]
    assert opt["series"][0]["data"] == [900.0, 600.0, 100.0]


def test_unnamed_dimension_or_missing_column_returns_none():
    df = _sales_df()
    assert build_question_chart("帮我分析一下这份数据", df) is None  # 没点名维度
    assert build_question_chart("各门店销售额占比", df) is None  # 数据里没有门店列
    assert build_question_chart("11月份哪个客户买得最多", df) is None  # 11 月没数据, 不画空图


def test_quantity_question_uses_quantity_column():
    chart = build_question_chart("哪个产品卖的数量最多", _sales_df())
    assert chart["question_chart"]["measure"] == "销售数量"
    assert chart["option"]["series"][0]["data"][0] == 6  # 酱卤牛肉 4+2


def test_query_parsing():
    assert asked_dimension("10月份哪个客户买得最多") == "customer"
    assert asked_dimension("各产品的销售额占比") == "product"
    assert asked_month("10月份哪个客户买得最多") == 10
    assert asked_month("3月5日的订单") is None
    assert asked_month("各产品占比") is None


def _cached_category_payload():
    shares = [
        {"label": "华北演示客户", "share": 34.82, "total": 49094.1},
        {"label": "华南演示客户", "share": 33.35, "total": 47018.7},
        {"label": "华东演示客户", "share": 31.84, "total": 44889.66},
    ]
    return {
        "answer": "「华北演示客户」一家就占了34.8%的生意。",
        "charts": [{"type": "echarts", "title": {"text": "客户名称 占比 (按 销售金额)", "left": "center"},
                    "option": {"series": [{"type": "pie", "data": []}]}}],
        "template_code": "category_distribution",
        "data": {
            "measure": "销售金额",
            "shares": shares,
            "all_dims": {
                "客户名称": shares,
                "产品名称": [
                    {"label": "演示·藤椒掌中宝", "total": 62031.78},
                    {"label": "演示·香辣鸡丁", "total": 40108.68},
                    {"label": "演示·酱卤牛肉", "total": 38862.0},
                ],
            },
        },
    }


def test_cached_share_is_resliced_to_the_asked_dimension():
    out = adapt_category_distribution(_cached_category_payload(), "各产品的销售额占比是多少？")
    assert out["charts"][0]["type"] == "pie"
    assert _pie_data(out["charts"][0])["演示·藤椒掌中宝"] == 62031.78
    assert out["answer"].startswith("按销售金额看，演示·藤椒掌中宝占 44.0%")


def test_cached_share_without_asked_column_says_so_honestly():
    out = adapt_category_distribution(_cached_category_payload(), "分类占比")
    assert out["answer"].startswith("这份数据里没有「分类」这一列，下面按客户名称看销售金额占比。")
    assert out["charts"] == _cached_category_payload()["charts"]  # 图不改


def test_cached_share_same_dimension_untouched():
    payload = _cached_category_payload()
    assert adapt_category_distribution(payload, "各客户销售额占比") == payload


def test_cached_chart_type_normalized_for_chart_insight():
    charts = normalize_cached_charts(_cached_category_payload()["charts"])
    assert charts[0]["type"] == "pie"
    assert charts[0]["title"] == "客户名称 占比 (按 销售金额)"


class _FakeConn:
    """记录 SQL 参数; 按 _sales_df 全量在 Python 里模拟 GROUP BY (只读)。"""

    def __init__(self, df):
        self.df = df
        self.calls = []

    async def fetchrow(self, sql, *params):
        self.calls.append(("fetchrow", params))
        _, _, date_col, mm = params
        years = [d[:4] for d in self.df[date_col] if d[5:7] == mm]
        return {"y": max(years) if years else None}

    async def fetch(self, sql, *params):
        self.calls.append(("fetch", params))
        assert "LIMIT" not in sql.upper()  # 整份上传, 不抽样
        scope = self.df
        if len(params) == 6:
            scope = scope[scope[params[4]].str[:7] == params[5]]
        g = scope.groupby(params[2])[params[3]].agg(["sum", "count"])
        return [{"label": k, "total": v["sum"], "n": v["count"]} for k, v in g.iterrows()]


def test_upload_path_reads_columns_from_sample_but_sums_whole_upload():
    import asyncio
    from smartbi.services.question_chart import build_question_chart_for_upload

    full = _sales_df()
    sample = full.head(1)  # 流式路径的样本只有前几行 (8 月), 没有 10 月
    assert build_question_chart("10月份哪个客户买得最多，帮我分析一下", sample) is None  # 对照: 拿样本算就画不出
    conn = _FakeConn(full)
    chart = asyncio.run(build_question_chart_for_upload(
        conn, 5306, "F006", "10月份哪个客户买得最多，帮我分析一下", sample))
    assert chart is not None
    assert chart["option"]["xAxis"]["data"] == ["华北演示客户", "华东演示客户", "华南演示客户"]
    assert chart["option"]["series"][0]["data"] == [900.0, 600.0, 100.0]
    assert chart["question_chart"]["rows"] == 4
    # 每条 SQL 都带上传号和工厂号
    assert all(params[0] == 5306 and params[1] == "F006" for _, params in conn.calls)
    assert conn.calls[-1][1][2:] == ("客户名称", "销售金额", "订单日期", "2026-10")


def test_upload_path_share_question_matches_pandas_on_full_data():
    import asyncio
    from smartbi.services.question_chart import build_question_chart_for_upload

    full = _sales_df()
    q = "各产品的销售额占比是多少？用图表展示"
    via_sql = asyncio.run(build_question_chart_for_upload(_FakeConn(full), 1, "F006", q, full.head(1)))
    via_pandas = build_question_chart(q, full)
    assert _pie_data(via_sql) == _pie_data(via_pandas)
