"""Synthetic wire contracts explicitly do not claim to represent the vendor schema."""
import hashlib
import json
from datetime import datetime, timezone

import httpx
import pytest

from blacklake.client import BlacklakeConnector
from blacklake.contracts import BlacklakeError, ENDPOINTS, Settings, load_contract
from blacklake.metrics import cost_coverage, task_metrics, production_bi
from blacklake.sync import normalize, timestamp


def settings(factory="factory-test"):
    return Settings("tenant-test", factory, "synthetic-phone", "synthetic-password")


def contract():
    return {"wire_verified": True, "code_path": "status", "token_path": "session.token",
            "resources": {"project": {
                "items_path": "result.rows", "total_path": "result.total",
                "page_key": "page", "size_key": "size", "first_page": 1,
                "factory_path": "factory", "id_path": "id", "updated_path": "updated",
                "mapping_verified": True, "window_verified": True,
                "updated_only_verified": True, "window_basis": "updated",
                "max_window_days": 7, "window_start_key": "after", "window_end_key": "before",
                "fields": {"good": "fineAmount", "planned": "planAmount"}}}}


class Coordination:
    def __init__(self):
        self.token, self.factory, self.calls = None, None, []

    def check_lease(self):
        pass

    def acquire(self, path, body, login=False):
        self.calls.append((path, login))

    def get_token(self):
        return self.token

    def get_factory(self):
        return self.factory

    def set_token(self, value, factory=None):
        self.token = value
        self.factory = factory

    def clear_token(self):
        self.token = None
        self.factory = None


def response(rows, total=None):
    return httpx.Response(200, json={"status": "01000000", "result": {
        "rows": rows, "total": len(rows) if total is None else total}})


def client(handler, co=None):
    return BlacklakeConnector(settings(), contract(), co or Coordination(),
                              transport=httpx.MockTransport(handler))


def test_hash_login_token_reuse_and_read_only_paths():
    requests = []

    def handler(request):
        requests.append(request)
        assert request.method == "POST"
        assert request.headers["x-client"] == "lite-web"
        if request.url.path.endswith("_login"):
            assert json.loads(request.content) == {
                "type": 0, "phone": "synthetic-phone",
                "password": hashlib.sha3_224(b"synthetic-password").hexdigest()}
            return httpx.Response(200, json={"status": "01000000", "session": {"token": "synthetic-token"}})
        assert request.headers["x-auth"] == "synthetic-token"
        return response([])

    c = client(handler)
    c.query("project", {})
    c.query("project", {})
    assert len(requests) == 3
    with pytest.raises(BlacklakeError, match="ALLOWLIST"):
        c.query("create_project", {})
    with pytest.raises(BlacklakeError, match="ALLOWLIST"):
        c._send("/api/delete", {})
    assert len(requests) == 3


def test_auth_refresh_once_and_permission_error_never_relogs():
    co = Coordination()
    co.token = "expired"
    calls = []

    def handler(request):
        calls.append(request.url.path)
        if request.url.path.endswith("_login"):
            return httpx.Response(200, json={"status": "01000000", "session": {"token": "new"}})
        return httpx.Response(401)

    with pytest.raises(BlacklakeError, match="AUTH_REFRESH_EXHAUSTED"):
        client(handler, co).query("project", {})
    assert sum(path.endswith("_login") for path in calls) == 1
    co.token = "not-expired"
    c = client(lambda req: httpx.Response(403, text="secret-from-upstream"), co)
    with pytest.raises(BlacklakeError, match="UPSTREAM_HTTP_ERROR") as exc:
        c.query("project", {})
    assert "secret" not in str(exc.value)


@pytest.mark.parametrize("reply,code", [
    (httpx.Response(200, json={"status": "999", "message": "secret"}), "BUSINESS"),
    (httpx.Response(200, text="<html>secret</html>"), "INVALID_JSON"),
    (httpx.Response(302, headers={"location": "https://untrusted.invalid"}), "HTTP_ERROR"),
    (httpx.Response(429), "RETRY_NEXT_RUN"),
    (httpx.Response(503), "RETRY_NEXT_RUN"),
])
def test_fail_closed_and_sanitized_errors(reply, code):
    co = Coordination()
    co.token = "t"
    with pytest.raises(BlacklakeError, match=code):
        client(lambda request: reply, co).query("project", {})


def test_pagination_and_time_filters_are_preserved():
    seen = []
    co = Coordination()
    co.token = "t"

    def handler(request):
        body = json.loads(request.content)
        seen.append(body)
        return response([{"id": body["page"]}], 2)

    rows = list(client(handler, co).pages("project", {"after": "start", "before": "end"}, page_size=1))
    assert rows == [[{"id": 1}], [{"id": 2}]]
    assert seen == [{"after": "start", "before": "end", "page": i, "size": 1} for i in (1, 2)]


@pytest.mark.parametrize("case", ["repeat", "short", "changed", "oversized", "badtotal"])
def test_pagination_rejects_incomplete_or_unstable_results(case):
    co = Coordination()
    co.token = "t"
    count = 0

    def handler(request):
        nonlocal count
        count += 1
        if case == "repeat":
            return response([{"id": 1}], 2)
        if case == "short":
            return response([], 2)
        if case == "changed":
            return response([{"id": count}], count + 1)
        if case == "oversized":
            return response([{}, {}], 2)
        return response([], "0")

    with pytest.raises(BlacklakeError, match="PAGINATION"):
        list(client(handler, co).pages("project", {}, page_size=1))


@pytest.mark.parametrize("size", [0, 101, True])
def test_page_limit(size):
    with pytest.raises(BlacklakeError, match="PAGE_SIZE"):
        list(client(lambda req: response([])).pages("project", {}, size))


@pytest.mark.parametrize("order_type", [None, 0, "10", True, 30])
def test_stock_direction_is_required(order_type):
    with pytest.raises(BlacklakeError, match="ORDER_TYPE"):
        client(lambda req: response([])).query("stock_order", {"orderType": order_type})


def test_mapping_scope_missing_values_and_zero():
    spec = contract()["resources"]["project"]
    row = {"factory": "factory-test", "id": 1, "updated": "2026-10-31T00:00:00Z", "fineAmount": 0}
    external_id, updated, mapped = normalize(row, spec, settings())
    assert external_id == "1" and updated.tzinfo == timezone.utc
    assert mapped == {"good": "0", "planned": None}
    with pytest.raises(BlacklakeError, match="FACTORY_MISMATCH"):
        normalize(row, spec, settings("another-factory"))


@pytest.mark.parametrize("value", ["NaN", "Infinity", -1, {}, True])
def test_invalid_number(value):
    spec = contract()["resources"]["project"]
    with pytest.raises(BlacklakeError, match="NUMERIC"):
        normalize({"factory": "factory-test", "id": 1, "updated": "2026-10-31T00:00:00Z",
                   "fineAmount": value}, spec, settings())


def test_metrics_missing_zero_approval_and_cost_semantics():
    missing = task_metrics({})
    assert missing["good_rate"] is None and missing["labor_cost"] is None
    assert task_metrics({"good": "0", "defective": "0", "planned": "0"})["good_rate"] is None
    actual = task_metrics({"good": "90", "defective": "10", "planned": "100"})
    assert actual["good_rate"] == "0.9" and actual["plan_attainment"] == "0.9"
    costs = cost_coverage([{"material_cost_signed": "0"}, {}, {"material_cost_signed": "-5"}])
    assert costs["priced_records"] == 2 and costs["signed_stock_movement_cost"] == "-5"
    assert not costs["is_manufacturing_cost"]
    assert cost_coverage([])["coverage"] is None


def test_unverified_contract_and_unapproved_origin(tmp_path):
    path = tmp_path / "contract.json"
    path.write_text(json.dumps({"wire_verified": False}))
    with pytest.raises(BlacklakeError, match="NOT_VERIFIED"):
        load_contract(str(path))
    with pytest.raises(BlacklakeError, match="ORIGIN"):
        Settings("t", "f", "p", "s", "http://localhost:8080")
    with pytest.raises(BlacklakeError, match="TIMEZONE"):
        timestamp("2026-10-31T00:00:00")
    assert set(ENDPOINTS) == {"project", "project_expand", "task", "output", "product", "process",
                             "routing", "materials", "stock_order", "stock", "warehouse", "unit", "bom",
                             # 2026-11-12 read-only supplements (live_extras.py), each probed with one page.
                             "project_cost", "project_cost_detail", "inspection_item", "inspection_spec",
                             "inspection_order", "inspection_result", "collaborative_task", "sale_order",
                             "purchase_order", "purchase_requirement", "customer", "vendor", "vendor_price",
                             "customer_price", "receivable", "receipt", "payable", "payment", "customer_ledger",
                             "vendor_ledger", "invoicing", "outsource_task", "outsource_deliver_return",
                             "outsource_receive_return", "equipment", "owner_project", "operation_log"}


def test_documented_nested_pagination_does_not_mutate_request():
    co = Coordination()
    co.token = "t"
    requests = []
    def handler(request):
        requests.append(json.loads(request.content))
        return response([])
    c = client(handler, co)
    c.contract["resources"]["project"].update(page_key="page.pageNum", size_key="page.pageSize")
    body = {"page": {"other": "retained"}}
    list(c.pages("project", body))
    assert body == {"page": {"other": "retained"}}
    assert requests == [{"page": {"other": "retained", "pageNum": 1, "pageSize": 100}}]


def test_factory_login_binding_and_scope_fallback():
    co = Coordination()
    co.token, co.factory = "old", "different-factory"
    c = client(lambda req: httpx.Response(200, json={
        "status": "01000000", "session": {"token": "t", "factory": "factory-test"}}), co)
    c.contract["login_factory_path"] = "session.factory"
    assert c._token() == "t" and c.factory_verified
    assert co.factory == "factory-test"
    spec = contract()["resources"]["project"]
    spec.update(factory_path=None, scope_evidence="login")
    row = {"id": 1, "updated": "2026-10-31T00:00:00Z"}
    with pytest.raises(BlacklakeError, match="NOT_PROVEN"):
        normalize(row, spec, settings())
    assert normalize(row, spec, settings(), factory_verified=True)[0] == "1"


def test_documented_local_timestamps_require_explicit_timezone():
    assert timestamp("2026-11-01 08:00:00", "Asia/Shanghai").isoformat() == "2026-11-01T00:00:00+00:00"


@pytest.mark.skipif(__import__('os').name != 'nt', reason="Windows DPAPI")
def test_local_dpapi_round_trip(tmp_path):
    from blacklake.secrets import save_local_secret, load_local_credentials
    path = tmp_path / "synthetic.dpapi"
    save_local_secret(path, {"phone": "synthetic-phone", "password": "synthetic-password"})
    assert b"synthetic-password" not in path.read_bytes()
    assert load_local_credentials(path) == ("synthetic-phone", "synthetic-password")


def test_real_login_envelope_is_separate_from_business_envelope():
    co = Coordination()
    def handler(request):
        if request.url.path.endswith("_login"):
            return httpx.Response(200, json={"statusCode": 200, "data": "t"})
        return httpx.Response(200, json={"code": "01000000", "data": []})
    c = client(handler, co)
    c.contract.update(code_path="code", login_code_path="statusCode", login_success_code=200, token_path="data")
    assert c.query("project", {})["code"] == "01000000"


def test_period_cost_excludes_retained_old_and_future_orders():
    start, end = timestamp("2026-10-27T00:00:00Z"), timestamp("2026-11-03T00:00:00Z")
    def order(when, amount):
        return {"event_time": when, "biz_type": 32, "approval_status": 1,
                "details": [{"associated_order_type": 10, "associated_order_code": "synthetic",
                             "material_cost_signed": amount}]}
    result = production_bi([], [], [], [
        order("2026-10-26T00:00:00+00:00", "-100"),
        order("2026-10-28T00:00:00+00:00", "-5"),
        order("2026-11-03T00:00:00+00:00", "-200"),
    ], start, end)
    assert result["material_cost"]["records"] == 1
    assert result["material_cost"]["signed_stock_movement_cost"] == "-5"


def test_schema_diagnostics_omit_values_and_custom_identifiers():
    from blacklake.bootstrap import shape
    result = shape({"data": {"password": "secret", "token": "token-value", "phone": "123"},
                    "reference": {"private_id": "private"}})
    assert set(result) == {"data.password", "data.token", "data.phone", "reference"}
    assert "secret" not in json.dumps(result)
    assert "private_id" not in json.dumps(result)


def test_independent_source_binding_is_not_overwritten(monkeypatch, tmp_path):
    import blacklake.local_environment as local
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.setenv("BLACKLAKE_SOURCE_FACTORY_ID", "wrong-source")
    monkeypatch.setattr(local, "load_local_secret", lambda path: {
        "owner_confirmed": True, "expected_org_id": "approved-source"})
    with pytest.raises(BlacklakeError, match="CONFIGURED_SOURCE_FACTORY_MISMATCH"):
        local.apply_source_binding()
    monkeypatch.delenv("BLACKLAKE_SOURCE_FACTORY_ID")
    local.apply_source_binding()
    assert __import__('os').environ["BLACKLAKE_SOURCE_FACTORY_ID"] == "approved-source"


def test_wrong_login_factory_never_reaches_business_query():
    calls = []
    def handler(request):
        calls.append(request.url.path)
        return httpx.Response(200, json={"status": "01000000", "session": {"token": "t", "factory": "foreign"}})
    c = client(handler)
    c.contract["login_factory_path"] = "session.factory"
    with pytest.raises(BlacklakeError, match="LOGIN_FACTORY_MISMATCH"):
        c.query("project", {})
    assert len(calls) == 1 and calls[0].endswith("_login")


def test_verified_empty_output_null_requires_explicit_zero_total():
    co = Coordination()
    co.token = "t"
    c = client(lambda req: httpx.Response(200, json={"status": "01000000", "result": {"rows": None, "total": 0}}), co)
    c.contract["resources"]["output"] = c.contract["resources"]["project"]
    assert list(c.pages("output", {})) == [[]]
    with pytest.raises(BlacklakeError, match="PAGINATION_SHAPE"):
        list(c.pages("project", {}))


def _verified_client(handler, co):
    import base64
    from pathlib import Path
    import blacklake
    real = load_contract(str(Path(blacklake.__file__).parent / "verified_contract.json"))
    return BlacklakeConnector(settings(), real, co, transport=httpx.MockTransport(handler))


def _jwt(org="factory-test"):
    import base64
    claims = base64.urlsafe_b64encode(json.dumps({"orgId": org}).encode()).decode().rstrip("=")
    return "h." + claims + ".s"


# Documented token-invalid reply (OpenAPI接入说明, 登录接口调用频次控制): HTTP 200 with business code "401".
EXPIRED = {"code": "401", "data": None, "subCode": "USER_VERIFICATION_ERROR", "msg": "用户认证失败"}


@pytest.mark.parametrize("expired", [httpx.Response(200, json=EXPIRED), httpx.Response(401, json=EXPIRED)])
def test_documented_token_invalid_reply_relogs_once_and_retries_same_request(expired):
    co = Coordination()
    co.token, co.factory = "invalidated-by-web-login", "factory-test"
    calls, replies = [], [expired, httpx.Response(200, json={"code": "01000000", "data": []})]

    def handler(request):
        calls.append((request.url.path, request.headers.get("x-auth"), request.content))
        if request.url.path.endswith("_login"):
            return httpx.Response(200, json={"statusCode": 200, "data": _jwt()})
        return replies.pop(0)

    assert _verified_client(handler, co).query("product", {"page": {"pageNum": 1}})["code"] == "01000000"
    assert [p.rsplit("/", 1)[-1] for p, _, _ in calls] == ["queryList2", "_login", "queryList2"]
    assert calls[0][1] == "invalidated-by-web-login" and calls[2][1] == _jwt() and calls[0][2] == calls[2][2]
    assert co.token == _jwt()


def test_token_still_invalid_after_one_relogin_is_exhausted_without_login_loop():
    co = Coordination()
    co.token, co.factory = "invalidated-by-web-login", "factory-test"
    calls = []

    def handler(request):
        calls.append(request.url.path)
        if request.url.path.endswith("_login"):
            return httpx.Response(200, json={"statusCode": 200, "data": _jwt()})
        return httpx.Response(200, json=EXPIRED)

    with pytest.raises(BlacklakeError, match="AUTH_REFRESH_EXHAUSTED"):
        _verified_client(handler, co).query("product", {})
    assert sum(p.endswith("_login") for p in calls) == 1 and len(calls) == 3


def test_other_business_errors_are_not_mistaken_for_token_expiry():
    co = Coordination()
    co.token, co.factory = "valid", "factory-test"
    calls = []

    def handler(request):
        calls.append(request.url.path)
        return httpx.Response(200, json={"code": "01000002", "msg": "参数错误"})

    with pytest.raises(BlacklakeError, match="UPSTREAM_BUSINESS_ERROR:01000002"):
        _verified_client(handler, co).query("product", {})
    assert not any(p.endswith("_login") for p in calls)
