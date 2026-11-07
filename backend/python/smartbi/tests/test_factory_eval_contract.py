"""Battery defects must stay red: transport, permission, routing and payload integrity all matter."""
import copy

import pytest

from smartbi.scripts import factory_ai_eval as read
from smartbi.scripts import factory_write_eval as write


def read_response(code="RETURN_ORDER_LIST"):
    return {"intentCode": code, "status": "SUCCESS", "aiMode": "READ",
            "message": "totalPages: 1 | totalElements: 1",
            "metadata": {"toolName": "return_order_list"},
            "resultData": {"content": [{"id": "owned-fixture"}], "totalElements": 1}}


def return_case():
    return next(case for case in read.CASES if case["q"] == "最近的退货情况")


def test_real_return_page_does_not_require_a_chinese_label():
    assert read._read_problems(return_case(), read_response()) == []


@pytest.mark.parametrize("change", [
    {"intentCode": "ORDER_LIST"}, {"aiMode": None}, {"status": "NO_PERMISSION"},
    {"confirmableAction": {"confirmToken": "not-a-read"}},
    {"resultData": {"content": [], "totalElements": 3}},
    {"resultData": {"content": [], "totalElements": "3"}},
    {"resultData": {"content": [], "totalElements": -1}},
    {"resultData": {"success": True}},
])
def test_return_regressions_are_not_converted_to_passes(change):
    response = read_response()
    response.update(change)
    assert read._read_problems(return_case(), response)


def test_empty_return_result_requires_a_real_zero_count():
    response = read_response()
    response["resultData"] = {"data": {"content": [], "totalElements": 0}}
    assert read._read_problems(return_case(), response) == []


def test_production_clarification_requires_a_real_read_choice_and_finishes_query():
    case = next(case for case in read.CASES if case["q"] == "今天的生产情况怎么样")
    calls = []

    def post(url, payload, **kwargs):
        calls.append((url, copy.deepcopy(payload)))
        if len(calls) == 1:
            return {"success": True, "data": {"status": "NEED_CLARIFICATION", "aiMode": "READ",
                    "suggestedActions": [
                        {"actionCode": "SELECT_INTENT", "actionName": "生产报表",
                         "parameters": {"intentCode": "REPORT_PRODUCTION", "confirmed": True}},
                        {"actionCode": "SELECT_INTENT", "actionName": "生产进度",
                         "parameters": {"intentCode": "PRODUCTION_PROGRESS_DASHBOARD"}}]}}
        return {
            "success": True,
            "data": {
                "status": "SUCCESS", "aiMode": "READ", "intentCode": "REPORT_PRODUCTION", "message": "生产报表",
                "resultData": {"reportType": "PRODUCTION", "data": {"totalOutput": 0, "dailyOutput": []}},
            },
        }

    response = read._execute_read("https://fixture.invalid", {}, case, "same-session", post=post)
    assert read._read_problems(case, response) == []
    assert len(calls) == 2
    assert all(payload["mode"] == "READ" and payload["sessionId"] == "same-session" for _, payload in calls)
    assert calls[1][1]["intentCode"] == "REPORT_PRODUCTION"
    assert "confirmed" not in calls[1][1] and "context" not in calls[1][1]
    assert all("confirm" not in url for url, _ in calls)


def test_clarification_cannot_be_the_final_success_or_select_a_write():
    case = next(case for case in read.CASES if case["q"] == "今天的生产情况怎么样")
    response = {"status": "NEED_CLARIFICATION", "aiMode": "READ", "suggestedActions": [
        {"actionCode": "PRODUCT_CREATE_FLYWHEEL", "actionName": "写产品"},
        {"actionCode": "ORDER_CREATE", "actionName": "建订单"}]}
    assert read._read_problems(case, response)
    calls = []

    def post(*args, **kwargs):
        calls.append(args)
        return {"success": True, "data": response}

    with pytest.raises(ValueError):
        read._execute_read("https://fixture.invalid", {}, case, "s", post=post)
    assert len(calls) == 1


@pytest.mark.parametrize("payload", [
    {"error": "boom"}, {"status": "FAILED"}, {"success": True}, {"batchCount": 0},
    {"reportType": "PRODUCTION", "data": {"totalOutput": "0", "dailyOutput": []}},
    {"reportType": "PRODUCTION", "data": {"totalOutput": float("nan"), "dailyOutput": []}},
    {"reportType": "PRODUCTION", "data": {"totalOutput": 0, "dailyOutput": [], "error": "boom"}},
])
def test_production_error_or_untyped_payload_never_passes(payload):
    case = next(case for case in read.CASES if case["q"] == "今天的生产情况怎么样")
    response = {"status": "SUCCESS", "aiMode": "READ", "intentCode": "REPORT_PRODUCTION",
                "message": "生产报表", "resultData": payload}
    assert read._read_problems(case, response)


def test_dashboard_requires_its_actual_count_schema():
    payload = {"data": {"summary": {"totalPlans": 0, "totalWorkOrders": 0, "completedWorkOrders": 0,
                                    "inProgressWorkOrders": 0, "pendingWorkOrders": 0}, "plans": []}}
    assert read._production_payload_valid("PRODUCTION_PROGRESS_DASHBOARD", payload)
    payload["data"]["summary"]["totalPlans"] = 3
    assert not read._production_payload_valid("PRODUCTION_PROGRESS_DASHBOARD", payload)


@pytest.mark.parametrize("body", [{"success": False, "data": read_response()},
                                  {"success": True, "data": {}},
                                  {"success": True, "data": None}])
def test_read_envelope_failures_never_pass(body):
    with pytest.raises(ValueError):
        read._execute_read("https://fixture.invalid", {}, return_case(), "s", post=lambda *a, **kw: body)


def test_transport_failure_cannot_pass_a_case_without_word_markers(monkeypatch):
    monkeypatch.setattr(read, "factory_login", lambda *a: {})
    monkeypatch.setattr(read.time, "sleep", lambda *a: None)

    def unavailable(*args, **kwargs):
        raise OSError("fixture offline")

    monkeypatch.setattr(read, "_post_json", unavailable)
    assert read.run_eval("https://fixture.invalid", only="整体毛利率") == 1


def test_full_read_battery_always_sends_read_mode(monkeypatch):
    calls = []
    monkeypatch.setattr(read, "factory_login", lambda *a: {})
    monkeypatch.setattr(read.time, "sleep", lambda *a: None)

    def post(url, payload, **kwargs):
        calls.append((url, payload))
        return {"success": False, "data": {}}

    monkeypatch.setattr(read, "_post_json", post)
    assert read.run_eval("https://fixture.invalid") == 1
    assert len(calls) == len(read.CASES) * 2
    assert all(payload["mode"] == "READ" for _, payload in calls)
    assert all(url.endswith("/ai-intents/execute") for url, _ in calls)


def denied_purchase():
    return {"intentCode": "PURCHASE_ORDER_CREATE", "status": "NO_PERMISSION", "aiMode": "WRITE",
            "requiredPermission": "procurement:read_write", "message": "没有权限"}


def test_viewer_permission_denial_is_a_strict_negative_not_a_write_success():
    assert write._purchase_denial_problems(denied_purchase()) == []
    for field, value in [("status", "SUCCESS"), ("requiredPermission", None), ("aiMode", None),
                         ("intentCode", "ORDER_CREATE"), ("confirmableAction", {"confirmToken": "x"}),
                         ("resultData", {"id": "unexpected-write"})]:
        changed = denied_purchase()
        changed[field] = value
        assert write._purchase_denial_problems(changed), field


def test_obsolete_customer_delete_fixture_remains_an_unsupported_negative():
    response = {"status": "COMPLETED", "aiMode": "READ", "intentRecognized": False,
                "message": "无法执行删除，请使用业务模块"}
    assert write._unsupported_problems(response) == []
    for change in ({"intentCode": "CUSTOMER_DELETE"}, {"resultData": {"deleted": True}},
                   {"metadata": {"toolName": "customer_delete"}}, {"affectedEntities": ["x"]},
                   {"message": "已删除"}, {"aiMode": None}):
        assert write._unsupported_problems({**response, **change})


def test_configuration_clarification_does_not_restore_config_reset():
    response = {"intentCode": "FACTORY_CONFIG_AGENT", "status": "NEED_CLARIFICATION", "aiMode": "WRITE",
                "suggestedActions": [
                    {"actionCode": "SELECT_INTENT", "actionName": "配置向导",
                     "parameters": {"intentCode": "FACTORY_CONFIG_AGENT"}},
                    {"actionCode": "SELECT_INTENT", "actionName": "系统设置",
                     "parameters": {"intentCode": "SYSTEM_SETTINGS"}}]}
    assert write._configuration_problems(response) == []
    for change in ({"intentCode": "CONFIG_RESET"}, {"status": "SUCCESS"},
                   {"aiMode": None}, {"suggestedActions": []}, {"resultData": {"reset": True}}):
        assert write._configuration_problems({**response, **change})


def test_supported_write_in_read_mode_must_be_blocked_without_confirmation():
    response = {"intentCode": "PRODUCT_CREATE_FLYWHEEL", "status": "READ_MODE_WRITE_BLOCKED", "aiMode": "WRITE",
                "message": "这是操作类请求"}
    assert write._read_block_problems(response) == []
    for change in ({"status": "COMPLETED"}, {"aiMode": "READ"},
                   {"confirmableAction": {"confirmToken": "x"}}, {"resultData": {"created": True}}):
        assert write._read_block_problems({**response, **change})


def test_preview_requires_exact_intent_and_all_original_impact_fields():
    response = {"intentCode": "PRODUCT_CREATE_FLYWHEEL", "status": "PREVIEW", "aiMode": "WRITE",
                "confirmableAction": {"confirmToken": "fixture", "commandDigest": "digest",
                                      "impactSummary": "preview only", "actionType": "CREATE"}}
    assert write._preview_problems(response) == []
    for change in ({"intentCode": "OTHER_CREATE"}, {"status": "SUCCESS"}, {"aiMode": "READ"}):
        assert write._preview_problems({**response, **change})
    for key in response["confirmableAction"]:
        changed = copy.deepcopy(response)
        del changed["confirmableAction"][key]
        assert write._preview_problems(changed)


def test_full_write_battery_is_preview_only_and_preserves_both_delete_negatives(monkeypatch):
    calls = []
    monkeypatch.setattr(write, "factory_login", lambda *a: {})
    monkeypatch.setattr(write.time, "sleep", lambda *a: None)

    def post(url, payload, **kwargs):
        calls.append((url, copy.deepcopy(payload)))
        return {"success": False, "data": {}}

    monkeypatch.setattr(write, "_post_json", post)
    assert write.run_eval("https://fixture.invalid") == 1
    assert len(calls) == 8
    assert all(payload["previewOnly"] is True for _, payload in calls)
    assert all(url.endswith("/ai-intents/execute") for url, _ in calls)
    assert sum(payload["userInput"] == "帮我删除客户张三" for _, payload in calls) == 2
    assert any(payload.get("intentCode") == "PRODUCT_CREATE_FLYWHEEL" and payload.get("mode") == "READ"
               for _, payload in calls)


def test_auth_not_measured_stays_exit_two(monkeypatch):
    monkeypatch.setattr(write, "factory_login", lambda *a: None)
    monkeypatch.setattr(read, "factory_login", lambda *a: None)
    assert write.run_eval("https://fixture.invalid") == 2
    assert read.run_eval("https://fixture.invalid") == 2
