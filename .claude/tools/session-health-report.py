#!/usr/bin/env python3
"""session-health-report.py — 只读统计本机 Claude Code 会话的上下文体积与压缩次数。

用途: 2026-11-10 起 organizer 改成「一批一个会话 + 压缩后收口」, 一周后用本脚本对比前后读数,
决定是否加 UserPromptSubmit 提醒脚本、是否让 organizer 自动起新会话。

读什么: ~/.claude/projects/<本仓库相关目录>/*.jsonl (会话记录)。
  - 上下文体积: 每条 assistant 消息 usage 的 input + cache_creation + cache_read
  - 压缩: type=system 且 subtype=compact_boundary 的行(本机实测存在; 官方说记录格式是内部的, 可能随版本变)
  - organizer 识别(启发式, 标注为推断): 调用过 organizer skill, 或至少起过 1 个会话 (start_session)
不写任何文件。用法:
  python -I .claude/tools/session-health-report.py --since 2026-11-03 --until 2026-11-10
退出码: 0=有读数; 2=没量到(没有任何会话落在窗口内或一条 usage 都没解析到) —— 不要把 2 当成「一切正常」。
"""
import argparse
import datetime as dt
import glob
import json
import os
import statistics
import sys

PRICE = {  # 每百万 token 美元 (Opus 5.5 / Sonnet 5.5 标价, 仅作相对比较)
    "opus": dict(inp=4.0, cw=5.0, cr=0.20, out=20.0),
    "sonnet": dict(inp=2.0, cw=2.5, cr=0.10, out=10.0),
}


def tier(model):
    m = (model or "").lower()
    return "sonnet" if "sonnet" in m or "haiku" in m else "opus"


def scan(path):
    ctx_max = 0
    ctx_last = 0
    compactions = 0
    turns = 0
    cost = 0.0
    coord_calls = 0
    organizer = False
    first_ts = last_ts = None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if '"timestamp"' in line:
                try:
                    r = json.loads(line)
                except Exception:
                    continue
            else:
                continue
            ts = r.get("timestamp")
            if ts:
                first_ts = first_ts or ts
                last_ts = ts
            if r.get("type") == "system" and r.get("subtype") == "compact_boundary":
                compactions += 1
                continue
            if r.get("type") != "assistant" or r.get("isSidechain"):
                if '"skill":"organizer"' in line or "<command-name>/organizer</command-name>" in line:
                    organizer = True
                continue
            msg = r.get("message") or {}
            for c in msg.get("content") or []:
                if isinstance(c, dict) and c.get("type") == "tool_use":
                    n = c.get("name", "")
                    if n.endswith("start_session"):
                        coord_calls += 1
                    if n == "Skill" and (c.get("input") or {}).get("skill") == "organizer":
                        organizer = True
            u = msg.get("usage")
            if not u:
                continue
            ctx = (u.get("input_tokens") or 0) + (u.get("cache_creation_input_tokens") or 0) + (u.get("cache_read_input_tokens") or 0)
            if ctx <= 0:
                continue
            turns += 1
            ctx_last = ctx
            ctx_max = max(ctx_max, ctx)
            p = PRICE[tier(msg.get("model"))]
            cost += ((u.get("input_tokens") or 0) * p["inp"] + (u.get("cache_creation_input_tokens") or 0) * p["cw"]
                     + (u.get("cache_read_input_tokens") or 0) * p["cr"] + (u.get("output_tokens") or 0) * p["out"]) / 1e6
    return dict(path=path, first=first_ts, last=last_ts, turns=turns, ctx_max=ctx_max, ctx_last=ctx_last,
                compactions=compactions, cost=cost, organizer=organizer or coord_calls >= 1)


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", required=True, help="YYYY-MM-DD (含)")
    ap.add_argument("--until", required=True, help="YYYY-MM-DD (不含)")
    ap.add_argument("--glob", default="C--Users-Steve-my-prototype-logistics*")
    a = ap.parse_args()
    since = dt.datetime.fromisoformat(a.since).replace(tzinfo=dt.timezone.utc)
    until = dt.datetime.fromisoformat(a.until).replace(tzinfo=dt.timezone.utc)
    root = os.path.join(os.path.expanduser("~"), ".claude", "projects")
    files = glob.glob(os.path.join(root, a.glob, "*.jsonl"))
    rows = []
    for p in files:
        if os.path.getmtime(p) < since.timestamp():
            continue
        r = scan(p)
        if not r["first"] or r["turns"] == 0:
            continue
        start = dt.datetime.fromisoformat(r["first"].replace("Z", "+00:00"))
        if not (since <= start < until):
            continue
        rows.append(r)

    print(f"window {a.since} .. {a.until}  files_scanned={len(files)}  sessions_in_window={len(rows)}")
    if not rows:
        print("NO_DATA: 窗口内没有会话(或 usage 一条都没解析到) => 这次没量到, 不是「一切正常」")
        return 2

    def summary(label, rs):
        if not rs:
            print(f"[{label}] n=0")
            return
        mx = sorted(r["ctx_max"] for r in rs)
        cp = [r["compactions"] for r in rs]
        cost = sorted(r["cost"] for r in rs)
        print(f"[{label}] n={len(rs)}  max_ctx p50={mx[len(mx)//2]//1000}K p90={mx[int(len(mx)*.9)]//1000}K"
              f"  reached>=800K={sum(1 for m in mx if m >= 800000)}"
              f"  compactions total={sum(cp)} sessions_with>=1={sum(1 for c in cp if c >= 1)} >=2={sum(1 for c in cp if c >= 2)}"
              f"  est_cost median=${statistics.median(cost):.0f} p90=${cost[int(len(cost)*.9)]:.0f} total=${sum(cost):.0f}")

    summary("all", rows)
    summary("organizer(推断)", [r for r in rows if r["organizer"]])
    summary("other", [r for r in rows if not r["organizer"]])
    print("\norganizer 会话明细 (推断):")
    for r in sorted((r for r in rows if r["organizer"]), key=lambda r: r["first"]):
        print(f"  {r['first'][:16]}  turns={r['turns']:4d}  max={r['ctx_max']//1000:4d}K  compactions={r['compactions']}  est=${r['cost']:.0f}  {os.path.basename(os.path.dirname(r['path']))[-40:]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
