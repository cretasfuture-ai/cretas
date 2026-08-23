# -*- coding: utf-8 -*-
"""同一句问句问两次，产品行为翻面 —— **翻面发生在哪一层、翻的是哪一维**。

## 被测的那件事（⛔ 不是「答不上」）

prod 全景重测（24 问句 × 2 轮）读到三条同句两轮不同归宿：

    Q04 这个月比上个月好还是差   r1=answer(1306字)  r2=clarification
    Q10 我要不要关掉最差的那家店  r1=clarification   r2=answer(2081字)
    Q18 哪个供应商报价最贵      r1=clarification(45字) r2=answer(196字)

▎老板问一次拿到答案、再问一次被反问。**那比稳定地拒答更难建立信任。**

## 这个探针要回答的唯一问题

▎**翻的是哪一层、哪一维？** 五层各自逐轮记全，然后问：
▎「哪个字段**在每个归宿组内部恒定、组之间不同**」——那个字段就是根因。

    L0 raw     `call_chain` 返回体里模型自己写的那串 JSON 文本 + 实际应答的 model
    L1 parsed  `_semantic_spec_from_t3` 收到的模型原始 dict（含 `missing_fields`，
               ⛔ 它到不了 `_build_spec`）
    L2 build   `_build_spec` 收到的每一个 kwarg
    L3 spec    最终 `RestaurantQuerySpec` 的**每一个** dataclass 字段
    L4 out     `kind` / 字数 / contract / 答案指纹

🔴 **L0 是判层的关键**：raw content 两轮不同 ⇒ 翻在模型；raw content 相同
   而下游不同 ⇒ 翻在代码。⛔ 这两种的修法完全相反，从代码推不出来。

## ⛔ 不许只记「我挑的那几格」

本仓实测过：改记 43 个字段之后，「全同却翻转」的组数是 **0** ——
挑字段这个动作本身会造出「找不到原因」的假结论。
所以 L1 记 `parsed` 的**全部键**、L3 记 dataclass 的**全部字段**，
⛔ 不写 TRACKED 白名单。

## 两种模式（⛔ 都要跑 —— 它们量的**不是同一件事**）

    PROBE_CLEAR_CACHES=1  冷编译模式（默认）：每轮清 4 个缓存
                          ⇒ 量的是「LLM 对同一句话的原始不确定性」
    PROBE_CLEAR_CACHES=0  生产复刻模式：**一次都不清**
                          ⇒ 量的是「老板真的连问两次会不会翻面」

🔴 **这两个数会差很多，而只报第一个会把严重程度说错。**
   `_semantic_plan_cache_put` 的注释白纸黑字写着「反问的计划**从不入缓存**」
   ⇒ 作答会被缓存住、反问不会 ⇒ 生产上的翻面**方向是不对称的**。
   ⛔ 这一句是从代码读出来的，所以它**必须由生产复刻模式实测确认**（形态 B⁷）。

## 有效样本的定义（⛔ 先定义再决定跑多少轮）

一次调用算**有效**，当且仅当下面几条同时成立：

    ① `source_tier == 'llm'`  —— **仅冷编译模式**要求；生产复刻模式里
       缓存回放正是被测对象，要求它就等于把被测现象筛掉了
    ② `planner_authority != 'llm_unavailable'` —— 不是探针自己没 key
    ③ `_build_spec` 钩子被调用过 ≥1 次      —— 这条链真的跑到了规划器
       （⛔ 生产复刻模式不要求：零 token 回放本来就不调它）
    ④ 拿到了 `kind`（result 非 None）        —— 跑到了产品出口

一个**问句**算有效对比样本，当且仅当它有 ≥2 次有效调用。
⚠️ 有效率单独报 —— 本仓栽过「18 个问句只换来 2 个有效样本(11%)」而计数看着正常。

## 派生判据层 L1p（⛔ 不是可选的装饰）

🔴 第一轮实测暴露了一件事：**确定性地在翻的往往不是字段的值，是字段上的一个谓词。**
   Q18 的 `dimensions` 三个值 `[ingredient]` / `[]` / `[supplier]` 被分类器判成
   「噪声」（作答组内部就有两种值），而真正与归宿 100% 对齐的是
   **「这些维度在不在词表里」**。只记原始值会漏掉根因。
   ⇒ 每个列表型契约字段都配一条「在不在取值域里」，缺项配一条「含不含它」。

## 阳性 / 阴性对照（⛔ 不许省）

- 阳性①  菜单目录 > 0 条（执行链起得来）
- 阳性②  `_build_spec` 钩子总调用 > 0（否则 L2 全是 ∅，分不清「没给」和「没钩上」）
- 阳性③  `call_chain` 钩子总调用 > 0（否则 L0 全空，而 L0 是判层的那一层）
- 阳性④  被参数化的那一维真的不同：问句集逐条不同 **且** session_key 逐次不同
- 阴性   本轮至少出现一次 `clarification` —— 全是 answer 时「没翻面」和
          「整条链没跑到反问那一步」是同一个读数

## 三态退出码

    0  跑通了，且**没读到翻面**（⚠️ 这是「本次没复现」，不是「问题不存在」）
    1  读到翻面，读数有效且指向缺陷
    2  没量到 —— 阳性对照未通过 / 有效样本不足

## 用法

    ssh root@47.100.235.168 "/tmp/runprobe.sh smartbi.scripts._flipflop_layers /tmp/ff.log"
"""
from __future__ import annotations

import asyncio
import dataclasses
import difflib
import functools
import hashlib
import json
import os
import time

from smartbi.scripts._probe_bootstrap import (
    LLM_UNAVAILABLE_AUTHORITY,
    RESTAURANT_TENANTS_WITH_DATA,
    bootstrap_probe,
)

#: ⛔ 常量一律带 `FLIPFLOP_` 前缀 —— 本仓有「同名常量漂移」闸，且泛名
#:    (`QS`/`TARGET`/`TENANTS`…) 已经撞过。
FLIPFLOP_FID = os.environ.get("PROBE_FID", RESTAURANT_TENANTS_WITH_DATA[0])
FLIPFLOP_ROUNDS = int(os.environ.get("PROBE_ROUNDS", "6"))
#: 1 = 冷编译模式（每轮清缓存）/ 0 = 生产复刻模式（一次都不清）。见文件头。
FLIPFLOP_CLEAR_CACHES = os.environ.get("PROBE_CLEAR_CACHES", "1") != "0"

#: 🔴 **每次跑必须换一批 session_key。**（2026-08-19 实测的仪器缺陷，写在这里
#:    是为了下一个人不再踩）
#:
#:    `restaurant_pending_clarifications` 是 **Postgres 表**，主键
#:    `(factory_id, session_key)`，**跨进程、跨天存活**。探针原来用
#:    `ff-r{轮}-q{下标}` 这种**只跟轮次有关**的 key ——
#:    于是上一次跑留下的「待澄清」被这一次跑当成了「用户在回答上一次的反问」：
#:    实测 `is_clarification_continuation=True` / `resolver_query_seed` 变成
#:    **同一句话拼两遍**（「这个月比上个月好还是差 这个月比上个月好还是差」）。
#:
#:    ▎那时量到的不是「同一句问两次」，是「回答上一次的反问」——**换了被测对象**。
#:    ⚠️ 同形的 key 命名在 `_panorama24`（`p24-r%d-q%d`）里也有，见交付说明。
FLIPFLOP_RUN_ID = os.environ.get("PROBE_RUN_ID") or ("%d" % int(time.time()))

flipflop_ctx = bootstrap_probe(FLIPFLOP_FID)

from common import llm_router as flipflop_router  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.scripts.restaurant_panorama_probe import QUESTIONS as FLIPFLOP_ALL_QUESTIONS  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: ⛔ **不写第二份问句集** —— 按下标引用全景探针那一份（形态 D：两份一定会漂，
#:    而这份要漂的话，漂的方向恰好是「两个基线的分母不一样却被并排比」）。
#:
#: F = 全景重测里**实测翻过面**的三条； S = 同一轮里两轮同归宿的对照组。
#: 🔴 对照组不是装饰：没有它就分不清「这三条特殊」和「所有问句都在翻」。
FLIPFLOP_QUESTION_PLAN = (
    ("F", 3),    # 这个月比上个月好还是差   r1=answer r2=clarification
    ("F", 9),    # 我要不要关掉最差的那家店  r1=clarification r2=answer
    ("F", 17),   # 哪个供应商报价最贵       r1=clarification r2=answer
    ("S", 0),    # 哪家店卖得最好
    ("S", 12),   # 按门店看领料趋势
    ("S", 19),   # 一共有多少家店
)

#: ⛔ 用模块自带的 helper，不拼名字 —— 拼错的属性名不会报错，只会静默 no-op。
FLIPFLOP_CACHE_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)

#: 阴性对照专用：**真的没指定**任何指标/对象的问句，反问是**对的**行为。
#: 🔴 为什么需要它：被测的 6 条问句可能一整轮都不反问，那时
#:    「没翻面」和「这个进程根本走不到反问那条路」是同一个读数。
#: ⛔ 它不属于被测问句集，不进任何比例的分母 —— 只用来证明那条路通。
#: 📏 出处：`_superlative_ref` 的 C 组（实测 raw dimensions 恒为空 12/12）。
FLIPFLOP_REFUSAL_CONTROL_QUERIES = ("生意下滑了怎么办", "怎么提升营收")
FLIPFLOP_REFUSAL_CONTROL_TRIES = 3

#: 本轮所有钩子的落点。每次 turn 前清空。
FLIPFLOP_RAW_CALLS: list = []      # L0  call_chain
FLIPFLOP_PARSED_CALLS: list = []   # L1  _semantic_spec_from_t3
FLIPFLOP_BUILD_CALLS: list = []    # L2  _build_spec


def flipflop_clear_caches() -> list:
    done = []
    for name in FLIPFLOP_CACHE_CLEARERS:
        getattr(ri, name)()
        done.append(name)
    return done


def _norm(value) -> str:
    """把任意读数压成**可比较的字符串**。⛔ 不排序 —— 顺序本身可能就是翻的那一维。"""
    if value is None:
        return "∅"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_norm(v) for v in value) + "]"
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    if isinstance(value, str):
        return value
    return repr(value)


def _fp(text: str) -> str:
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


def flipflop_install_hooks() -> None:
    """三层钩子。⛔ `functools.wraps` 保签名 —— 裸 lambda 会静默改行为，
    那就不是「观测」是「变异」了。"""

    orig_chain = flipflop_router.call_chain

    @functools.wraps(orig_chain)
    async def _chain_spy(slot, payload, *args, **kwargs):
        t0 = time.time()
        # 🔴 记**输入**，不只记输出。没有这一格就分不清两件相反的事：
        #    「同一个 prompt 得到两种输出」= 模型侧不确定
        #    「prompt 本身就不同」        = 代码侧喂了不同的上下文
        #    ⛔ 从代码推不出来 —— hint / available_stores / 日期都进 prompt。
        prompt = ""
        try:
            prompt = "\n\n".join(
                (m.get("content") or "") for m in (payload.get("messages") or ()))
        except Exception:  # noqa: BLE001
            prompt = "<unreadable>"
        try:
            body = await orig_chain(slot, payload, *args, **kwargs)
        except Exception as exc:  # noqa: BLE001 —— 失败也要记，⛔ 不 continue
            FLIPFLOP_RAW_CALLS.append({
                "slot": getattr(slot, "value", str(slot)),
                "prompt": prompt,
                "model": "<raised>",
                "content": "%s: %s" % (type(exc).__name__, str(exc)[:200]),
                "elapsed": round(time.time() - t0, 2),
            })
            raise
        content = ""
        try:
            content = (body["choices"][0]["message"]["content"] or "").strip()
        except Exception:  # noqa: BLE001
            content = "<no-content>"
        FLIPFLOP_RAW_CALLS.append({
            "slot": getattr(slot, "value", str(slot)),
            "prompt": prompt,
            "temperature": payload.get("temperature"),
            "model": (body or {}).get("model"),
            "content": content,
            "elapsed": round(time.time() - t0, 2),
        })
        return body

    flipflop_router.call_chain = _chain_spy

    orig_t3 = ri._semantic_spec_from_t3

    @functools.wraps(orig_t3)
    def _t3_spy(parsed, query, **kw):
        # ⛔ 记 parsed 的**全部键**，不挑
        FLIPFLOP_PARSED_CALLS.append({
            "parsed": dict(parsed or {}),
            "available_stores": len(kw.get("available_stores") or ()),
            "is_continuation": kw.get("is_continuation"),
            "allow_time_default": kw.get("allow_time_default"),
        })
        return orig_t3(parsed, query, **kw)

    ri._semantic_spec_from_t3 = _t3_spy

    orig_build = ri._build_spec

    @functools.wraps(orig_build)
    def _build_spy(code, query, **kw):
        # ⛔ 记全部 kwarg，不挑
        FLIPFLOP_BUILD_CALLS.append({"code": code, "kw": dict(kw)})
        return orig_build(code, query, **kw)

    ri._build_spec = _build_spy


#: 列表型契约字段 → 它的取值域**在生产模块里**的名字。
#: ⛔ 这里不写第二份词表（形态 D）——直接读 `restaurant_intent` 的那几个常量，
#:    词表一改，这个探针跟着变。
FLIPFLOP_LIST_CONTRACTS = (
    ("dimensions", "_SEMANTIC_DIMENSIONS"),
    ("requested_metrics", "_SEMANTIC_METRICS"),
    ("missing_fields", "_SEMANTIC_MISSING_FIELDS"),
)

#: `missing_fields` 的四个取值。每个都单独出一条「含不含」谓词 ——
#: ⛔ 整个列表的值会因为**别的项**变动而看起来在动，谓词才对得上归宿。
FLIPFLOP_MISSING_FIELD_NAMES = ("metric", "object", "time_range", "store_scope")


def flipflop_contract_predicates(parsed: dict) -> dict:
    """L1p —— 模型原始 JSON 上的**谓词**，不是它的值。

    🔴 加这一层的直接原因（第一轮实测）：Q18 的 `dimensions` 在作答组内部
       就有 `[ingredient]` 和 `[]` 两种值，于是被分类成「噪声」；
       而 `dimensions ⊆ 词表` 这个谓词与归宿 **6/6 对齐**。
       ▎**翻的是谓词，不是值。** 只记值会把根因判成噪声。
    """
    out = {}
    for field, vocab_name in FLIPFLOP_LIST_CONTRACTS:
        vocab = getattr(ri, vocab_name)
        values = parsed.get(field)
        if not isinstance(values, list):
            out["L1p:%s_in_vocab" % field] = "<非列表>"
            out["L1p:%s_offenders" % field] = "<非列表>"
            continue
        bad = tuple(v for v in values if v not in vocab)
        out["L1p:%s_in_vocab" % field] = _norm(not bad)
        out["L1p:%s_offenders" % field] = _norm(bad)
    missing = parsed.get("missing_fields")
    for name in FLIPFLOP_MISSING_FIELD_NAMES:
        out["L1p:missing_has_%s" % name] = _norm(
            isinstance(missing, list) and name in missing)
    return out


def flipflop_flatten(turn: dict) -> dict:
    """把一次 turn 的五层读数摊成 `层:字段 -> 字符串`。⛔ 白名单一个都没有。"""
    flat = {}

    raw = turn["raw_calls"]
    flat["L0:call_n"] = _norm(len(raw))
    first_raw = raw[0] if raw else {}
    flat["L0:model"] = _norm(first_raw.get("model"))
    flat["L0:slot"] = _norm(first_raw.get("slot"))
    flat["L0:temperature"] = _norm(first_raw.get("temperature"))
    # 🔑 输入指纹 —— 它和 `content_fp` 一起才判得了「模型侧不确定」还是
    #    「代码侧喂了不同的 prompt」。⛔ 只记输出的话这两件事长得一模一样。
    flat["L0:prompt_fp"] = _fp(first_raw.get("prompt") or "")
    flat["L0:prompt_len"] = _norm(len(first_raw.get("prompt") or ""))
    flat["L0:content_fp"] = _fp(first_raw.get("content") or "")

    parsed_calls = turn["parsed_calls"]
    flat["L1:call_n"] = _norm(len(parsed_calls))
    first_parsed = (parsed_calls[0] if parsed_calls else {}).get("parsed") or {}
    for key in sorted(first_parsed):
        flat["L1:" + key] = _norm(first_parsed[key])
    if first_parsed:
        flat.update(flipflop_contract_predicates(first_parsed))
    if parsed_calls:
        flat["L1:available_stores"] = _norm(parsed_calls[0].get("available_stores"))
        flat["L1:is_continuation"] = _norm(parsed_calls[0].get("is_continuation"))
        flat["L1:allow_time_default"] = _norm(parsed_calls[0].get("allow_time_default"))

    build_calls = turn["build_calls"]
    flat["L2:call_n"] = _norm(len(build_calls))
    first_build = build_calls[0] if build_calls else {}
    flat["L2:code"] = _norm(first_build.get("code"))
    for key in sorted(first_build.get("kw") or {}):
        flat["L2:" + key] = _norm(first_build["kw"][key])

    spec = turn["spec"]
    if spec is not None:
        for field in dataclasses.fields(spec):
            flat["L3:" + field.name] = _norm(getattr(spec, field.name, None))
    else:
        flat["L3:<spec>"] = "None"

    flat["L4:kind"] = _norm(turn["kind"])
    flat["L4:contract"] = _norm(turn["contract"])
    flat["L4:answer_fp"] = _fp(turn["text"])
    return flat


def flipflop_outcome(turn: dict) -> str:
    """老板侧的**归宿** —— 判翻面就按它分组。⛔ 不用字数（字数不同不等于行为不同）。"""
    return "%s/clar=%s" % (turn["kind"], turn["clar"])


def flipflop_is_effective(turn: dict) -> tuple:
    """有效样本判据。返回 `(bool, 不合格的理由)`。

    ⚠️ ①③ **只在冷编译模式下要求** —— 生产复刻模式里零 token 回放正是被测
       对象，要求 `source_tier == 'llm'` 就等于把被测现象自己筛掉了
       （本仓踩过同形：清缓存的动作**亲手保证了**两侧不同）。
    """
    reasons = []
    if FLIPFLOP_CLEAR_CACHES:
        if turn["tier"] != "llm":
            reasons.append("source_tier=%s(冷编译模式下不算)" % turn["tier"])
        if not turn["build_calls"]:
            reasons.append("_build_spec 未被调用(没跑到规划器)")
    if turn["authority"] == LLM_UNAVAILABLE_AUTHORITY:
        reasons.append("planner_authority=llm_unavailable(探针缺key)")
    if turn["kind"] in (None, "-"):
        reasons.append("没拿到 kind(没跑到产品出口)")
    return (not reasons, "、".join(reasons))


async def flipflop_run_turn(pool, query: str, session_key: str) -> dict:
    FLIPFLOP_RAW_CALLS.clear()
    FLIPFLOP_PARSED_CALLS.clear()
    FLIPFLOP_BUILD_CALLS.clear()
    set_factory_id(FLIPFLOP_FID)
    async with rr.dish_catalogue_scope(pool, FLIPFLOP_FID):
        catalogue = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FLIPFLOP_FID, session_key=session_key,
            semantic_first=True,
        )
    result = None
    if spec is not None and svc.should_delegate(spec, None, query=query):
        result = await svc.tiered_answer(
            query, pool, FLIPFLOP_FID, flipflop_ctx.role,
            precomputed_spec=spec, session_key=session_key,
        )
    text = (result or {}).get("answer_text") or ""
    return {
        "q": query,
        "session_key": session_key,
        "catalogue_n": len(catalogue or ()),
        "raw_calls": list(FLIPFLOP_RAW_CALLS),
        "parsed_calls": list(FLIPFLOP_PARSED_CALLS),
        "build_calls": list(FLIPFLOP_BUILD_CALLS),
        "spec": spec,
        "tier": getattr(spec, "source_tier", None) if spec else None,
        "authority": getattr(spec, "planner_authority", None) if spec else None,
        "clar": bool(getattr(spec, "clarification_needed", False)) if spec else None,
        "kind": (result or {}).get("kind") or ("-" if result is None else None),
        "contract": (result or {}).get("contract_pass"),
        "continuation": (bool(getattr(spec, "is_clarification_continuation", False))
                         if spec else None),
        "text": text,
    }


def flipflop_classify_fields(turns: list) -> dict:
    """把每个字段分成三类。**这是本探针的裁定动作。**

        deterministic  组内恒定 + 组间不同  ⇒ 它就是翻面的那一维
        noisy          同一组内部就在变      ⇒ 与归宿无关的抖动
        stable         处处相同
    """
    flats = [flipflop_flatten(t) for t in turns]
    outcomes = [flipflop_outcome(t) for t in turns]
    keys = set()
    for flat in flats:
        keys |= set(flat)
    verdict = {"deterministic": [], "noisy": [], "stable": []}
    for key in sorted(keys):
        values = [flat.get(key, "<缺>") for flat in flats]
        if len(set(values)) == 1:
            verdict["stable"].append(key)
            continue
        per_outcome = {}
        for outcome, value in zip(outcomes, values):
            per_outcome.setdefault(outcome, set()).add(value)
        if all(len(vals) == 1 for vals in per_outcome.values()) and len(
                {next(iter(v)) for v in per_outcome.values()}) > 1:
            verdict["deterministic"].append(key)
        else:
            verdict["noisy"].append(key)
    return verdict


async def main() -> int:
    pool = await flipflop_ctx.pool()
    questions = [(grp, FLIPFLOP_ALL_QUESTIONS[idx], idx)
                 for grp, idx in FLIPFLOP_QUESTION_PLAN]
    print("PROBE_DB=%s FID=%s ROUNDS=%d 问句=%d 条 TODAY=%s 模式=%s"
          % (flipflop_ctx.db_name, FLIPFLOP_FID, FLIPFLOP_ROUNDS,
             len(questions), time.strftime("%Y-%m-%d"),
             "冷编译(每轮清缓存)" if FLIPFLOP_CLEAR_CACHES else "生产复刻(一次都不清)"))
    print("PROBE_RUN_ID=%s（session_key 前缀。⛔ 跨次复用会被当成「回答上一次的反问」）"
          % FLIPFLOP_RUN_ID)
    print("问句集来源=restaurant_panorama_probe.QUESTIONS 的下标 %s（⛔ 不写第二份）"
          % [idx for _grp, idx in FLIPFLOP_QUESTION_PLAN])
    for grp, q, idx in questions:
        print("   [%s] Q%02d %s" % (grp, idx + 1, q))

    if flipflop_ctx.llm_dead_slots:
        print("⛔ rc=2 这些槽一个活账号都没有: %s —— 每条都会是 fail-closed 拒答"
              % "、".join(flipflop_ctx.llm_dead_slots))
        return 2

    # ── 阳性对照④：被参数化的那一维真的不同 ────────────────────────────────
    q_texts = [q for _g, q, _i in questions]
    print("\n阳性对照④ 问句逐条不同: %d/%d" % (len(set(q_texts)), len(q_texts)))
    if len(set(q_texts)) != len(q_texts):
        print("⛔ rc=2 问句有重复 —— 「N 次全绿」可能只是同一个样本跑了 N 遍")
        return 2

    flipflop_install_hooks()
    # ⛔ 生产复刻模式也要**先清一次**：否则第 1 轮可能命中上一次跑留下的计划，
    #    量到的就不是「老板第一次问」而是「上一次探针问过了」。
    if not FLIPFLOP_CLEAR_CACHES:
        print("\n开跑前一次性清缓存（此后一次都不清）: %s"
              % "、".join(flipflop_clear_caches()))
    turns = []
    session_keys = []
    for rnd in range(1, FLIPFLOP_ROUNDS + 1):
        cleared = flipflop_clear_caches() if FLIPFLOP_CLEAR_CACHES else []
        print("\n" + "#" * 78)
        print("# 第 %d 轮   %s" % (
            rnd, ("已清缓存: " + "、".join(cleared)) if cleared
            else "⚠️ 生产复刻模式：一个缓存都没清（老板连问两次就是这样）"))
        print("#" * 78)
        for grp, q, idx in questions:
            key = "ff-%s-r%d-q%d" % (FLIPFLOP_RUN_ID, rnd, idx)
            session_keys.append(key)
            turn = await flipflop_run_turn(pool, q, key)
            turn |= {"round": rnd, "idx": idx, "grp": grp}
            turns.append(turn)
            ok, why = flipflop_is_effective(turn)
            print("\nr%d [%s] Q%02d %s" % (rnd, grp, idx + 1, q))
            print("  归宿 = %-24s 有效=%s %s"
                  % (flipflop_outcome(turn), "✓" if ok else "✗", why))
            print("  来源 tier=%-10s authority=%-16s L0模型=%s 用时=%ss"
                  % (turn["tier"], turn["authority"],
                     (turn["raw_calls"][0].get("model") if turn["raw_calls"] else "∅"),
                     (turn["raw_calls"][0].get("elapsed") if turn["raw_calls"] else "∅")))
            print("  钩子 L0=%d L1=%d L2=%d   答案 %d 字 fp=%s"
                  % (len(turn["raw_calls"]), len(turn["parsed_calls"]),
                     len(turn["build_calls"]), len(turn["text"]), _fp(turn["text"])))
            raw0 = (turn["raw_calls"][0].get("content") if turn["raw_calls"] else "") or ""
            print("  L0 模型原文（首 300 字）: %s" % raw0[:300].replace("\n", " ⏎ "))

    # ── 阳性对照 ────────────────────────────────────────────────────────────
    print("\n" + "=" * 78)
    print("阳性 / 阴性对照（⛔ 先判它，再看任何结论）")
    cat_min = min(t["catalogue_n"] for t in turns)
    l0_total = sum(len(t["raw_calls"]) for t in turns)
    l2_total = sum(len(t["build_calls"]) for t in turns)
    clar_total = sum(1 for t in turns if t["kind"] == "clarification")
    print("  阳性① 菜单目录最小条数 = %d" % cat_min)
    print("  阳性② _build_spec 钩子总调用 = %d" % l2_total)
    print("  阳性③ call_chain 钩子总调用 = %d" % l0_total)
    print("  阳性④ session_key 逐次不同 = %d/%d" % (len(set(session_keys)), len(session_keys)))
    print("  阴性  kind=clarification 出现 = %d 次" % clar_total)
    # 🔴 阳性⑤ —— 每一轮都是**一句全新的问句**，⛔ 不是在回答上一轮的反问。
    #    这条为 0 之外的任何值都说明 session_key 与**别的进程/上一次跑**撞了，
    #    那时量到的是「回答反问」而不是「同一句问两次」——换了被测对象。
    conts = [t for t in turns if t.get("continuation")]
    print("  阳性⑤ is_clarification_continuation=True 的条数 = %d（必须为 0）"
          % len(conts))
    for t in conts:
        print("     r%d Q%02d %-24s session_key=%s"
              % (t["round"], t["idx"] + 1, t["q"], t["session_key"]))
    if conts:
        print("⛔ rc=2 有轮次被当成「用户在回答上一次的反问」——"
              " `restaurant_pending_clarifications` 是 Postgres 表、跨进程存活，"
              " session_key 撞上了。换 PROBE_RUN_ID 重跑。")
        return 2
    if cat_min == 0:
        print("⛔ rc=2 菜单目录为 0 —— 读数作废")
        return 2
    if l2_total == 0:
        print("⛔ rc=2 _build_spec 钩子一次没调 —— L2 全是 ∅")
        return 2
    if l0_total == 0:
        print("⛔ rc=2 call_chain 钩子一次没调 —— L0 全空，而 L0 是判层的那一层")
        return 2
    if len(set(session_keys)) != len(session_keys):
        print("⛔ rc=2 session_key 有重复")
        return 2

    # ── 有效率（⛔ 先报它，再报任何比例）──────────────────────────────────
    print("\n" + "=" * 78)
    eff = [t for t in turns if flipflop_is_effective(t)[0]]
    print("有效样本（判据：source_tier=llm ∧ authority≠%s ∧ _build_spec 被调 ∧ 有 kind）"
          % LLM_UNAVAILABLE_AUTHORITY)
    print("  有效 %d / 总 %d = %.1f%%" % (len(eff), len(turns), len(eff) / len(turns) * 100))
    bad = [t for t in turns if not flipflop_is_effective(t)[0]]
    if bad:
        print("  ⛔ 无效的逐条列（⛔ 不 continue 掉）:")
        for t in bad:
            print("     r%d Q%02d %-24s %s"
                  % (t["round"], t["idx"] + 1, t["q"], flipflop_is_effective(t)[1]))
    tiers = {}
    for t in turns:
        tiers[t["tier"]] = tiers.get(t["tier"], 0) + 1
    print("  来源标记 source_tier 分布: %s"
          % "  ".join("%s=%s" % kv for kv in sorted(tiers.items(), key=lambda kv: str(kv[0]))))

    # ── 同一个 prompt 得到几种输出（⛔ 与「翻没翻面」分开报）─────────────────
    print("\n" + "=" * 78)
    print("L0 输入 / 输出的多样性（口径：每问句取本轮**第一次** call_chain）")
    print("%-3s %-24s %-8s %-10s %-10s %s"
          % ("组", "问句", "调用数", "不同prompt", "不同输出", "模型"))
    for grp, q, idx in questions:
        ts = [t for t in turns if t["idx"] == idx and t["raw_calls"]]
        prompts = {_fp(t["raw_calls"][0].get("prompt") or "") for t in ts}
        contents = {_fp(t["raw_calls"][0].get("content") or "") for t in ts}
        models = {str(t["raw_calls"][0].get("model")) for t in ts}
        temps = {str(t["raw_calls"][0].get("temperature")) for t in ts}
        print("%-3s %-24s %-8d %-10d %-10d %s  temperature=%s"
              % (grp, q, len(ts), len(prompts), len(contents),
                 "/".join(sorted(models)), "/".join(sorted(temps))))
    print("  ▎不同prompt=1 而 不同输出>1 ⇒ **同一个输入两种输出**，翻在模型侧")
    print("  ▎不同prompt>1              ⇒ 代码侧喂了不同的上下文，先查那个")

    # ── prompt 到底哪里不一样（⛔ 不猜，逐行 diff）──────────────────────────
    print("\n" + "=" * 78)
    print("同一句问句的 prompt 之间**逐行 diff**（⛔ 只报「有 N 种」等于没说）")
    for grp, q, idx in questions:
        seen = {}
        for t in turns:
            if t["idx"] != idx or not t["raw_calls"]:
                continue
            text = t["raw_calls"][0].get("prompt") or ""
            seen.setdefault(_fp(text), (t["round"], text))
        if len(seen) <= 1:
            print("  [%s] %-24s 只有 1 种 prompt ✓" % (grp, q))
            continue
        items = list(seen.values())
        base_round, base_text = items[0]
        print("  [%s] %-24s **%d 种** prompt —— 以 r%d 为基准逐个 diff:"
              % (grp, q, len(items), base_round))
        for other_round, other_text in items[1:]:
            diff = [line for line in difflib.unified_diff(
                base_text.splitlines(), other_text.splitlines(),
                fromfile="r%d" % base_round, tofile="r%d" % other_round,
                lineterm="", n=0)]
            print("     ── r%d vs r%d: %d 行差异" % (base_round, other_round,
                                                     len(diff)))
            for line in diff[:24]:
                print("        %s" % line[:150])

    by_q = {}
    for t in eff:
        by_q.setdefault(t["idx"], []).append(t)
    comparable = {i: ts for i, ts in by_q.items() if len(ts) >= 2}
    print("  可比问句（≥2 次有效）= %d/%d" % (len(comparable), len(questions)))
    if not comparable:
        print("⛔ rc=2 没有一个问句拿到 ≥2 次有效调用 —— 翻面这件事本轮没量到")
        return 2

    # ── 逐问句：归宿分布 + 翻面判定 ────────────────────────────────────────
    print("\n" + "=" * 78)
    print("逐问句归宿（⛔ 逐轮打，不折叠）")
    flipped = []
    for grp, q, idx in questions:
        ts = sorted(by_q.get(idx, []), key=lambda t: t["round"])
        marks = "  ".join("r%d:%s" % (t["round"], flipflop_outcome(t)) for t in ts)
        outcomes = {flipflop_outcome(t) for t in ts}
        flag = "🔴 翻面" if len(outcomes) > 1 else ("稳定" if ts else "无有效样本")
        print("  [%s] Q%02d %-24s %-8s %s" % (grp, idx + 1, q, flag, marks))
        if len(outcomes) > 1:
            flipped.append((grp, q, idx, ts))

    # ── 阴性对照：反问那条路在**这个进程里**通不通 ────────────────────────
    #    ⛔ 判据不是「被测问句里出现过 clarification」—— 那会把「本轮没复现」
    #       误判成仪器坏了（实测：连着两次跑，48 条全是 answer）。
    #       用一组**真的没指定**的对照问句来证明那条路通。
    if clar_total == 0:
        print("\n被测的 %d 条问句本轮一次反问都没出现 ——"
              " 先证明反问那条路在这个进程里通得了：" % len(questions))
        ctrl_hit = ctrl_total = 0
        for cq in FLIPFLOP_REFUSAL_CONTROL_QUERIES:
            for i in range(FLIPFLOP_REFUSAL_CONTROL_TRIES):
                flipflop_clear_caches()
                ck = "ff-%s-ctrl-%s-%d" % (FLIPFLOP_RUN_ID, _fp(cq), i)
                ct = await flipflop_run_turn(pool, cq, ck)
                ctrl_total += 1
                hit = ct["kind"] == "clarification"
                ctrl_hit += 1 if hit else 0
                print("    对照 %-12s #%d → kind=%-14s %s"
                      % (cq, i + 1, ct["kind"], "✓ 走到反问" if hit else ""))
        print("  阴性对照命中 %d/%d" % (ctrl_hit, ctrl_total))
        if ctrl_hit == 0:
            print("⛔ rc=2 连**真的没指定**的对照问句都不反问 ——"
                  " 这个进程走不到反问那条路，「没翻面」这个读数作废")
            return 2
        print("  ✅ 反问那条路通 ⇒ 上面的「稳定」是真的稳定，⛔ 不是仪器读不出来")

    if not flipped:
        print("\n本轮**没读到翻面** —— ⚠️ 这是「本次没复现」，⛔ 不是「问题不存在」。")
        print("  口径：%s / %d 轮 / 每轮清 4 个缓存 / 独立 session_key" % (FLIPFLOP_FID, FLIPFLOP_ROUNDS))
        return 0

    # ── 翻的是哪一维 ────────────────────────────────────────────────────────
    print("\n" + "=" * 78)
    print("翻的是哪一维（判据：**组内恒定 + 组间不同** ⇒ deterministic）")
    for grp, q, idx, ts in flipped:
        verdict = flipflop_classify_fields(ts)
        flats = [flipflop_flatten(t) for t in ts]
        print("\n" + "-" * 78)
        print("[%s] Q%02d %s   有效 %d 次" % (grp, idx + 1, q, len(ts)))
        print("  归宿: %s" % "  ".join("r%d=%s" % (t["round"], flipflop_outcome(t)) for t in ts))
        print("  字段分类: 确定性翻面 %d / 噪声 %d / 恒定 %d"
              % (len(verdict["deterministic"]), len(verdict["noisy"]),
                 len(verdict["stable"])))
        if not verdict["deterministic"]:
            print("  ⚠️ 没有任何字段是「组内恒定 + 组间不同」——"
                  " 要么样本量不够，要么翻的东西不在这五层的读数里")
        for key in verdict["deterministic"]:
            print("  🔑 %-34s %s" % (key, "  ".join(
                "r%d=%s" % (t["round"], (flat.get(key, "<缺>") or "")[:44])
                for t, flat in zip(ts, flats))))
        if verdict["noisy"]:
            print("  ── 噪声字段（组内就在变，⛔ 不是根因）——")
            for key in verdict["noisy"]:
                print("     %-34s %s" % (key, "  ".join(
                    "r%d=%s" % (t["round"], (flat.get(key, "<缺>") or "")[:28])
                    for t, flat in zip(ts, flats))))

    # ── 跨问句汇总：哪个字段在最多问句上确定性地翻 ────────────────────────
    print("\n" + "=" * 78)
    print("跨问句汇总 —— 哪个字段确定性地在翻（分母 = 翻面问句数 %d）" % len(flipped))
    tally = {}
    for _grp, _q, _idx, ts in flipped:
        for key in flipflop_classify_fields(ts)["deterministic"]:
            tally[key] = tally.get(key, 0) + 1
    for key, count in sorted(tally.items(), key=lambda kv: (-kv[1], kv[0])):
        print("  %-3d/%-3d  %s" % (count, len(flipped), key))
    if not tally:
        print("  （空）")

    print("\n口径：租户=%s / 问句=%d 条（下标 %s）/ 轮数=%d / %s /"
          " 独立 session_key / 有效样本判据见文件头"
          % (FLIPFLOP_FID, len(questions),
             [i for _g, i in FLIPFLOP_QUESTION_PLAN], FLIPFLOP_ROUNDS,
             "每轮清 4 个缓存" if FLIPFLOP_CLEAR_CACHES else "开跑前清一次、此后不清"))
    return 1


if __name__ == "__main__":
    _t0 = time.time()
    _rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - _t0, _rc))
    raise SystemExit(_rc)
