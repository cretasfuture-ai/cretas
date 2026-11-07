"""工厂 AI 写路径回归电池 (2026-07-24 验收轮建立)。

锁住当前工厂能力与读写分块契约，不为旧电池恢复不存在的能力:
1. 旧删除/开关问法保持无能力、无执行；配置问法走现有向导澄清。
2. viewer 采购写请求必须按精确权限拒绝；mode=READ 的有效写能力必须阻断。
3. TCC 预览 (previewOnly → PREVIEW + confirmableAction: token/digest/300s)
4. 读问法 aiMode=READ 标记

全程 F006 (六膳门食品科技, 团队测试租户), 用 FACTORY_EVAL_USERNAME/PASSWORD 登录
(见 factory_eval_auth.py)。⛔ 不调用 /ai-intents/confirm —— F006 没有 demo 写闸,
confirm 会真写; 本电池零写入靠「只到预览」保证。

    python -X utf8 -m smartbi.scripts.factory_write_eval --base https://admin.cretaceousfuture.com

Exit 0 = 全过; 1 = 有失败。
"""
from __future__ import annotations

import argparse
import json
import random
import string
import sys
import time
import urllib.request
from typing import Any, Dict, Optional

from smartbi.scripts.factory_eval_auth import FACTORY_ID, factory_login
from smartbi.scripts.factory_ai_eval import _choice_code

# TCC 全链探针: 需要 supportsPreview 的写工具 (product_create)
TCC_INTENT = "PRODUCT_CREATE_FLYWHEEL"
TCC_QUERY = "飞轮建产品 电池验证产品"


def _rand_sid(prefix: str) -> str:
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"weval-{prefix}-{tail}"


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


def _no_execution_problems(data):
    problems = []
    if data.get("confirmableAction"):
        problems.append("拒绝/澄清不应铸出确认令牌")
    if data.get("resultData") or data.get("affectedEntities"):
        problems.append("拒绝/澄清不应携带已执行业务结果")
    return problems


def _has_choices(data):
    choices = data.get("suggestedActions") or []
    return len({_choice_code(c) for c in choices
                if isinstance(c, dict) and _choice_code(c) and c.get("actionName")}) >= 2


def _unsupported_problems(data):
    problems = _no_execution_problems(data)
    if data.get("intentCode") or data.get("intentRecognized") is not False:
        problems.append("不存在的旧能力不应被识别为可执行意图")
    if data.get("aiMode") != "READ":
        problems.append("无执行能力的答复必须标记 READ")
    if (data.get("metadata") or {}).get("toolName"):
        problems.append("无执行能力的答复不应运行工具")
    status = data.get("status")
    if status == "NEED_CLARIFICATION":
        if not _has_choices(data):
            problems.append("澄清必须给出至少两个实际候选")
    elif status in ("COMPLETED", "UNSUPPORTED_CAPABILITY", "FACTORY_PACK_NO_MATCH"):
        message = str(data.get("message") or "")
        if not any(word in message for word in ("无法", "不能", "无权", "不支持", "未理解", "退役")):
            problems.append("缺少能力不可执行的明确说明")
    else:
        problems.append(f"旧能力不应返回执行成功: {status}")
    return problems


def _purchase_denial_problems(data):
    problems = _no_execution_problems(data)
    for key, expected in (("intentCode", "PURCHASE_ORDER_CREATE"), ("status", "NO_PERMISSION"),
                          ("aiMode", "WRITE"), ("requiredPermission", "procurement:read_write")):
        if data.get(key) != expected:
            problems.append(f"viewer采购拒绝 {key} 应为 {expected}, 实为 {data.get(key)}")
    return problems


def _configuration_problems(data):
    problems = _no_execution_problems(data)
    for key, expected in (("intentCode", "FACTORY_CONFIG_AGENT"), ("status", "NEED_CLARIFICATION"),
                          ("aiMode", "WRITE")):
        if data.get(key) != expected:
            problems.append(f"配置向导 {key} 应为 {expected}, 实为 {data.get(key)}")
    if not _has_choices(data):
        problems.append("配置澄清缺少两个实际候选")
    return problems


def _read_block_problems(data):
    problems = _no_execution_problems(data)
    for key, expected in (("intentCode", TCC_INTENT), ("status", "READ_MODE_WRITE_BLOCKED"), ("aiMode", "WRITE")):
        if data.get(key) != expected:
            problems.append(f"READ写阻断 {key} 应为 {expected}, 实为 {data.get(key)}")
    if "操作" not in str(data.get("message") or ""):
        problems.append("缺少操作页跳转说明")
    return problems


def _preview_problems(data):
    problems = []
    if data.get("intentCode") != TCC_INTENT:
        problems.append(f"预览意图必须是 {TCC_INTENT}")
    if data.get("status") != "PREVIEW" or data.get("aiMode") != "WRITE":
        problems.append("有效写能力必须返回 PREVIEW / WRITE")
    ca = data.get("confirmableAction") or {}
    for key in ("confirmToken", "commandDigest", "impactSummary", "actionType"):
        if not ca.get(key):
            problems.append(f"预览缺 {key}")
    return problems


def _read_mode_problems(data):
    problems = []
    if data.get("aiMode") != "READ" or data.get("confirmableAction"):
        problems.append("只读问题必须标 READ, 不生成确认令牌")
    if data.get("status") == "NEED_CLARIFICATION":
        if not _has_choices(data):
            problems.append("只读澄清缺少两个实际候选")
    elif data.get("status") not in ("SUCCESS", "COMPLETED") or not data.get("message"):
        problems.append("只读问题既未完成也未给出有效澄清")
    return problems


def run_eval(base: str) -> int:
    auth = factory_login(base, _post_json)
    if auth is None:
        return 2
    cases = [
        ("旧客户删除能力不可执行", {"userInput": "帮我删除客户张三"}, _unsupported_problems),
        ("viewer采购写权限拒绝", {"userInput": "创建采购单"}, _purchase_denial_problems),
        ("配置向导必须澄清", {"userInput": "重置配置"}, _configuration_problems),
        ("旧功能开关能力不可执行", {"userInput": "禁用功能开关测试"}, _unsupported_problems),
        # Preserve the original deleted-capability negative, then add a real active
        # write capability to prove READ blocking (a refusal alone cannot prove it).
        ("READ旧客户删除无执行", {"userInput": "帮我删除客户张三", "mode": "READ"}, _unsupported_problems),
        ("READ有效写能力阻断", {"userInput": TCC_QUERY, "intentCode": TCC_INTENT, "mode": "READ"},
         _read_block_problems),
        ("TCC预览与影响契约", {"userInput": TCC_QUERY, "intentCode": TCC_INTENT}, _preview_problems),
        ("读问法aiMode=READ", {"userInput": "库存有哪些预警", "mode": "READ"}, _read_mode_problems),
    ]
    passed, failures = 0, []
    for index, (name, payload, validate) in enumerate(cases):
        # Also protect the natural-language probes: absence of /confirm alone is
        # insufficient if a future recognizer starts executing a formerly unknown verb.
        payload = {**payload, "previewOnly": True, "sessionId": _rand_sid(f"c{index}")}
        try:
            envelope = _post_json(f"{base}/api/mobile/{FACTORY_ID}/ai-intents/execute", payload, headers=auth)
            data = envelope.get("data")
            if envelope.get("success") is not True or not isinstance(data, dict) or not data:
                raise ValueError("评测响应封装失败或为空")
            problems = validate(data)
        except Exception as error:  # noqa: BLE001 — record failure, never reinterpret it as denial.
            problems = [f"未取得有效响应: {type(error).__name__}"]
        if problems:
            failures.append(f"{name}: {'; '.join(problems)}")
            print(f"✗ {failures[-1]}")
        else:
            passed += 1
            print(f"✓ {name}")
        time.sleep(1)
    print(f"\n== {passed} passed, {len(failures)} failed / {len(cases)} run ==")
    if failures:
        print("\n".join(["", "── 失败明细 ──", *failures]))
    return 1 if failures else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://admin.cretaceousfuture.com")
    args = parser.parse_args()
    sys.exit(run_eval(args.base))


if __name__ == "__main__":
    main()
