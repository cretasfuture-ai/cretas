"""主数据完整度: 接口级 + 真实数据形态测试 (复审 H1/H2/M5)。

不连真库: 用内存里的「假表」解释我们生成的 SQL 不现实, 所以这里分两层:
  * 口径: 联系人/电话表达式必须覆盖现行写入列 contact_person/phone 与历史列 contact_name/contact_phone,
    用 sqlite 对同一段表达式真实求值 (COALESCE/NULLIF/TRIM 语义一致), 样本取自各写入路径的实际形态。
  * 接口: TestClient + 假连接池, 守租户 403 / 角色 403 / 字段白名单 400 / limit 钳制 / total_missing 取 COUNT。
"""
import os
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


def _client(monkeypatch, role, factory="F001", total=321, user_id=7):
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
        request.state.user_id = user_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(cc.router, prefix="/api/client-requirement")
    return TestClient(app), log


URL = "/api/client-requirement/completeness/missing"


def _grants(monkeypatch, granted):
    """假 Java 权限判定: granted = {(factory, user_id): {权限码, ...}}; 记录每次被问的 (工厂, 用户, 码)。"""
    asked = []

    async def fake(factory_id, user_id, codes):
        asked.append((factory_id, user_id, list(codes)))
        return bool(set(codes) & granted.get((factory_id, user_id), set()))

    monkeypatch.setattr(cc, "_java_user_has_any_permission", fake)
    return asked


def test_unprivileged_role_gets_403_and_no_query_runs(monkeypatch):
    c, log = _client(monkeypatch, "operator")
    _grants(monkeypatch, {})
    r = c.post(URL, json={"entity_type": "CUSTOMER", "field": "credit_limit"})
    assert r.status_code == 403 and log == []
    r = c.post(URL, json={"entity_type": "SUPPLIER", "field": "bank_account"})
    assert r.status_code == 403 and log == []


def test_same_role_allowed_in_factory_a_but_revoked_in_factory_b(monkeypatch):
    # 工厂在 L2 收回权限后, Python 立刻跟 (判据来自 Java 权限, 不是写死的岗位名)
    granted = {("A", 7): {"sales:read"}}
    ca, _ = _client(monkeypatch, "dispatcher", factory="A")
    asked = _grants(monkeypatch, granted)
    assert ca.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 200
    cb, log_b = _client(monkeypatch, "dispatcher", factory="B")
    asked = _grants(monkeypatch, granted)
    assert cb.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 403
    assert log_b == [] and asked[-1][:2] == ("B", 7)


def test_role_not_in_old_hardcoded_list_is_allowed_when_permission_is_granted(monkeypatch):
    # viewer / dispatcher 这类过去会被写死角色表误拒的岗位, 只要有权限码就能看
    c, _ = _client(monkeypatch, "viewer")
    _grants(monkeypatch, {("F001", 7): {"procurement:read"}})
    assert c.post(URL, json={"entity_type": "SUPPLIER", "field": "address"}).status_code == 200
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 403


def test_permission_codes_sent_to_java_are_the_entity_group(monkeypatch):
    c, _ = _client(monkeypatch, "sales_manager")
    asked = _grants(monkeypatch, {("F001", 7): {"sales:read_write"}})
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 200
    assert asked[-1][2] == cc.MASTER_DATA_DRILLDOWN_PERMISSIONS["CUSTOMER"]
    assert c.post(URL, json={"entity_type": "SUPPLIER", "field": "address"}).status_code == 403
    assert asked[-1][2] == cc.MASTER_DATA_DRILLDOWN_PERMISSIONS["SUPPLIER"]


def test_same_role_but_personally_revoked_user_gets_403(monkeypatch):
    # 同岗位 (sales_manager) 两个人: 甲有权, 乙被个人 REVOKE —— 判据按人, 不按岗位
    granted = {("F001", 1): {"sales:read"}}
    c1, _ = _client(monkeypatch, "sales_manager", user_id=1)
    _grants(monkeypatch, granted)
    assert c1.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 200
    c2, log2 = _client(monkeypatch, "sales_manager", user_id=2)
    _grants(monkeypatch, granted)
    assert c2.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 403
    assert log2 == []


def test_missing_user_id_is_403(monkeypatch):
    c, log = _client(monkeypatch, "sales_manager", user_id=None)
    _grants(monkeypatch, {("F001", 7): {"sales:read"}})
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 403 and log == []


def _fake_httpx(monkeypatch, behavior):
    """假 httpx.AsyncClient: behavior(url) -> 返回 (status, json) 或抛异常; 记录访问过的 url。"""
    urls = []

    class _Resp:
        def __init__(self, status, body):
            self.status_code, self._b = status, body

        def raise_for_status(self):
            if self.status_code >= 400:
                raise RuntimeError(f"http {self.status_code}")

        def json(self):
            return self._b

    class _Cli:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url, **kw):
            urls.append((url, kw.get("params")))
            status, body = behavior(url)
            return _Resp(status, body)

    monkeypatch.setattr(cc.httpx, "AsyncClient", _Cli)
    return urls


def test_blue_green_failover_10010_refused_falls_to_10020(monkeypatch):
    monkeypatch.delenv("JAVA_API_BASE_URL", raising=False)
    monkeypatch.delenv("BACKEND_BASE_URL", raising=False)

    def behavior(url):
        if ":10010" in url:
            raise cc.httpx.ConnectError("refused")
        return 200, {"data": {"allowed": True}}

    urls = _fake_httpx(monkeypatch, behavior)
    c, _ = _client(monkeypatch, "sales_manager")
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 200
    assert [u.split("/api/")[0] for u, _ in urls] == ["http://127.0.0.1:10010", "http://127.0.0.1:10020"]
    assert urls[-1][1]["userId"] == 7 and urls[-1][1]["factoryId"] == "F001"


def test_configured_base_does_not_fail_over_and_both_refused_is_503(monkeypatch):
    monkeypatch.setenv("JAVA_API_BASE_URL", "http://127.0.0.1:10010")

    def behavior(url):
        raise cc.httpx.ConnectError("refused")

    urls = _fake_httpx(monkeypatch, behavior)
    c, log = _client(monkeypatch, "sales_manager")
    assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 503
    assert len(urls) == 1 and log == []


def test_java_rejecting_the_internal_key_is_503_and_logged_as_config(monkeypatch, caplog):
    monkeypatch.setenv("JAVA_API_BASE_URL", "http://127.0.0.1:10010")
    _fake_httpx(monkeypatch, lambda url: (403, {}))
    c, log = _client(monkeypatch, "sales_manager")
    with caplog.at_level("ERROR"):
        assert c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"}).status_code == 503
    assert "config" in caplog.text and log == []


def test_permission_service_down_is_503_not_allowed(monkeypatch):
    c, log = _client(monkeypatch, "factory_super_admin")

    class _Boom:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            raise RuntimeError("connection refused")

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(cc.httpx, "AsyncClient", _Boom)
    r = c.post(URL, json={"entity_type": "CUSTOMER", "field": "tax_number"})
    assert r.status_code == 503 and log == []


def _java_src(rel):
    from pathlib import Path
    p = Path(__file__).resolve().parents[3] / "backend/java/cretas-api/src/main/java/com/cretas/aims" / rel
    if not p.exists():
        if os.environ.get("CI"):
            pytest.fail(f"CI 是全仓检出, Java 源码必须在: {p}")  # 跳过不算通过
        pytest.skip(f"Java 源码不在此检出中: {p}")
    return p.read_text(encoding="utf-8")


def test_python_permission_codes_equal_the_java_endpoints_codes():
    import re
    customer = _java_src("controller/CustomerController.java")
    roster = [re.search(rf'{n}\s*=\s*"([^"]+)"', customer).group(1)
              for n in ("ROSTER_SALES_RW", "ROSTER_SALES_R", "ROSTER_FINANCE_RW", "ROSTER_FINANCE_R")]
    assert sorted(cc.MASTER_DATA_DRILLDOWN_PERMISSIONS["CUSTOMER"]) == sorted(roster)

    supplier = _java_src("controller/SupplierController.java")
    export = re.search(r'@RequirePermission\(\{([^}]*)\}\)\s*@GetMapping\("/export"\)', supplier).group(1)
    assert sorted(cc.MASTER_DATA_DRILLDOWN_PERMISSIONS["SUPPLIER"]) == sorted(re.findall(r'"([^"]+)"', export))

    perm = _java_src("service/PermissionService.java")
    maintain = re.search(r'MATERIAL_TYPE_MAINTAIN_PERMISSION\s*=\s*"([^"]+)"', perm).group(1)
    assert cc.MASTER_DATA_DRILLDOWN_PERMISSIONS["MATERIAL_TYPE"] == [maintain]


def test_privileged_role_gets_total_from_count_not_from_limit(monkeypatch):
    c, log = _client(monkeypatch, "factory_super_admin", total=321)
    _grants(monkeypatch, {("F001", 7): {"procurement:read", "finance:read"}})
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


def test_empty_master_table_overall_is_none_not_zero_but_production_stays_zero(monkeypatch):
    # 走真实的 compute_completeness 分支: 没有记录 (total=0) 时主数据 overall 为 None, 生产实体保持旧的 0
    class _C:
        async def fetchrow(self, q, *a):
            return {"total": 0}

    class _P(_FakePool):
        pass

    pool = _P(_C())

    async def fake_pool():
        return pool

    monkeypatch.setattr(cc, "_get_db_pool", fake_pool)
    import asyncio
    res = asyncio.run(cc.compute_completeness("F001", ["SUPPLIER", "EQUIPMENT"]))
    by = {r.entity_type: r for r in res}
    assert by["SUPPLIER"].overall_completeness is None
    assert by["EQUIPMENT"].overall_completeness == 0
