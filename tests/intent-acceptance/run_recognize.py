"""对线上「识别」接口跑验收集 (只识别, 不执行): 经 ssh 在 prod Python 服务器上调本机 127.0.0.1:8083 /api/ai/intent/match。

为什么走服务器本机而不是公网 /recognize
  公网 /recognize 只回意图 + 置信度, 看不到「先问 / 直接办」(requiresConfirmation) 和决策痕迹; 而且只有 F006 一个评测号, 没法按角色发问。
  Python 的 /intent/match 就是 Java 识别层调用的那一个, 带 role 参数, 能还原五个角色。它只做识别 (候选检索 + 决策), 不执行任何 Tool, 不写业务库。
  ⛔ 不碰 /execute 与 /execute/stream (执行入口)。租户固定 F006; ⛔ LIUSHANMEN 不碰。

凭证: 服务器 .env.prod 的 INTERNAL_API_SECRET 只在服务器上读、只在那里用, 不经过本机、不打印。
传输: 题目经 ssh stdin 以二进制传过去 (windows 文本模式会变 CRLF); 远端脚本经 base64 内联, 不在服务器落文件。

用法:
  python -I -X utf8 run_recognize.py --items <items.jsonl> --out <results.jsonl> [--ask-policy AGGRESSIVE|CONSERVATIVE|NONE] [--limit N]
退出码: 0 = 跑完且每题都有读数; 1 = 有题没读到 (传输/超时/非 200, 计入 unmeasured, 不当通过); 2 = 一条都没量到
"""
import argparse
import base64
import json
import subprocess
import sys
import time
from pathlib import Path

SERVER = "root@47.100.235.168"
ROLE_MAP = {  # 验收集角色 -> 平台角色码 (FactoryUserRole)
    "OPERATOR": "operator",
    "WAREHOUSE": "warehouse_manager",
    "QC": "quality_inspector",
    "DISPATCHER": "dispatcher",
    "BOSS": "factory_super_admin",
}

REMOTE = r'''
import json, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
secret = None
for line in open("/www/wwwroot/cretas/.env.prod", encoding="utf-8"):
    if line.startswith("INTERNAL_API_SECRET="):
        secret = line.split("=", 1)[1].strip().strip('"').strip("'")
if not secret:
    sys.stderr.write("no INTERNAL_API_SECRET\n"); sys.exit(3)
payload = json.loads(sys.stdin.buffer.read().decode("utf-8"))
ask = payload.get("askPolicy")
def one(it):
    body = {"query": it["input"], "factoryId": "F006", "userId": "intent-acceptance", "role": it["role"], "businessType": "FACTORY",
            "history": [], "options": {"enableLlmFallback": True, "timeoutMs": 30000, "minConfidence": 0.70}}
    if ask:
        body["askPolicy"] = ask
    req = urllib.request.Request("http://127.0.0.1:8083/api/ai/intent/match", data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Internal-Secret": secret, "X-Factory-Id": "F006"})
    t0 = time.time(); rec = {"id": it["id"]}
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            doc = json.loads(r.read().decode("utf-8")); rec["http"] = r.status
    except urllib.error.HTTPError as e:
        rec["http"] = e.code; rec["error"] = e.read()[:200].decode("utf-8", "replace"); doc = None
    except Exception as e:
        rec["http"] = 0; rec["error"] = repr(e)[:200]; doc = None
    rec["ms"] = int((time.time() - t0) * 1000)
    if doc and doc.get("success") and doc.get("data"):
        d = doc["data"]; bm = d.get("bestMatch") or {}
        tr = d.get("decisionTrace") or {}
        rec.update(intent=bm.get("intentCode"), confidence=d.get("confidence"), method=d.get("matchMethod"),
                   needs_confirm=bool(d.get("requiresConfirmation")), clarify=bool(d.get("clarificationQuestion")),
                   candidates=[c.get("intentCode") for c in (d.get("topCandidates") or [])][:5],
                   trace=tr)
    elif doc is not None:
        rec["error"] = (doc.get("message") or "no data")[:200]
    return rec
with ThreadPoolExecutor(max_workers=payload.get("workers", 4)) as ex:
    for rec in ex.map(one, payload["items"]):
        sys.stdout.write(json.dumps(rec, ensure_ascii=False) + "\n"); sys.stdout.flush()
'''


def run(items, ask_policy, workers):
    payload = json.dumps(dict(items=items, askPolicy=ask_policy, workers=workers), ensure_ascii=False).encode("utf-8")
    b64 = base64.b64encode(REMOTE.encode("utf-8")).decode("ascii")
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ServerAliveInterval=30", SERVER,
           "python3 -c \"import base64;exec(base64.b64decode('%s').decode())\"" % b64]
    r = subprocess.run(cmd, input=payload, capture_output=True, timeout=3600)
    if r.returncode != 0:
        sys.stderr.write("remote rc=%s %s\n" % (r.returncode, r.stderr.decode("utf-8", "replace")[:300]))
    return [json.loads(x) for x in r.stdout.decode("utf-8").splitlines() if x.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--items", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ask-policy", default="AGGRESSIVE", choices=["AGGRESSIVE", "CONSERVATIVE", "NONE"])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--chunk", type=int, default=100)
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    items = [json.loads(x) for x in Path(a.items).read_text("utf-8").splitlines() if x.strip()]
    if a.limit:
        items = items[: a.limit]
    todo = [dict(id=i["id"], input=i["input"], role=ROLE_MAP[i["role"]]) for i in items]
    out = Path(a.out)
    done = {json.loads(x)["id"] for x in out.read_text("utf-8").splitlines() if x.strip()} if out.exists() else set()
    todo = [t for t in todo if t["id"] not in done]
    ask = None if a.ask_policy == "NONE" else a.ask_policy
    t0 = time.time()
    got = 0
    for k in range(0, len(todo), a.chunk):
        recs = run(todo[k:k + a.chunk], ask, a.workers)
        with open(out, "a", encoding="utf-8", newline="") as f:
            for r in recs:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        got += len(recs)
        print("chunk %d: %d/%d, elapsed %ds" % (k // a.chunk, got, len(todo), time.time() - t0), flush=True)
    rows = [json.loads(x) for x in out.read_text("utf-8").splitlines() if x.strip()]
    measured = sum(1 for r in rows if r.get("http") == 200 and "intent" in r)
    print("measured %d / %d (http!=200 or no data: %d)" % (measured, len(rows), len(rows) - measured))
    return 2 if measured == 0 else (1 if measured < len(items) else 0)


if __name__ == "__main__":
    sys.exit(main())
