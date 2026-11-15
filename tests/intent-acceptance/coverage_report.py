"""从仓库里的指纹文件 (封存半边, 带分组标签、不含原文) 和开发半边题生成覆盖表 COVERAGE.md。只统计, 无题目原文。

用法: python -I -X utf8 coverage_report.py [--out COVERAGE.md]
"""
import argparse
import collections
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import workflow_spec as W  # noqa: E402


def rows(p):
    return [json.loads(x) for x in Path(p).read_text("utf-8").splitlines() if x.strip()]


def table(title, key, names, sealed, dev):
    out = ["### %s" % title, "", "| %s | 封存(确定) | 封存(歧义) | 开发(确定) | 开发(歧义) |" % key, "|---|---|---|---|---|"]
    for k in names:
        s_ok = sum(1 for r in sealed if r[key.split()[0]] == k and not r["ambiguous"])
        s_am = sum(1 for r in sealed if r[key.split()[0]] == k and r["ambiguous"])
        d_ok = sum(1 for r in dev if r[key.split()[0]] == k and not r["ambiguous"])
        d_am = sum(1 for r in dev if r[key.split()[0]] == k and r["ambiguous"])
        out.append("| %s | %d | %d | %d | %d |" % (k, s_ok, s_am, d_ok, d_am))
    return out + [""]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "COVERAGE.md"))
    a = ap.parse_args()
    sealed = rows(HERE / "sealed_v3_fingerprints.jsonl")
    dev = rows(HERE / "dev" / "items.jsonl")
    lines = ["# 验收集 v3 覆盖表", "", "由 `coverage_report.py` 生成，只含统计。「歧义」= 出题模型与另一家盲标模型不一致，单列、不计分。", "",
             "封存半边 %d 题（确定 %d），开发半边 %d 题（确定 %d）。" % (
                 len(sealed), sum(1 for r in sealed if not r["ambiguous"]), len(dev), sum(1 for r in dev if not r["ambiguous"])), ""]
    lines += table("业务域（采购→入库→生产→质检→出库→销售对账→经营）", "stage", list(W.STAGES), sealed, dev)
    lines += table("角色", "role", list(W.ROLES), sealed, dev)
    lines += table("说法风格", "style", list(W.STYLES), sealed, dev)
    lines += table("期望姿态", "stance", list(W.STANCES), sealed, dev)
    cross = collections.Counter((r["stage"], r["role"]) for r in sealed if not r["ambiguous"])
    lines += ["### 业务域 x 角色（封存确定题）", "", "| 业务域 \\ 角色 | " + " | ".join(W.ROLES) + " |", "|---|" + "---|" * len(W.ROLES)]
    for s in W.STAGES:
        lines.append("| %s | " % s + " | ".join(str(cross[(s, r)]) for r in W.ROLES) + " |")
    Path(a.out).write_text("\n".join(lines) + "\n", "utf-8", newline="")
    print("wrote", a.out)


if __name__ == "__main__":
    main()
