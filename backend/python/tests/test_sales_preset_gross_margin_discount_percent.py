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


def add_line(db, product, discount, cost):
    db.execute("INSERT INTO sales_order_items VALUES ('o1',?,?,2,'kg',100,?,?,NULL)",
               (product, product, discount, cost))


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
