# -*- coding: utf-8 -*-
"""无金额权限的角色，正文里到底有没有金额。

## 我想知道的 X / 我实际在量的 Y（形态 A，动手前先并排写一遍）

    X：`restaurant_waiter`（∉ PRICE_VIEW_ROLES）读到的**正文**里有没有具体金额
    Y：`answer_text` 里 `¥<数字>` 形态的 token 逐条列出 + 附一个**结构判据**

⚠️ 单靠 `¥` 子串是**子串判据**（纪律 1 禁止拿它当唯一判据）。所以每条读数
   同时带一个结构读数：把 `daily_table.render(compute_dish_margins(...))`
   **单独算一遍**，看它是不是 `answer_text` 的后缀（`endswith`）——
   那是「这段金额是不是拼表拼进去的」的结构证据，⛔ 不是文本匹配。

## 三个阳性对照（⛔ 一个都不许省，且要求**每一轮**都成立，纪律 13）

1. `restaurant_manager` ∈ PRICE_VIEW_ROLES / `restaurant_waiter` ∉ ——
   两侧都不成立就是角色选错，rc=2
2. `restaurant_manager` 在同一句上必须**照常**给出金额（¥ token > 0）——
   它也读出 0 说明我的尺子坏了（正则读空 / 执行链没起来），⛔ 不是「修好了」
3. `planner_authority != llm_unavailable` —— 非 0 就 rc=2 作废

▎第 2 条要问一句「仪器坏掉时它会不会也成立」：不会 —— 尺子读空时它是 0，
▎执行链不通时 `kind` 不是 `answer`。两种坏法它都会红。

## 变异对照

`PROBE_NO_DISH_TABLE=1` 把 `_maybe_append_dish_table` 换成 no-op，并记录
**它被调用了几次**（先证明行为变了，再看 ¥ 数掉没掉）。

## 三态退出码

    0  waiter 正文零金额（修好了）   1  waiter 正文有金额（缺陷在）   2  没量到

## 用法

    PROBE_ROUNDS=2 ./venv-current/bin/python -m smartbi.scripts._priceleak_probe
"""
from __future__ import annotations

import asyncio
import json
import os
import re

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_FID = os.environ.get("PROBE_FID", "MOCK_REST")
_ROUNDS = int(os.environ.get("PROBE_ROUNDS", "2"))
_CORPUS = os.environ.get("PROBE_CORPUS", "/tmp/priceleak_corpus.jsonl")

_ctx = bootstrap_probe(_FID)

from smartbi.gold.restaurant import restaurant_intent as _ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as _rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    should_delegate,
    tiered_answer,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES  # noqa: E402

_ROLE_PRIV = "restaurant_manager"
_ROLE_PLAIN = "restaurant_waiter"

#: 命中 `_DISH_TABLE_INTENTS`（SALES_SUMMARY / GROSS_MARGIN）的金额问句。
_MONEY_QS = (
    "这个月赚钱了吗",
    "最近30天毛利怎么样",
    "米饭的毛利怎么样",
    "各家店的营收和毛利怎么样",
    "最近30天营收多少",
)
if os.environ.get("PROBE_QS"):
    _MONEY_QS = tuple(q for q in os.environ["PROBE_QS"].split("|") if q.strip())

#: 回归格：与金额权限无关的三条，两个角色的 kind 应当一致。
_REGRESSION_QS = ("哪个菜卖得好", "最近30天订单量多少", "有几家店")

_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)

#: `¥` / `￥` 后面**紧跟数字**才算金额 token。⛔ 光有 `¥` 不算
#: （`¥` 也可能出现在「金额已隐藏」这类说明里）。
_MONEY_TOKEN = re.compile(r"[¥￥]\s?-?[\d,]+(?:\.\d+)?")

_TABLE_HITS: list = []


def install_dish_table_mutation() -> None:
    from smartbi.gold.restaurant import restaurant_intent_service as _svc

    original = _svc._maybe_append_dish_table

    async def _noop(*args, **kwargs):
        # 兼容修复前(5 参)/修复后(带 role)两种签名 —— 变异不该因为签名变了而打空。
        spec = kwargs.get("spec") or (args[2] if len(args) > 2 else None)
        answer_text = kwargs.get("answer_text") or (args[3] if len(args) > 3 else "")
        _TABLE_HITS.append(getattr(spec, "intent", None))
        return answer_text

    _svc._maybe_append_dish_table = _noop
    print("[mutation] _maybe_append_dish_table -> no-op (原 %r)" % (original,))


def clear_caches() -> tuple:
    for name in _CLEARERS:
        getattr(_ri, name)()
    return _CLEARERS


async def _standalone_dish_table(pool, spec) -> str:
    """把拼表那一段**单独**算一遍 —— 结构判据的另一半。

    ⛔ 不复制 `_maybe_append_dish_table` 的口径判断（那会长出第二份），
       只是把它的数据源+渲染跑一遍，用来判「正文尾巴是不是这张表」。
    """
    try:
        from smartbi.gold.restaurant.daily_table import render
        from smartbi.gold.restaurant.dish_margin import compute_dish_margins

        start, end = getattr(spec, "date_range", (None, None))
        kwargs = {"date_range": (start, end)} if start and end else {}
        data = await compute_dish_margins(pool, _FID, **kwargs)
        return render(data, top_n=10)
    except Exception as e:  # noqa: BLE001
        return "<standalone-render-failed: %s>" % (e,)


def _markdown_table_rows(text: str) -> int:
    """正文里以 `|` 开头的行数 —— 「有没有拼进一张表」的结构读数。"""
    return sum(1 for line in (text or "").splitlines() if line.strip().startswith("|"))


async def run_one(pool, query: str, role: str, session_key: str) -> dict:
    set_factory_id(_FID)
    async with _rr.dish_catalogue_scope(pool, _FID):
        catalogue = _rr.current_dish_catalogue()
        spec = await _ri.parse_restaurant_query(
            query, pool, factory_id=_FID,
            session_key=session_key, semantic_first=True,
        )
    row = {"q": query, "role": role,
           "catalogue_n": len(catalogue) if catalogue else 0}
    if spec is None:
        return row | {"kind": "spec-None", "text": ""}
    row |= {
        "intent": (spec.intent or "∅").replace("RESTAURANT_OPS_", ""),
        "planner_authority": spec.planner_authority,
        "source_tier": spec.source_tier,
    }
    if not should_delegate(spec, None, query=query):
        return row | {"kind": "no-delegate", "text": ""}
    result = await tiered_answer(
        query, pool, _FID, role,
        precomputed_spec=spec, session_key=session_key, history=[],
    )
    if not result:
        return row | {"kind": "None", "text": ""}
    text = result.get("answer_text") or ""
    standalone = await _standalone_dish_table(pool, spec)
    row |= {
        "kind": result.get("kind") or "-",
        "contract_pass": result.get("contract_pass"),
        "output_preference": result.get("output_preference"),
        "text": text,
        "money_tokens": _MONEY_TOKEN.findall(text),
        "table_rows": _markdown_table_rows(text),
        # 结构判据：正文尾巴逐字等于单独渲染出来的那张表 ⇒ 金额是拼表拼进去的
        "tail_is_dish_table": bool(standalone) and text.endswith(standalone),
        "standalone_table_rows": _markdown_table_rows(standalone),
    }
    try:
        with open(_CORPUS, "a", encoding="utf-8", newline="") as fh:
            fh.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")
    except Exception:  # noqa: BLE001
        pass
    return row


def _fmt(v) -> str:
    if v is None:
        return "∅"
    if isinstance(v, (list, tuple)):
        return ",".join(str(x) for x in v) or "∅"
    return str(v)


def emit(row: dict) -> None:
    print("\n" + "=" * 78)
    print("%s r%d  role=%-20s  %s" % (row["tag"], row["round"], row["role"], row["q"]))
    print("  spec   intent=%-22s planner_authority=%s  source_tier=%s"
          % (_fmt(row.get("intent")), _fmt(row.get("planner_authority")),
             _fmt(row.get("source_tier"))))
    print("  最终   kind=%-12s contract_pass=%-6s output_pref=%s"
          % (_fmt(row.get("kind")), _fmt(row.get("contract_pass")),
             _fmt(row.get("output_preference"))))
    print("  💴 金额 token 数=%d   %s"
          % (len(row.get("money_tokens") or []),
             _fmt((row.get("money_tokens") or [])[:8])))
    print("  📊 正文表格行=%s  单独渲染表格行=%s  尾巴逐字等于那张表=%s"
          % (_fmt(row.get("table_rows")), _fmt(row.get("standalone_table_rows")),
             _fmt(row.get("tail_is_dish_table"))))
    text = row.get("text") or ""
    print("  正文 len=%d" % len(text))
    print("  ┌" + "─" * 74)
    for line in (text or "<空>").splitlines() or ["<空>"]:
        print("  │" + line)
    print("  └" + "─" * 74)


async def main() -> int:
    print("PRICE_VIEW_ROLES 含 %-20s -> %s"
          % (_ROLE_PRIV, _ROLE_PRIV in PRICE_VIEW_ROLES))
    print("PRICE_VIEW_ROLES 含 %-20s -> %s"
          % (_ROLE_PLAIN, _ROLE_PLAIN in PRICE_VIEW_ROLES))
    if _ROLE_PRIV not in PRICE_VIEW_ROLES or _ROLE_PLAIN in PRICE_VIEW_ROLES:
        print("XX rc=2 角色选错了 —— 阳性/阴性两侧不成立, 本次读数作废")
        return 2
    print("金额问句 %d 条: %s" % (len(_MONEY_QS), list(_MONEY_QS)))
    print("回归问句 %d 条: %s" % (len(_REGRESSION_QS), list(_REGRESSION_QS)))

    if os.environ.get("PROBE_NO_DISH_TABLE"):
        install_dish_table_mutation()
    pool = await _ctx.pool()
    rows: list = []
    llm_dead = 0

    for rnd in range(1, _ROUNDS + 1):
        cleared = clear_caches()
        print("\n\n############ ROUND %d  cleared=%s ############"
              % (rnd, ",".join(cleared)))
        for qi, query in enumerate(_MONEY_QS):
            for role in (_ROLE_PRIV, _ROLE_PLAIN):
                sk = "priceleak-r%d-q%d-%s-%d" % (rnd, qi, role, os.getpid())
                row = await run_one(pool, query, role, sk)
                row |= {"tag": "M%02d" % (qi + 1), "round": rnd}
                rows.append(row)
                if row.get("planner_authority") == "llm_unavailable":
                    llm_dead += 1
                emit(row)
        for qi, query in enumerate(_REGRESSION_QS):
            for role in (_ROLE_PRIV, _ROLE_PLAIN):
                sk = "priceleak-reg-r%d-q%d-%s-%d" % (rnd, qi, role, os.getpid())
                row = await run_one(pool, query, role, sk)
                row |= {"tag": "R%02d" % (qi + 1), "round": rnd,
                        "regression": True}
                rows.append(row)
                if row.get("planner_authority") == "llm_unavailable":
                    llm_dead += 1
                emit(row)

    print("\n\n" + "#" * 78)
    print("汇总（逐条已在上面；这里只做阳性对照与判定）")
    print("  planner_authority == llm_unavailable 的行数 = %d" % llm_dead)
    if llm_dead:
        print("XX rc=2 没量到 —— LLM 编译不出来, 本次读数作废")
        return 2
    if os.environ.get("PROBE_NO_DISH_TABLE"):
        print("  [mutation] 拼表函数被调用次数 = %d  intents=%s"
              % (len(_TABLE_HITS), _TABLE_HITS))
        if not _TABLE_HITS:
            print("XX rc=2 变异没到达 —— 拼表函数一次都没被调用, 本次对照无意义")
            return 2

    money_rows = [r for r in rows if not r.get("regression")]

    # ── 阳性对照 2：**每一轮**都要求有权限角色照常给出金额 ──────────────
    ok_every_round = True
    for rnd in range(1, _ROUNDS + 1):
        priv = [r for r in money_rows
                if r["role"] == _ROLE_PRIV and r["round"] == rnd]
        with_money = [r for r in priv if r.get("money_tokens")]
        print("  阳性对照2 r%d: 有权限角色带金额的行 = %d / %d"
              % (rnd, len(with_money), len(priv)))
        if not with_money:
            ok_every_round = False
    if not ok_every_round:
        print("XX rc=2 没量到 —— 有金额权限的角色也读不到金额, "
              "要么我的尺子读空了, 要么这条路整个坏了")
        return 2

    print("\n  ── 主判据：无权限角色正文里的金额 token（逐条，⛔ 不折叠）──")
    leaking = []
    for r in money_rows:
        if r["role"] != _ROLE_PLAIN:
            continue
        toks = r.get("money_tokens") or []
        print("    %s r%d %-18s kind=%-10s ¥token=%-3d 表格行=%-3s 尾巴=%s  %s"
              % (r["tag"], r["round"], r["q"][:16], _fmt(r.get("kind")),
                 len(toks), _fmt(r.get("table_rows")),
                 _fmt(r.get("tail_is_dish_table")), _fmt(toks[:4])))
        if toks:
            leaking.append(r)

    plain = [r for r in money_rows if r["role"] == _ROLE_PLAIN]
    priv = [r for r in money_rows if r["role"] == _ROLE_PRIV]
    priv_leak = [r for r in priv if r.get("money_tokens")]
    print("\n  无权限角色正文含金额的行数 = %d / %d" % (len(leaking), len(plain)))
    print("  有权限角色正文含金额的行数 = %d / %d (阳性对照, 应当非 0)"
          % (len(priv_leak), len(priv)))

    print("\n  回归格（3 条无关问句 × 2 角色, 比 kind）")
    for r in rows:
        if r.get("regression"):
            print("    %s r%d %-14s role=%-20s kind=%-10s ¥token=%d"
                  % (r["tag"], r["round"], r["q"], r["role"],
                     _fmt(r.get("kind")), len(r.get("money_tokens") or [])))

    if leaking:
        print("XX rc=1 无权限角色的正文里有金额 —— 缺陷在")
        return 1
    print("OK rc=0 无权限角色正文零金额")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
