# -*- coding: utf-8 -*-
"""缺口 #15「否定写进反馈通道」—— 真实调用证据探针（只读）。

⛔ 不判「端点存在」。判的是三态：

    通道不存在/断线  ——  静态接线链上某一层调用数 = 0
    通道在但无人调    ——  接线全通, 但真实客户端调用 = 0
    有真实调用        ——  接线全通, 且真实客户端调用 > 0

## 为什么不能只用 DB 计数（本轮实测出来的形态 A）

上一棒的判据是「`smart_bi_llm_fallback_log` 里 `user_feedback IS NOT NULL`
的行数」。那个数**会因为捕获表的保留期而归零** —— 本轮实测: 访问日志里
2026-07-23 有 3 次真实客户端 POST（2 次 200 成功写库），而今天 DB 里
一行都没有，因为那几行捕获数据已经不在保留窗口里了。

    我想知道的 X: 有没有人调用过这个通道
    上一棒量的 Y: 现在库里还剩几行带反馈的捕获行

X ≠ Y。⇒ **主证据换成访问日志**（append-only + 轮转 .gz 全留着，
覆盖端点上线日至今），DB 计数降级为佐证。

## 阳性/阴性对照（会读出「全无」的仪器必须配）

  DB 侧:
    阳性 total(public.smart_bi_llm_fallback_log) > 0          —— 库连上了、表对了
    阳性 count(user_feedback IS NULL) > 0                     —— FILTER 机制正向可用
    阴性 count(user_feedback = 999) == 0                      —— FILTER 机制反向可用
    ⚠️ 问一句「仪器坏掉时它会不会也成立」: 池挂了→抛异常→rc=2;
       表名错→total=0→对照红; RLS 挡住→total=0→对照红。⇒ 不是恒真式。
  日志侧:
    阳性 同一批文件里 /tiered-answer 命中 > 0                 —— 日志窗口里确实有流量
    ⚠️ 若阳性为 0, 「feedback=0」就分不清「没人调」和「日志窗口是空的」⇒ rc=2。

rc: 0=有真实调用 / 1=通道在但无人调 或 接线断了 / 2=没量到（对照没过）
"""
from __future__ import annotations

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: F401  必须第一行

import asyncio
import glob
import gzip
import os
import re
import sys

CTX = bootstrap_probe("MOCK_REST")

TABLE_NAME = "smart_bi_llm_fallback_log"
#: ⛔ 不在这里写第二份租户清单 —— 见 `_probe_bootstrap.SURVIVING_TENANTS`。
#:    反馈是**跨域**表, 所以用「还存在的租户」而不是「有餐饮数据的租户」。
from smartbi.scripts._probe_bootstrap import (  # noqa: E402
    SURVIVING_TENANTS as TENANTS,
)

#: 端点上线日（`restaurant_feedback.py` 模块 docstring: 飞轮断点 2, 2026-07-23）
ENDPOINT_SHIPPED = "2026-07-23"

LOG_GLOBS = ("/www/wwwroot/cretas/python-prod.log",
             "/www/wwwroot/cretas/python-prod.log-*.gz")

FEEDBACK_PATH = "POST /api/smartbi/restaurant/feedback"
CONTROL_PATH = "POST /api/smartbi/gold/restaurant/tiered-answer"

#: 环回 + docker 内网 = 本机发起, 不算「客户端调用」
_LOOPBACK = re.compile(r"^(127\.0\.0\.1|::1|localhost)$")
_DOCKER = re.compile(r"^(172\.1[6-9]\.|172\.2[0-9]\.|172\.3[01]\.)")

_ACCESS_LINE = re.compile(r"INFO:\s+([0-9a-fA-F:.]+):(\d+)\s+-\s+\"([^\"]+)\"\s+(\d{3})")

#: uvicorn 的 access 行**自己没有时间戳** —— 用同一文件里最近一条带时间戳的
#: 日志行归属。⛔ 不用行号推时间（行号不是时钟）。
_TS = re.compile(r"^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})")

#: 端点上线当天 = 构建期验收窗口。当天的调用**不算**「有人在用」——
#: 那是开发者自己点按钮验收。⚠️ 不排除它, 判据就会被 3 次构建期点击
#: 永久钉成 ✅ —— 那是「端点存在就打勾」换了身衣服。
#: 可用 GAP15_BUILD_DAY 覆盖 —— **专供变异对照**: 换成一个没有调用的日期,
#: organic 必须从 0 变成 3。不变 ⇒ 这个排除根本没在排除, 那个 0 不作数。
BUILD_DAY = os.environ.get("GAP15_BUILD_DAY", ENDPOINT_SHIPPED)


def _classify_source(ip: str) -> str:
    if _LOOPBACK.match(ip):
        return "本机(诊断探针)"
    if _DOCKER.match(ip):
        return "docker内网"
    return "客户端(经网关/外部)"


def _iter_log_files():
    for pat in LOG_GLOBS:
        for path in sorted(glob.glob(pat)):
            yield path


def _open_log(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def scan_access_logs() -> dict:
    """扫全部轮转 + 活文件。⛔ 不 head / 不 tail —— 判存在性不许截断。"""
    out = {"files": [], "feedback_lines": [], "feedback_total": 0,
           "control_total": 0, "log_present": False}
    for path in _iter_log_files():
        out["log_present"] = True
        fb = 0
        ctl = 0
        last_ts = None
        try:
            with _open_log(path) as fh:
                for line in fh:
                    mt = _TS.match(line)
                    if mt:
                        last_ts = "%s %s" % (mt.group(1), mt.group(2))
                    if FEEDBACK_PATH in line:
                        fb += 1
                        m = _ACCESS_LINE.search(line)
                        ip = m.group(1) if m else "?"
                        status = m.group(4) if m else "?"
                        out["feedback_lines"].append(
                            {"file": os.path.basename(path), "ip": ip,
                             "kind": _classify_source(ip), "status": status,
                             "ts": last_ts or "?"})
                    elif CONTROL_PATH in line:
                        ctl += 1
        except OSError as exc:
            out["files"].append((os.path.basename(path), -1, -1, str(exc)))
            continue
        out["files"].append((os.path.basename(path), fb, ctl, ""))
        out["feedback_total"] += fb
        out["control_total"] += ctl
    return out


async def scan_db() -> dict:
    pool = await CTX.pool()
    out = {}

    # ── 0) schema 限定: 这个表名在哪几个 schema 下 + RLS 开没开 ──────────
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT n.nspname AS schema, c.relrowsecurity, c.relforcerowsecurity
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relname = $1 AND c.relkind = 'r'
             ORDER BY n.nspname
            """, TABLE_NAME)
        out["schemas"] = [(r["schema"], r["relrowsecurity"], r["relforcerowsecurity"])
                          for r in rows]

    # ── 1) 跨租户读全表（SELECT 策略对空 GUC 放行, migration V20260502_03）──
    async with pool.acquire() as conn:
        await conn.execute("RESET app.factory_id")
        out["guc_after_reset"] = await conn.fetchval(
            "SELECT current_setting('app.factory_id', true)")
        out["total"] = await conn.fetchval(
            "SELECT count(*) FROM public.%s" % TABLE_NAME)
        out["ctrl_null"] = await conn.fetchval(
            "SELECT count(*) FROM public.%s WHERE user_feedback IS NULL" % TABLE_NAME)
        out["ctrl_impossible"] = await conn.fetchval(
            "SELECT count(*) FROM public.%s WHERE user_feedback = 999" % TABLE_NAME)
        out["fb_rows"] = [dict(r) for r in await conn.fetch(
            """
            SELECT id, factory_id, user_feedback, template_code, created_at,
                   left(query, 90) AS query_head,
                   left(coalesce(feedback_comment, ''), 70) AS comment_head
              FROM public.%s
             WHERE user_feedback IS NOT NULL
             ORDER BY created_at
            """ % TABLE_NAME)]
        out["window"] = await conn.fetchrow(
            "SELECT min(created_at) AS lo, max(created_at) AS hi,"
            " count(DISTINCT factory_id) AS tenants FROM public.%s" % TABLE_NAME)
        out["by_tenant"] = [dict(r) for r in await conn.fetch(
            "SELECT factory_id, count(*) AS n FROM public.%s"
            " GROUP BY factory_id ORDER BY n DESC" % TABLE_NAME)]

    # ── 2) 每个租户**独立会话**, 并断言 GUC 真的生效（贴出来）─────────────
    out["per_tenant"] = []
    for fid in TENANTS:
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            seen = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
            n = await conn.fetchval(
                "SELECT count(*) FROM public.%s" % TABLE_NAME)
            nf = await conn.fetchval(
                "SELECT count(*) FROM public.%s WHERE user_feedback IS NOT NULL"
                % TABLE_NAME)
            out["per_tenant"].append({"asked": fid, "guc_seen": seen,
                                      "guc_ok": seen == fid, "rows": n, "fb": nf})
    return out


def main() -> int:
    print("=" * 78)
    print("缺口 #15 · 否定写进反馈通道 —— 真实调用证据（只读探针）")
    print("=" * 78)

    log = scan_access_logs()
    print("\n[A] 访问日志（主证据; append-only, 轮转 .gz 全扫, ⛔ 无 head/tail）")
    if not log["log_present"]:
        print("  ⚠️ 一个日志文件都没找到 —— 本探针必须在 prod 上跑。rc=2")
        return 2
    for name, fb, ctl, err in log["files"]:
        print("    %-34s feedback=%-4s tiered=%-6s %s" % (name, fb, ctl, err))
    print("    合计: feedback=%d  tiered(阳性对照)=%d"
          % (log["feedback_total"], log["control_total"]))
    if log["control_total"] <= 0:
        print("  ⚠️ 阳性对照 0 —— 日志窗口里连问答流量都没有,"
              " 「feedback=0」分不清是没人调还是窗口空。rc=2")
        return 2
    print("    逐条列出每一次 feedback 调用（时间 + 源 IP + 归类 + 状态码）:")
    for r in log["feedback_lines"]:
        print("      %-19s %-34s %-18s %-22s %s"
              % (r["ts"], r["file"], r["ip"], r["kind"], r["status"]))
    real = [r for r in log["feedback_lines"] if r["kind"].startswith("客户端")]
    probe = [r for r in log["feedback_lines"] if not r["kind"].startswith("客户端")]
    organic = [r for r in real if not r["ts"].startswith(BUILD_DAY)]
    print("    ⇒ 客户端调用 %d 次 / 本机诊断 %d 次" % (len(real), len(probe)))
    print("    ⇒ 其中**排除端点上线当天(%s, 构建期验收)** 后, 自然使用 = %d 次"
          % (BUILD_DAY, len(organic)))
    # 硬约束 9: 验证「organic 没发生」必须配一个「设计上必然发生」的读数 ——
    # 否则日期解析坏掉 / 时间戳全成 "?" 也会读出 organic=0, 长得一模一样。
    dated = [r for r in log["feedback_lines"] if r["ts"] != "?"]
    non_build_any = [r for r in dated if not r["ts"].startswith(BUILD_DAY)]
    print("    [必发生对照] 拿到时间戳的调用 %d/%d 条;"
          " 排除上线当天后**全部来源**(含本机探针)剩 %d 条"
          % (len(dated), len(log["feedback_lines"]), len(non_build_any)))
    if not dated or not non_build_any:
        print("  ⚠️ 日期归属机制自身读不出非上线当天的任何一条 ——"
              " organic=0 分不清「没人调」和「日期解析坏了」。rc=2")
        return 2

    print("\n[B] 数据库（佐证; ⚠️ 捕获表有保留期, 单看它会把「调过」读成「没调过」）")
    try:
        db = asyncio.run(scan_db())
    except Exception as exc:  # noqa: BLE001 —— 连不上就是「没量到」, 不是「没问题」
        print("  ⚠️ 库读不到: %r  ⇒ rc=2" % (exc,))
        return 2
    print("    表所在 schema / RLS: %s" % (db["schemas"],))
    print("    RESET 后 GUC = %r" % (db["guc_after_reset"],))
    ctrl_ok = (db["total"] > 0 and db["ctrl_null"] > 0 and db["ctrl_impossible"] == 0)
    print("    阳性对照 total=%d, user_feedback IS NULL=%d;"
          " 阴性对照 user_feedback=999 → %d  ⇒ %s"
          % (db["total"], db["ctrl_null"], db["ctrl_impossible"],
             "✅ 仪器活着" if ctrl_ok else "🔴 对照没过"))
    if not ctrl_ok:
        print("  ⇒ DB 读数作废 rc=2")
        return 2
    w = db["window"]
    print("    全表窗口 %s ~ %s, distinct factory_id=%d" % (w["lo"], w["hi"], w["tenants"]))
    print("    按租户: %s" % (db["by_tenant"],))
    for t in db["per_tenant"]:
        print("      租户 %-12s GUC 生效=%s(%r)  行数=%-6d 带反馈=%d"
              % (t["asked"], t["guc_ok"], t["guc_seen"], t["rows"], t["fb"]))
    print("    user_feedback IS NOT NULL 的行（全部, 不截断）: %d 行"
          % len(db["fb_rows"]))
    for r in db["fb_rows"]:
        print("      id=%s %s fb=%s tpl=%s at=%s q=%r c=%r"
              % (r["id"], r["factory_id"], r["user_feedback"], r["template_code"],
                 r["created_at"], r["query_head"], r["comment_head"]))

    print("\n[C] 判定（三态）")
    print("    端点上线日 %s；日志覆盖窗口见 [A] 的文件清单。" % ENDPOINT_SHIPPED)
    if organic:
        print("  ✅ 有真实调用: 上线当天之外的客户端调用 %d 次 —— 逐条证据见 [A]"
              % len(organic))
        return 0
    if real:
        print("  🔴 通道在、上线后无人调: 客户端调用只有 %d 次且**全部在上线当天**"
              "(构建期验收), 之后 0 次; 同期问答 %d 次作阳性对照"
              % (len(real), log["control_total"]))
        return 1
    print("  🔴 通道在、从未被客户端调用: 客户端调用 0 次"
          " (同期问答 %d 次作阳性对照)" % log["control_total"])
    return 1


if __name__ == "__main__":
    sys.exit(main())
