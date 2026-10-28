"""gross-margin 报表: discount_rate 是百分数 (5 = 打 95 折), 与 Java SalesOrderItem 一致。

SQLite 执行 handler 里未改动的聚合 SQL (算术/过滤覆盖, 不是 PostgreSQL 集成测试)。
折扣 0 的对照行证明: 折扣为 0 时数值与修复前一致 (prod 全部 79 条明细折扣均为 0)。
"""
import asyncio
import sqlite3
from contextlib import asynccontextmanager
from datetime import date
from types import SimpleNamespace

import pytest

from smartbi.api import sales_preset


class QueryPool:
    def __init__(self, connection):
        self.connection = connection
        self.factory = None

    @asynccontextmanager
    async def acquire(self):
        yield self

    async def execute(self, sql, factory):
        assert sql == "SELECT set_config('app.factory_id', $1, false)"
        self.factory = factory

    async def fetch(self, sql, *args):
        assert self.factory == args[0] == "F006"
        params = tuple(v.isoformat() if isinstance(v, date) else v for v in args)
        return self.connection.execute(sql, params).fetchall()


@pytest.fixture
def db(monkeypatch):
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.executescript("""
        CREATE TABLE sales_orders (
            id TEXT PRIMARY KEY, factory_id TEXT, order_date TEXT,
            status TEXT, deleted_at TEXT);
        CREATE TABLE sales_order_items (
            sales_order_id TEXT, product_type_id TEXT, product_name TEXT,
            quantity REAL, unit TEXT, unit_price REAL, discount_rate REAL,
            cost_unit_price REAL, deleted_at TEXT);
        INSERT INTO sales_orders VALUES ('o1','F006','2026-09-15','CONFIRMED',NULL);
    """)
    pool = QueryPool(connection)

    async def get_pool():
        return pool

    monkeypatch.setattr(sales_preset, "_get_pool", get_pool)
    yield connection
    connection.close()


@pytest.fixture(autouse=True)
def _full_cost_authority(monkeypatch):
    """口径测试按「有权看全部产品成本」的账号跑 (工艺保密 FULL)。"""
    from smartbi.services.factory_charts import cost_authorization

    async def _authorize_batches(request, factory_id, product_ids, *, unscoped=False):
        return cost_authorization.CostAuthorization(
            factory_id, 1, frozenset(product_ids), True)

    monkeypatch.setattr(cost_authorization, "authorize_batches", _authorize_batches)


def add_line(db, product, discount, cost, qty=2, price=100):
    db.execute("INSERT INTO sales_order_items VALUES ('o1',?,?,?,'kg',?,?,?,NULL)",
               (product, product, qty, price, discount, cost))


def margin_rows():
    request = SimpleNamespace(state=SimpleNamespace(factory_id="F006", role="finance_manager"))
    data = asyncio.run(sales_preset.gross_margin("F006", request, "2026-09-01", "2026-09-30"))["data"]
    return {r["productTypeId"]: r for r in data["rank"]}


def test_discount_five_percent_takes_five_percent_off_revenue(db):
    add_line(db, "DISC", 5, 60)
    r = margin_rows()["DISC"]
    assert r["revenue"] == pytest.approx(190)          # 2 * 100 * 0.95, 不是 2 * 100 * (1 - 5) = -800
    assert r["costedRevenue"] == pytest.approx(190)
    assert r["cost"] == pytest.approx(120)
    assert r["grossProfit"] == pytest.approx(70)
    assert r["marginRate"] == pytest.approx(70 / 190)


def test_discount_zero_and_null_control_is_unchanged(db):
    add_line(db, "ZERO", 0, 60)
    add_line(db, "NULLD", None, 60)
    rows = margin_rows()
    assert rows["ZERO"]["revenue"] == pytest.approx(200)
    assert rows["NULLD"]["revenue"] == pytest.approx(200)
    assert rows["ZERO"]["marginRate"] == pytest.approx(80 / 200)


# ---- NULL unit_price: 不是「收入 0」, 是「没有售价, 不计入」, 并单独计数 ----

def test_null_price_line_is_excluded_from_revenue_and_cost_and_counted(db):
    add_line(db, "MIX", 0, 60)                       # 2 * 100, 成本 60
    add_line(db, "MIX", 0, 60, qty=5, price=None)    # 没有售价: 旧 SQL 把它当收入 0 却照算成本 300
    r = margin_rows()["MIX"]
    assert r["revenue"] == pytest.approx(200)
    assert r["costedRevenue"] == pytest.approx(200)
    assert r["cost"] == pytest.approx(120)            # 不含无价明细的 5 * 60
    assert r["marginRate"] == pytest.approx(80 / 200)  # 旧口径: (200 - 420) / 200 = -110%
    assert r["lineCount"] == 2
    assert r["costedLineCount"] == 1
    assert r["missingPriceLineCount"] == 1


def test_only_null_price_lines_give_no_margin_not_zero_revenue_margin(db):
    add_line(db, "NOPRICE", 0, 60, price=None)
    r = margin_rows()["NOPRICE"]
    assert r["missingPriceLineCount"] == 1
    assert r["lineCount"] == 1
    assert r["costedLineCount"] == 0
    assert r["cost"] is None and r["grossProfit"] is None and r["marginRate"] is None


def test_zero_price_is_a_price_not_a_missing_price_control(db):
    add_line(db, "FREE", 0, 60, price=0)              # 赠品: 有价格(0), 不是缺价格
    r = margin_rows()["FREE"]
    assert r["missingPriceLineCount"] == 0
    assert r["costedLineCount"] == 1
    assert r["revenue"] == pytest.approx(0)
    assert r["cost"] == pytest.approx(120)
