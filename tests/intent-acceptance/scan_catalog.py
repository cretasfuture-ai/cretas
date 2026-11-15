"""从仓库当前这一版扫出「意图 -> Tool -> 读写属性」(验收集出题和计分共用)。

读写属性来自 Tool 类的 getAccessMode(); 没声明的按 WRITE (fail-closed), 与产品里的写闸口径一致。
用法: python -I -X utf8 scan_catalog.py --out <catalog_rw.json> [--repo <repo-root>]
"""
import argparse
import collections
import json
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent


def scan(repo: pathlib.Path):
    root = repo / "backend/java/cretas-api/src/main/java"
    tools = {}
    for p in root.rglob("*.java"):
        t = p.read_text("utf-8", errors="replace")
        m = re.search(r'String\s+getToolName\(\)\s*\{\s*return\s+"([^"]+)"', t)
        if not m:
            continue
        a = re.search(r'AccessMode\s+getAccessMode\(\)\s*\{\s*return\s+(?:ToolExecutor\.)?(?:AccessMode\.)?(READ|WRITE)', t)
        tools[m.group(1)] = dict(mode=a.group(1) if a else "UNDECLARED_DEFAULT_WRITE",
                                 file=p.relative_to(repo).as_posix())
    cat = json.load(open(repo / "backend/java/cretas-api/src/main/resources/ai/factory-intent-catalog.json",
                         encoding="utf-8-sig"))
    out = []
    for r in cat["canonical_intents"]:
        tn = r["tool_name"]
        if r["route_type"] == "SKILL":
            mode = "READ" if r.get("skill_read_only") else "WRITE"
        elif tn in tools:
            mode = "READ" if tools[tn]["mode"] == "READ" else "WRITE"  # UNDECLARED -> WRITE
        else:
            mode = "WRITE"  # 找不到 Tool: 保守按写
        out.append(dict(code=r["intent_code"], name=r["intent_name"], cat=r["intent_category"],
                        route=r["route_type"], tool=tn, mode=mode, desc=(r.get("description") or "")[:90]))
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--repo", default=str(HERE.parents[1]))
    a = ap.parse_args()
    rows = scan(pathlib.Path(a.repo))
    with open(a.out, "w", encoding="utf-8", newline="") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print(len(rows), collections.Counter(r["mode"] for r in rows), collections.Counter(r["cat"] for r in rows))
