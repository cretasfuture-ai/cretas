"""工厂 AI 评测（读/写电池）的登录：用租户自己的账号，不用免密 demo-login。

🔴 2026-08-18 起这两道电池每天 04:40 在 `/auth/demo-login?tenant=factory` 上吃 403
   （`cretas.demo.enabled` 默认值 08-17 由 true 改成 false，prod 无覆盖 ⇒ 入口关闭，
   Java 抛 BusinessException(403, "演示模式未开启")）。电池没有捕获，直接 traceback、
   rc=1，和「断言失败」混在一起，78 天没读数。

⛔ 不把 `CRETAS_DEMO_ENABLED` 打开：demo-login 在 JwtAuthInterceptor 免鉴权白名单里，
   开了就是免密入口（见 application.properties 的 2026-08-17 注释）。
⛔ 目标租户不再是 DEMO_FACTORY2（租户收敛后已不存在），改为 F006「六膳门食品科技」——
   团队自己的测试/演示租户。⚠️ LIUSHANMEN 是真客户，评测账号必须指向 F006。

凭证只从环境变量取（服务器上由部署环境从 .env.prod 注入），
绝不落进仓库：FACTORY_EVAL_USERNAME / FACTORY_EVAL_PASSWORD。缺了或登录失败返回 None，
调用方按「没量到」退 2，⛔ 不回落到任何免密路径。
"""
from __future__ import annotations

import os
import random
import string
import urllib.error
from typing import Any, Callable, Dict, Optional

FACTORY_ID = "F006"


def factory_login(base: str, post_json: Callable[..., Dict[str, Any]]) -> Optional[Dict[str, str]]:
    """返回 Authorization 头；任何一步没成功都打印原因并返回 None（调用方退 2）。"""
    username = os.environ.get("FACTORY_EVAL_USERNAME", "").strip()
    password = os.environ.get("FACTORY_EVAL_PASSWORD", "")
    if not username or not password:
        print("FATAL: 缺少评测凭证环境变量 FACTORY_EVAL_USERNAME / FACTORY_EVAL_PASSWORD。"
              f"它们应指向 {FACTORY_ID} 的一个活跃账号，由 .env.prod 经 cron 脚本注入。")
        return None
    tail = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    try:
        login = post_json(f"{base}/api/mobile/auth/unified-login", {
            "username": username,
            "password": password,
            "deviceInfo": {"deviceId": f"feval-device-{tail}", "deviceModel": "regression-eval",
                           "platform": "android", "osVersion": "1"},
        })
    except urllib.error.HTTPError as exc:
        print(f"FATAL: 评测账号登录被拒 HTTP {exc.code} (user={username}) —— 入口收紧或账号失效，"
              "这次没量到，不是断言失败。")
        return None
    except Exception as exc:  # noqa: BLE001
        print(f"FATAL: 评测账号登录请求失败 (user={username}): {exc}")
        return None
    data = login.get("data") or {}
    token = data.get("token") or (data.get("tokens") or {}).get("token")
    if not token:
        print(f"FATAL: 登录没拿到 token (user={username}) -> {login}")
        return None
    if data.get("factoryId") != FACTORY_ID:
        # 登进了别的租户 = 后面断言全部量错了对象; 宁可停下。
        print(f"FATAL: 账号 {username} 属于租户 {data.get('factoryId')}, 而电池要测的是 "
              f"{FACTORY_ID}。")
        return None
    return {"Authorization": f"Bearer {token}"}
