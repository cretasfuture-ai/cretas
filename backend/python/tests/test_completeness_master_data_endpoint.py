"""主数据完整度: 接口级 + 真实数据形态测试 (复审 H1/H2/M5)。

不连真库: 用内存里的「假表」解释我们生成的 SQL 不现实, 所以这里分两层:
  * 口径: 联系人/电话表达式必须覆盖现行写入列 contact_person/phone 与历史列 contact_name/contact_phone,
    用 sqlite 对同一段表达式真实求值 (COALESCE/NULLIF/TRIM 语义一致), 样本取自各写入路径的实际形态。
  * 接口: TestClient + 假连接池, 守租户 403 / 角色 403 / 字段白名单 400 / limit 钳制 / total_missing 取 COUNT。
"""
import sqlite3

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from client_requirement import completeness_calculator as cc


# ─────────── 口径: 真实数据形态 ───────────

def _sqlite_expr(expr: str) -> str:
    return expr.replace("BTRIM(", "TRIM(")


def _eval_missing(table_cols, rows, entity, field):
    """用 sqlite 对 build_missing_query 的 WHERE 条件真实求值, 返回被判缺失的行 id。"""
    q = cc.build_missing_query(entity, field)
    cond = q.split("AND deleted_at IS NULL AND ", 1)[1].split(" ORDER BY")[0]
    db = sqlite3.connect(":memory:")
    db.execute(f"CREATE TABLE t (id INTEGER, {', '.join(c + ' TEXT' for c in table_cols)})")
    for r in rows:
        db.execute(f"INSERT INTO t VALUES ({', '.join('?' * (len(table_cols) + 1))})", r)
    return {i for (i,) in db.execute(f"SELECT id FROM t WHERE {_sqlite_expr(cond)}")}


SUPPLIER_COLS = ["contact_person", "contact_name", "phone", "contact_phone"]
SUPPLIER_ROWS = [
    # id, contact_person, contact_name, phone, contact_phone
    (1, "张三", None, "13800000000", None),      # 供应商表单: 只写 contact_person/phone
    (2, None, "李四", None, "13900000000"),      # 历史双写列
    (3, "  ", "   ", "", None),                  # 全空白: 真缺失
    (4, None, None, None, None),                 # 全空: 真缺失
]


def test_supplier_with_only_contact_person_and_phone_is_not_missing():
    assert _eval_missing(SUPPLIER_COLS, SUPPLIER_ROWS, "SUPPLIER", "contact") == {3, 4}
    assert _eval_missing(SUPPLIER_COLS, SUPPLIER_ROWS, "SUPPLIER", "phone") == {3, 4}


def test_customer_excel_import_shape_not_missing():
    # CustomerServiceImpl.convertFromExportDTO 只写 contact_person/phone
    assert _eval_missing(SUPPLIER_COLS, SUPPLIER_ROWS, "CUSTOMER", "contact") == {3, 4}


def test_completeness_query_counts_contact_with_same_expression_as_missing_query():
    q = cc.build_completeness_query("SUPPLIER")
    for f in ("contact", "phone"):
        assert f"COUNT({cc._CONTACT_EXPRS[f]}) as cnt_{f}" in q
        assert f"({cc._CONTACT_EXPRS[f]}) IS NULL" in cc.build_missing_query("SUPPLIER", f)


def test_tax_rate_is_text_field():
    assert "tax_rate" in cc.ENTITY_FIELD_MAP["MATERIAL_TYPE"]["text_fields"]
    assert "COUNT(NULLIF(BTRIM(tax_rate), '')) as cnt_tax_rate" in cc.build_completeness_query("MATERIAL_TYPE")


# ─────────── 接口 ───────────

class _FakeConn:
    def __init__(self, log, total):
        self.log, self.total = log, total

    async def fetch(self, query, *args):
        self.log.append(("fetch", query, args))
        return [{"id": "7", "label": "某供应商"}]

    async def fetchval(self, query, *args):
        self.log.append(("fetchval", query, args))
        return self.total


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, conn):
        self.conn = conn
        self.closed = False

    def acquire(self):
        return _Acquire(self.conn)

    async def close(self):
        self.closed = True


def _client(monkeypatch, role, factory="F001", total=321):
    log = []
    pool = _FakePool(_FakeConn(log, total))

    async def fake_pool():
        return pool

    monkeypatch.setattr(cc, "_get_db_pool", fake_pool)
    app = FastAPI()

    @app.middleware("http")
    async def _auth(request, call_next):
        request.state.factory_id = factory
        request.state.role = role
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(cc.router, prefix="/api/client-requirement")
    return TestClient(app), log


URL = "/api/client-requirement/completeness/missing"


def test_unprivileged_role_gets_403_and_no_query_runs(monkeypatch):
    c, log = _client(monkeypatch, "operator")
    r = c.post(URL, json={"entity_type": "CUSTOMER", "field": "credit_limit"})
    assert r.status_code == 403 and log == []
    r = c.post(URL, json={"entity_type": "SUPPLIER", "field": "bank_account"})
    assert r.status_code == 403 and log == []


def test_roster_roles_are_not_interchangeable(monkeypatch):
    # 采购看得到供应商, 看不到客户; 销售反之
    c, _ = _client(monkeypatch, "procurement_manager")
    assert c.post(URL, json={"entity_type": "SUPPLIER", "field": "address"}).status_code == 200
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "address"}).status_code == 400  # address 非客户列
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 403
    c, _ = _client(monkeypatch, "sales_manager")
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 200
    assert c.post(URL, json={"entity_type": "SUPPLIER", "field": "address"}).status_code == 403


def test_privileged_role_gets_total_from_count_not_from_limit(monkeypatch):
    c, log = _client(monkeypatch, "factory_super_admin", total=321)
    r = c.post(URL, json={"entity_type": "SUPPLIER", "field": "contact", "limit": 1000})
    assert r.status_code == 200
    d = r.json()["data"]
    assert d["total_missing"] == 321 and d["records"] == [{"id": "7", "label": "某供应商"}]
    fetch = next(x for x in log if x[0] == "fetch")
    assert fetch[2] == ("F001", 200)  # limit 钳到 200, 租户取登录身份


def test_cross_factory_is_403(monkeypatch):
    c, log = _client(monkeypatch, "factory_super_admin", factory="F001")
    r = c.post(URL, json={"factory_id": "F002", "entity_type": "SUPPLIER", "field": "contact"})
    assert r.status_code == 403 and log == []


@pytest.mark.parametrize("entity,field", [("CUSTOMER", "password"), ("users", "name"), ("SUPPLIER", "x; DROP")])
def test_bad_whitelist_is_400_even_for_admin(monkeypatch, entity, field):
    c, log = _client(monkeypatch, "factory_super_admin")
    assert c.post(URL, json={"entity_type": entity, "field": field}).status_code == 400 and log == []


def test_empty_master_table_overall_is_none_not_zero():
    # 没有任何记录 ≠ 全部缺失
    assert cc.CompletenessResult(entity_type="SUPPLIER", total_records=0, field_completeness={}).overall_completeness is None
