"""销售报表 4 张经营分析卡片: 业务员 / 区域 / 增长率 / 毛利率。

接口测试直接调路由处理函数 (FastAPI 注册的就是这些函数), 用假连接池喂行数据,
同时记录传给 SQL 的参数, 验证日期窗口算对了。
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from smartbi.api import sales_preset
from smartbi.services.address_region import big_region_of, province_short_of


# ─── 假连接池 ────────────────────────────────────────────────────────────


class _FakeConn:
    def __init__(self, rows):
        self.rows = rows
        self.fetch_args: list[tuple] = []

    async def execute(self, *args):
        return None

    async def fetch(self, sql, *args):
        self.fetch_args.append(args)
        return self.rows


class _FakePool:
    def __init__(self, rows):
        self.conn = _FakeConn(rows)

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                return pool.conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


def _req(role: str | None = "finance_manager", factory_id: str = "F1") -> Any:
    """路由只读 request.state.factory_id / role (由 JWT 中间件写入)。"""
    return SimpleNamespace(state=SimpleNamespace(factory_id=factory_id, role=role))


@pytest.fixture
def use_rows(monkeypatch):
    def _install(rows):
        pool = _FakePool(rows)

        async def _get_pool():
            return pool

        monkeypatch.setattr(sales_preset, "_get_pool", _get_pool)
        return pool

    return _install


def _run(coro):
    return asyncio.run(coro)


# ─── 地址 → 省 ───────────────────────────────────────────────────────────


@pytest.mark.parametrize("address,expected", [
    ("江苏省苏州市昆山市玉山镇", "江苏"),
    ("江苏苏州工业园区", "江苏"),
    ("上海市闵行区七莘路", "上海"),
    ("上海闵行区", "上海"),
    ("中国浙江省杭州市", "浙江"),
    ("  广东 深圳市南山区", "广东"),
    ("苏州市吴中区", "江苏"),
    ("昆山市开发区", "江苏"),
    ("连云港市海州区", "江苏"),
    ("黑龙江省哈尔滨市", "黑龙江"),
    ("内蒙古包头市", "内蒙古"),
    ("广西壮族自治区南宁市", "广西"),
    ("山西省太原市", "山西"),
    ("陕西省西安市", "陕西"),
    ("义乌市稠城街道", "浙江"),
])
def test_province_recognized(address, expected):
    assert province_short_of(address) == expected


@pytest.mark.parametrize("address", [
    None, "", "   ",
    "中山北路100号",      # 路名, 不是中山市
    "北京东路88号",       # 路名, 不是北京
    "南京路步行街",       # 路名, 不是南京
    "某某县城关镇",       # 不在城市表 → 不猜
    "仓库自提",
])
def test_province_unrecognized_returns_none(address):
    assert province_short_of(address) is None


def test_big_region_mapping():
    assert big_region_of("江苏") == "华东"
    assert big_region_of("广东") == "华南"
    assert big_region_of("黑龙江") == "东北"
    assert big_region_of("香港") == "港澳台"


# ─── 业绩 by 业务员 ──────────────────────────────────────────────────────


def _sp_row(key, sp_id, name, orders, customers, total, tax, paid):
    return {
        "sp_key": key, "salesperson_id": sp_id, "salesperson_name": name,
        "order_count": orders, "customer_count": customers,
        "total_amount": Decimal(total), "tax_amount": Decimal(tax), "paid": Decimal(paid),
    }


def test_salesperson_ranked_and_unassigned_kept_separate(use_rows):
    use_rows([
        _sp_row("id:7", 7, "张三", 2, 2, "100", "13", "50"),
        _sp_row(None, None, None, 3, 3, "200", "26", "0"),
        _sp_row("id:9", 9, "李四", 4, 3, "300", "39", "339"),
    ])
    data = _run(sales_preset.salesperson_performance("F1", _req(), "2026-09-01", "2026-09-30"))["data"]

    assert [r["salespersonName"] for r in data["rank"]] == ["李四", "张三"]
    assert data["rank"][0]["unpaid"] == pytest.approx(0.0)
    assert data["rank"][1]["unpaid"] == pytest.approx(63.0)
    # 没填业务员的订单不进排行, 单独计数
    assert data["unassigned"]["orderCount"] == 3
    assert data["unassigned"]["totalAmountWithTax"] == pytest.approx(226.0)
    assert data["summary"] == {"orderCount": 9, "assignedOrderCount": 6, "totalAmountWithTax": pytest.approx(678.0)}
    shares = [r["share"] for r in data["rank"]] + [data["unassigned"]["share"]]
    assert sum(shares) == pytest.approx(1.0)


def test_salesperson_no_orders_gives_none_share_not_zero(use_rows):
    use_rows([])
    data = _run(sales_preset.salesperson_performance("F1", _req(), "2026-09-01", "2026-09-30"))["data"]
    assert data["rank"] == []
    assert data["unassigned"]["orderCount"] == 0
    assert data["unassigned"]["share"] is None


def test_reversed_date_range_rejected(use_rows):
    use_rows([])
    with pytest.raises(HTTPException) as e:
        _run(sales_preset.salesperson_performance("F1", _req(), "2026-09-30", "2026-09-01"))
    assert e.value.status_code == 400


def test_other_tenant_rejected(use_rows):
    use_rows([])
    with pytest.raises(HTTPException) as e:
        _run(sales_preset.salesperson_performance("F2", _req(factory_id="F1"), "2026-09-01", "2026-09-30"))
    assert e.value.status_code == 403


# ─── 销售毛利率 by 产品 ──────────────────────────────────────────────────


def _gm_row(pid, name, revenue, costed_revenue, cost, lines, costed_lines):
    return {
        "product_type_id": pid, "product_name": name, "unit": "kg",
        "total_qty": Decimal("10"), "order_count": 1,
        "line_count": lines, "costed_line_count": costed_lines,
        "revenue": Decimal(revenue),
        "costed_revenue": Decimal(costed_revenue) if costed_revenue is not None else None,
        "cost": Decimal(cost) if cost is not None else None,
    }


_GM_ROWS = [
    _gm_row("P1", "牛肉酱", "1000", "1000", "600", 2, 2),   # 40%
    _gm_row("P2", "火锅底料", "2000", "1000", "900", 4, 2),  # 10%, 成本覆盖一半收入
    _gm_row("P3", "蘸料", "500", None, None, 1, 0),          # 没回填成本
]


@pytest.mark.parametrize("role", [None, "", "operator", "warehouse_manager", "quality_inspector"])
def test_gross_margin_forbidden_without_price_view(use_rows, role):
    pool = use_rows(_GM_ROWS)
    with pytest.raises(HTTPException) as e:
        _run(sales_preset.gross_margin("F1", _req(role=role), "2026-09-01", "2026-09-30"))
    assert e.value.status_code == 403
    assert "价格查看权限" in e.value.detail
    assert pool.conn.fetch_args == []  # 被拦在查库之前


@pytest.mark.parametrize("role", ["finance_manager", "FACTORY_SUPER_ADMIN", "sales_manager"])
def test_gross_margin_allowed_roles(use_rows, role):
    use_rows(_GM_ROWS)
    res = _run(sales_preset.gross_margin("F1", _req(role=role), "2026-09-01", "2026-09-30"))
    assert res["success"] is True


def test_gross_margin_only_on_costed_lines_and_uncosted_last(use_rows):
    use_rows(_GM_ROWS)
    data = _run(sales_preset.gross_margin("F1", _req(), "2026-09-01", "2026-09-30"))["data"]
    by_id = {r["productTypeId"]: r for r in data["rank"]}

    assert [r["productTypeId"] for r in data["rank"]] == ["P1", "P2", "P3"]
    assert by_id["P1"]["marginRate"] == pytest.approx(0.4)
    assert by_id["P2"]["marginRate"] == pytest.approx(0.1)
    assert by_id["P2"]["costCoverage"] == pytest.approx(0.5)
    # 没成本 ≠ 毛利 100%: 全部为 None
    assert by_id["P3"]["cost"] is None
    assert by_id["P3"]["grossProfit"] is None
    assert by_id["P3"]["marginRate"] is None
    assert by_id["P3"]["costCoverage"] == pytest.approx(0.0)

    s = data["summary"]
    assert s["productCount"] == 3 and s["costedProductCount"] == 2
    assert s["cost"] == pytest.approx(1500.0)
    assert s["grossProfit"] == pytest.approx(500.0)
    assert s["marginRate"] == pytest.approx(0.25)
    assert s["costCoverage"] == pytest.approx(2000 / 3500)


def test_gross_margin_ascending_lists_lowest_first(use_rows):
    use_rows(_GM_ROWS)
    data = _run(sales_preset.gross_margin("F1", _req(), "2026-09-01", "2026-09-30", order="asc"))["data"]
    assert [r["productTypeId"] for r in data["rank"]] == ["P2", "P1", "P3"]


def test_gross_margin_no_cost_at_all_summary_is_none(use_rows):
    use_rows([_gm_row("P3", "蘸料", "500", None, None, 1, 0)])
    s = _run(sales_preset.gross_margin("F1", _req(), "2026-09-01", "2026-09-30"))["data"]["summary"]
    assert s["cost"] is None and s["grossProfit"] is None and s["marginRate"] is None


# ─── 月度增长率 ──────────────────────────────────────────────────────────


def _m_row(y, m, total, tax="0", orders=1):
    return {"m": date(y, m, 1), "order_count": orders, "total_amount": Decimal(total), "tax_amount": Decimal(tax)}


def test_growth_rates_and_query_window(use_rows):
    pool = use_rows([
        _m_row(2025, 8, "50"),
        _m_row(2025, 9, "100"),
        _m_row(2026, 7, "80"),
        _m_row(2026, 8, "100"),
        _m_row(2026, 9, "150"),
    ])
    data = _run(sales_preset.growth_rate("F1", _req(), endMonth="2026-09", months=3))["data"]

    # 查询多拉 12 个月作为同比基数: 窗口 2026-07..09 → 从 2025-07-01 查到 2026-09-30
    assert pool.conn.fetch_args[0][1:] == (date(2025, 7, 1), date(2026, 9, 30))
    months = {m["yearMonth"]: m for m in data["months"]}
    assert list(months) == ["2026-07", "2026-08", "2026-09"]
    assert months["2026-09"]["momRate"] == pytest.approx(0.5)
    assert months["2026-09"]["yoyRate"] == pytest.approx(0.5)
    assert months["2026-08"]["yoyRate"] == pytest.approx(1.0)
    # 基数为 0 (2026-06 无订单 / 2025-07 无订单) → 无法计算, 不是 0%
    assert months["2026-07"]["momRate"] is None
    assert months["2026-07"]["yoyRate"] is None


def test_growth_month_without_orders_is_real_zero(use_rows):
    use_rows([_m_row(2026, 7, "100")])
    data = _run(sales_preset.growth_rate("F1", _req(), endMonth="2026-08", months=2))["data"]
    aug = data["months"][1]
    assert aug["orderCount"] == 0
    assert aug["totalAmountWithTax"] == pytest.approx(0.0)
    assert aug["momRate"] == pytest.approx(-1.0)


def test_growth_year_boundary_and_partial_flag():
    payload = sales_preset._growth_payload(
        [_m_row(2025, 12, "100"), _m_row(2026, 1, "120")], 2026, 1, 2, today=date(2026, 1, 15),
    )
    jan = payload["months"][-1]
    assert jan["yearMonth"] == "2026-01"
    assert jan["momRate"] == pytest.approx(0.2)
    assert jan["isPartial"] is True
    assert payload["months"][0]["isPartial"] is False


@pytest.mark.parametrize("kwargs", [{"months": 0}, {"months": 37}, {"endMonth": "2026-13"}, {"endMonth": "bad"}])
def test_growth_bad_params(use_rows, kwargs):
    use_rows([])
    with pytest.raises(HTTPException) as e:
        _run(sales_preset.growth_rate("F1", _req(), **kwargs))
    assert e.value.status_code == 400


# ─── 区域销售对比 ────────────────────────────────────────────────────────


def _o(d, address, total, customer="C1", tax="0"):
    return {"order_date": d, "customer_id": customer, "address": address,
            "total_amount": Decimal(total), "tax_amount": Decimal(tax)}


_REGION_ROWS = [
    _o(date(2026, 9, 5), "江苏省苏州市", "300", "C1"),
    _o(date(2026, 9, 6), "昆山市玉山镇", "100", "C2"),
    _o(date(2026, 9, 7), "上海市闵行区", "200", "C3"),
    _o(date(2026, 9, 8), None, "50", "C4"),
    _o(date(2026, 9, 9), "仓库自提", "30", "C5"),
    _o(date(2025, 9, 5), "江苏省南京市", "200", "C1"),
    _o(date(2025, 9, 6), "浙江省杭州市", "80", "C6"),    # 去年有、今年没有
]


def test_region_province_level_with_yoy_and_buckets(use_rows):
    pool = use_rows(_REGION_ROWS)
    data = _run(sales_preset.regional_comparison("F1", _req(), "2026-09-01", "2026-09-30"))["data"]

    assert pool.conn.fetch_args[0][1:] == (date(2026, 9, 1), date(2026, 9, 30), date(2025, 9, 1), date(2025, 9, 30))
    regions = {r["region"]: r for r in data["regions"]}
    assert [r["region"] for r in data["regions"]] == ["江苏", "上海", "浙江"]
    assert regions["江苏"]["label"] == "江苏省"
    assert regions["江苏"]["totalAmountWithTax"] == pytest.approx(400.0)
    assert regions["江苏"]["customerCount"] == 2
    assert regions["江苏"]["yoyRate"] == pytest.approx(1.0)
    assert regions["上海"]["yoyRate"] is None          # 去年同期没有 → 无法计算
    assert regions["浙江"]["totalAmountWithTax"] == pytest.approx(0.0)
    assert regions["浙江"]["yoyRate"] == pytest.approx(-1.0)  # 今年掉到 0 要看得见

    assert data["noAddress"]["orderCount"] == 1
    assert data["unrecognized"]["orderCount"] == 1
    assert data["summary"]["totalAmountWithTax"] == pytest.approx(680.0)
    assert data["summary"]["recognizedShare"] == pytest.approx(600 / 680)


def test_region_big_region_level(use_rows):
    use_rows(_REGION_ROWS)
    data = _run(sales_preset.regional_comparison("F1", _req(), "2026-09-01", "2026-09-30", level="region"))["data"]
    east = data["regions"][0]
    assert east["region"] == "华东" and east["label"] == "华东"
    assert east["totalAmountWithTax"] == pytest.approx(600.0)
    assert east["lastYearAmountWithTax"] == pytest.approx(280.0)


def test_region_rejects_range_over_one_year(use_rows):
    use_rows([])
    with pytest.raises(HTTPException) as e:
        _run(sales_preset.regional_comparison("F1", _req(), "2025-01-01", "2026-06-30"))
    assert e.value.status_code == 400


def test_region_leap_day_shifts_to_feb_28(use_rows):
    pool = use_rows([])
    _run(sales_preset.regional_comparison("F1", _req(), "2028-02-01", "2028-02-29"))
    assert pool.conn.fetch_args[0][4] == date(2027, 2, 28)


# ─── 不再是 stub ─────────────────────────────────────────────────────────


@pytest.mark.parametrize("path", [
    "/salesperson-performance", "/gross-margin", "/growth-rate", "/regional-comparison",
])
def test_routes_registered(path):
    paths = {r.path for r in sales_preset.router.routes}
    assert f"/api/smartbi/{{factory_id}}/sales-preset{path}" in paths
