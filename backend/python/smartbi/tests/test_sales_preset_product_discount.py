"""Execute the product-rank handler's SQL against synthetic in-memory tables.

SQLite executes the unchanged aggregate query; this is arithmetic/filter/ranking
coverage, not a PostgreSQL production integration test. No business DB is used.
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
        assert self.factory == args[0] == 'F006'
        # SQLite understands the query's $1..$4 bindings and NULLS LAST.
        params = tuple(value.isoformat() if isinstance(value, date) else value for value in args)
        return self.connection.execute(sql, params).fetchall()


@pytest.fixture
def query_db(monkeypatch):
    connection = sqlite3.connect(':memory:')
    connection.row_factory = sqlite3.Row
    connection.executescript('''
        CREATE TABLE sales_orders (
            id TEXT PRIMARY KEY, factory_id TEXT, order_date TEXT,
            status TEXT, deleted_at TEXT);
        CREATE TABLE sales_order_items (
            sales_order_id TEXT, product_type_id TEXT, product_name TEXT,
            quantity REAL, unit TEXT, unit_price REAL, tax_rate REAL,
            discount_rate REAL, deleted_at TEXT);
        INSERT INTO sales_orders VALUES ('order-1','F006','2026-09-15','CONFIRMED',NULL);
    ''')
    pool = QueryPool(connection)

    async def get_pool():
        return pool

    monkeypatch.setattr(sales_preset, '_get_pool', get_pool)
    yield connection
    connection.close()


def add_line(db, discount, product='DISCOUNTED', quantity=2, price=100, order='order-1', deleted=None):
    db.execute('INSERT INTO sales_order_items VALUES (?,?,?,?,?,?,?,?,?)',
               (order, product, product, quantity, 'kg', price, 10, discount, deleted))


def report():
    request = SimpleNamespace(state=SimpleNamespace(factory_id='F006', role='finance_manager'))
    return asyncio.run(sales_preset.product_rank('F006', request, '2026-09-01', '2026-09-30'))['data']['rank']


@pytest.mark.parametrize('discount,net,tax,gross', [
    (None, 200, 20, 220),
    (0, 200, 20, 220),
    (0.25, 150, 15, 165),
    (1, 0, 0, 0),
])
def test_discount_applies_to_net_tax_and_gross(query_db, discount, net, tax, gross):
    add_line(query_db, discount)
    row, = report()
    assert row['taxableAmount'] == pytest.approx(net)
    assert row['taxAmount'] == pytest.approx(tax)
    assert row['totalAmountWithTax'] == pytest.approx(gross)
    assert row['revenue'] == pytest.approx(net)
    assert row['totalQty'] == 2
    assert row['orderCount'] == 1


def test_mixed_line_discounts_aggregate_without_changing_quantity(query_db):
    add_line(query_db, 0.25)
    add_line(query_db, None, quantity=1, price=50)
    row, = report()
    assert row['taxableAmount'] == pytest.approx(200)
    assert row['taxAmount'] == pytest.approx(20)
    assert row['totalAmountWithTax'] == pytest.approx(220)
    assert row['totalQty'] == 3
    assert row['orderCount'] == 1


def test_rank_uses_discounted_tax_inclusive_sum(query_db):
    add_line(query_db, 0.25)
    add_line(query_db, 0, product='PLAIN', quantity=1, price=175)
    rows = report()
    assert [row['productTypeId'] for row in rows] == ['PLAIN', 'DISCOUNTED']
    assert [row['rank'] for row in rows] == [1, 2]
    assert rows[0]['totalAmountWithTax'] == pytest.approx(192.5)
    assert rows[1]['totalAmountWithTax'] == pytest.approx(165)


def test_discount_keeps_tenant_date_status_and_soft_delete_filters(query_db):
    add_line(query_db, 0.25)
    excluded = [
        ('other-tenant', 'F007', '2026-09-15', 'CONFIRMED', None),
        ('outside-range', 'F006', '2026-08-31', 'CONFIRMED', None),
        ('draft', 'F006', '2026-09-15', 'DRAFT', None),
        ('cancelled', 'F006', '2026-09-15', 'CANCELLED', None),
        ('deleted-order', 'F006', '2026-09-15', 'CONFIRMED', '2026-09-16'),
    ]
    query_db.executemany('INSERT INTO sales_orders VALUES (?,?,?,?,?)', excluded)
    for order, *_ in excluded:
        add_line(query_db, 0, order=order, quantity=10, price=10000)
    add_line(query_db, 0, quantity=10, price=10000, deleted='2026-09-16')
    row, = report()
    assert row['totalAmountWithTax'] == pytest.approx(165)
    assert row['orderCount'] == 1
    assert row['totalQty'] == 2
