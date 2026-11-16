#!/usr/bin/env python3
"""六膳门 AI 问答 BI 演示题单实跑 (F006 只读评测号, mode=READ, 只读)。

记录每题: 状态/意图/回答文本/耗时/是否带图表字段。凭证只来自环境变量或服务器 .env.prod, 不打印不落盘。
用法: python scripts/ai-bi-demo/run_demo_questions.py --out <结果.json> [--only Q01,Q05]
"""
import argparse, json, os, sys, time, uuid
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "ai-live-probe"))
import ai_live_probe as P  # noqa: E402


def _utf8_console():
    """Windows GBK 控制台 print「¥」会 UnicodeEncodeError 中断整轮; 输出改 utf-8, 仍编不了的字符替换, 不崩。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main():
    _utf8_console()
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="")
    ap.add_argument("--base", default=P.DEFAULT_BASE)
    ap.add_argument("--account", choices=["read", "write"], default="read", help="write=F006 QA 账号(sales_manager, 只做 READ 模式提问, 不写)")
    a = ap.parse_args()
    qs = json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))["questions"]
    only = {x for x in a.only.split(",") if x}
    http = P.Http(a.base)
    if a.account == "write":
        u, p, src = P.resolve_write_creds(os.path.expanduser("~/.cretas/ai-live-probe.env"))
    else:
        u, p, src = P.resolve_read_creds(P.DEFAULT_SERVER)
    if not u:
        print("NOT_MEASURED: 没有只读评测号凭证"); return 2
    who = P.login(http, u, p, "read")
    print("login ok factory=%s role=%s via %s" % (who["factoryId"], who["role"], src))
    rows = []
    for q in qs:
        if only and q["id"] not in only:
            continue
        sid = "bi-demo-" + uuid.uuid4().hex[:8]
        t = time.time()
        body = {"userInput": q["say"], "sessionId": sid, "mode": "READ"}
        code, env = http.json("POST", "/api/mobile/F006/ai-intents/execute", body, who["token"])
        d = env.get("data") if isinstance(env.get("data"), dict) else {}
        rd = d.get("resultData") if isinstance(d.get("resultData"), dict) else {}
        rows.append({
            "id": q["id"], "kind": q["kind"], "say": q["say"], "http": code,
            "status": d.get("status"), "intent": d.get("intentCode"), "recognized": d.get("intentRecognized"),
            "message": d.get("message") or d.get("formattedText"),
            "resultKeys": sorted((rd.get("data") or rd).keys())[:30] if isinstance(rd.get("data") or rd, dict) else [],
            "hasChart": any(k in json.dumps(rd, ensure_ascii=False)[:20000] for k in ("chartConfig", "echarts", "chart_type", "chartType", "\"series\"")),
            "latency_s": round(time.time() - t, 1),
        })
        r = rows[-1]
        print("%s [%s] %s intent=%s %.1fs chart=%s\n   %s" % (r["id"], r["status"], r["say"], r["intent"], r["latency_s"], r["hasChart"], (r["message"] or "")[:160].replace("\n", " ")))
    json.dump(rows, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
