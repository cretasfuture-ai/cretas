"""工厂 AI 读路径回归电池 (2026-07-24 验收轮建立)。

覆盖工厂 F006 (六膳门食品科技, 测试租户) 的核心只读问法: 库存/设备/生产/质检/财务/
考勤/退货/订单。断言是结构性 marker (含/不含), 不锁具体数字。
本电池同时锁住 2026-07-24 验收修复: legacy 权限误拒全员 (原料查询)、
考勤占位符、退货/销售订单误路由。

    python -X utf8 -m smartbi.scripts.factory_ai_eval --base https://admin.cretaceousfuture.com

Exit 0 = 全过; 1 = 有失败; 2 = 没量到 (登录/凭证) — cron/CI 可直接接。
"""
from __future__ import annotations

import argparse
import json
import math
import random
import string
import sys
import time
import urllib.request
from typing import Any, Dict, List, Optional

from smartbi.scripts.factory_eval_auth import FACTORY_ID, factory_login

_FORBIDDEN_EVERYWHERE = [
    "暂未配置执行器",
    "没有获得可展示的结果",
    "操作已完成。",          # 占位符掩盖真答案 (2026-07-23 修)
    "查询成功",              # 考勤占位符 (2026-07-24 修)
    "没有权限",              # 只读问法不该被权限拒 (legacy 误拒, 2026-07-24 修)
]

CASES: List[Dict[str, Any]] = [
    {"q": "库存有哪些预警", "contains": ["库存"]},
    {"q": "有哪些设备", "contains": ["设备"]},
    # Broad production questions legitimately ask for a choice. Complete that choice;
    # clarification alone is never a passing data query.
    {"q": "今天的生产情况怎么样", "contains": [],
     "intents": ["REPORT_PRODUCTION", "PRODUCTION_PROGRESS_DASHBOARD"], "structured": True},
    {"q": "最近的质检合格率怎么样", "contains": ["质检"]},
    # 财务: 会话延续可能路由到利润表(含营业收入)或SKU成本(含成本)两条腿,
    # 两者都是正确答案 — 只锁全局禁词 (查询成功/没有获得可展示 等占位符)
    {"q": "整体毛利率是多少", "contains": []},
    # 2026-07-24 修: legacy hasPermission=false 误拒全员 → 修后正常出数据
    {"q": "原材料库存还剩多少", "contains": [], "excludes": []},
    # 2026-07-24 修: 考勤只回"查询成功"四字 → 修后有汇总数字
    {"q": "员工出勤情况", "contains": ["考勤统计"]},
    # 2026-07-24 修: 退货误路由到销售订单列表 → 新建 RETURN_ORDER_LIST 意图
    # The real page formatter can show counts without a Chinese heading. Assert its
    # exact route and actual page, not just a word that a wrong route could repeat.
    {"q": "最近的退货情况", "contains": [], "excludes": ["销售订单"],
     "intents": ["RETURN_ORDER_LIST"], "page": True},
    # 2026-07-24 修: 销售订单误路由到生产仪表盘 → ORDER_LIST 补关键词
    {"q": "销售订单情况怎么样", "contains": ["订单"], "excludes": ["仪表盘总览"]},
    {"q": "销售订单有多少", "contains": ["订单"]},
]


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"feval-{prefix}-{tail}"


def _post_json(url: str, payload: Dict[str, Any],
               headers: Optional[Dict[str, str]] = None,
               timeout: int = 240) -> Dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _read_problems(case: Dict[str, Any], data: Dict[str, Any]) -> List[str]:
    problems = []
    if data.get("aiMode") != "READ":
        problems.append("只读响应缺少 aiMode=READ")
    if data.get("status") not in ("SUCCESS", "COMPLETED"):
        problems.append(f"只读查询未完成: {data.get('status')}")
    if data.get("confirmableAction"):
        problems.append("只读查询不应生成确认令牌")
    flat = " ".join(str(data.get("message") or "").split())
    if not flat:
        problems.append("缺少用户可见的查询结果")
    for marker in case.get("contains", []):
        if marker not in flat:
            problems.append(f"缺少「{marker}」")
    for marker in case.get("excludes", []) + _FORBIDDEN_EVERYWHERE:
        if marker in flat:
            problems.append(f"不应出现「{marker}」")
    if case.get("intents") and data.get("intentCode") not in case["intents"]:
        problems.append(f"错误查询路由: {data.get('intentCode')}")
    payload = data.get("resultData")
    if isinstance(payload, dict) and payload.get("success") is False:
        problems.append("工具执行失败")
    if case.get("structured") and not _production_payload_valid(data.get("intentCode"), payload):
        problems.append("缺少类型正确的真实生产统计/进度结果")
    if case.get("page"):
        page = payload
        for _ in range(3):
            if not isinstance(page, dict) or "content" in page:
                break
            page = page.get("data")
        if not isinstance(page, dict) or not isinstance(page.get("content"), list):
            problems.append("缺少退货分页数据")
        else:
            total, content = page.get("totalElements"), page["content"]
            if type(total) is not int or total < len(content) or total < 0 or (total > 0 and not content):
                problems.append("退货分页计数与数据不一致")
    return problems


def _production_payload_valid(code, payload):
    if not isinstance(payload, dict):
        return False
    business = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    for layer in (payload, business):
        if any(layer.get(key) for key in ("error", "errors", "exception")):
            return False
        if layer.get("success") is False or layer.get("status") in ("FAILED", "ERROR"):
            return False
    if code == "REPORT_PRODUCTION":
        output = business.get("totalOutput")
        return (payload.get("reportType") == "PRODUCTION" and type(output) in (int, float)
                and math.isfinite(output) and output >= 0 and isinstance(business.get("dailyOutput"), list))
    if code == "PRODUCTION_PROGRESS_DASHBOARD":
        summary, plans = business.get("summary"), business.get("plans")
        counts = ("totalPlans", "totalWorkOrders", "completedWorkOrders", "inProgressWorkOrders", "pendingWorkOrders")
        return (isinstance(summary, dict) and isinstance(plans, list)
                and all(type(summary.get(key)) is int and summary[key] >= 0 for key in counts)
                and summary["totalPlans"] == len(plans))
    return False


def _choice_code(choice):
    """The real clarification API uses SELECT_INTENT + parameters.intentCode."""
    if not isinstance(choice, dict):
        return None
    code = choice.get("actionCode")
    if code == "SELECT_INTENT":
        parameters = choice.get("parameters")
        code = parameters.get("intentCode") if isinstance(parameters, dict) else None
    return code if isinstance(code, str) and code else None


def _execute_read(base, auth, case, session_id, post=None):
    post = post or _post_json

    def execute(payload):
        envelope = post(f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute", payload, headers=auth)
        data = envelope.get("data")
        if envelope.get("success") is not True or not isinstance(data, dict) or not data:
            raise ValueError("查询响应封装失败或为空")
        return data

    data = execute({"userInput": case["q"], "sessionId": session_id, "mode": "READ"})
    if data.get("status") == "NEED_CLARIFICATION" and case.get("intents"):
        choices = data.get("suggestedActions") or []
        codes = {_choice_code(choice) for choice in choices}
        if data.get("aiMode") != "READ" or len(codes - {None, ""}) < 2 or data.get("confirmableAction"):
            raise ValueError("澄清响应缺少两个真实只读候选")
        selected = next((choice for choice in choices if _choice_code(choice) in case["intents"]), None)
        if selected is None:
            raise ValueError("澄清候选不含目标生产查询；不选择其他能力")
        data = execute({"userInput": selected.get("actionName") or case["q"],
                        "intentCode": _choice_code(selected), "sessionId": session_id, "mode": "READ"})
    return data


def run_eval(base: str, only: Optional[str] = None) -> int:
    auth = factory_login(base, _post_json)
    if auth is None:
        return 2  # 没量到 (≠ 1 断言失败)

    passed, failed = 0, 0
    failures: List[str] = []
    latencies: List[float] = []
    for idx, case in enumerate(CASES, 1):
        q = case["q"]
        if only and only not in q:
            continue
        problems: List[str] = []
        started = time.time()
        flat = ""
        for attempt in (1, 2):  # 蓝绿切换窗瞬态失败重试一次
            try:
                data = _execute_read(base, auth, case, _rand_sid(f"c{idx}"))
                message = str(data.get("message") or "")
                problems = _read_problems(case, data)
            except Exception as exc:  # noqa: BLE001
                message = f"<TRANSPORT ERROR: {exc}>"
                problems = ["查询未取得有效响应"]
            flat = " ".join(message.split())
            if not problems:
                break
            if attempt == 1:
                time.sleep(20)
        elapsed = time.time() - started
        latencies.append(elapsed)
        if problems:
            failed += 1
            failures.append(f"[{idx:02d}] {q}\n     {'; '.join(problems)}\n     实际: {flat[:160]}")
            print(f"✗ [{idx:02d}] {elapsed:5.1f}s {q} — {'; '.join(problems)}")
        else:
            passed += 1
            print(f"✓ [{idx:02d}] {elapsed:5.1f}s {q}")

    if latencies:
        ordered = sorted(latencies)
        p95 = ordered[max(0, int(len(ordered) * 0.95) - 1)]
        print(f"\n耗时: 平均 {sum(latencies)/len(latencies):.1f}s | 中位 "
              f"{ordered[len(ordered)//2]:.1f}s | p95 {p95:.1f}s | 最慢 {ordered[-1]:.1f}s")
    print(f"== {passed} passed, {failed} failed / {passed + failed} run ==")
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com")
    parser.add_argument("--only", default="")
    args = parser.parse_args()
    sys.exit(run_eval(args.base, args.only or None))


if __name__ == "__main__":
    main()
