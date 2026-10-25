"""经营分析页「销售概览」: 工厂销售订单汇总接口。

原来这张卡读的是餐饮 POS 汇总 (账单/门店), 工厂账号下全空。现在读工厂业务库的销售订单:
口径与 Java SalesServiceImpl.getSalesStatistics 对齐 (金额 = 订单 total_amount, 不计已取消),
另外不计草稿; 工厂来自登录身份。
"""
import os
import sys
from datetime import date
from decimal import Decimal
from typing import Optional

import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

STATUS_ROWS = [
    {"status": "COMPLETED", "orders": 20, "amount": Decimal("10000.00"), "amount_rows": 20,
     "first_date": date(2026, 6, 1), "last_date": date(2026, 10, 3)},
    {"status": "CONFIRMED", "orders": 5, "amount": Decimal("2500.50"), "amount_rows": 5,
     "first_date": date(2026, 7, 1), "last_date": date(2026, 10, 20)},
    {"status": "PROCESSING", "orders": 3, "amount": None, "amount_rows": 0,
     "first_date": date(2026, 8, 1), "last_date": date(2026, 9, 1)},
    {"status": "CANCELLED", "orders": 30, "amount": Decimal("99999.00"), "amount_rows": 30,
     "first_date": date(2026, 5, 1), "last_date": date(2026, 10, 22)},
    {"status": "DRAFT", "orders": 1, "amount": Decimal("777.00"), "amount_rows": 1,
     "first_date": date(2026, 10, 21), "last_date": date(2026, 10, 21)},
    {"status": "FINANCE_REJECTED", "orders": 2, "amount": Decimal("8849.56"), "amount_rows": 2,
     "first_date": date(2026, 4, 1), "last_date": date(2026, 10, 23)},
]
CUSTOMER_ROWS = [
    {"customer_name": "华东商超", "orders": 12, "amount": Decimal("8000.00")},
    {"customer_name": "某经销商", "orders": 8, "amount": Decimal("3000.50")},
]
PRODUCT_ROWS = [
    {"product_name": "黄油鸡 800g", "unit": "盒", "quantity": Decimal("240"), "amount": Decimal("9600.00")},
    {"product_name": "卤猪蹄 200g", "unit": "袋", "quantity": Decimal("100"), "amount": Decimal("1500.00")},
]


class _Conn:
    def __init__(self, calls, empty):
        self.calls = calls
        self.empty = empty

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        if self.empty:
            return []
        if "GROUP BY so.status" in sql:
            return STATUS_ROWS
        if "customers" in sql:
            return CUSTOMER_ROWS
        if "sales_order_items" in sql:
            return PRODUCT_ROWS
        raise AssertionError(f"unexpected SQL: {sql}")


class _Pool:
    def __init__(self, empty=False):
        self.calls = []
        self.empty = empty

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                return _Conn(pool.calls, pool.empty)

            async def __aexit__(self, *exc):
                return False
        return _Ctx()


def _client(monkeypatch, token_factory: Optional[str], empty=False):
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient
    from smartbi.api import factory_overview as api

    pool = _Pool(empty)

    async def fake_pool():
        return pool

    monkeypatch.setattr("smartbi.config.get_cretas_pool", fake_pool)
    app = FastAPI()

    @app.middleware("http")
    async def inject_claims(request: Request, call_next):
        request.state.factory_id = token_factory
        return await call_next(request)

    app.include_router(api.router, prefix="/api/smartbi/factory-overview")
    return TestClient(app), pool


class TestSalesSummary:
    def test_totals_exclude_cancelled_draft_and_rejected(self, monkeypatch):
        client, _ = _client(monkeypatch, "F006")
        body = client.get("/api/smartbi/factory-overview/sales").json()
        assert body["success"] is True
        d = body["data"]
        assert d["orderCount"] == 61
        assert d["effectiveOrderCount"] == 28  # 20 已完成 + 5 已确认 + 3 处理中
        assert d["cancelledCount"] == 30 and d["draftCount"] == 1 and d["rejectedCount"] == 2
        assert d["inFlightCount"] == 8  # 已确认 + 处理中 (Java SO_IN_FLIGHT)
        assert d["orderAmount"] == 12500.5  # 不含已取消 99999、草稿 777、财务驳回 8849.56
        assert d["ordersWithoutAmount"] == 3  # 处理中那 3 张没有金额: 不当成 0
        assert d["firstOrderDate"] == "2026-06-01" and d["lastOrderDate"] == "2026-10-20"
        assert d["topCustomers"][0] == {"name": "华东商超", "orders": 12, "amount": 8000.0}
        assert d["topProducts"][0] == {"name": "黄油鸡 800g", "unit": "盒", "quantity": 240.0, "amount": 9600.0}

    def test_tenant_comes_from_login_not_from_query(self, monkeypatch):
        client, pool = _client(monkeypatch, "F006")
        client.get("/api/smartbi/factory-overview/sales?factory_id=F001")
        assert pool.calls, "no query ran"
        for sql, args in pool.calls:
            assert "so.factory_id = $1" in sql, sql
            assert args[0] == "F006", args
            assert "so.deleted_at IS NULL" in sql, sql
            if "GROUP BY so.status" not in sql:  # 排名两条 SQL 自己排除不算成交的单
                assert "NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')" in sql, sql

    def test_anonymous_is_rejected(self, monkeypatch):
        client, pool = _client(monkeypatch, None)
        resp = client.get("/api/smartbi/factory-overview/sales")
        assert resp.status_code == 401
        assert pool.calls == []

    def test_no_orders_says_so_instead_of_zero(self, monkeypatch):
        client, _ = _client(monkeypatch, "F006", empty=True)
        d = client.get("/api/smartbi/factory-overview/sales").json()["data"]
        assert d["orderCount"] == 0
        assert d["orderAmount"] is None
        assert d["firstOrderDate"] is None and d["topCustomers"] == [] and d["topProducts"] == []


def test_unconfigured_database_is_an_explicit_503(monkeypatch):
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient
    from smartbi.api import factory_overview as api

    async def no_pool():
        return None

    monkeypatch.setattr("smartbi.config.get_cretas_pool", no_pool)
    app = FastAPI()

    @app.middleware("http")
    async def inject_claims(request: Request, call_next):
        request.state.factory_id = "F006"
        return await call_next(request)

    app.include_router(api.router, prefix="/api/smartbi/factory-overview")
    resp = TestClient(app).get("/api/smartbi/factory-overview/sales")
    assert resp.status_code == 503
    assert "业务数据库" in resp.json()["detail"]


def test_router_is_mounted_and_not_public():
    import main
    paths = {getattr(r, "path", "") for r in main.app.routes}
    assert "/api/smartbi/factory-overview/sales" in paths
    from auth_middleware import PUBLIC_PREFIXES
    assert not any("/api/smartbi/factory-overview".startswith(p) for p in PUBLIC_PREFIXES)
