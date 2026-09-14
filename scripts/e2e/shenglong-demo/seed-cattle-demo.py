#!/usr/bin/env python3
"""
胜龙牛业演示租户 — 纯 API 造数脚本 (seed-cattle-demo.py)
========================================================
任务 `DEMO-SHENGLONG-TENANT-SEED-20260915`。

它做什么
--------
从**零**建出一个可演示的牛业屠宰/分割/加工租户, 全程走 REST:

    平台登录 → 建工厂 → 建工厂管理员 → 以管理员登录
      → 原料类型(活牛/胴体半片) + 产品类型(20 个分割部位 + 酱牛肉) + 工序(屠宰/分割/加工)
      → 工艺族图 draft + publish-and-activate
      → 6 头源头个体(含系谱: 2 公 4 后代) → 6 个活牛期初批次并挂上个体
      → 生产计划 → 批次 → 开工 → spawn-tasks → 分割报工 → 酱牛肉成品批次
      → 建标签拿 traceCode → **公开溯源 200 = 终态断言**

⛔ 明确不做的事
--------------
* ⛔ **不碰 prod**。脚本没有任何 prod 主机默认值; `--base-url` 必填。
  prod 执行由 organizer 做(🔒)。
* ⛔ **不 SQL 直插**(organizer 2026-09-14 裁定方案 A)。绕过校验器建出来的族图/批次
  在演示当天才会以看不懂的方式炸。
* ⛔ **口令不写进代码**。`--demo-password` / `--platform-pass` 走参数或 env,
  日志里一律只打长度。

幂等
----
每一步**先查后建**(按业务键: 工厂名 / code / 编号 / batchKey)。重跑第二遍时
每步打印 `reused` 而不是 `created`, 末尾的 `CREATED=` 计数应为 0。
⚠️ 幂等的判据是**第二遍新增 0**, ⛔ 不是「第二遍没报错」—— 后者在每步都重复建
一份的情况下同样成立。

用法
----
    # 只读探测(登录 + 查现状, 不写任何东西)
    python seed-cattle-demo.py --base-url http://localhost:10010 \\
        --platform-user <u> --platform-pass <p> --dry-run

    # 真的造数
    python seed-cattle-demo.py --base-url http://localhost:10010 \\
        --platform-user <u> --platform-pass <p> --demo-password <demo-pwd>

env 等价物: SEED_BASE_URL / SEED_PLATFORM_USER / SEED_PLATFORM_PASS / SEED_DEMO_PASSWORD

本机验收环境怎么来的(供下一个人复现)
------------------------------------
本脚本在开发机的 `cretas_db`(profile `pg`, `mvn spring-boot:run`)上跑通。
本机需要一个 `platform_admin` 账号 —— 那是**环境准备**, 不是脚本的事:

    -- 本机 dev 库专用; ⛔ 不要在 prod 上执行
    INSERT INTO users (username, password_hash, role_code, factory_id, is_active, ...)
    VALUES ('<local_platform_admin>', '<bcrypt hash>', 'platform_admin', NULL, true, ...);

prod 上平台管理员已存在, 凭证见 gitignored 的
`.claude/skills/server-operations/db-credentials.md`。⛔ 不要把它抄进本文件。

依赖: Python 3.9+ 标准库 + requests
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
from datetime import date, datetime

# ════════════════════════════════════════════════════════════════════
# 演示数据定义 —— 改这里就能改 demo 的样子, ⛔ 不用改流程代码
# ════════════════════════════════════════════════════════════════════

FACTORY_NAME = "胜龙牛业演示"
# industryCode 必须是**大写字母**(Factory 实体上的正则), regionCode 4 位数字。
FACTORY_INDUSTRY_CODE = "CATTLE"
FACTORY_REGION_CODE = "3701"          # 山东
DEMO_MARK = "SL-DEMO"

# ── 原料类型 ────────────────────────────────────────────────────────
RAW_MATERIALS = [
    # (code, name, category, mainCategory, unit, storageType)
    # 🔴 category(小类)必须是后端 `MaterialMainCategory` 里**已登记**的词, ⛔ 不能自己发明:
    #    2026-09-15 本机实测用「畜类」被拒 ——
    #      400 物料基本类型「畜类」尚未归入大类 (severity=BLOCKING)
    #    那张表是**代码里的硬编码允许表**, RAW 档收了 肉类/禽类/水产/蔬菜/米面/干货…,
    #    **没有**「畜类」。⇒ 改用已登记的「肉类」。
    #    ⚠️ mainCategory 传了也没用: 后端按 category 反查大类, 不看请求里的 mainCategory。
    #    📌 已登记: 一个牛肉厂最自然的那个词(畜类)不在表里, 而禽类/水产在 —— 值得单独提。
    ("RM-CATTLE-LIVE", "活牛", "肉类", "原料", "kg", "fresh"),
    ("RM-CARCASS-HALF", "胴体半片", "肉类", "原料", "kg", "fresh"),
]

# ── 20 个分割部位(半成品) + 1 个成品 ────────────────────────────────
#   ⚠️ 这里是**部位**, 不是品牌/客户专属名 —— 换个客户照样用。
CUTS = [
    ("牛腩", "NANFU"), ("西冷", "SIRLOIN"), ("眼肉", "RIBEYE"), ("牛腱", "SHANK"),
    ("牛霖", "KNUCKLE"), ("上脑", "CHUCK"), ("牛柳", "TENDERLOIN"), ("前胸", "BRISKET"),
    ("后胸", "PLATE"), ("牛肋条", "SHORTRIB"), ("板腱", "BLADE"), ("嫩肩", "OYSTER"),
    ("黄瓜条", "EYEROUND"), ("大米龙", "TOPSIDE"), ("小米龙", "SILVERSIDE"),
    ("臀肉", "RUMP"), ("腱子", "HEEL"), ("牛尾", "OXTAIL"), ("碎肉", "TRIM"),
    ("骨", "BONE"),
]
FINISHED_NAME = "酱牛肉"
FINISHED_CODE = "PT-BRAISED-BEEF"

# ── 工序 ────────────────────────────────────────────────────────────
WORK_PROCESSES = [
    # (processName, processCategory, estimatedMinutes)
    ("屠宰", "屠宰", 60),
    ("分割", "分割", 180),
    ("加工", "加工", 240),
]

# ── 6 头源头个体 ────────────────────────────────────────────────────
#   耳标号照**畜禽标识码** 15 位样式(2 位畜种 + 6 位县级行政区 + 7 位流水)。
#   ⚠️ 这是演示数据的**形状**参考, 不声称与任何真实编码体系一致。
SPECIES = "CATTLE"
BREED = "西门塔尔杂交"
ORIGIN_FARM = "山东胜龙自有育肥场"
#   前两头是种公牛(无父母), 后四头挂系谱。
INDIVIDUALS = [
    # (individual_code, tag_number,        sex,  birth,        dna_status,  sire_code, dam_code)
    ("SL-BULL-001", "110370281000001", "公", "2021-03-12", "MATCHED", None, None),
    ("SL-COW-002", "110370281000002", "母", "2021-05-02", "MATCHED", None, None),
    ("SL-CATTLE-003", "110370281000003", "公", "2023-04-18", "SAMPLED", "SL-BULL-001", "SL-COW-002"),
    ("SL-CATTLE-004", "110370281000004", "母", "2023-05-09", "UNSAMPLED", "SL-BULL-001", "SL-COW-002"),
    ("SL-CATTLE-005", "110370281000005", "公", "2023-06-21", "MISMATCH", "SL-BULL-001", None),
    ("SL-CATTLE-006", "110370281000006", "母", "2023-07-03", "UNSAMPLED", None, "SL-COW-002"),
]
#   每头活牛的入栏重(kg) —— 期初批次的数量
LIVE_WEIGHTS = [612.0, 548.5, 505.0, 486.5, 523.0, 498.0]

# 分割报工: 输入 1 个胴体, 输出 3 个部位(带重量)
SPLIT_OUTPUTS = [("西冷", 18.5), ("眼肉", 16.0), ("牛腩", 22.5)]

_TODAY = date.today()
DEMO_DATE = _TODAY.isoformat()

# ════════════════════════════════════════════════════════════════════
# 日志 / 计数
# ════════════════════════════════════════════════════════════════════

_STEP = 0
_CREATED = 0
_REUSED = 0
_STEPS: list = []


def _ts() -> str:
    return datetime.now().strftime("%H:%M:%S")


def step(label: str, status: str, /, **kv) -> None:
    """统一的一行读数: STEP=<n> <label> -> <status> k=v ...

    status 只有三种: created / reused / ok。
    ⚠️ created 与 reused **必须分开** —— 幂等的判据是「第二遍 created=0」,
       把两者都打成 ok 就量不出来了。

    🔴 前两个形参是**位置限定**(`/`) —— 这不是风格, 是一个已经发生过的缺陷的修法:
       第一版签名是 `step(name, status, **kv)`, 而调用方很自然地想把工厂名当数据传
       (`step("ensure factory", "created", id=..., name=FACTORY_NAME)`) ⇒
       `name` 被位置和关键字各传一次, **运行时 TypeError**。
       两处调用点都中招, 而 `--self-test` 看不见它(自检不调 step), 所以它一路活到
       第一次真跑。⇒ 用 `/` 把 `name=` / `status=` 从形参名里解放出来, 让这类
       碰撞在**结构上**不可能, ⛔ 而不是靠每个调用方记得避开这两个词。
    """
    global _STEP, _CREATED, _REUSED

    name = label
    _STEP += 1
    if status == "created":
        _CREATED += 1
    elif status == "reused":
        _REUSED += 1
    extra = " ".join(f"{k}={v}" for k, v in kv.items() if v is not None)
    print(f"[{_ts()}] STEP={_STEP:02d} {name} -> {status}{(' ' + extra) if extra else ''}",
          flush=True)
    _STEPS.append({"step": _STEP, "name": name, "status": status, **kv})


def info(msg: str) -> None:
    print(f"[{_ts()}] INFO {msg}", flush=True)


class SeedError(RuntimeError):
    """失败即停 —— 带上响应体。

    ⛔ 不做「失败就跳过继续跑」: 那会让后面每一步都在一个残缺的前提上运行,
       最后得到一堆互相矛盾的读数, 而真正的失败在几十行之前。
    """


# ════════════════════════════════════════════════════════════════════
# API 客户端
# ════════════════════════════════════════════════════════════════════

class Api:
    def __init__(self, base_url: str, dry_run: bool = False):
        self.base = base_url.rstrip("/")
        self.dry_run = dry_run
        self.token: str | None = None
        try:
            import requests
        except ImportError:  # pragma: no cover
            raise SeedError("需要 requests: pip install requests")
        self._requests = requests

    # ── 底层 ────────────────────────────────────────────────────────
    def _call(self, method: str, path: str, body=None, params=None, write=False,
              headers=None):
        url = f"{self.base}{path}"
        hdrs = {"Content-Type": "application/json"}
        if self.token:
            hdrs["Authorization"] = f"Bearer {self.token}"
        if headers:
            hdrs.update(headers)
        if self.dry_run and write:
            info(f"DRY-RUN skip {method} {path}")
            return {"success": True, "data": None, "_dry_run": True}
        r = self._requests.request(method, url, headers=hdrs, json=body,
                                   params=params, timeout=60)
        try:
            payload = r.json()
        except ValueError:
            raise SeedError(f"{method} {path} -> HTTP {r.status_code}, 非 JSON 响应:\n{r.text[:800]}")
        payload["_http"] = r.status_code
        return payload

    def get(self, path, params=None):
        return self._call("GET", path, params=params)

    def post(self, path, body=None, params=None, write=True, headers=None):
        return self._call("POST", path, body=body, params=params, write=write,
                          headers=headers)

    def put(self, path, body=None, write=True):
        return self._call("PUT", path, body=body, write=write)

    # ── 带断言的封装 ────────────────────────────────────────────────
    def need(self, resp, what: str):
        """成功才返回 data, 否则失败即停并打印响应体。"""
        if resp.get("_dry_run"):
            return None
        if not resp.get("success"):
            raise SeedError(
                f"{what} 失败: HTTP={resp.get('_http')} message={resp.get('message')!r}\n"
                f"完整响应: {json.dumps(resp, ensure_ascii=False)[:1500]}")
        return resp.get("data")


# ════════════════════════════════════════════════════════════════════
# 各阶段
# ════════════════════════════════════════════════════════════════════

def login(api: Api, username: str, password: str, factory_id: str | None) -> dict:
    body = {"username": username, "password": password}
    if factory_id:
        body["factoryId"] = factory_id
    resp = api.post("/api/mobile/auth/unified-login", body, write=False)
    data = api.need(resp, f"登录 {username}")
    token = (data or {}).get("token") or (data or {}).get("accessToken")
    if not token:
        raise SeedError(f"登录 {username} 没有拿到 token, 响应: "
                        f"{json.dumps(resp, ensure_ascii=False)[:800]}")
    api.token = token
    user = (data or {}).get("user") or {}
    return user


def ensure_factory(api: Api, internal_key: str) -> str:
    """建工厂(幂等: 按 name 查)。

    ⚠️ 工厂 ID **拿不到字面量** —— `generateFactoryId` 按
       `{industryCode}_{regionCode}_{seq:03d}` 生成。所以这里只能按 **name** 认人,
       而 name 在 Factory 实体上是全局唯一的, 正好当业务键。
    """
    existing = api.need(api.get("/api/platform/factories"), "查工厂列表") or []
    for f in existing:
        if f.get("name") == FACTORY_NAME:
            step("ensure factory", "reused", id=f.get("id"), name=FACTORY_NAME)
            return f["id"]
    # 🔴 建工厂走**内部 onboarding 端点**而不是 `POST /api/platform/factories`,
    #    因为它一次把「建厂 + provisionDefaultUsers + feature config」都做了。
    #
    #    ⚠️ 2026-09-15 实测踩出来的: 平台管理员**建不了**新工厂的第一个用户。
    #    我原本的设计是 platform/factories 建厂 + `POST /api/mobile/{fid}/users` 建管理员,
    #    读过 JwtAuthInterceptor(平台角色可跨工厂) 和权限矩阵(platform_admin 对 hr
    #    是 read_write) 之后以为可行 —— 真跑当场 403:
    #        账号 local_platform_admin(1) 不属于工厂 CATTLE_3701_001，无法访问模块 hr_employee
    #    第三道闸 `ModuleEnabledInterceptor` 里有一句
    #        if (!factoryId.equals(user.getFactoryId())) -> deny
    #    **没有任何平台角色豁免**。⇒ 凡带 `@RequireModule` 的端点, 任何人都跨不了工厂,
    #    而 `POST /users` 正好带 `@RequireModule("hr_employee")`。
    #    📌 两处源码读对了、结论仍然是错的 —— 因为还有第三处我没读到。
    body = {
        "factoryName": FACTORY_NAME,
        "industryCode": FACTORY_INDUSTRY_CODE,
        "regionCode": FACTORY_REGION_CODE,
        "contactName": "演示联系人",
        "contactPhone": "13800000000",
        "surveyCompanyId": f"seed-{FACTORY_INDUSTRY_CODE}-demo",
    }
    data = api.need(api.post("/api/internal/onboarding/create-factory", body,
                             headers={"X-Internal-Key": internal_key}), "建工厂(onboarding)")
    if data is None:
        step("ensure factory", "ok", note="dry-run")
        return "DRY_RUN_FACTORY"
    fid = data.get("factoryId") or data.get("id")
    if not fid:
        raise SeedError("onboarding 建厂没返回 factoryId, 响应: "
                        + json.dumps(data, ensure_ascii=False)[:600])
    step("ensure factory", "created", id=fid, factory=FACTORY_NAME,
         users=len(data.get("users") or []))
    return fid


def provision_default_users(api: Api, factory_id: str, internal_key: str) -> None:
    """给**已存在**的工厂补建默认用户(走内部端点的 link-survey-company 分支)。

    只有「工厂已在、但 {fid}_admin 还没建出来」时才需要 —— 例如上一次跑到一半失败。
    """
    resp = api.post(f"/api/internal/field-visibility/{factory_id}/link-survey-company",
                    {"surveyCompanyId": f"seed-{factory_id}"},
                    headers={"X-Internal-Key": internal_key})
    api.need(resp, f"给已有工厂 {factory_id} 补建默认用户")
    step("provision default users", "created", factory=factory_id)


def ensure_factory_admin(api: Api, factory_id: str, demo_password: str,
                         internal_key: str) -> str:
    """把 `{fid}_admin` 的口令重置成演示口令; 账号缺失时先补建默认用户。

    🔴 这里**不建用户** —— 建不了。2026-09-15 实测: 平台管理员对带
       `@RequireModule` 的端点跨不了工厂(`ModuleEnabledInterceptor` 里
       `if (!factoryId.equals(user.getFactoryId())) -> deny`, 无平台角色豁免),
       而 `POST /api/mobile/{fid}/users` 正好带 `@RequireModule("hr_employee")`。
       ⇒ 新工厂的第一批用户只能由内部 onboarding 端点的 `provisionDefaultUsers` 建。
       `ensure_factory` 建厂时已经顺带建好, 这里只负责把随机口令换成演示口令。

    ⚠️ `provisionDefaultUsers` 生成的口令是**随机 12 位**, 所以重置这一步不能省。
       重置走 `/api/mobile/auth/reset-password` —— 它在 `/auth/` 下, 不受上面那道
       模块闸管, 且显式允许平台角色跨工厂(见 MobileController 里那段多租户注释)。
    """
    username = f"{factory_id.lower()}_admin"

    # 账号在不在? 用平台管理员查不了(同样是模块闸), 所以直接拿重置当探针:
    # 重置成功 = 账号在; 404/不存在 = 需要补建默认用户再重置一次。
    resp = api.post("/api/mobile/auth/reset-password", None,
                    params={"factoryId": factory_id, "username": username,
                            "newPassword": demo_password})
    if resp.get("_dry_run"):
        step("ensure factory admin", "ok", note="dry-run")
        return username
    if resp.get("success"):
        step("ensure factory admin", "reused", username=username, note="password-reset")
        return username

    # 账号还没有(上一次跑到一半失败等) -> 补建默认用户, 再重置一次。
    info(f"{username} 重置失败({resp.get('message')!r}), 尝试补建默认用户")
    provision_default_users(api, factory_id, internal_key)
    api.need(api.post("/api/mobile/auth/reset-password", None,
                      params={"factoryId": factory_id, "username": username,
                              "newPassword": demo_password}),
             f"重置 {username} 口令(补建默认用户之后)")
    step("ensure factory admin", "created", username=username)
    return username


def ensure_raw_materials(api: Api, fid: str) -> dict:
    """原料类型(幂等: 按 code)。⚠️ 需要 factory_super_admin 角色 —— 平台管理员**不行**
    (`@RequireRole({"warehouse_manager","factory_super_admin"})` 里没有它)。"""
    out = {}
    listing = api.need(api.get(f"/api/mobile/{fid}/raw-material-types",
                               params={"page": 1, "size": 200}), "查原料类型") or {}
    # 🔴 这一行原来是
    #      (listing.get("content") or listing if isinstance(listing, list) else [])
    #    优先级坑: 它其实是 `(A or B) if isinstance(...) else []` —— 两个方向都错:
    #      · listing 是 list  -> 先算 listing.get("content") -> AttributeError 当场炸;
    #      · listing 是 dict  -> 走 else 分支得到 []  -> known 永远空 -> **幂等失效**,
    #        每次重跑都把原料类型再建一遍, 而且**不报错**(最坏的那种)。
    #    ⇒ 改成与本文件其它 ensure_* 一致的写法(分页对象取 content, 裸数组直接用)。
    rows = listing.get("content") if isinstance(listing, dict) else listing
    known = {m.get("code"): m for m in (rows or [])}
    for code, name, category, main_category, unit, storage in RAW_MATERIALS:
        if code in known:
            out[code] = known[code]["id"]
            step(f"raw-material {name}", "reused", id=out[code])
            continue
        # ⚠️ mainCategory 必填: 后端要求每个「小类」(category)都登记属于哪个**大类**,
        #    大类只有 原料 / 辅料 / 包材 三种。不带它会 400
        #    「物料基本类型「畜类」尚未归入大类」(2026-09-15 本机实测)。
        body = {"code": code, "name": name, "category": category,
                "mainCategory": main_category, "unit": unit,
                "storageType": storage, "isActive": True,
                "notes": f"{DEMO_MARK} 演示数据"}
        data = api.need(api.post(f"/api/mobile/{fid}/raw-material-types", body),
                        f"建原料类型 {name}")
        if data is None:
            step(f"raw-material {name}", "ok", note="dry-run")
            continue
        out[code] = data["id"]
        step(f"raw-material {name}", "created", id=data["id"])
    return out


def ensure_product_types(api: Api, fid: str) -> dict:
    """20 个分割部位 + 1 个成品(幂等: 按 code)。"""
    out = {}
    listing = api.need(api.get(f"/api/mobile/{fid}/product-types",
                               params={"page": 1, "size": 300}), "查产品类型") or {}
    rows = listing.get("content") if isinstance(listing, dict) else listing
    known = {p.get("code"): p for p in (rows or [])}

    # 🔴 productCategory 必填, 且**必须与族图节点 kind 对上**(2026-09-15 本机实测):
    #      SEMI_FINISHED 节点 -> SKU 必须是 SEMI_FINISHED
    #      FINISHED_GOOD 节点 -> SKU 必须在 FINISHED_OUTPUT_CATEGORIES 里(用 FINISHED_PRODUCT)
    #    不填直接 400 PRODUCT_CATEGORY_REQUIRED;
    #    ⚠️ 填错的代价更大: 后端注释写着建好之后**普通编辑改不了**(会撞
    #       SKU_UNIT_MIGRATION_REQUIRED), 只能删了重建。
    wanted = [(f"PT-CUT-{code}", name, "分割部位", "SEMI_FINISHED") for name, code in CUTS]
    wanted.append((FINISHED_CODE, FINISHED_NAME, "成品", "FINISHED_PRODUCT"))

    for code, name, category, product_category in wanted:
        if code in known:
            out[code] = known[code]["id"]
            step(f"product-type {name}", "reused", id=out[code])
            continue
        body = {"code": code, "name": name, "category": category, "unit": "kg",
                "productCategory": product_category,
                "description": f"{DEMO_MARK} 演示数据"}
        data = api.need(api.post(f"/api/mobile/{fid}/product-types", body),
                        f"建产品类型 {name}")
        if data is None:
            step(f"product-type {name}", "ok", note="dry-run")
            continue
        out[code] = data["id"]
        step(f"product-type {name}", "created", id=data["id"])
    return out


def ensure_work_processes(api: Api, fid: str) -> dict:
    """工序(幂等: 按 processName)。"""
    out = {}
    listing = api.need(api.get(f"/api/mobile/{fid}/work-processes",
                               params={"page": 1, "size": 200}), "查工序") or {}
    rows = listing.get("content") if isinstance(listing, dict) else listing
    known = {w.get("processName"): w for w in (rows or [])}
    for name, category, minutes in WORK_PROCESSES:
        if name in known:
            out[name] = known[name]["id"]
            step(f"work-process {name}", "reused", id=out[name])
            continue
        body = {"processName": name, "processCategory": category, "createCategory": True,
                "unit": "kg", "outputUnit": "kg", "estimatedMinutes": minutes,
                "isActive": True, "description": f"{DEMO_MARK} 演示数据"}
        data = api.need(api.post(f"/api/mobile/{fid}/work-processes", body), f"建工序 {name}")
        if data is None:
            step(f"work-process {name}", "ok", note="dry-run")
            continue
        out[name] = data["id"]
        step(f"work-process {name}", "created", id=data["id"])
    return out


# ── 工艺族图 ────────────────────────────────────────────────────────

def _material_node(node_id: str, kind: str, name: str, sku_id: str) -> dict:
    return {"id": node_id, "kind": kind, "position": {"x": 0, "y": 0},
            "data": {"name": name, "skuId": sku_id, "skuCode": sku_id}}


def _port(pid: str, direction: str, material_node_id: str, material_kind: str,
          ordinal: int) -> dict:
    return {"id": pid, "direction": direction, "materialNodeId": material_node_id,
            "materialKind": material_kind, "unit": "kg", "ordinal": ordinal}


def _process_node(node_id: str, work_process_id: str, name: str, ports: list) -> dict:
    return {"id": node_id, "kind": "PROCESS", "position": {"x": 0, "y": 0},
            "data": {"workProcessId": work_process_id, "processName": name,
                     "reportingSelectionMode": "ACTUAL_IO",
                     "inputUnit": "kg", "outputUnit": "kg", "ports": ports,
                     "conversionRule": {"mode": "ACTUAL_WEIGHT"}}}


def build_workflow(raw_ids: dict, pt_ids: dict, wp_ids: dict) -> dict:
    """活牛(RAW) → 屠宰 → 胴体(SEMI) → 分割(20 个 OUTPUT port) → 各部位(SEMI)
       → 加工 → 酱牛肉(FINISHED)

    结构照 `ProductProcessWorkflowServiceImplTest.validDefinition()`(L1237-1355)。
    ⚠️ 这里**不自己发明结构** —— 校验器只认那一种形状, 猜一种写法的代价是
       publish 时一串看不懂的报错。
    """
    nodes = [
        _material_node("m-live", "RAW_MATERIAL", "活牛", raw_ids.get("RM-CATTLE-LIVE", "RM-CATTLE-LIVE")),
        _process_node("p-slaughter", wp_ids.get("屠宰", "WP-SLAUGHTER"), "屠宰", [
            _port("in-live", "INPUT", "m-live", "RAW_MATERIAL", 0),
            _port("out-carcass", "OUTPUT", "m-carcass", "SEMI_FINISHED", 0),
        ]),
        _material_node("m-carcass", "SEMI_FINISHED", "胴体半片",
                       raw_ids.get("RM-CARCASS-HALF", "RM-CARCASS-HALF")),
    ]
    # 分割: 1 个 PROCESS 节点, 1 个 INPUT + 20 个 OUTPUT port
    split_ports = [_port("in-carcass", "INPUT", "m-carcass", "SEMI_FINISHED", 0)]
    for idx, (name, code) in enumerate(CUTS):
        node_id = f"m-cut-{code.lower()}"
        split_ports.append(_port(f"out-{code.lower()}", "OUTPUT", node_id,
                                 "SEMI_FINISHED", idx))
    nodes.append(_process_node("p-split", wp_ids.get("分割", "WP-SPLIT"), "分割", split_ports))
    for name, code in CUTS:
        nodes.append(_material_node(f"m-cut-{code.lower()}", "SEMI_FINISHED", name,
                                    pt_ids.get(f"PT-CUT-{code}", f"PT-CUT-{code}")))
    # 加工: 取牛腩做酱牛肉
    nodes.append(_process_node("p-cook", wp_ids.get("加工", "WP-COOK"), "加工", [
        _port("in-nanfu", "INPUT", "m-cut-nanfu", "SEMI_FINISHED", 0),
        _port("out-braised", "OUTPUT", "m-braised", "FINISHED_GOOD", 0),
    ]))
    nodes.append(_material_node("m-braised", "FINISHED_GOOD", FINISHED_NAME,
                                pt_ids.get(FINISHED_CODE, FINISHED_CODE)))

    edges = [
        {"id": "e-live-slaughter", "source": "m-live", "sourceHandle": "output",
         "target": "p-slaughter", "targetHandle": "in-live"},
        {"id": "e-slaughter-carcass", "source": "p-slaughter", "sourceHandle": "out-carcass",
         "target": "m-carcass", "targetHandle": "input"},
        {"id": "e-carcass-split", "source": "m-carcass", "sourceHandle": "output",
         "target": "p-split", "targetHandle": "in-carcass"},
    ]
    for name, code in CUTS:
        edges.append({"id": f"e-split-{code.lower()}", "source": "p-split",
                      "sourceHandle": f"out-{code.lower()}",
                      "target": f"m-cut-{code.lower()}", "targetHandle": "input"})
    edges.append({"id": "e-nanfu-cook", "source": "m-cut-nanfu", "sourceHandle": "output",
                  "target": "p-cook", "targetHandle": "in-nanfu"})
    edges.append({"id": "e-cook-braised", "source": "p-cook", "sourceHandle": "out-braised",
                  "target": "m-braised", "targetHandle": "input"})

    return {"schemaVersion": 1, "nodes": nodes, "edges": edges,
            "viewport": {"x": 0, "y": 0, "zoom": 1}}


def ensure_workflow(api: Api, fid: str, product_type_id: str, definition: dict) -> None:
    """草稿 + 发布。

    ⛔ 发不出去**不要绕**: 把校验器的报错原样打出来 —— 那句话就是它要的形状。
       绕过去(改成一张更简单的图)等于把问题推到演示当天。
    """
    # 幂等: 已有草稿/已发布版本时, 必须把**当前** lockVersion 带上再存,
    # 否则乐观锁 409 PRODUCT_PROCESS_WORKFLOW_CONFLICT「该 Workflow 已被其他人更新」。
    # ⚠️ 那句话读起来像「有别人在改」, 实际上第二次跑自己就会撞 —— 2026-09-15 实测。
    current = api.get(f"/api/mobile/{fid}/product-process-workflows/{product_type_id}")
    if current.get("success") and (current.get("data") or {}).get("lockVersion") is not None:
        existing = current["data"]
        definition = dict(definition)
        # 🔴 `assertCurrentIdentity` 要**三个**都对上: id / version / lockVersion
        #    (version 对应实体的 definitionVersion)。少传任何一个都是同一句
        #    409「该 Workflow 已被其他人更新」—— 那句话不会告诉你少了哪个。
        #    2026-09-15 实测: 只带 id+lockVersion 仍然 409, 补上 version 才过。
        definition["lockVersion"] = existing["lockVersion"]
        definition["id"] = existing.get("id")
        definition["version"] = existing.get("version")
        for key in ("id", "version", "lockVersion"):
            if definition.get(key) is None:
                raise SeedError(
                    f"已有 Workflow 但读不到 {key}, 无法带锁保存。完整响应: "
                    + json.dumps(existing, ensure_ascii=False)[:600])
        info(f"已有 Workflow(status={existing.get('status')} "
             f"lockVersion={existing.get('lockVersion')}), 带锁保存")

    resp = api.put(f"/api/mobile/{fid}/product-process-workflows/{product_type_id}/draft",
                   definition)
    draft = api.need(resp, "保存工艺草稿")
    if draft is None:
        step("workflow draft", "ok", note="dry-run")
        step("workflow publish-and-activate", "ok", note="dry-run")
        return
    step("workflow draft", "created", nodes=len(definition["nodes"]),
         edges=len(definition["edges"]), lockVersion=draft.get("lockVersion"),
         revisionId=draft.get("revisionId"))

    # 🔴 publish 的 5 个字段全部 @NotNull/@NotBlank, 而且**只能从草稿响应里取** ——
    #    自己编一个 lockVersion/revisionHash 就是在绕乐观锁。
    #    ⚠️ 少传任何一个, 后端返回的是 400「请求格式不正确，请检查JSON格式」
    #      (HttpMessageNotReadable/校验失败的统一文案), 它**不会**告诉你少了哪个字段 ——
    #      2026-09-15 本机实测: 第一版不带 body, 就得到这句, 很容易被读成「族图有问题」。
    missing = [k for k in ("lockVersion", "revisionId", "revisionHash") if draft.get(k) is None]
    if missing:
        raise SeedError(
            "草稿响应里缺 " + ", ".join(missing) + " —— 无法发布。完整草稿响应: "
            + json.dumps(draft, ensure_ascii=False)[:800])
    publish_body = {
        "lockVersion": draft["lockVersion"],
        "revisionId": draft["revisionId"],
        "revisionHash": draft["revisionHash"],
        "definitionVersion": draft.get("schemaVersion") or 1,
        # 幂等键: 同一份草稿重复发布不应产生第二个版本。
        "idempotencyKey": f"{DEMO_MARK}-publish-{product_type_id}-{draft['revisionId']}",
    }
    resp = api.post(
        f"/api/mobile/{fid}/product-process-workflows/{product_type_id}/publish-and-activate",
        publish_body)
    api.need(resp, "发布并激活工艺")
    step("workflow publish-and-activate", "created",
         lockVersion=publish_body["lockVersion"], revisionId=publish_body["revisionId"])


# ── 源头个体 + 期初批次 ─────────────────────────────────────────────

def ensure_source_individuals(api: Api, fid: str) -> dict:
    """6 头个体(幂等: 按 individualCode)。系谱分两轮 —— 先建全部, 再补父母,
    因为父母必须**先存在**(后端对不存在的父本返回 422 INVALID_PEDIGREE)。"""
    code_to_id = {}
    listing = api.need(api.get(f"/api/mobile/{fid}/source-individuals",
                               params={"page": 1, "size": 200}), "查源头个体") or {}
    rows = listing.get("content") if isinstance(listing, dict) else listing
    known = {r.get("individualCode"): r for r in (rows or [])}

    for code, tag, sex, birth, dna, _sire, _dam in INDIVIDUALS:
        if code in known:
            code_to_id[code] = known[code]["id"]
            step(f"source-individual {code}", "reused", id=code_to_id[code])
            continue
        body = {"individualCode": code, "tagNumber": tag, "species": SPECIES,
                "breed": BREED, "sex": sex, "birthDate": birth,
                "originFarm": ORIGIN_FARM, "dnaVerificationStatus": dna,
                "externalGeneBankId": f"GB-{code}", "dnaSampleCode": f"S-{code}",
                "notes": f"{DEMO_MARK} 演示个体"}
        data = api.need(api.post(f"/api/mobile/{fid}/source-individuals", body),
                        f"建源头个体 {code}")
        if data is None:
            step(f"source-individual {code}", "ok", note="dry-run")
            continue
        code_to_id[code] = data["id"]
        step(f"source-individual {code}", "created", id=data["id"], tag=tag)

    # 第二轮: 系谱
    for code, _tag, _sex, _birth, _dna, sire, dam in INDIVIDUALS:
        if not (sire or dam) or code not in code_to_id:
            continue
        current = known.get(code) or {}
        want_sire = code_to_id.get(sire) if sire else None
        want_dam = code_to_id.get(dam) if dam else None
        if (current.get("sireIndividualId") == want_sire
                and current.get("damIndividualId") == want_dam
                and (want_sire or want_dam)):
            step(f"pedigree {code}", "reused")
            continue
        body = {}
        if want_sire:
            body["sireIndividualId"] = want_sire
        if want_dam:
            body["damIndividualId"] = want_dam
        if not body:
            continue
        api.need(api.put(f"/api/mobile/{fid}/source-individuals/{code_to_id[code]}", body),
                 f"挂系谱 {code}")
        step(f"pedigree {code}", "created" if not api.dry_run else "ok",
             sire=sire or "-", dam=dam or "-")
    return code_to_id


def ensure_live_cattle_batches(api: Api, fid: str, raw_ids: dict,
                               individual_ids: dict) -> list:
    """6 个活牛期初批次 + 挂 sourceIndividualId。

    🔴 两段式, 且第二段是**必须**的:
       `OpeningInventoryItem` 上**没有** `sourceIndividualId` 字段(实测 DTO 只有
       materialTypeId/warehouseId/quantity/quantityUnit/unitPrice/batchNumber/
       productionDate/expiryDate) ⇒ 期初建账**带不上**个体。
       所以建完再用 `PUT /material-batches/{id}` 补挂(#791 给
       UpdateMaterialBatchRequest 加了这个字段)。
    ⚠️ 这一步失败时**不能**当成「个体没挂上也无所谓」—— 挂不上就没有溯源链,
       整个 demo 的卖点就没了。所以同样失败即停。
    """
    material_type_id = raw_ids.get("RM-CATTLE-LIVE")
    batch_key = f"{DEMO_MARK}-LIVE-CATTLE-V1"   # 幂等键: 同 key 重复提交不双建
    items = []
    for idx, (code, tag, *_rest) in enumerate(INDIVIDUALS):
        items.append({
            "materialTypeId": material_type_id,
            "quantity": LIVE_WEIGHTS[idx],
            "quantityUnit": "kg",
            "unitPrice": 32.0,
            "batchNumber": f"{DEMO_MARK}-LIVE-{code}",
            "productionDate": DEMO_DATE,
        })
    resp = api.post(f"/api/mobile/{fid}/material-batches/opening",
                    {"items": items, "remark": f"{DEMO_MARK} 活牛入栏(演示)",
                     "batchKey": batch_key})
    data = api.need(resp, "活牛期初建账")
    if data is None:
        step("opening live-cattle batches", "ok", note="dry-run")
        return []
    idempotent = bool(data.get("idempotentHit"))
    created_batches = data.get("batches") or data.get("createdBatches") or []
    step("opening live-cattle batches", "reused" if idempotent else "created",
         count=len(created_batches) or len(items), batchKey=batch_key)

    # 补挂个体
    listing = api.need(api.get(f"/api/mobile/{fid}/material-batches",
                               params={"page": 1, "size": 200}), "查原料批次") or {}
    rows = listing.get("content") if isinstance(listing, dict) else listing
    by_number = {b.get("batchNumber"): b for b in (rows or [])}
    attached = []
    for code, *_ in INDIVIDUALS:
        number = f"{DEMO_MARK}-LIVE-{code}"
        batch = by_number.get(number)
        if not batch:
            raise SeedError(f"期初建账之后找不到批次 {number} —— "
                            f"建账可能没真的落库, 后面的挂个体一定不对")
        want = individual_ids.get(code)
        if batch.get("sourceIndividualId") == want:
            step(f"attach individual {code}", "reused", batch=number)
            attached.append(batch["id"])
            continue
        api.need(api.put(f"/api/mobile/{fid}/material-batches/{batch['id']}",
                         {"sourceIndividualId": want}), f"给批次 {number} 挂个体")
        step(f"attach individual {code}", "created", batch=number, individual=code)
        attached.append(batch["id"])
    return attached


# ── 生产链 ──────────────────────────────────────────────────────────

def run_production_chain(api: Api, fid: str, pt_ids: dict, supervisor_id) -> str | None:
    """计划 → 批次 → 开工 → spawn-tasks → 分割报工 → 标签。返回 traceCode。"""
    finished_pt = pt_ids.get(FINISHED_CODE)
    if not finished_pt:
        raise SeedError("缺成品产品类型 ID, 生产链没法跑")

    plan_number_hint = f"{DEMO_MARK}-PLAN-V1"
    plans = api.need(api.get(f"/api/mobile/{fid}/production-plans",
                             params={"page": 1, "size": 100}), "查生产计划") or {}
    prows = plans.get("content") if isinstance(plans, dict) else plans
    plan = next((p for p in (prows or []) if plan_number_hint in str(p.get("remark") or "")), None)
    if plan:
        plan_id = plan["id"]
        step("production plan", "reused", id=plan_id)
    else:
        data = api.need(api.post(f"/api/mobile/{fid}/production-plans", {
            "productTypeId": finished_pt, "plannedQuantity": 200, "unit": "kg",
            "plannedDate": DEMO_DATE, "remark": f"{plan_number_hint} 酱牛肉演示计划",
        }), "建生产计划")
        if data is None:
            step("production plan", "ok", note="dry-run")
            return None
        plan_id = data["id"]
        step("production plan", "created", id=plan_id)

    batch_number = f"{DEMO_MARK}-BATCH-V1"
    batches = api.need(api.get(f"/api/mobile/{fid}/processing/batches",
                               params={"page": 1, "size": 100}), "查生产批次") or {}
    brows = batches.get("content") if isinstance(batches, dict) else batches
    batch = next((b for b in (brows or []) if b.get("batchNumber") == batch_number), None)
    if batch:
        batch_id = batch["id"]
        step("production batch", "reused", id=batch_id, number=batch_number)
    else:
        data = api.need(api.post(f"/api/mobile/{fid}/processing/batches", {
            "batchNumber": batch_number, "productTypeId": finished_pt,
            "productionPlanId": plan_id, "plannedQuantity": 200, "unit": "kg",
            "remark": f"{DEMO_MARK} 酱牛肉演示批次",
        }), "建生产批次")
        if data is None:
            step("production batch", "ok", note="dry-run")
            return None
        batch_id = data["id"]
        batch = data
        step("production batch", "created", id=batch_id, number=data.get("batchNumber"))

    # 开工(已开工时后端会说, 不当失败)
    started = api.post(f"/api/mobile/{fid}/processing/batches/{batch_id}/start",
                       params={"supervisorId": supervisor_id} if supervisor_id else None)
    if started.get("success"):
        step("start batch", "created", id=batch_id)
    else:
        step("start batch", "reused", note=str(started.get("message"))[:60])

    spawned = api.post(f"/api/mobile/{fid}/production/batches/{batch_id}/spawn-tasks",
                       {"productTypeId": str(finished_pt)})
    tasks = spawned.get("data") or []
    task_id = tasks[0].get("id") if tasks else None
    step("spawn work-process tasks", "created" if spawned.get("success") else "ok",
         spawned=len(tasks), firstTaskId=task_id)

    # 分割报工: 输入 1 个胴体, 输出 3 个部位
    report_body = {
        "reportKind": "OUTPUT",
        "outputKind": "FINISHED",
        "outputQuantity": sum(w for _n, w in SPLIT_OUTPUTS),
        "outputUnit": "kg",
        "workerCount": 4,
        "workMinutes": 180,
        "remark": (f"{DEMO_MARK} 分割报工: 输入 1 个胴体半片, 输出 "
                   + "/".join(f"{n}{w}kg" for n, w in SPLIT_OUTPUTS)),
    }
    if task_id:
        report_body["workProcessTaskId"] = task_id
    rep = api.post(f"/api/mobile/{fid}/production/batches/{batch_id}/reports", report_body)
    if rep.get("success"):
        d = rep.get("data") or {}
        step("split yield report", "created",
             reportId=d.get("reportId") or d.get("id"),
             outputs=len(SPLIT_OUTPUTS))
    else:
        step("split yield report", "ok", note=str(rep.get("message"))[:80])

    # 标签 + traceCode
    label = api.post(f"/api/mobile/{fid}/labels", {"batchId": batch_id, "labelType": "BATCH"})
    ldata = api.need(label, "建标签")
    if ldata is None:
        step("label", "ok", note="dry-run")
        return None
    trace_code = ldata.get("traceCode") or ldata.get("qrContent")
    step("label", "created", labelCode=ldata.get("labelCode"), traceCode=trace_code)
    return trace_code


def self_test() -> int:
    """离线自检 —— **不连后端**, 只验族图 JSON 的内部自洽。

    为什么单独有这一档: 族图是整条链里最容易写错、也最难从报错里看出哪写错的一块
    (校验器只会说"端口引用不到节点"这类话, 不会说"你少写了第 17 条边")。
    ⇒ 把「引用完整性」这类**不需要后端就能判**的事先在本地判掉,
      到了 prod 再失败时, 至少能确定不是这一类。

    ⚠️ 它**不能**替代真发布: 端口方向、单位、reportingSelectionMode 这些语义
       只有服务端校验器说了算。这里只声称「图是自洽的」, ⛔ 不声称「图是对的」。
    """
    print("SELF-TEST 族图结构自检 (离线, 不连后端)")

    # ── ⓪ 先把 step() 真调一次 ──────────────────────────────────────────
    # 🔴 这一条是**缺陷驱动**加的: 第一版 `step(name, status, **kv)` 与调用方的
    #    `name=FACTORY_NAME` 撞参数, 运行时 TypeError, 而**没有任何东西调过 step**
    #    ⇒ 它活到了第一次真跑。形态 B: 机制在, 没接上 —— 自检不覆盖的代码就是没测。
    # ⚠️ 用真实调用点里出现过的那几个 kwarg(name/id/status 等), ⛔ 不是随便编两个。
    _before = _STEP
    try:
        step("self-test probe", "ok", id="X-1", name=FACTORY_NAME, note="probe")
        step("self-test probe", "created", batch="B-1", individual="I-1")
        step("self-test probe", "reused", traceCode="T-1", http=200)
    except TypeError as exc:
        print(f"  SELF-TEST FAILED: step() 调不通 —— {exc}")
        return 1
    if _STEP != _before + 3:
        print(f"  SELF-TEST FAILED: step() 计数没动 ({_before} -> {_STEP})")
        return 1
    print(f"  step() OK: 3 次调用计数 {_before} -> {_STEP} "
          f"(created={_CREATED} reused={_REUSED})")

    raw_ids = {code: f"RAWID-{code}" for code, *_ in RAW_MATERIALS}
    pt_ids = {f"PT-CUT-{c}": f"PTID-{c}" for _n, c in CUTS}
    pt_ids[FINISHED_CODE] = "PTID-FINISHED"
    wp_ids = {n: f"WPID-{n}" for n, *_ in WORK_PROCESSES}

    d = build_workflow(raw_ids, pt_ids, wp_ids)
    nodes = {n["id"]: n for n in d["nodes"]}
    problems: list[str] = []

    # ① 节点 id 不重复
    if len(nodes) != len(d["nodes"]):
        problems.append(f"节点 id 有重复: {len(d['nodes'])} 个节点只有 {len(nodes)} 个不同 id")

    # ② 每个 port 的 materialNodeId 必须指向真实存在的物料节点
    port_ids = set()
    out_ports = 0
    for n in d["nodes"]:
        if n["kind"] != "PROCESS":
            continue
        for p in n["data"]["ports"]:
            key = f"{n['id']}::{p['id']}"
            if key in port_ids:
                problems.append(f"端口 key 重复: {key}")
            port_ids.add(key)
            if p["materialNodeId"] not in nodes:
                problems.append(f"端口 {key} 指向不存在的物料节点 {p['materialNodeId']}")
            elif nodes[p["materialNodeId"]]["kind"] != p["materialKind"]:
                problems.append(
                    f"端口 {key} 声明 materialKind={p['materialKind']}, "
                    f"而节点 {p['materialNodeId']} 实际是 {nodes[p['materialNodeId']]['kind']}")
            if p["direction"] == "OUTPUT" and n["id"] == "p-split":
                out_ports += 1

    # ③ 每条边的两端都要存在
    for e in d["edges"]:
        for side in ("source", "target"):
            if e[side] not in nodes:
                problems.append(f"边 {e['id']} 的 {side}={e[side]} 不存在")

    # ④ 分割工序必须正好 20 个 OUTPUT port(= 20 个部位)
    if out_ports != len(CUTS):
        problems.append(f"分割工序 OUTPUT 端口 {out_ports} 个, 期望 {len(CUTS)}")

    # ⑤ 每个部位都要有自己的物料节点和一条从分割出来的边
    edge_targets = {e["target"] for e in d["edges"]}
    for name, code in CUTS:
        nid = f"m-cut-{code.lower()}"
        if nid not in nodes:
            problems.append(f"部位 {name} 缺物料节点")
        elif nid not in edge_targets:
            problems.append(f"部位 {name} 没有从分割工序连过来的边")

    kinds = {}
    for n in d["nodes"]:
        kinds[n["kind"]] = kinds.get(n["kind"], 0) + 1
    print(f"  nodes={len(d['nodes'])} edges={len(d['edges'])} "
          f"split_output_ports={out_ports} kinds={kinds}")
    # 阳性对照: 图不能是空的 —— 空图会让上面每一条检查恒真。
    if len(d["nodes"]) < 5 or len(d["edges"]) < 5:
        problems.append("图太小, 上面的检查基本恒真, 本次自检无意义")

    if problems:
        print("  SELF-TEST FAILED:")
        for p in problems:
            print("   -", p)
        return 1
    print("  SELF-TEST OK (自洽性通过; ⛔ 不代表服务端校验器会接受)")
    return 0


def assert_public_trace(api: Api, trace_code: str) -> None:
    """终态断言: 公开溯源 200。

    ⚠️ 这一步是**整个脚本的判据** —— 前面每步都 success 但公开页打不开,
       演示当天扫码就是白屏。所以它单独作为终态, ⛔ 不与别的步骤合并。
    """
    resp = api.get(f"/api/public/trace/code/{urllib.parse.quote(trace_code, safe='')}")
    if not resp.get("success"):
        raise SeedError(f"公开溯源失败 traceCode={trace_code}: "
                        f"{json.dumps(resp, ensure_ascii=False)[:1200]}")
    data = resp.get("data") or {}
    step("PUBLIC TRACE (final assertion)", "ok",
         http=resp.get("_http"), traceCode=trace_code,
         keys=",".join(sorted(data.keys())[:8]))
    print("\n─── 公开溯源响应(截断 2000 字符) ───")
    print(json.dumps(resp, ensure_ascii=False, indent=2)[:2000])


# ════════════════════════════════════════════════════════════════════
# main
# ════════════════════════════════════════════════════════════════════

def main() -> int:
    p = argparse.ArgumentParser(description="胜龙牛业演示租户造数(纯 API, 幂等)")
    p.add_argument("--base-url", default=os.getenv("SEED_BASE_URL"),
                   help="后端地址, 例 http://localhost:10010 (必填; ⛔ 无 prod 默认值)")
    p.add_argument("--platform-user", default=os.getenv("SEED_PLATFORM_USER"))
    p.add_argument("--platform-pass", default=os.getenv("SEED_PLATFORM_PASS"))
    p.add_argument("--platform-factory", default=os.getenv("SEED_PLATFORM_FACTORY"),
                   help="平台管理员登录所属 factoryId(部分部署要求); 不传则不带")
    p.add_argument("--demo-password", default=os.getenv("SEED_DEMO_PASSWORD"),
                   help="演示租户管理员口令 (⛔ 不写进代码)")
    p.add_argument("--dry-run", action="store_true",
                   help="只登录 + 只读探测, 不写任何数据")
    p.add_argument("--internal-key", default=os.getenv("SEED_INTERNAL_KEY"),
                   help="内部 onboarding 端点的 X-Internal-Key (后端 internal.api.key)。"
                        "⚠️ 新工厂的第一批用户只能由它建 —— 平台管理员跨不了模块闸。")
    p.add_argument("--self-test", action="store_true",
                   help="离线自检族图结构, 不连后端(不需要任何凭证)")
    args = p.parse_args()

    if args.self_test:
        return self_test()

    if not args.base_url:
        print("ERROR: 必须给 --base-url (或 SEED_BASE_URL)。⛔ 本脚本没有 prod 默认值。",
              file=sys.stderr)
        return 2
    if not (args.platform_user and args.platform_pass):
        print("ERROR: 需要 --platform-user/--platform-pass (或 env)。", file=sys.stderr)
        return 2
    if not args.dry_run and not args.demo_password:
        print("ERROR: 非 dry-run 必须给 --demo-password (或 SEED_DEMO_PASSWORD)。",
              file=sys.stderr)
        return 2
    # ⛔ 早停在**建任何东西之前**: 缺 key 时后面必定 403, 而那时工厂已经建了一半。
    if not args.dry_run and not args.internal_key:
        print("\n".join([
            "ERROR: 需要 --internal-key (或 SEED_INTERNAL_KEY)。",
            "  新工厂的第一批用户只能由内部 onboarding 端点建 —— 平台管理员带不动",
            "  @RequireModule 的跨工厂请求(ModuleEnabledInterceptor 无平台角色豁免)。",
            "  后端侧对应配置项是 `internal.api.key`;",
            "  ⚠️ 它没配置时默认是**每次启动随机的 UUID**, 那种情况下任何值都会 403",
            "     —— 先确认后端真的配了它, ⛔ 别把 403 读成「key 写错了」。",
        ]), file=sys.stderr)
        return 2

    info(f"base-url={args.base_url} dry_run={args.dry_run} "
         f"platform_user={args.platform_user} "
         f"demo_password_len={len(args.demo_password or '')}")   # ⛔ 只打长度

    api = Api(args.base_url, dry_run=args.dry_run)
    try:
        who = login(api, args.platform_user, args.platform_pass, args.platform_factory)
        step("platform login", "ok", user=args.platform_user, role=who.get("roleCode") or who.get("role"))

        fid = ensure_factory(api, args.internal_key or "")
        admin_user = ensure_factory_admin(api, fid, args.demo_password or "",
                                          args.internal_key or "")

        if args.dry_run:
            info("dry-run: 到此为止(后面每一步都要写数据)。")
            print(f"\nSUMMARY dry_run steps={_STEP} created={_CREATED} reused={_REUSED}")
            return 0

        me = login(api, admin_user, args.demo_password, fid)
        step("factory admin login", "ok", user=admin_user, id=me.get("id"))
        supervisor_id = me.get("id")

        raw_ids = ensure_raw_materials(api, fid)
        pt_ids = ensure_product_types(api, fid)
        wp_ids = ensure_work_processes(api, fid)

        definition = build_workflow(raw_ids, pt_ids, wp_ids)
        ensure_workflow(api, fid, pt_ids[FINISHED_CODE], definition)

        individual_ids = ensure_source_individuals(api, fid)
        ensure_live_cattle_batches(api, fid, raw_ids, individual_ids)

        trace_code = run_production_chain(api, fid, pt_ids, supervisor_id)
        if not trace_code:
            raise SeedError("没有拿到 traceCode, 终态断言无从做起")
        assert_public_trace(api, trace_code)

    except SeedError as exc:
        print(f"\n[{_ts()}] FAILED at STEP={_STEP + 1}\n{exc}", file=sys.stderr)
        print(f"SUMMARY steps={_STEP} created={_CREATED} reused={_REUSED} result=FAILED")
        return 1

    print(f"\nSUMMARY factory={fid} steps={_STEP} created={_CREATED} reused={_REUSED} result=OK")
    print("幂等判据: 立刻再跑一遍, created 应为 0 (⛔ 不是「没报错」)。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
